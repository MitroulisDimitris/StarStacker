package com.starstacker.diag

import com.starstacker.camera.CameraAccess
import com.starstacker.camera.FramingFrame
import com.starstacker.camera.FramingRequest
import com.starstacker.camera.FramingSession
import com.starstacker.device.CameraProfile
import com.starstacker.device.NoiseProfileEntry
import com.starstacker.exposure.ExposureSolver
import com.starstacker.exposure.OemNoiseModel
import com.starstacker.exposure.SkyMeasurement
import com.starstacker.exposure.TrailingLimit
import com.starstacker.focus.FocusRunner
import com.starstacker.focus.FocusSweep
import com.starstacker.stars.CfaBinner
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Head-free versions of the Phase 1B checks, driven from `adb` rather than from the UI.
 *
 * Phase 1B was written with no device attached, so every one of its acceptances is a claim. The
 * ones that need a night sky have to wait for one; the ones that only need a camera — does the
 * HAL accept the two-stream configuration, does a repeating one-second RAW request actually
 * deliver, does the lens go where it is told — do not, and waiting for darkness to find out that
 * the session will not configure would waste the night.
 *
 * These run without a preview, so they also test the property D-22 claims: nothing in a capture
 * session belongs to the display. A run that survives the screen being switched off mid-loop is
 * the T-2.1 screen-off acceptance, demonstrated rather than argued.
 */
object FieldDiagnostics {

    /**
     * T-2.1 + T-2.2: configure, stream, and measure. Logs one line per frame with the interval
     * since the previous one, which is what tells a stalled buffer queue apart from a slow HAL.
     */
    suspend fun framing(
        access: CameraAccess,
        cameraId: String,
        frameCount: Int,
        iso: Int,
        exposureNs: Long,
        log: (String) -> Unit,
    ) {
        log("--- framing loop: camera $cameraId, $frameCount frames at ISO $iso / ${exposureNs / 1_000_000} ms")
        FramingSession.open(access, cameraId).use { session ->
            log("plan: ${session.plan.reason}")
            log("support: ${session.support.detail}")
            log("supported=${session.support.supported} guaranteed=${session.support.guaranteedBy}")
            log("sensor: orientation ${session.sensorOrientation}°, CFA ${session.cfaCodes}, " +
                "black ${session.blackLevel}, white ${session.whiteLevel}")

            val openedAt = System.currentTimeMillis()
            session.apply(FramingRequest(iso = iso, exposureNs = exposureNs, focusDiopters = 0f))

            var previous = openedAt
            var seen = 0
            val collected = withTimeoutOrNull(exposureNs / 1_000_000 * frameCount * 4 + 30_000L) {
                session.frames.take(frameCount).collect { frame ->
                    val now = System.currentTimeMillis()
                    seen++
                    log(
                        "frame $seen: +${now - previous} ms · gen ${frame.generation} · " +
                            "settled=${frame.settled} · " +
                            "iso ${frame.appliedIso} · exp ${frame.appliedExposureNs?.div(1_000_000)} ms · " +
                            "focus ${frame.appliedFocus} · lensStationary=${frame.lensStationary} · " +
                            "stars ${frame.stars.count} · hfr ${fmt(frame.stars.medianHfr)} · " +
                            "bg ${fmt(frame.stars.background)}" +
                            (if (frame.stars.saturatedFrame) " CLIPPED" else "") + " · " +
                            "noise ${fmt(frame.stars.noise)} · " +
                            "analysis ${frame.analysisMs} ms · " +
                            "preview ${frame.preview.width}x${frame.preview.height}",
                    )
                    previous = now
                }
            }
            if (collected == null) log("TIMED OUT after $seen of $frameCount frames")
            log("framing loop done: $seen frames in ${System.currentTimeMillis() - openedAt} ms")
        }
    }

    /**
     * Where can this lens actually go?
     *
     * `LENS_FOCUS_DISTANCE` is a request, not a command: the HAL is free to clamp it, and on an
     * `APPROXIMATE`-calibration lens the value that comes back is under no obligation to equal
     * the value that went in. Everything in T-2.4 rests on the answer, so it is measured rather
     * than read off the characteristics — this walks a ladder of positions and reports where the
     * lens actually landed at each, with no settle requirement to hide a lens that never moved.
     */
    suspend fun lensRange(
        access: CameraAccess,
        cameraId: String,
        iso: Int,
        exposureNs: Long,
        requests: List<Float>,
        framesPerPosition: Int,
        log: (String) -> Unit,
    ) {
        log("--- lens range: camera $cameraId, ${requests.size} positions, " +
            "$framesPerPosition frames each at ${exposureNs / 1_000_000} ms")
        FramingSession.open(access, cameraId).use { session ->
            for (asked in requests) {
                session.apply(FramingRequest(iso, exposureNs, asked))
                val seen = mutableListOf<String>()
                withTimeoutOrNull(exposureNs / 1_000_000 * framesPerPosition * 3 + 15_000L) {
                    session.frames.take(framesPerPosition).collect { frame ->
                        seen += "%.4f%s".format(
                            frame.appliedFocus ?: Float.NaN,
                            if (frame.lensStationary) "" else "*",
                        )
                    }
                }
                log("asked %.4f -> %s".format(asked, seen.joinToString(" ")))
            }
        }
        log("(* = LENS_STATE reports MOVING)")
    }

    /**
     * T-2.4: does the lens actually move, and does its reported position track the request?
     *
     * Indoors this will almost certainly land on `TOO_FEW_STARS` — that is the expected answer,
     * and it is still worth running. The verdict is not the thing under test here; the applied
     * focus per position is. A sweep whose `appliedFocus` never leaves 0.0 is a sweep that will
     * silently report a flat curve under a real sky.
     */
    suspend fun focusSweep(
        access: CameraAccess,
        cameraId: String,
        iso: Int,
        exposureNs: Long,
        maxDiopters: Float,
        log: (String) -> Unit,
    ) {
        log("--- focus sweep: camera $cameraId, near limit ${maxDiopters} dioptres")
        val positions = FocusSweep.infinitySweep(maxDiopters = maxDiopters)
        log("positions: " + positions.joinToString { "%.3f".format(it) })
        log("park at %.3f".format(FocusSweep.parkPosition(positions.first(), maxDiopters)))

        FramingSession.open(access, cameraId).use { session ->
            session.apply(FramingRequest(iso, exposureNs, 0f))
            val started = System.currentTimeMillis()
            val outcome = FocusRunner(session, maxDiopters).sweep(
                cameraId = cameraId,
                iso = iso,
                exposureNs = exposureNs,
                altitudeDeg = null,
                positions = positions,
            ) { progress ->
                log(
                    "  ${progress.index}/${progress.total}: asked ${"%.3f".format(positions[progress.index - 1])} · " +
                        "applied ${"%.3f".format(progress.sample.diopters)} · " +
                        "hfr ${fmt(progress.sample.hfr)} · stars ${progress.sample.starCount}",
                )
            }
            log("verdict ${outcome.status} · ${outcome.message}")
            outcome.curve?.let { log("curve: ${it.verdict} at ${it.bestDiopters} — ${it.note}") }
            log("sweep took ${(System.currentTimeMillis() - started) / 1000} s")
        }
    }

    /**
     * T-3.1 through T-3.5 against the real sensor.
     *
     * Sweeps the candidate ISOs collecting `SENSOR_NOISE_PROFILE` from each frame's metadata,
     * measures the sky background from the last frame, and runs the trailing limit, the solver
     * and the planner over the result — printing the whole derivation.
     *
     * This is the experiment **OI-9** names: whether the OEM's noise figures are good enough to
     * pick a sane ISO at Functional tier. It cannot be answered by a unit test, because the
     * question is not whether the arithmetic is right but whether the inputs are.
     */
    suspend fun solveExposure(
        access: CameraAccess,
        camera: CameraProfile,
        isos: List<Int>,
        exposureNs: Long,
        log: (String) -> Unit,
    ) {
        log("--- exposure solve: camera ${camera.id}, ISOs $isos at ${exposureNs / 1_000_000} ms")

        val profiles = LinkedHashMap<Int, List<NoiseProfileEntry>>()
        val frames = mutableListOf<FramingFrame>()

        FramingSession.open(access, camera.id).use { session ->
            for (iso in isos) {
                session.apply(FramingRequest(iso, exposureNs, null))
                val frame = runCatching {
                    session.awaitSettledFrame(
                        exposureNs / 1_000_000 * (FramingSession.PIPELINE_DEPTH_FRAMES + 6) + 8_000L,
                    )
                }.getOrNull()

                if (frame == null) {
                    log("ISO $iso: no settled frame")
                    continue
                }
                frames += frame
                val profile = frame.noiseProfile
                if (profile == null) {
                    log("ISO $iso: SENSOR_NOISE_PROFILE absent")
                    continue
                }
                profiles[iso] = profile
                log(
                    "ISO %d (applied %s): profile %s · bg %.1f ADU%s".format(
                        iso, frame.appliedIso,
                        profile.joinToString { "(%.3e, %.3e)".format(it.s, it.o) },
                        frame.stars.background,
                        if (frame.stars.saturatedFrame) " CLIPPED" else "",
                    ),
                )
            }
        }

        if (profiles.isEmpty()) {
            log("no noise profiles collected — the OEM path of T-3.1 is unavailable on this device")
            return
        }

        val whiteLevel = camera.whiteLevel ?: 1023
        val blackLevel = camera.blackLevelPattern?.average() ?: 0.0
        val model = OemNoiseModel.from(
            profilesByIso = profiles,
            cfaCodes = CfaBinner.codesFor(camera.cfaArrangement),
            whiteLevel = whiteLevel,
            blackLevel = blackLevel,
        )

        log("")
        log("noise model — ${model.source}, measured at ${model.measuredIsos}")
        for (iso in model.measuredIsos) {
            val n = model.at(iso)!!
            log(
                "  ISO %5d: full scale %8.0f e-, read noise %6.2f e-, %6.2f e-/ADU"
                    .format(iso, n.fullScaleElectrons, n.readNoiseElectrons, n.electronsPerAdu),
            )
        }
        val dualGain = model.dualGainIso()
        log("  dual conversion gain point: ${dualGain ?: "none visible"}")

        // Whether the OEM figures vary with ISO at all is the first thing OI-9 needs to know: a
        // profile that is identical at every ISO is a stub, not a measurement.
        val distinctRead = model.measuredIsos.mapNotNull { model.at(it)?.readNoiseElectrons }
            .map { "%.3f".format(it) }.distinct()
        log(
            "  read noise varies with ISO: ${distinctRead.size > 1} " +
                "(${distinctRead.size} distinct values across ${model.measuredIsos.size} ISOs)",
        )

        // The sky must be measured from a frame that is not clipped, and among those the highest
        // ISO — the one with the most signal above the noise. Taking whichever frame happened to
        // be last picks the *highest* ISO of the sweep, which is the one most likely to be
        // saturated; that is how the first run of this diagnostic measured the sky from a frame
        // pinned at the white level.
        val frame = frames.filter { !it.stars.saturatedFrame && it.appliedIso != null }
            .maxByOrNull { it.appliedIso!! }
        if (frame == null) {
            log(
                "every frame in the sweep was clipped — no sky measurement is possible. " +
                    "Shorten the test exposure or point somewhere darker.",
            )
            return
        }
        log("measuring the sky from the brightest unclipped frame, ISO ${frame.appliedIso}")
        val skyIso = frame.appliedIso!!
        val measuredNoise = model.at(skyIso) ?: run {
            log("no noise data at ISO $skyIso")
            return
        }

        val sky = SkyMeasurement.from(
            backgroundAdu = frame.stars.background,
            blackLevelAdu = blackLevel,
            whiteLevelAdu = whiteLevel,
            iso = skyIso,
            exposureSeconds = (frame.appliedExposureNs ?: exposureNs) / 1e9,
            noise = measuredNoise,
        )
        log("")
        log(
            "sky: %.1f ADU background at ISO %d / %.2f s → %.1f e-/s (%.1f%% of full scale)%s"
                .format(
                    sky.backgroundAdu, sky.iso, sky.exposureSeconds, sky.electronsPerSecond,
                    sky.backgroundFraction * 100, if (sky.clipped) " CLIPPED" else "",
                ),
        )

        val pitch = camera.effectivePixelPitchUm
        val focal = camera.focalLengthsMm.firstOrNull()?.toDouble()
        val physical = camera.physicalSizeMm
        if (pitch == null || focal == null || physical == null) {
            log("cannot compute a trailing limit without pitch, focal length and sensor size")
            return
        }

        val trailing = TrailingLimit.solve(
            pixelPitchUm = pitch,
            focalLengthMm = focal,
            sensorWidthMm = physical.width.toDouble(),
            sensorHeightMm = physical.height.toDouble(),
            fieldCentreDecDeg = null,
        )
        log("")
        log(
            "trailing: %.2f s at %.1f px tolerance · %.2f arcsec/px · half-field %.1f° · %s"
                .format(
                    trailing.maxExposureSeconds, trailing.tolerancePx, trailing.arcsecPerPixel,
                    trailing.halfFieldDeg, trailing.note,
                ),
        )

        val solution = ExposureSolver.solve(
            sky = sky,
            noiseModel = model,
            trailing = trailing,
            isoCandidates = isos,
            maxExposureSeconds = (camera.exposureMaxSeconds ?: 30.0),
            dualGainIso = dualGain,
        )
        log("")
        log("SOLVE → ${solution.headline}")
        log("  ${solution.advisory}")
        for (candidate in solution.candidates) {
            log(
                "  %s ISO %5d: %s".format(
                    if (candidate.iso == solution.chosen?.iso) "→" else " ",
                    candidate.iso, candidate.reason,
                ),
            )
        }
    }

    private fun fmt(value: Double?) = value?.let { "%.2f".format(it) } ?: "—"
}
