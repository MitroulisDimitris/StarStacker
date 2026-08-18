package com.starstacker.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.util.Log
import com.starstacker.camera.CameraAccess
import com.starstacker.camera.FramingSession
import com.starstacker.camera.SequenceSession
import com.starstacker.session.FrameKind
import com.starstacker.session.FrameRecord
import com.starstacker.session.SessionLog
import com.starstacker.session.SessionState
import com.starstacker.session.SessionWriter
import com.starstacker.stars.CfaBinner
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
         * Peak angular movement of the gravity vector since the last call, **degrees**. Null when
         * there is no accelerometer. See [DeviceEnvironment] for why it is an angle and not an
         * acceleration.
         */
        fun consumePeakTiltDeg(): Double?

        fun nowEpochMs(): Long
    }

    data class Request(
        val cameraId: String,
        val iso: Int,
        val exposureNs: Long,
        val focusDiopters: Float?,
        val lightCount: Int,
        val darkCount: Int,
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

    /** Reused across the whole sequence — a 25 MB allocation per frame is FR-12.2's warning. */
    private var buffer: ShortArray? = null
    private var detector: StarDetector? = null

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
        val frame = session.nextVerifiedFrame(
            timeoutFor(request.exposureNs), request.exposureNs, minGeneration,
        )
        val reading = environment.reading()
        val capturedAt = environment.nowEpochMs()

        // The sensor buffer is held only for as long as the DNG write and the pixel copy take —
        // the camera is one buffer short until it is released, and the next exposure is already
        // running.
        val pixels = buffer ?: ShortArray(frame.width * frame.height).also { buffer = it }
        val appliedIso = frame.appliedIso ?: request.iso
        val appliedExposure = frame.appliedExposureNs ?: request.exposureNs

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
                write = { out -> captured.writeDng(out) },
            )
            captured.copyPixels(pixels)
            stored
        }

        // Analysis runs with the sensor buffer already released, so it overlaps the next
        // exposure rather than delaying it (≈130 ms against a multi-second sub).
        val stars = analyse(session, pixels, request)
        val metrics = FrameGate.Metrics(
            starCount = stars?.count ?: 0,
            medianEccentricity = stars?.medianEccentricity,
            saturated = stars?.saturatedFrame ?: false,
            medianHfr = stars?.medianHfr,
            peakTiltDeg = environment.consumePeakTiltDeg(),
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
        )
        writer.update { log ->
            log.copy(frames = log.frames.map { if (it.index == index && it.kind == kind) measured else it })
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
            log = log,
        )
    }

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
        (detector ?: StarDetector(saturationLevel = session.whiteLevel))
            .detect(binned.data, binned.width, binned.height)
    }.onFailure { Log.w(TAG, "frame analysis failed", it) }.getOrNull()

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
    }
}
