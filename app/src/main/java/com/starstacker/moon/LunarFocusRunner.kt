package com.starstacker.moon

import android.util.Log
import com.starstacker.camera.FramingRequest
import com.starstacker.camera.FramingSession
import com.starstacker.focus.FocusSweep

/**
 * T-11.3 — driving a focus sweep against the moon.
 *
 * ### Why this is here and not a branch inside `FocusRunner`
 *
 * `FocusRunner` owns the motor discipline — overshoot to take up backlash, always approach a
 * setpoint from the near side, wait for a *stable* frame rather than a settled one — and none of
 * that changes for a lunar target. What changes is only what gets measured at each position.
 *
 * It lives in this package rather than as a second method on `FocusRunner` so the dependency
 * points one way. `moon` already depends on `focus` for [FocusSweep]'s position generators and its
 * parabola; making `focus` depend back on `moon` for a sharpness metric would leave the two
 * packages unable to be read independently. The duplicated part is the eight lines of park-and-
 * step below, which is a cheaper price than the cycle.
 *
 * ### What it measures, and where
 *
 * The binned analysis plane, not the full RAW: [FramingSession] already produces it for the star
 * detector, it is the same plane [FocusSweep]'s HFR is measured on, and binning suppresses exactly
 * the per-pixel noise a sharpness metric is most easily fooled by. The disc is located per frame
 * rather than once, because the whole point of a sweep is that the frame changes.
 */
class LunarFocusRunner(
    private val session: FramingSession,
    /** The lens's near limit in dioptres; positions past it are silently ignored by the HAL. */
    private val maxDiopters: Float = Float.MAX_VALUE,
) {

    data class Progress(val index: Int, val total: Int, val sample: LunarFocus.Sample)

    data class Outcome(
        val curve: LunarFocus.Curve,
        /** True when the sweep produced a position worth applying. */
        val usable: Boolean,
        val message: String,
    )

    /**
     * A sweep across focus, reading sharpness over the disc at each position.
     *
     * @param whiteLevel the sensor's white level, for the clipping count [Disc] reports. The plane
     *   is float and already black-subtracted, so this only affects that count.
     */
    suspend fun sweep(
        iso: Int,
        exposureNs: Long,
        whiteLevel: Double,
        positions: List<Float> = FocusSweep.infinitySweep(maxDiopters = maxDiopters),
        onProgress: (Progress) -> Unit = {},
    ): Outcome {
        require(positions.isNotEmpty()) { "a sweep needs at least one position" }

        // Overshoot first, so the motor takes up its own backlash before anything is measured.
        park(FocusSweep.parkPosition(positions.first(), maxDiopters), iso, exposureNs)

        val samples = mutableListOf<LunarFocus.Sample>()
        positions.forEachIndexed { index, diopters ->
            val sample = measureAt(diopters, iso, exposureNs, whiteLevel)
            samples += sample
            onProgress(Progress(index + 1, positions.size, sample))
        }

        val curve = LunarFocus.analyse(samples)
        Log.i(TAG, "lunar sweep: ${curve.verdict} at ${curve.bestDiopters} (${curve.note})")

        if (curve.usable) {
            // Approached from the near side like every other position, so the lens arrives the
            // same way it did when the winning measurement was taken.
            park(FocusSweep.parkPosition(curve.bestDiopters, maxDiopters), iso, exposureNs)
            session.apply(FramingRequest(iso, exposureNs, curve.bestDiopters))
        }

        return Outcome(
            curve = curve,
            usable = curve.usable,
            message = when (curve.verdict) {
                LunarFocus.Verdict.CLEAR_PEAK ->
                    "focused at %.4f dioptres — %s".format(curve.bestDiopters, curve.note)
                LunarFocus.Verdict.PEAK_AT_EDGE ->
                    "best at %.4f dioptres, but at the end of the sweep — %s"
                        .format(curve.bestDiopters, curve.note)
                LunarFocus.Verdict.FLAT -> "could not focus: ${curve.note}"
                LunarFocus.Verdict.NO_DISC -> "could not focus: ${curve.note}"
            },
        )
    }

    private suspend fun park(diopters: Float, iso: Int, exposureNs: Long) {
        session.apply(FramingRequest(iso, exposureNs, diopters))
        runCatching { session.awaitStableFrame(timeoutFor(exposureNs)) }
    }

    private suspend fun measureAt(
        diopters: Float,
        iso: Int,
        exposureNs: Long,
        whiteLevel: Double,
    ): LunarFocus.Sample {
        session.apply(FramingRequest(iso, exposureNs, diopters))
        // Stable rather than merely settled: the sample is filed under the position the lens
        // reports, so measuring before the motor has arrived files a real reading under the wrong
        // position — and a sweep whose x-axis is wrong interpolates confidently to nowhere.
        val frame = runCatching { session.awaitStableFrame(timeoutFor(exposureNs)) }.getOrNull()
            ?: return LunarFocus.Sample(diopters, null, 0)

        val plane = frame.plane
        val pixels = DoubleArray(plane.width * plane.height) { plane.data[it].toDouble() }
        val disc = Disc.find(pixels, plane.width, plane.height, whiteLevel)

        return LunarFocus.Sample(
            diopters = frame.appliedFocus ?: diopters,
            sharpness = Sharpness.overDisc(pixels, plane.width, plane.height, disc),
            discPixels = disc?.pixelCount ?: 0,
        )
    }

    /**
     * Budgeted in **frames**, not seconds — the same reasoning as `FocusRunner`'s.
     *
     * The pipeline is ten frames deep, so the wait has to scale with the exposure. Lunar exposures
     * are sub-millisecond, which makes the constant term the whole budget here; it is kept in the
     * same shape as the deep-sky runner's so the two cannot drift apart.
     */
    private fun timeoutFor(exposureNs: Long): Long =
        exposureNs / 1_000_000 * (FramingSession.PIPELINE_DEPTH_FRAMES + 6) + 8_000L

    private companion object {
        const val TAG = "LunarFocusRunner"
    }
}
