package com.starstacker.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.location.Location
import android.media.ExifInterface
import android.util.Log
import com.starstacker.camera.CameraAccess
import com.starstacker.camera.FramingSession
import com.starstacker.camera.SequenceSession
import com.starstacker.session.FrameDescription
import com.starstacker.session.FrameKind
import com.starstacker.session.FrameRecord
import com.starstacker.session.SessionLog
import com.starstacker.session.SessionPointing
import com.starstacker.session.SessionState
import com.starstacker.session.SessionWriter
import com.starstacker.registration.LiveRegistration
import com.starstacker.stars.CfaBinner
import com.starstacker.stars.BinnedPlane
import com.starstacker.stars.FrameStars
import com.starstacker.stars.PreviewStack
import com.starstacker.stars.Star
import com.starstacker.stars.StarOffset
import com.starstacker.stars.StarDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * T-3.6 — the unattended sequence. **The primary deliverable**: press start, walk away, come back
 * to a folder of good subs.
 *
 * Everything that makes this hard is a consequence of "walk away". The sequence outlives the
 * screen, the Activity and the user's attention, so it holds no reference to any of them; its
 * entire output is a [SessionWriter] on disk and one [StateFlow] that the UI may or may not be
 * watching. Measured 2026-08-17: an Activity-scoped loop is frozen within seconds of the screen
 * going off with its process still alive, which is why this runs inside a `camera`-type
 * foreground service (D-12, OI-20) and not anywhere else.
 *
 * Per-frame ordering is deliberate and matches [SessionWriter]'s: capture, verify the metadata,
 * write the DNG, analyse, gate, log. Analysis happens on the copied sensor buffer while the next
 * exposure is already running, so the ~130 ms it costs is free against a multi-second sub.
 */
class CaptureEngine(
    private val access: CameraAccess,
    private val writer: SessionWriter,
    private val environment: Environment,
    private val gate: FrameGate = FrameGate(),
    private val thermal: ThermalPolicy = ThermalPolicy(),
) {

    /** Everything the engine needs from the platform that is not the camera. */
    interface Environment {
        fun reading(): ThermalPolicy.Reading

        /**
         * Peak rotation across exactly `[startNs, endNs]`, **degrees**, on the clock the camera's
         * `SENSOR_TIMESTAMP` uses. Null when it could not be measured, in which case [FrameGate]
         * skips the check rather than guessing. See [DeviceEnvironment] for why this reads the
         * gyroscope rather than the accelerometer, and why it is bounded by the exposure rather
         * than by the last call.
         */
        fun peakRotationDegDuring(startNs: Long, endNs: Long): Double?

        fun nowEpochMs(): Long
    }

    data class Request(
        val cameraId: String,
        val iso: Int,
        val exposureNs: Long,
        val focusDiopters: Float?,
        val lightCount: Int,
        val darkCount: Int,
        /**
         * Where the camera was pointed when Start was pressed, frozen at that instant — see
         * [SessionPointing]. Null when there was no fix, which is survivable: the trailing limit
         * assumes the equator and says so.
         */
        val pointing: SessionPointing? = null,
    )

    data class Progress(
        val state: SessionState = SessionState.IDLE,
        val framesCaptured: Int = 0,
        val framesAccepted: Int = 0,
        val darksCaptured: Int = 0,
        val target: Int = 0,
        val lastHfr: Double? = null,
        val lastStarCount: Int? = null,
        val lastBackground: Double? = null,
        val lastRejection: String? = null,
        val thermalNote: String? = null,
        val cooling: Boolean = false,
        val message: String? = null,
        val sessionPath: String? = null,
        /**
         * T-3.14 — the live preview stack, ARGB at [PreviewStack.WIDTH] x [PreviewStack.HEIGHT],
         * or null before the first accepted frame. Carried by reference; the engine owns the
         * buffer and overwrites it, so the UI must render it rather than retain it.
         */
        val preview: IntArray? = null,
        val previewDepth: Int = 0,
        /**
         * T-3.22 — when the frame now in flight began waiting, on the monotonic clock, and how
         * long its exposure is.
         *
         * The engine publishes the *start* and lets Compose animate; ticking a progress value from
         * the capture thread would burn a coroutine on a number nobody is looking at with the
         * screen off. Null between frames and while paused.
         */
        val frameStartedElapsedNs: Long? = null,
        val frameExposureNs: Long = 0L,
        /**
         * The log as it stands. Carried here rather than fetched separately so the screen sees a
         * frame count and the frames it names from the same instant — [SessionLog] is immutable,
         * so this is a reference copy and costs nothing.
         */
        val log: SessionLog? = null,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = false

    /** Skips the remaining lights and goes to darks — the `End & take darks` control. */
    @Volatile
    private var finishEarly = false

    /** Set when the user confirms the lens is covered, or chooses to skip darks (FR-4.2.1). */
    @Volatile
    private var darksConfirmed = false

    @Volatile
    private var darksSkipped = false

    fun pause() { paused = true }

    fun resume() { paused = false }

    fun stop() { stopRequested = true }

    fun endAndTakeDarks() { finishEarly = true }

    /** The lens is covered — go ahead with darks. */
    fun confirmDarks() { darksConfirmed = true }

    /** FR-4.2.1's skip. The cost is stated in the UI, not here. */
    fun skipDarks() { darksSkipped = true }

    /**
     * Runs the whole sequence. Returns when it is done, cancelled or failed; the session log on
     * disk is authoritative either way.
     */
    suspend fun run(request: Request) = withContext(Dispatchers.IO) {
        _progress.value = Progress(
            state = SessionState.CAPTURING,
            target = request.lightCount,
            sessionPath = writer.displayPath,
            log = writer.log,
        )
        writer.setState(SessionState.CAPTURING)

        try {
            SequenceSession.open(access, request.cameraId).use { session ->
                Log.i(TAG, "capture session: ${session.support.detail}")
                // FR-6.1: fixed WB, focus, exposure and ISO across the whole sequence. Applied
                // once, here, and never touched again — a re-applied request is a chance for the
                // HAL to land somewhere different, and every frame must be the same frame.
                session.apply(request.iso, request.exposureNs, request.focusDiopters)
                dngLocation = locationOf(request.pointing)
                buffer = ShortArray(session.plan.raw.width * session.plan.raw.height)
                detector = StarDetector(saturationLevel = session.whiteLevel)
                captureRun(session, request)
            }
            writer.setState(SessionState.DONE, environment.nowEpochMs())
            _progress.value = _progress.value.copy(
                state = SessionState.DONE,
                message = "session complete — ${writer.log.accepted.size} accepted frames",
                log = writer.log,
            )
        } catch (c: CancellationException) {
            // A cancelled session is interrupted, not failed: T-3.13 offers to resume it.
            writer.setState(SessionState.PAUSED)
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed", t)
            writer.setState(SessionState.FAILED, environment.nowEpochMs())
            _progress.value = _progress.value.copy(
                state = SessionState.FAILED,
                message = "${t::class.java.simpleName}: ${t.message}",
                log = writer.log,
            )
        }
    }

    /**
     * T-3.16 step 4 — the GPS tags, built once because the fix is frozen at Start and every frame
     * of the session therefore carries the same one. A desktop plate-solve asks for this, and it
     * is the same fix the trailing limit was derived from.
     */
    private var dngLocation: Location? = null

    /**
     * T-3.14 / D-18. Built lazily on the first accepted light, because a session that never gets
     * one should not pay 1.5 MB for a preview of nothing.
     */
    private var preview: PreviewStack? = null

    /**
     * Alignment is **frame to frame**, accumulated — not every frame against the first.
     *
     * The field drifts by design: the trailing limit budgets 1.5 sensor px per sub, so over 40
     * subs the first frame and the last are ~15 analysis pixels apart and the star lists stop
     * overlapping enough for a vote to find them. Matching each frame against its predecessor
     * keeps the offsets small and the vote healthy; the running total carries the drift.
     */
    private var previousStars: List<Star>? = null
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var lastDeltaX = 0.0
    private var lastDeltaY = 0.0

    /**
     * T-4.4 — registration against the session's reference frame, carried across the sequence.
     *
     * Held here rather than passed in because the reference *is* session state: the first frame
     * good enough to register against defines the coordinate system every later frame is measured
     * in, and a session that restarted its reference halfway through would produce two stacks
     * wearing one name.
     */
    private val registration = LiveRegistration()

    /** Reused across the whole sequence — a 25 MB allocation per frame is FR-12.2's warning. */
    private var buffer: ShortArray? = null
    private var detector: StarDetector? = null

    /** `SENSOR_INFO_EXPOSURE_TIME_RANGE`'s upper bound, read once and kept for the sequence. */
    private var ceilingNs: Long? = null

    /**
     * What the camera *says* its longest exposure is — which is not what it will actually do
     * (§1.20), and is used here only to decide when the app has gone off-contract deliberately.
     *
     * Read from the characteristics rather than carried on [Request]: it is a property of the
     * hardware, not of the plan, and a copy travelling through the service intent is a second
     * source of truth that can disagree with the device it describes.
     *
     * [Long.MAX_VALUE] when the camera reports no range, which reads as "nothing is past the
     * ceiling" and so leaves the original skip-until-settled behaviour untouched. That is the
     * conservative direction: a device too vague to state a limit should not have its frames
     * refused on the strength of a limit we invented.
     */
    private fun statedCeilingNs(cameraId: String): Long =
        ceilingNs ?: runCatching {
            access.characteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?.upper
        }.getOrNull().let { it ?: Long.MAX_VALUE }.also { ceilingNs = it }

    /** Null unless there is a real fix — an absent GPS IFD is honest, a zeroed one is not. */
    private fun locationOf(pointing: SessionPointing?): Location? {
        val lat = pointing?.latitudeDeg ?: return null
        val lon = pointing.longitudeDeg ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Location("starstacker").apply {
            latitude = lat
            longitude = lon
            time = environment.nowEpochMs()
        }
    }

    private suspend fun captureRun(session: SequenceSession, request: Request) {
        val already = writer.log.lights.size
        for (index in (already + 1)..request.lightCount) {
            if (stopRequested || finishEarly) break
            awaitUnpaused()
            if (stopRequested) break

            pauseForHeatIfNeeded()
            captureOne(session, request, FrameKind.LIGHT, index)
        }

        if (stopRequested || request.darkCount <= 0) return

        // FR-4.2.1: darks at the end, at matched ISO and exposure, along the same warming curve
        // the lights were taken on (D-16). **Covering the lens is a thing a person does**, so the
        // sequence stops here and asks rather than rolling on — darks taken through an uncovered
        // lens are not darks, they are light frames filed under `darks/`, and nothing downstream
        // can tell the difference.
        if (!awaitLensCovered(session, request)) return

        writer.setState(SessionState.DARKS)
        _progress.value = _progress.value.copy(
            state = SessionState.DARKS,
            target = request.darkCount,
        )

        // Everything the sensor produced before the lens went on belongs to the previous
        // generation and must not be filed as a dark.
        session.apply(request.iso, request.exposureNs, request.focusDiopters)
        val firstDarkGeneration = session.currentGeneration

        val darksAlready = writer.log.darks.size
        for (index in (darksAlready + 1)..request.darkCount) {
            if (stopRequested) break
            captureOne(session, request, FrameKind.DARK, index, firstDarkGeneration)
        }
    }

    /**
     * Waits for the lens to be covered, and gives up after [DARK_PROMPT_TIMEOUT_MS].
     *
     * The timeout is the point. Waiting forever holds the camera open and the wake lock high all
     * night for someone who has gone to bed; skipping immediately throws away the darks of anyone
     * who is standing right there. Waiting a while and then finishing cleanly, with the reason in
     * the log, is the only option that is honest in both cases.
     *
     * @return true if darks should be captured.
     */
    private suspend fun awaitLensCovered(
        session: SequenceSession,
        request: Request,
    ): Boolean {
        darksConfirmed = false
        darksSkipped = false

        // The sensor is stopped while waiting: this could be ten minutes, and there is no reason
        // to keep reading out a frame a second into a folder nobody will look at.
        session.stopRepeating()
        writer.setState(SessionState.AWAITING_DARKS)
        _progress.value = _progress.value.copy(
            state = SessionState.AWAITING_DARKS,
            target = request.darkCount,
            message = "Cover the lens for ${request.darkCount} dark frames",
        )

        val deadline = environment.nowEpochMs() + DARK_PROMPT_TIMEOUT_MS
        while (!darksConfirmed && !darksSkipped && !stopRequested) {
            if (environment.nowEpochMs() > deadline) {
                Log.i(TAG, "no answer on the darks prompt — finishing without them")
                writer.update {
                    it.copy(
                        info = it.info.copy(
                            exposureDerivation = it.info.exposureDerivation +
                                "darks skipped: nobody confirmed the lens was covered within " +
                                "${DARK_PROMPT_TIMEOUT_MS / 60_000} minutes",
                        ),
                    )
                }
                return false
            }
            delay(PAUSE_POLL_MS)
        }
        if (darksSkipped) {
            writer.update {
                it.copy(
                    info = it.info.copy(
                        exposureDerivation = it.info.exposureDerivation + "darks skipped by the user",
                    ),
                )
            }
        }
        return darksConfirmed && !stopRequested
    }

    private suspend fun captureOne(
        session: SequenceSession,
        request: Request,
        kind: FrameKind,
        index: Int,
        minGeneration: Int = 0,
    ) {
        // Published before the wait, not after: this is the only moment the UI can learn that an
        // exposure is running rather than finished.
        _progress.value = _progress.value.copy(
            frameStartedElapsedNs = android.os.SystemClock.elapsedRealtimeNanos(),
            frameExposureNs = request.exposureNs,
        )

        val frame = session.nextVerifiedFrame(
            timeoutMs = timeoutFor(request.exposureNs),
            exposureNs = request.exposureNs,
            minGeneration = minGeneration,
            // Past the sensor's advertised ceiling the app is off-contract by choice (§1.20), so a
            // frame returning the wrong exposure is a decline rather than a settle — and worth
            // failing fast on, because at these lengths every skipped frame costs minutes.
            refuseAfter = if (request.exposureNs > statedCeilingNs(request.cameraId)) {
                REFUSE_AFTER_FRAMES
            } else {
                null
            },
        )
        val reading = environment.reading()
        val capturedAt = environment.nowEpochMs()

        // The sensor buffer is held only for as long as the DNG write and the pixel copy take —
        // the camera is one buffer short until it is released, and the next exposure is already
        // running.
        val pixels = buffer ?: ShortArray(frame.width * frame.height).also { buffer = it }
        val appliedIso = frame.appliedIso ?: request.iso
        val appliedExposure = frame.appliedExposureNs ?: request.exposureNs
        /*
         * The exposure window, and the sign of it is measured rather than read from the docs.
         *
         * `SENSOR_TIMESTAMP` is documented as the start of exposure of the first row. On this
         * device it is not. Measured 2026-08-18 with 7.4 s subs: frames are analysed a stable
         * **3.35-3.38 s after** their own timestamp, which is impossible if the exposure had only
         * started then, and consecutive timestamps sit exactly one exposure apart. Both facts fit
         * a timestamp taken at the *end* of exposure and nothing else.
         *
         * Getting this backwards put the window entirely in the future, where the gyro record
         * cannot reach, so every query returned "unmeasured" and the bump check was silently off.
         */
        val exposureEndNs = frame.timestampNs
        val exposureStartNs = exposureEndNs - appliedExposure

        val record = frame.use { captured ->
            val stored = writer.writeFrame(
                kind = kind,
                index = index,
                record = { fileName ->
                    // Placeholder — the real metrics are not known until the pixels are analysed,
                    // which happens after the bytes are safely down. Rewritten below.
                    FrameRecord(
                        index = index,
                        fileName = fileName,
                        kind = kind,
                        capturedAtEpochMs = capturedAt,
                        iso = appliedIso,
                        exposureNs = appliedExposure,
                        temperatureC = reading.batteryTempC,
                        hfr = null,
                        starCount = null,
                        eccentricity = null,
                        backgroundAdu = null,
                        accepted = true,
                        thermalHeadroom = reading.headroom,
                        batteryPercent = reading.batteryPercent,
                    )
                },
                write = { out ->
                    // T-3.16: the frame states its own identity, so a DNG separated from
                    // session.json can still say which session and which sub it is.
                    captured.writeDng(
                        out = out,
                        // Measured: DngCreator leaves Orientation at 9, which is not a value TIFF
                        // defines (1-8) and leaves every reader free to invent one. On a tripod
                        // the answer is always "do not rotate" — the sensor data is identical
                        // whichever way up the phone is, and a reader that rotated one frame and
                        // not another would break the stack.
                        orientation = ExifInterface.ORIENTATION_NORMAL,
                        location = dngLocation,
                        description = FrameDescription.of(
                            sessionId = writer.log.info.sessionId,
                            index = index,
                            kind = kind,
                            iso = appliedIso,
                            exposureNs = appliedExposure,
                            capturedAtEpochMs = capturedAt,
                            temperatureC = reading.batteryTempC,
                            thermalHeadroom = reading.headroom,
                            batteryPercent = reading.batteryPercent,
                            focusDiopters = request.focusDiopters,
                        ),
                    )
                },
            )
            captured.copyPixels(pixels)
            stored
        }

        // Analysis runs with the sensor buffer already released, so it overlaps the next
        // exposure rather than delaying it (≈130 ms against a multi-second sub).
        val analysis = analyse(session, pixels, request)
        val stars = analysis?.stars

        // T-4.4 — registered against the session's reference frame, lights only. A dark has no
        // stars by construction, and running registration over one would establish a reference
        // made of hot pixels.
        val registered = if (kind == FrameKind.LIGHT && analysis != null && stars != null) {
            runCatching {
                registration.register(
                    stars = stars.stars,
                    plane = analysis.plane,
                    sensorWidth = session.plan.raw.width,
                    sensorHeight = session.plan.raw.height,
                )
            }.onFailure { Log.w(TAG, "registration failed for frame $index", it) }.getOrNull()
        } else {
            null
        }

        val metrics = FrameGate.Metrics(
            starCount = stars?.count ?: 0,
            medianEccentricity = stars?.medianEccentricity,
            saturated = stars?.saturatedFrame ?: false,
            medianHfr = stars?.medianHfr,
            // Bounded by the exposure itself, so motion during the readout or the DNG write
            // cannot condemn a frame whose pixels are clean — see [DeviceEnvironment].
            peakTiltDeg = environment.peakRotationDegDuring(
                startNs = exposureStartNs,
                endNs = exposureEndNs,
            ),
            registrationBumped = registered?.bumped ?: false,
            registrationFailed = registered?.failed ?: false,
            registrationDetail = registered?.let { registration.describe(it) },
        )
        // The rotation is worth a line even when the frame passes: "unmeasured" and "did not
        // move" are the same verdict and very different facts, and only this distinguishes them.
        Log.d(
            TAG,
            "frame $index rotation=" +
                (metrics.peakTiltDeg?.let { "%.3f deg".format(it) } ?: "unmeasured"),
        )

        // A dark is not judged on its stars — it is supposed to have none. Running the gate over
        // darks would reject every one of them as cloud.
        val verdict = if (kind == FrameKind.DARK) FrameGate.Verdict(true) else gate.accept(metrics)

        val measured = record.copy(
            hfr = stars?.medianHfr,
            starCount = stars?.count,
            eccentricity = stars?.medianEccentricity,
            backgroundAdu = stars?.background,
            accepted = verdict.accepted,
            rejectReason = verdict.reason,
            rejectDetail = verdict.detail,
            // FR-9.2's per-frame transform, in **sensor** coordinates so a restack working at full
            // resolution can use it directly. Recorded even for a rejected frame: D-10 keeps the
            // evidence, and a frame rejected for cloud may still be worth including by hand later.
            transform = registered?.transform?.toMatrix(),
        )
        writer.update { log ->
            log.copy(frames = log.frames.map { if (it.index == index && it.kind == kind) measured else it })
        }

        if (verdict.accepted && kind == FrameKind.LIGHT && analysis != null) {
            updatePreview(analysis)
        }

        val log = writer.log
        _progress.value = _progress.value.copy(
            framesCaptured = log.lights.size,
            framesAccepted = log.accepted.size,
            darksCaptured = log.darks.size,
            lastHfr = stars?.medianHfr,
            lastStarCount = stars?.count,
            lastBackground = stars?.background,
            lastRejection = verdict.detail,
            thermalNote = thermal.evaluate(reading).note,
            preview = preview?.toArgb(),
            previewDepth = preview?.depth ?: 0,
            log = log,
        )
    }

    /** The binned plane and what was found in it — the preview needs the pixels, not just stars. */
    private class Analysis(val plane: BinnedPlane, val stars: FrameStars)

    private fun analyse(
        session: SequenceSession,
        pixels: ShortArray,
        request: Request,
    ) = runCatching {
        val binned = CfaBinner.binGreen(
            pixels = pixels,
            width = session.plan.raw.width,
            height = session.plan.raw.height,
            cfaCodes = session.cfaCodes,
            factor = session.plan.binFactor,
        )
        Analysis(
            binned,
            (detector ?: StarDetector(saturationLevel = session.whiteLevel))
                .detect(binned.data, binned.width, binned.height),
        )
    }.onFailure { Log.w(TAG, "frame analysis failed", it) }.getOrNull()

    /**
     * Folds an accepted light into the preview (D-18: no rejection logic of its own — the gate has
     * already decided).
     *
     * A frame whose offset cannot be established is **skipped rather than stacked unaligned**. The
     * vote returning null means the frames do not agree, and adding it anyway would smear the one
     * thing the preview exists to show.
     */
    private fun updatePreview(analysis: Analysis) {
        val stack = preview ?: PreviewStack(PreviewStack.WIDTH, PreviewStack.HEIGHT)
            .also { preview = it }
        val stars = analysis.stars.stars
        val previous = previousStars

        if (previous != null) {
            // D-18: no rejection logic of its own. A failed vote is not a verdict on the frame —
            // the gate already passed it — so the frame still goes in, carried on the last known
            // drift rather than dropped. Skipping instead leaves the depth stuck at 1 while the
            // counter climbs, which reads as a broken app rather than a cautious one.
            val delta = StarOffset.estimate(previous, stars)
            if (delta != null) {
                lastDeltaX = delta.dx
                lastDeltaY = delta.dy
            }
            offsetX += lastDeltaX
            offsetY += lastDeltaY
        }
        previousStars = stars

        stack.add(
            analysis.plane.data,
            analysis.plane.width,
            analysis.plane.height,
            offsetX,
            offsetY,
        )
    }

    private suspend fun awaitUnpaused() {
        if (!paused) return
        writer.setState(SessionState.PAUSED)
        _progress.value = _progress.value.copy(state = SessionState.PAUSED)
        while (paused && !stopRequested) delay(PAUSE_POLL_MS)
        writer.setState(SessionState.CAPTURING)
        _progress.value = _progress.value.copy(state = SessionState.CAPTURING)
    }

    private suspend fun pauseForHeatIfNeeded() {
        val decision = thermal.evaluate(environment.reading())
        _progress.value = _progress.value.copy(
            thermalNote = decision.note,
            cooling = decision.pausing,
        )
        if (decision.pausing) {
            Log.i(TAG, "thermal gap ${decision.gapSeconds}s — ${decision.note}")
            delay((decision.gapSeconds * 1000).toLong())
            _progress.value = _progress.value.copy(cooling = false)
        }
    }

    /** Frame-budgeted like the focus sweep's, for the same measured reason (§1.7). */
    private fun timeoutFor(exposureNs: Long): Long =
        exposureNs / 1_000_000 * (FramingSession.PIPELINE_DEPTH_FRAMES + 6) + 15_000L

    private companion object {
        const val TAG = "CaptureEngine"
        const val PAUSE_POLL_MS = 250L

        /** How long the darks prompt waits for an answer before finishing without them. */
        const val DARK_PROMPT_TIMEOUT_MS = 15L * 60 * 1000

        /**
         * Consecutive wrong-exposure frames tolerated past the stated ceiling before the session
         * gives up with `ExposureRefused`.
         *
         * Three, not one: the sensor legitimately takes a frame or two to apply a change, and
         * those settling frames come back at the *previous* exposure, which is short. Three is
         * enough to let a settle finish and few enough that a device which really does clamp is
         * caught in a couple of frames rather than after a whole night of two-minute discards.
         */
        const val REFUSE_AFTER_FRAMES = 3
    }
}
