package com.starstacker.stacking

import com.starstacker.session.FrameRecord
import kotlin.math.ceil

/**
 * T-5.5 / FR-7.6 — how good each frame is, and what that is worth in the stack.
 *
 * ### Two separate uses, and they are not the same question
 *
 * **Weighting** asks *"how much should this frame count?"* and applies to every frame that is
 * stacked. **The keep-best cut** asks *"should this frame be stacked at all?"* and is a threshold.
 * They are built from the same score and do different jobs: a slightly soft frame should count a
 * little less, and a frame ruined by cloud should not be averaged in at any weight, because its
 * contribution is noise plus a gradient rather than a weaker version of the signal.
 *
 * ### The score, and why each term has the exponent it has
 *
 * The weight that minimises noise in a weighted mean is `1 / variance`, so each term is an estimate
 * of how much signal-to-noise a frame carries relative to the best one in the session:
 *
 * - **Sharpness, squared.** A star's flux is spread over an area proportional to `HFR²`, so its
 *   peak signal — the thing that has to beat the noise — goes as `1 / HFR²`. The exponent is the
 *   geometry, not a tuning constant.
 * - **Background, inverse.** Under light pollution the noise is sky shot noise, whose variance is
 *   proportional to the background level itself. Weight is `1 / variance`, hence `1 / background`.
 * - **Star count, linear.** A proxy for transparency: thin cloud takes stars below the detection
 *   threshold before it visibly dims anything. **This is the softest of the three** and is
 *   deliberately linear rather than squared — it is partly redundant with background, since haze
 *   raises the sky *and* hides stars, and a frame should not be punished twice for one cause.
 *
 * Each term is a ratio against the best frame in the session, so the best frame scores 1 and the
 * numbers read as "a fraction of the best frame I had".
 *
 * ### Missing metrics weigh nothing, in both directions
 *
 * A frame with no HFR recorded gets 1 for that term rather than 0 or a guess. Sessions shot before
 * the gate existed, and folders that have been to a PC and back (FR-10.6.4), carry partial logs —
 * and **the whole feature has to degrade to "no change" when there is no data**, or turning it on
 * would silently reweight a session by which fields happened to be populated. When nothing is
 * known, every weight is 1 and a weighted mean is the plain mean.
 *
 * ### Where the weights stop applying, which is not obvious
 *
 * Two places, both inherited rather than chosen here. [Combine.Median] has no weighted form — it
 * picks a value rather than averaging them — so a session stacked that way ignores them. And
 * `SigmaClip` falls back to the median below its five-sample floor (§1.33), so a **session of four
 * frames or fewer is unweighted whatever the setting says**, as is every pixel near the edge of the
 * common area where only a handful of frames overlapped. Both are right for their own reasons and
 * neither is visible from the setting, so both have tests that say so.
 */
object FrameQuality {

    /** One frame's score and the terms behind it, so the UI can say *why* rather than just rank. */
    data class Score(
        val index: Int,
        val fileName: String,
        val weight: Double,
        val sharpness: Double,
        val transparency: Double,
        val darkness: Double,
    ) {
        fun describe(): String = "%.2f".format(weight)
    }

    /**
     * Scores every frame against the best of them.
     *
     * @return one [Score] per input, in the same order. Weights are in `(0, 1]`.
     */
    fun score(frames: List<FrameRecord>): List<Score> {
        if (frames.isEmpty()) return emptyList()

        // The references are the best *observed* values, so the scale is the session's own. An
        // absolute scale would need to know what a good HFR is for this focal length and pixel
        // pitch, which is a Phase 6 question and not one worth guessing here.
        val bestHfr = frames.mapNotNull { it.hfr }.filter { it.isFinite() && it > 0 }.minOrNull()
        val bestStars = frames.mapNotNull { it.starCount }.filter { it > 0 }.maxOrNull()
        val bestBackground = frames.mapNotNull { it.backgroundAdu }
            .filter { it.isFinite() && it > 0 }.minOrNull()

        return frames.map { frame ->
            val sharpness = ratio(bestHfr, frame.hfr) { best, value -> (best / value) * (best / value) }
            val transparency = ratio(bestStars?.toDouble(), frame.starCount?.toDouble()) { best, value ->
                value / best
            }
            val darkness = ratio(bestBackground, frame.backgroundAdu) { best, value -> best / value }

            Score(
                index = frame.index,
                fileName = frame.fileName,
                weight = (sharpness * transparency * darkness).coerceIn(MIN_WEIGHT, 1.0),
                sharpness = sharpness,
                transparency = transparency,
                darkness = darkness,
            )
        }
    }

    /**
     * One term, or 1.0 when the metric is missing or unusable.
     *
     * Clamped at the top as well as the bottom. A frame cannot be *better* than the reference by
     * construction, but floating-point and a zero-ish denominator can both produce a term above 1,
     * and a single frame with a weight of 400 would quietly become the entire master.
     */
    private inline fun ratio(
        best: Double?,
        value: Double?,
        term: (Double, Double) -> Double,
    ): Double {
        if (best == null || value == null) return 1.0
        if (!best.isFinite() || !value.isFinite() || best <= 0.0 || value <= 0.0) return 1.0
        return term(best, value).coerceIn(MIN_WEIGHT, 1.0)
    }

    /**
     * The best [keepPercent] of [scores], by weight — FR-7.6's quality cut.
     *
     * **Rounded up, so a small session keeps everything.** At 95% a twenty-frame session drops
     * one, and a three-frame session drops none: `ceil(0.95 × 3) = 3`. That is the right way round
     * — the cut exists to remove the occasional ruined frame from a long run, and a session short
     * enough for one frame to be a third of the data cannot afford to lose it.
     *
     * Ties are broken by frame index so the result is reproducible; FR-10.4 wants a restack to
     * reproduce a master, and a set that depends on sort stability would not.
     *
     * @param keepPercent 1–100. 100 keeps everything, which is what turns the cut off.
     */
    fun keepBest(scores: List<Score>, keepPercent: Int): List<Score> {
        if (scores.isEmpty()) return scores
        val percent = keepPercent.coerceIn(1, 100)
        if (percent >= 100) return scores

        val keep = ceil(scores.size * percent / 100.0).toInt().coerceIn(1, scores.size)
        if (keep >= scores.size) return scores

        val kept = scores.sortedWith(
            compareByDescending<Score> { it.weight }.thenBy { it.index },
        ).take(keep).map { it.index }.toSet()

        // Returned in the original order: the caller pairs these with files and transforms, and
        // reordering the stack by quality would silently change which frame is the reference.
        return scores.filter { it.index in kept }
    }

    /**
     * A floor rather than zero.
     *
     * A weight of zero is a frame excluded, and exclusion is the keep-best cut's job — done there
     * it is counted, reported and reversible by moving one slider. Reached by arithmetic instead,
     * it would be a frame that silently contributed nothing while still being listed as stacked.
     */
    const val MIN_WEIGHT = 0.01

    /** FR-7.6's default: drop the worst 5%, which on a long run is the cloud and the aeroplanes. */
    const val DEFAULT_KEEP_PERCENT = 95
}
