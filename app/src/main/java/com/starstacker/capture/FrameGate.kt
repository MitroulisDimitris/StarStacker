package com.starstacker.capture

import com.starstacker.session.RejectReason

/**
 * T-3.10 / FR-7.5 — the quality gate that needs no registration.
 *
 * Three of the four checks here are cheap because they are properties of a single frame:
 * eccentricity says the stars are streaks, a collapse in star count says cloud, and a saturated
 * frame says the sky is lit. The fourth, a bump, comes from the accelerometer rather than the
 * pixels. Registration residuals and the common-area indicator need a reference frame and arrive
 * in Phase 2.
 *
 * **Nothing is deleted.** D-10 and FR-10.6.3: a rejected frame is written to `lights/` exactly
 * like any other and flagged in the log with the numbers that flagged it. A rejection rule is a
 * judgement made at 3 a.m. by a threshold, and the user is entitled to disagree with it in the
 * morning — which they can only do if the frame is still there and the reason is recorded.
 *
 * The star-count check is the one that needs care. It is **relative to this session's own
 * baseline**, not absolute: "180 stars" means a rich field on a wide lens and a cloudy sky on a
 * tele, so an absolute threshold would be wrong on one of them. The baseline is a rolling median
 * so that one thin-cloud frame cannot drag it down and hide the cloud that follows.
 */
class FrameGate(
    private val maxEccentricity: Double = DEFAULT_MAX_ECCENTRICITY,
    private val starCollapseFraction: Double = DEFAULT_STAR_COLLAPSE_FRACTION,
    private val baselineWindow: Int = DEFAULT_BASELINE_WINDOW,
    private val minBaselineFrames: Int = DEFAULT_MIN_BASELINE_FRAMES,
    private val bumpThresholdDeg: Double = DEFAULT_BUMP_THRESHOLD_DEG,
) {

    /** What the gate needs to know about a frame. Everything else about it is irrelevant here. */
    data class Metrics(
        val starCount: Int,
        val medianEccentricity: Double?,
        val saturated: Boolean,
        /**
         * Peak angular movement of the phone during the exposure, degrees. Null if unmeasured.
         *
         * An angle rather than an acceleration because it is tilt that moves the star field —
         * see [DeviceEnvironment]. It only catches *gross* bumps; the fine motion that matters at
         * the pixel level is far below the accelerometer's noise floor and is caught by
         * registration residuals in Phase 2 instead.
         */
        val peakTiltDeg: Double? = null,
    )

    data class Verdict(
        val accepted: Boolean,
        val reason: RejectReason? = null,
        val detail: String? = null,
    )

    private val recentStarCounts = ArrayDeque<Int>()

    /** The rolling median star count, or null before there are enough frames to have one. */
    val baselineStarCount: Int?
        get() = if (recentStarCounts.size < minBaselineFrames) null else recentStarCounts.median()

    /**
     * Judges a frame and folds it into the baseline.
     *
     * Order matters: a saturated or trailed frame is judged first and **does not** join the
     * baseline, because a baseline that includes frames it just rejected drifts towards whatever
     * went wrong.
     */
    fun accept(metrics: Metrics): Verdict {
        if (metrics.saturated) {
            return Verdict(
                false, RejectReason.SATURATED,
                "frame is clipped — the sky or a light source has saturated the sensor",
            )
        }

        metrics.peakTiltDeg?.let { peak ->
            if (peak > bumpThresholdDeg) {
                return Verdict(
                    false, RejectReason.BUMPED,
                    "the phone moved %.2f° during the exposure (limit %.2f°)"
                        .format(peak, bumpThresholdDeg),
                )
            }
        }

        val eccentricity = metrics.medianEccentricity
        if (eccentricity != null && metrics.starCount > 0 && eccentricity > maxEccentricity) {
            // Trailing, not cloud: the stars are there and they are streaks. The remedy is a
            // shorter sub, which is the opposite of the advice a cloud diagnosis gives.
            return Verdict(
                false, RejectReason.TRAILED,
                "eccentricity %.2f over %.2f — stars are elongated, shorten the sub"
                    .format(eccentricity, maxEccentricity),
            )
        }

        val baseline = baselineStarCount
        if (baseline != null && metrics.starCount < baseline * starCollapseFraction) {
            // Deliberately does not join the baseline — see the method note.
            return Verdict(
                false, RejectReason.CLOUD,
                "%d stars against a baseline of %d — cloud, or the target has set"
                    .format(metrics.starCount, baseline),
            )
        }

        remember(metrics.starCount)
        return Verdict(true)
    }

    private fun remember(starCount: Int) {
        recentStarCounts.addLast(starCount)
        while (recentStarCounts.size > baselineWindow) recentStarCounts.removeFirst()
    }

    private fun ArrayDeque<Int>.median(): Int {
        val sorted = sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        /**
         * Round stars sit near 0.2 in practice rather than 0.0 — the CFA binning is not perfectly
         * isotropic and neither is the optics. 0.6 is elongated enough to be visible.
         */
        const val DEFAULT_MAX_ECCENTRICITY = 0.6

        /** Half the usual star count is cloud by any reasonable reading. */
        const val DEFAULT_STAR_COLLAPSE_FRACTION = 0.5

        const val DEFAULT_BASELINE_WINDOW = 15

        /**
         * Below this many frames there is no baseline and the star check is skipped entirely.
         * Rejecting the first frames of a session against a baseline built from one frame would
         * throw away the start of every session.
         */
        const val DEFAULT_MIN_BASELINE_FRAMES = 3

        /**
         * Gross movement only, in degrees.
         *
         * Set from measurement rather than intuition, and the measurement says something worth
         * writing down: a phone lying **still** on a desk registers a few hundredths of a degree
         * of apparent tilt from accelerometer noise, while the field motion that would actually
         * trail a star is smaller still — 1.5 px at the reference camera's plate scale is 0.031°.
         * The accelerometer therefore *cannot* see the motion that matters; it can only see a
         * tripod being knocked. This threshold is set to catch that and nothing else, and the
         * sub-pixel case belongs to registration residuals in Phase 2 (FR-7.2), which measure
         * the frame rather than the phone.
         */
        const val DEFAULT_BUMP_THRESHOLD_DEG = 1.0
    }
}
