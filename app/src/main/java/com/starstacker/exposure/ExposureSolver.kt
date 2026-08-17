package com.starstacker.exposure

import kotlin.math.min

/**
 * T-3.3 / FR-5.2 — the sky-limited solver.
 *
 * The criterion is **not** "as long as possible". It is:
 *
 * > expose until sky background shot noise dominates read noise — target ≈ 3–5× read noise in
 * > variance
 *
 * Past that point a longer sub buys almost no signal-to-noise that a *shorter* sub could not buy
 * by being stacked, and it costs everything a long sub costs: more trailing, more to lose when an
 * aeroplane crosses the frame, coarser rejection statistics, and a worse outcome if the session
 * ends early. That inequality is the whole engine:
 *
 *     sky_electrons(t) ≥ k · read_noise_electrons²          k ∈ [3,5]
 *     t_sky            = k · R² / sky_rate
 *
 * Then clamp to the trailing limit (FR-5.1), and among the ISOs that still reach sky-limited
 * inside that clamp, take the one with the most **clipping headroom** — because at that point
 * every candidate is sky-limited and therefore equivalent in noise, so the only thing left to
 * choose on is which one keeps the most stars from saturating.
 *
 * ### Why it emits a derivation instead of a number
 *
 * FR-5.3 requires `Show work` to explain *why this ISO was chosen*, and an explanation
 * reconstructed after the fact is a second implementation that can disagree with the first. Every
 * candidate is therefore kept, with the reason it lost recorded at the moment it lost — so the
 * expandable derivation is the solver's actual working, not a retelling of it.
 */
object ExposureSolver {

    /** FR-5.2's "3–5×". The midpoint, adjustable. */
    const val DEFAULT_SKY_LIMIT_FACTOR = 4.0

    /**
     * Fraction of full scale the sky background is allowed to reach before the sub is judged to
     * be wasting dynamic range. Sky at a third of full scale leaves nothing for the stars.
     */
    const val MAX_BACKGROUND_FRACTION = 0.33

    enum class Verdict {
        /** Clears the sky-limited floor at the recommended exposure. A real candidate. */
        SKY_LIMITED,

        /**
         * Cannot reach the sky-limited floor before something else stops it — the trailing limit,
         * the sensor's ceiling, or clipping. FR-5.2's dark-sky regime: usable advice, but the
         * frame is read-noise limited and the remedy is to stack more of them.
         */
        READ_NOISE_LIMITED,

        /** Below the sensor's dual conversion gain point — strictly worse, so not considered. */
        BELOW_DUAL_GAIN,
    }

    data class Candidate(
        val iso: Int,
        /** Exposure that would reach the sky-limited criterion at this ISO, seconds. */
        val skyLimitedSeconds: Double,
        /** What is actually recommended after clamping, seconds. */
        val exposureSeconds: Double,
        val readNoiseElectrons: Double,
        val skyElectrons: Double,
        /** Achieved ratio of sky shot variance to read variance. FR-5.2's target is 3–5. */
        val skyToReadVariance: Double,
        /** Fraction of full scale the sky background occupies at this exposure. */
        val backgroundFraction: Double,
        /** Stops of headroom between the sky background and clipping. */
        val clippingHeadroomStops: Double,
        /** True when it was clipping, not trailing, that set the recommended exposure. */
        val clippingLimited: Boolean,
        val interpolatedNoise: Boolean,
        val verdict: Verdict,
        val reason: String,
    ) {
        val usable: Boolean get() = verdict == Verdict.SKY_LIMITED
    }

    data class Solution(
        val chosen: Candidate?,
        /** Every ISO considered, ascending, each with the reason it won or lost. */
        val candidates: List<Candidate>,
        val trailing: TrailingLimit.Result,
        val sky: SkyMeasurement,
        val skyLimitFactor: Double,
        val dualGainIso: Int?,
        val noiseSource: String,
        /** Set when a value was pinned by the user (FR-5.3) and the solve worked around it. */
        val pinnedIso: Int? = null,
        val pinnedExposureSeconds: Double? = null,
    ) {
        /** FR-5.3's beginner line: "ISO 800 · 12s". Frame count comes from the planner. */
        val headline: String
            get() = chosen?.let { "ISO ${it.iso} · ${formatSeconds(it.exposureSeconds)}" }
                ?: "no workable exposure"

        /**
         * The regime, in the user's terms. Always present — the app owes an answer even when the
         * answer is "this sky will not let you reach sky-limited", and leaving the UI to work
         * that out from a null would put the reasoning in two places.
         */
        val advisory: String
            get() = when {
                sky.clipped ->
                    "the test frame's own background is clipped — nothing can be measured from " +
                        "it. Point away from direct light, or drop the test ISO."

                chosen == null -> "no candidate ISO was usable"

                chosen.verdict == Verdict.READ_NOISE_LIMITED ->
                    "read-noise limited: no ISO reaches sky-limited within the ${
                        formatSeconds(trailing.maxExposureSeconds)
                    } trailing limit. That means a dark sky, which is good news — shoot at the " +
                        "limit and stack more frames."

                chosen.clippingLimited ->
                    "sky-limited, and the sub length is set by clipping rather than trailing — " +
                        "a bright sky, where highlight headroom is what matters."

                else ->
                    "sky-limited at the trailing limit, which is as much light as this sub can " +
                        "safely collect."
            }

        /** Null when a recommendation was reached; the blocking problem otherwise. */
        val failureReason: String? get() = if (chosen != null) null else advisory
    }

    /**
     * @param isoCandidates the ISOs to consider, typically the sensor's own stops.
     * @param maxExposureSeconds the sensor's ceiling (`SENSOR_INFO_EXPOSURE_TIME_RANGE`), not a
     *   preference — 49.6 s on the reference device.
     * @param pinnedIso FR-5.3: when the user pins an ISO, the solve still runs in full so the
     *   derivation still explains itself; only the *choice* is forced.
     */
    fun solve(
        sky: SkyMeasurement,
        noiseModel: NoiseModel,
        trailing: TrailingLimit.Result,
        isoCandidates: List<Int>,
        maxExposureSeconds: Double,
        skyLimitFactor: Double = DEFAULT_SKY_LIMIT_FACTOR,
        dualGainIso: Int? = null,
        pinnedIso: Int? = null,
        pinnedExposureSeconds: Double? = null,
    ): Solution {
        val ceiling = min(trailing.maxExposureSeconds, maxExposureSeconds)

        val candidates = isoCandidates.sorted().map { iso ->
            evaluate(iso, sky, noiseModel, ceiling, skyLimitFactor, dualGainIso, pinnedExposureSeconds)
        }

        val considered = candidates.filter { it.verdict != Verdict.BELOW_DUAL_GAIN }
        val chosen = when {
            // A clipped test frame measured nothing. Every number below is derived from the sky
            // rate, and the sky rate derived from a clipped frame is a lower bound of unknown
            // slack — so there is no answer here, only an answer-shaped object. Measured
            // 2026-08-17: indoors the solver happily recommended "ISO 50 · 1.5 s" from a frame
            // pinned at the white level.
            sky.clipped -> null

            // A pinned ISO is the answer whether or not it won on merit — the user asked.
            pinnedIso != null -> candidates.firstOrNull { it.iso == pinnedIso }

            // Among sky-limited candidates, most clipping headroom wins: they are equivalent in
            // noise by construction — that is what reaching the floor *means* — so headroom is
            // the only axis left that distinguishes them. Where clipping set the exposure the
            // headroom is identical at every ISO, and the longest sub then wins: same light,
            // fewer frames, less storage and less write bandwidth.
            considered.any { it.usable } -> considered.filter { it.usable }
                .maxWithOrNull(
                    compareBy<Candidate> { round(it.clippingHeadroomStops) }
                        .thenBy { it.exposureSeconds },
                )

            // A dark sky reaches nothing. Still answer — FR-5.3 promises a recommendation, not
            // a diagnosis — but answer at the **ISO-invariance point** rather than at whichever
            // ISO has the single lowest read noise.
            //
            // Those are not the same, and the difference is expensive. On the reference sensor
            // read noise bottoms out around ISO 3200 and is flat above it: 2.07, 2.03, 2.07 e⁻
            // at 3200, 6400 and 12800. Picking the minimum outright selects ISO 6400, whose full
            // scale is 387 e⁻ — so every star of any brightness clips — to save 0.04 e⁻ of read
            // noise over ISO 3200, which holds 707 e⁻. Once read noise has stopped improving,
            // more gain buys nothing and costs all the highlight range there is.
            else -> considered.isoInvariancePoint()
        }

        return Solution(
            chosen = chosen,
            candidates = candidates,
            trailing = trailing,
            sky = sky,
            skyLimitFactor = skyLimitFactor,
            dualGainIso = dualGainIso,
            noiseSource = noiseModel.source,
            pinnedIso = pinnedIso,
            pinnedExposureSeconds = pinnedExposureSeconds,
        )
    }

    private fun evaluate(
        iso: Int,
        sky: SkyMeasurement,
        noiseModel: NoiseModel,
        ceilingSeconds: Double,
        skyLimitFactor: Double,
        dualGainIso: Int?,
        pinnedExposureSeconds: Double?,
    ): Candidate {
        val noise = noiseModel.at(iso)
            ?: return unusable(iso, Verdict.BELOW_DUAL_GAIN, "no noise data for ISO $iso")

        // Sky rate is measured in electrons at the sensor and does not depend on ISO; what
        // changes with ISO is how many electrons a full-scale pixel holds, and the read noise.
        val rate = sky.electronsPerSecond.coerceAtLeast(MIN_SKY_RATE)
        val required = skyLimitFactor * noise.readVarianceElectrons / rate

        // How long before the sky background alone eats the dynamic range the stars need.
        val clippingSeconds = MAX_BACKGROUND_FRACTION * noise.fullScaleElectrons / rate

        // FR-5.2's criterion is a **floor, not a target**. Once shot noise dominates read noise,
        // a longer sub costs nothing in noise terms and saves frames, storage and write
        // bandwidth — so the recommendation runs as long as trailing, the sensor and clipping
        // allow, and `required` is a condition it has to clear rather than the answer itself.
        // Taking `required` as the answer recommends 20 ms subs under a bright sky, which is
        // 24 MB of DNG every 20 ms.
        val allowed = min(ceilingSeconds, clippingSeconds)
        val exposure = pinnedExposureSeconds ?: allowed
        val clippingLimited = clippingSeconds < ceilingSeconds

        val skyElectrons = sky.electronsIn(exposure)
        val ratio = if (noise.readVarianceElectrons > 0) {
            skyElectrons / noise.readVarianceElectrons
        } else {
            Double.POSITIVE_INFINITY
        }
        val backgroundFraction = skyElectrons / noise.fullScaleElectrons
        val headroomStops = if (backgroundFraction > 0) {
            kotlin.math.ln(1.0 / backgroundFraction) / kotlin.math.ln(2.0)
        } else {
            Double.POSITIVE_INFINITY
        }

        val verdict = when {
            dualGainIso != null && iso < dualGainIso -> Verdict.BELOW_DUAL_GAIN
            skyElectrons >= skyLimitFactor * noise.readVarianceElectrons -> Verdict.SKY_LIMITED
            else -> Verdict.READ_NOISE_LIMITED
        }

        return Candidate(
            iso = iso,
            skyLimitedSeconds = required,
            exposureSeconds = exposure,
            readNoiseElectrons = noise.readNoiseElectrons,
            skyElectrons = skyElectrons,
            skyToReadVariance = ratio,
            backgroundFraction = backgroundFraction,
            clippingHeadroomStops = headroomStops,
            clippingLimited = clippingLimited,
            interpolatedNoise = noise.interpolated,
            verdict = verdict,
            reason = reasonFor(
                verdict, iso, required, exposure, ratio, headroomStops,
                clippingLimited, skyLimitFactor, dualGainIso,
            ),
        )
    }

    private fun reasonFor(
        verdict: Verdict,
        iso: Int,
        required: Double,
        exposure: Double,
        ratio: Double,
        headroom: Double,
        clippingLimited: Boolean,
        skyLimitFactor: Double,
        dualGainIso: Int?,
    ): String = when (verdict) {
        Verdict.SKY_LIMITED ->
            "%s reaches sky-limited in %s and runs to %s%s — %.1f× read noise in variance, %.1f stops of headroom"
                .format(
                    "ISO $iso", formatSeconds(required), formatSeconds(exposure),
                    if (clippingLimited) " where clipping stops it" else "",
                    ratio, headroom,
                )

        Verdict.READ_NOISE_LIMITED ->
            "needs %s to reach sky-limited but only %s is available — read-noise limited at %.1f× instead of %.0f×"
                .format(formatSeconds(required), formatSeconds(exposure), ratio, skyLimitFactor)

        Verdict.BELOW_DUAL_GAIN ->
            "ISO $iso is below the dual conversion gain point (${dualGainIso ?: "?"}), where read " +
                "noise is markedly worse — the same light costs more noise here"
    }

    private fun unusable(iso: Int, verdict: Verdict, reason: String) = Candidate(
        iso = iso,
        skyLimitedSeconds = Double.NaN,
        exposureSeconds = Double.NaN,
        readNoiseElectrons = Double.NaN,
        skyElectrons = Double.NaN,
        skyToReadVariance = Double.NaN,
        backgroundFraction = Double.NaN,
        clippingHeadroomStops = Double.NEGATIVE_INFINITY,
        clippingLimited = false,
        interpolatedNoise = false,
        verdict = verdict,
        reason = reason,
    )

    /**
     * The lowest ISO whose read noise is within [ISO_INVARIANCE_TOLERANCE] of the best on offer.
     *
     * "Within a few percent of the floor" is what ISO invariance means in practice: past that
     * point the sensor is no longer getting quieter, so the only thing another stop of gain does
     * is halve the full well.
     */
    private fun List<Candidate>.isoInvariancePoint(): Candidate? {
        val quietest = filter { it.readNoiseElectrons.isFinite() }
            .minOfOrNull { it.readNoiseElectrons } ?: return firstOrNull()
        return filter { it.readNoiseElectrons <= quietest * (1.0 + ISO_INVARIANCE_TOLERANCE) }
            .minByOrNull { it.iso }
            ?: minByOrNull { it.readNoiseElectrons }
    }

    /**
     * How much worse than the quietest ISO still counts as "read noise has bottomed out". Ten
     * percent of a read noise around 2 e⁻ is 0.2 e⁻, which is nothing next to a stop of well
     * depth.
     */
    const val ISO_INVARIANCE_TOLERANCE = 0.10

    /** Below this the sky is dark enough that the required exposure is effectively unbounded. */
    private const val MIN_SKY_RATE = 1e-6

    /**
     * Headroom compared to a tenth of a stop, so that values differing only by float noise count
     * as the tie they are and the exposure-length tiebreak gets to decide.
     */
    private fun round(stops: Double) = kotlin.math.round(stops * 10.0) / 10.0

    fun formatSeconds(seconds: Double): String = when {
        !seconds.isFinite() -> "∞"
        seconds >= 60 -> "%.0f min".format(seconds / 60)
        seconds >= 10 -> "%.0f s".format(seconds)
        seconds >= 1 -> "%.1f s".format(seconds)
        else -> "%.0f ms".format(seconds * 1000)
    }
}
