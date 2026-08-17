package com.starstacker.focus

import android.util.Log
import com.starstacker.camera.FramingRequest
import com.starstacker.camera.FramingSession

/**
 * Drives a focus sweep on a live [FramingSession] (T-2.4) and re-verifies a stored one (T-2.5).
 *
 * The sweep is just the framing loop with the lens stepped between frames, which is why it costs
 * a sweep's worth of exposures and no extra camera plumbing: each position submits a request and
 * waits for a frame whose metadata confirms the lens actually arrived and stopped moving. A
 * frame taken while the VCM is still travelling measures the motor, not the focus.
 */
class FocusRunner(
    private val session: FramingSession,
    /** The lens's near limit in dioptres; positions beyond it are silently ignored by the HAL. */
    private val maxDiopters: Float = Float.MAX_VALUE,
) {

    data class Progress(val index: Int, val total: Int, val sample: FocusSample)

    data class Outcome(
        val curve: FocusCurve?,
        val record: FocusRecord?,
        val status: FocusStatus,
        val message: String,
    )

    /**
     * A cold sweep from the near end down to infinity, always descending so every position is
     * approached from the same side (hysteresis, FR-4.1.4).
     */
    suspend fun sweep(
        cameraId: String,
        iso: Int,
        exposureNs: Long,
        altitudeDeg: Double?,
        positions: List<Float> = FocusSweep.infinitySweep(maxDiopters = maxDiopters),
        onProgress: (Progress) -> Unit = {},
    ): Outcome {
        require(positions.isNotEmpty()) { "a sweep needs at least one position" }

        // Overshoot first so the motor takes up its own backlash before the first measurement.
        park(FocusSweep.parkPosition(positions.first(), maxDiopters), iso, exposureNs)

        val samples = mutableListOf<FocusSample>()
        positions.forEachIndexed { index, diopters ->
            val sample = measureAt(diopters, iso, exposureNs)
            samples += sample
            onProgress(Progress(index + 1, positions.size, sample))
        }

        val curve = FocusSweep.analyse(samples)
        Log.i(TAG, "sweep: ${curve.verdict} at ${curve.bestDiopters} (${curve.note})")

        if (!curve.usable) {
            return Outcome(
                curve = curve,
                record = null,
                status = FocusStatus.UNKNOWN,
                message = curve.note,
            )
        }

        // Land on the answer from the same direction the sweep approached it from, so the
        // position that gets stored is the position the lens is actually sitting at.
        park(FocusSweep.parkPosition(curve.bestDiopters, maxDiopters), iso, exposureNs)
        val confirm = measureAt(curve.bestDiopters, iso, exposureNs)

        val record = FocusRecord(
            cameraId = cameraId,
            fixedFocus = false,
            diopters = curve.bestDiopters,
            hfr = confirm.hfr ?: curve.bestHfr,
            starCount = confirm.starCount,
            altitudeDeg = altitudeDeg,
            exposureNs = exposureNs,
            iso = iso,
            verdict = curve.verdict.name,
            capturedAtEpochMs = System.currentTimeMillis(),
        )
        return Outcome(
            curve = curve,
            record = record,
            status = FocusStatus.LOCKED,
            message = "HFR %.2f px at %.3f dioptres · %s".format(
                record.hfr, record.diopters, curve.verdict.name.lowercase().replace('_', ' '),
            ),
        )
    }

    /**
     * FR-6.3 at session start: drive to the stored position, measure once, and only sweep again
     * if it has actually degraded. A stored focus that still holds costs one frame to confirm.
     */
    suspend fun verify(
        stored: FocusRecord,
        iso: Int,
        exposureNs: Long,
        altitudeDeg: Double?,
    ): Outcome {
        park(FocusSweep.parkPosition(stored.diopters, maxDiopters), iso, exposureNs)
        val sample = measureAt(stored.diopters, iso, exposureNs)
        val status = FocusMonitor.verify(sample.hfr, sample.starCount, stored)

        if (status == FocusStatus.LOCKED) {
            return Outcome(
                curve = null,
                record = stored,
                status = status,
                message = "stored focus holds — HFR %.2f px against %.2f px stored".format(
                    sample.hfr ?: Double.NaN, stored.hfr,
                ),
            )
        }
        if (status == FocusStatus.UNKNOWN) {
            return Outcome(
                curve = null,
                record = stored,
                status = status,
                message = "only ${sample.starCount} stars — cannot verify focus against this sky",
            )
        }

        val sagNote = stored.altitudeDeg?.let { calibratedAt ->
            altitudeDeg?.let { now ->
                " (calibrated at %.0f° elevation, now %.0f° — gravity sag)".format(calibratedAt, now)
            }
        }.orEmpty()
        Log.i(TAG, "stored focus drifted ($status)$sagNote — local re-sweep")

        val local = sweep(
            cameraId = stored.cameraId,
            iso = iso,
            exposureNs = exposureNs,
            altitudeDeg = altitudeDeg,
            positions = FocusSweep.localSweep(stored.diopters, maxDiopters = maxDiopters),
        )
        return local.copy(
            message = "stored focus had drifted to HFR %.2f px$sagNote — re-swept: %s".format(
                sample.hfr ?: Double.NaN, local.message,
            ),
        )
    }

    private suspend fun park(diopters: Float, iso: Int, exposureNs: Long) {
        session.apply(FramingRequest(iso, exposureNs, diopters))
        runCatching { session.awaitSettledFrame(timeoutFor(exposureNs)) }
    }

    private suspend fun measureAt(diopters: Float, iso: Int, exposureNs: Long): FocusSample {
        session.apply(FramingRequest(iso, exposureNs, diopters))
        val frame = runCatching { session.awaitSettledFrame(timeoutFor(exposureNs)) }.getOrNull()
            ?: return FocusSample(diopters, null, 0)
        return FocusSample(
            diopters = frame.appliedFocus ?: diopters,
            hfr = frame.stars.medianHfr,
            starCount = frame.stars.count,
        )
    }

    /**
     * Generous on purpose: sensor settings take several frames to apply (T-1.4), and at a
     * four-second framing exposure "several frames" is most of a minute.
     */
    private fun timeoutFor(exposureNs: Long): Long = exposureNs / 1_000_000 * 6 + 8_000L

    private companion object {
        const val TAG = "FocusRunner"
    }
}
