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
    private val minHfrForEccentricity: Double = DEFAULT_MIN_HFR_FOR_ECCENTRICITY,
    private val minStars: Int = DEFAULT_MIN_STARS,
) {

    /** What the gate needs to know about a frame. Everything else about it is irrelevant here. */
    data class Metrics(
        val starCount: Int,
        val medianEccentricity: Double?,
        val saturated: Boolean,
        /**
         * Median half-flux radius in **analysis-plane pixels**, or null if unmeasured. Present
         * only so the eccentricity check can tell whether the stars are large enough for their
         * shape to mean anything — see [DEFAULT_MIN_HFR_FOR_ECCENTRICITY].
         */
        val medianHfr: Double? = null,
        /**
         * Peak angular movement of the phone during the exposure, degrees. Null if unmeasured.
         *
         * An angle rather than an acceleration because it is tilt that moves the star field —
         * see [DeviceEnvironment]. It only catches *gross* bumps; the fine motion that matters at
         * the pixel level is far below the accelerometer's noise floor and is caught by
         * registration residuals in Phase 2 instead.
         */
        val peakTiltDeg: Double? = null,
        /**
         * T-4.4 — what registration made of this frame, when it was attempted.
         *
         * Two separate flags rather than one "registration was bad", because they call for
         * opposite advice. [registrationBumped] is a good frame spoiled by movement — steady the
         * tripod. [registrationFailed] is a frame the pipeline cannot place at all, which is
         * almost always the sky rather than the mount. Collapsing them would tell someone to
         * stop knocking their tripod while a cloud goes over.
         */
        val registrationBumped: Boolean = false,
        val registrationFailed: Boolean = false,
        /** For the rejection detail, so the number can be argued with later (**D-10**). */
        val registrationDetail: String? = null,
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
        val sampled = metrics.medianHfr?.let { it >= minHfrForEccentricity } ?: true
        if (sampled && eccentricity != null && metrics.starCount > 0 &&
            eccentricity > maxEccentricity
        ) {
            // Trailing, not cloud: the stars are there and they are streaks. The remedy is a
            // shorter sub, which is the opposite of the advice a cloud diagnosis gives.
            return Verdict(
                false, RejectReason.TRAILED,
                "eccentricity %.2f over %.2f — stars are elongated, shorten the sub"
                    .format(eccentricity, maxEccentricity),
            )
        }

        // T-4.4's fine bump, judged before the star-count baseline for the same reason the tilt
        // check is: the frame is spoiled whatever the sky was doing, and a bumped frame that also
        // happens to be thin should be reported as bumped rather than as cloud.
        if (metrics.registrationBumped) {
            return Verdict(
                false, RejectReason.BUMPED,
                "the star field smeared during the exposure — " +
                    (metrics.registrationDetail ?: "registration residual spiked"),
            )
        }

        // An absolute floor, checked before the relative one, because **the relative one does not
        // exist yet at the start of a session** — and the start of a session is exactly when the
        // sky may be overcast. Measured on device 2026-08-19 (§1.29): indoors, the first four
        // frames of a session were reported as REGISTRATION failures, because the cloud check had
        // no baseline to speak from and the registration check was the only one left with an
        // opinion. Telling someone their tripod moved while a cloud sits overhead is the wrong
        // advice at the worst moment.
        if (metrics.starCount < minStars) {
            return Verdict(
                false, RejectReason.CLOUD,
                "%d stars — too few to register or stack, so cloud, twilight or a lens cap"
                    .format(metrics.starCount),
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

        // Last, and deliberately so: a frame that could not be registered is usually a frame with
        // too few stars, and the cloud check above gives that the better diagnosis. Reaching here
        // means the star count was normal and the frame still would not place — which is a
        // genuine registration failure and not a weather report.
        if (metrics.registrationFailed) {
            remember(metrics.starCount)
            return Verdict(
                false, RejectReason.REGISTRATION,
                metrics.registrationDetail
                    ?: "could not be registered against the reference frame",
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

        /**
         * Below this a frame is unusable whatever the session's history says.
         *
         * Registration needs three correspondences and wants far more to be trustworthy; a frame
         * with a handful of detections cannot be placed and cannot be stacked, so the reason it
         * is rejected should say *sky*, not *mount*. Deliberately low — this is a floor for
         * "nothing is there", not a quality bar, and the relative check is what catches a sky
         * that merely dimmed.
         */
        const val DEFAULT_MIN_STARS = 8

        /**
         * Below this median HFR (analysis-plane pixels) the eccentricity check is **skipped**,
         * because a star that small has no measurable shape.
         *
         * Second moments over a one- or two-pixel blob are degenerate: put the flux across two
         * adjacent pixels and the minor eigenvalue collapses towards zero, driving eccentricity
         * towards 1 whatever the star actually looks like. The threshold is where a Gaussian is
         * comfortably above Nyquist — HFR ≈ 1.18σ and FWHM = 2.355σ, so HFR 1.5 px is a FWHM of
         * about 3 px.
         *
         * Session `2026-08-18_0050` is the case that found this. Analysis runs on a 4× binned
         * plane, so one analysis pixel is 297 arcsec, and the real trail in a 7.4 s sub at the
         * equator was 111 arcsec — **0.375 analysis pixels**, which predicts an eccentricity near
         * 0.13. The measured median was 0.855 and all 56 otherwise-good frames were rejected as
         * trailed. At f/1.88 the diffraction-limited star is about 1.3 µm across against an 8 µm
         * analysis pixel: the pixel grid was being measured, not the star. The giveaway is that
         * the same frames reported HFR 1.0 — nothing can be both 2:1 elongated and one pixel wide.
         */
        const val DEFAULT_MIN_HFR_FOR_ECCENTRICITY = 1.5

        const val DEFAULT_BASELINE_WINDOW = 15

        /**
         * Below this many frames there is no baseline and the star check is skipped entirely.
         * Rejecting the first frames of a session against a baseline built from one frame would
         * throw away the start of every session.
         */
        const val DEFAULT_MIN_BASELINE_FRAMES = 3

        /**
         * Gross movement only, in degrees, measured by the gyroscope — see [DeviceEnvironment]
         * for why it cannot be the accelerometer.
         *
         * Bounded from both sides. Below, by what the instrument can actually resolve: integrated
         * over a multi-second sub, MEMS gyro noise and residual zero-rate drift amount to a few
         * hundredths of a degree, so a threshold near the trailing budget itself — 1.5 px at the
         * reference camera's 74.2 arcsec/px is 0.031° — would sit in the noise and reject good
         * frames, which is the failure this check has already caused once. Above, by the point a
         * frame is genuinely spoiled rather than merely nudged.
         *
         * 0.5° is roughly 24 px of streak at the reference plate scale: unambiguously a knocked
         * tripod, an order of magnitude clear of the noise floor, and far beyond anything a
         * springy mount produces by ringing. Everything finer is left to registration residuals
         * in Phase 2 (FR-7.2), which measure the frame rather than the phone and are the right
         * instrument for sub-pixel motion.
         */
        const val DEFAULT_BUMP_THRESHOLD_DEG = 0.5
    }
}
