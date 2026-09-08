package com.starstacker.moon

import com.starstacker.exposure.SessionPlanner
import com.starstacker.stacking.FrameQuality
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * T-11.4 — how many frames a lucky-imaging run should shoot, and what that costs.
 *
 * ### The decision §1.45 left open, settled here
 *
 * Lucky imaging wants as many frames as it can get: the whole method is to shoot through the
 * seeing and keep the handful of moments that were steady. The sensor is happy to oblige — the
 * moon is bright enough that exposure is irrelevant to the cadence, and OI-26 measured the real
 * floor at **33.2 ms per frame**, set by full-RAW readout rather than by the 0.13 ms exposure.
 *
 * What is *not* happy to oblige is storage. This app writes a 25 MB DNG per frame for an object
 * that occupies **0.03% of it** — 500 frames is 12.5 GB to photograph something 75 px across.
 * Real lunar imaging uses cropped video for exactly this reason.
 *
 * Three ways out were named in the plan, and the honest v1 is the first:
 *
 * 1. **Cap the frame count** and accept less luck. Implemented here.
 * 2. **Crop on write** — the right answer eventually, and a new on-disk shape: the DNG would no
 *    longer be the full sensor, so every reader, the calibration library and the dark matching all
 *    change. That is a Phase 8 project, not a tweak.
 * 3. **Treat the moon as a short session** and keep full frames, which is what a nightscape wants
 *    anyway and is what *moon in scene* (T-11.9) does.
 *
 * ### Why the cap is a budget rather than a number
 *
 * A fixed "shoot 200" would be wrong on both ends: wasteful on a phone with 200 GB free, and a
 * failed session on one with 3 GB. So the count comes from a **storage budget** and a hard ceiling,
 * and [Plan.note] says which of the two bound it — because "I got 120 frames" and "I got 120
 * frames because your phone is nearly full" are different pieces of news.
 */
object LunarPlan {

    /**
     * The most frames one lucky-imaging run will shoot, however much storage there is.
     *
     * Not a storage limit — a diminishing-returns limit. Stacking noise falls as `sqrt(N)`, so past
     * a few hundred frames another hundred buys almost nothing on a subject this bright, while the
     * seeing conditions the run is sampling drift underneath it. And the moon itself moves: at the
     * reference tele's 0.606 px/s worst case, 400 frames at 33 ms is 13 s and 8 px of drift, which
     * alignment handles. Ten thousand frames would be five minutes and 180 px, which is a different
     * picture by the end.
     */
    const val MAX_FRAMES = 400

    /** Below this a lucky-imaging cut has nothing to choose between. */
    const val MIN_FRAMES = 20

    /** How much of the free space a session may claim. The rest is the user's phone. */
    const val STORAGE_FRACTION = 0.25

    /** OI-26, measured: full-RAW readout, not the exposure, sets the cadence. */
    const val READOUT_FLOOR_MS = 33.2

    data class Plan(
        val frameCount: Int,
        val exposureNs: Long,
        val iso: Int,
        val keepPercent: Int,
        /** How many frames survive the cut and are actually stacked. */
        val stackedCount: Int,
        val bytesRequired: Long,
        val seconds: Double,
        /** Worst-case drift of the disc across the whole run, in pixels. */
        val driftPx: Double,
        val note: String,
    ) {
        fun describe(): String =
            "%d frames in %.1f s, keeping best %d%% = %d stacked · %.1f GB · %.0f px of drift"
                .format(frameCount, seconds, keepPercent, stackedCount, bytesRequired / 1e9, driftPx)
    }

    /**
     * Plans a run.
     *
     * @param freeBytes storage available now
     * @param arcsecPerPixel from [LunarGeometry], so the drift estimate is this camera's
     * @param driftArcsecPerSec worst case is the sidereal rate; §1.45 insists this is computed
     *   rather than remembered, since a measured figure was 54% of it purely because the moon was
     *   low
     */
    fun plan(
        exposureNs: Long,
        iso: Int,
        freeBytes: Long,
        arcsecPerPixel: Double,
        driftArcsecPerSec: Double = SIDEREAL_ARCSEC_PER_SEC,
        keepPercent: Int = FrameQuality.LUNAR_KEEP_PERCENT,
        bytesPerFrame: Long = SessionPlanner.DEFAULT_BYTES_PER_FRAME,
        maxFrames: Int = MAX_FRAMES,
    ): Plan {
        val budget = (freeBytes * STORAGE_FRACTION).toLong().coerceAtLeast(0L)
        val affordable = if (bytesPerFrame <= 0) maxFrames else (budget / bytesPerFrame).toInt()

        val wanted = maxFrames
        val count = affordable.coerceAtMost(wanted)

        val note = when {
            count < MIN_FRAMES ->
                "Only $count frames fit in %.1f GB of the %.1f GB free — lucky imaging needs at "
                    .format(budget / 1e9, freeBytes / 1e9) +
                    "least $MIN_FRAMES to have anything to choose between. Free some space."
            affordable < wanted ->
                "Storage caps this run at $count frames (%.1f GB of the %.1f GB free). "
                    .format(count * bytesPerFrame / 1e9, freeBytes / 1e9) +
                    "More space would mean more frames to pick the sharpest from."
            else ->
                "$count frames, the most one run will shoot — past this, `sqrt(N)` has flattened " +
                    "out and the seeing has moved on."
        }

        // The cadence is readout, not exposure: at 0.13 ms there is nothing for the 25 MB write to
        // hide behind, so the frame duration floors at what the sensor can read out (OI-26).
        val perFrameMs = maxOf(READOUT_FLOOR_MS, exposureNs / 1e6)
        val seconds = count * perFrameMs / 1000.0
        val driftPx = if (arcsecPerPixel <= 0) 0.0 else seconds * driftArcsecPerSec / arcsecPerPixel

        return Plan(
            frameCount = count,
            exposureNs = exposureNs,
            iso = iso,
            keepPercent = keepPercent,
            stackedCount = stacked(count, keepPercent),
            bytesRequired = count.toLong() * bytesPerFrame,
            seconds = seconds,
            driftPx = driftPx,
            note = note,
        )
    }

    /** What survives the cut — the same rounding [FrameQuality.keepBest] uses. */
    fun stacked(frameCount: Int, keepPercent: Int): Int {
        if (frameCount <= 0) return 0
        val percent = keepPercent.coerceIn(1, 100)
        return ceil(frameCount * percent / 100.0).toInt().coerceIn(1, frameCount)
    }

    /**
     * How long the *ground* set of a moon-in-scene session must run for the drifting disc to be
     * rejected out of the sky by `SigmaClip` (T-11.9).
     *
     * The disc has to cross its own diameter several times over, so that at any given pixel it is
     * present in a minority of frames. Under this, the composite has to mask a streak instead of a
     * disc; over it, the sky behind the moon comes out clean.
     */
    fun groundSetSeconds(
        moonPixelsAcross: Int,
        arcsecPerPixel: Double,
        driftArcsecPerSec: Double = SIDEREAL_ARCSEC_PER_SEC,
        crossings: Double = CROSSINGS_FOR_REJECTION,
    ): Double {
        if (arcsecPerPixel <= 0 || driftArcsecPerSec <= 0 || moonPixelsAcross <= 0) return 0.0
        val driftPxPerSec = driftArcsecPerSec / arcsecPerPixel
        return crossings * moonPixelsAcross / driftPxPerSec
    }

    /** Rounded to a whole minute, which is the unit a session length is actually chosen in. */
    fun groundSetMinutes(
        moonPixelsAcross: Int,
        arcsecPerPixel: Double,
        driftArcsecPerSec: Double = SIDEREAL_ARCSEC_PER_SEC,
    ): Int = ceil(
        groundSetSeconds(moonPixelsAcross, arcsecPerPixel, driftArcsecPerSec) / 60.0,
    ).roundToInt()

    /**
     * The diurnal rate at the celestial equator, in arcsec per second — the worst case.
     *
     * `15.041 x cos(dec)` is the true rate, and the moon's own eastward motion of ~0.55 arcsec/s
     * partly cancels it. Both reduce the figure, so using the bare rate over-estimates the drift,
     * which is the right way for a budget to be wrong.
     */
    const val SIDEREAL_ARCSEC_PER_SEC = 15.041

    /**
     * How many disc-diameters of travel a sigma-clipped stack needs to reject the moon.
     *
     * Three, so a pixel the disc crosses sees it in about a third of the frames — comfortably a
     * minority, which is what rejection needs, without demanding a session so long that the sky
     * has rotated.
     */
    const val CROSSINGS_FOR_REJECTION = 3.0
}
