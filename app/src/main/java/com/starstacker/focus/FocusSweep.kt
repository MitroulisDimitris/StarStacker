package com.starstacker.focus

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * T-2.4 — planning and reading a focus sweep, with no camera in sight.
 *
 * `LENS_FOCUS_DISTANCE` is in dioptres: 0.0 is infinity and larger values focus closer. Stars
 * live at 0.0 in theory and somewhere near it in practice, because a phone's voice-coil motor is
 * positioned open-loop and its idea of "0.0" moves with temperature and with which way the phone
 * is tilted.
 *
 * Three VCM behaviours shape everything here (FR-4.1.4):
 * - **Hysteresis** — the motor lands in a different place depending on which side it came from,
 *   so every position in a sweep is approached from the same direction after a deliberate
 *   overshoot ([parkPosition]).
 * - **Gravity sag** — the lens hangs differently at 30° than at the zenith, so the elevation the
 *   sweep ran at is recorded with the result ([FocusRecord]).
 * - **Thermal drift** — which is why the answer is re-verified at session start (T-2.5) rather
 *   than trusted for the night.
 */

/** One position of a sweep. [hfr] is null when the frame had too few stars to measure. */
data class FocusSample(
    val diopters: Float,
    val hfr: Double?,
    val starCount: Int,
)

enum class FocusVerdict {
    /** HFR falls and rises again — the minimum is bracketed and trustworthy. */
    CLEAR_MINIMUM,

    /** The best position is at an end of the sweep. Widen the range, or it is the hard stop. */
    MINIMUM_AT_EDGE,

    /** HFR barely varies across the sweep. Either already perfect or nothing was in focus. */
    FLAT,

    /** Not enough stars at enough positions to say anything. Usually cloud, or a bright sky. */
    TOO_FEW_STARS,
}

data class FocusCurve(
    val samples: List<FocusSample>,
    /** Best position found; interpolated between samples when the minimum is bracketed. */
    val bestDiopters: Float,
    val bestHfr: Double,
    val verdict: FocusVerdict,
    val interpolated: Boolean,
    val note: String,
) {
    val usable: Boolean
        get() = verdict == FocusVerdict.CLEAR_MINIMUM || verdict == FocusVerdict.MINIMUM_AT_EDGE
}

object FocusSweep {

    /** Full sweep span from infinity, dioptres. ~0.4 dioptre is 2.5 m — well past any star. */
    const val DEFAULT_SPAN = 0.4f

    /** Re-verification sweep: a few micro-steps either side of the stored position. */
    const val DEFAULT_LOCAL_SPAN = 0.12f

    /** How far past the first position to drive before starting, to take up motor backlash. */
    const val BACKLASH_DIOPTERS = 0.25f

    /** Below this a frame's HFR is not evidence about focus, it is evidence about the sky. */
    const val MIN_STARS = 5

    /**
     * Positions for a cold sweep, from the near end down to infinity.
     *
     * Descending, so every setpoint is approached from the near side — the same direction, every
     * time, which is the whole of the hysteresis fix.
     */
    fun infinitySweep(
        span: Float = DEFAULT_SPAN,
        steps: Int = 9,
        maxDiopters: Float = Float.MAX_VALUE,
    ): List<Float> = descending(from = min(span, maxDiopters), to = 0f, steps = steps)

    /** Positions for a re-verification sweep around a stored value (T-2.5). */
    fun localSweep(
        centre: Float,
        span: Float = DEFAULT_LOCAL_SPAN,
        steps: Int = 5,
        maxDiopters: Float = Float.MAX_VALUE,
    ): List<Float> = descending(
        from = min(centre + span / 2f, maxDiopters),
        to = max(centre - span / 2f, 0f),
        steps = steps,
    )

    /**
     * Where to drive before the first sample so the motor arrives at it from the near side.
     * Clamped to the lens's own near limit — asking for more is silently ignored by the HAL,
     * which would leave the backlash untaken.
     */
    fun parkPosition(firstPosition: Float, maxDiopters: Float = Float.MAX_VALUE): Float =
        min(firstPosition + BACKLASH_DIOPTERS, maxDiopters)

    private fun descending(from: Float, to: Float, steps: Int): List<Float> {
        require(steps >= 2) { "a sweep needs at least two positions, was $steps" }
        val hi = max(from, to)
        val lo = min(from, to)
        val step = (hi - lo) / (steps - 1)
        return List(steps) { i -> (hi - i * step).coerceAtLeast(0f) }
    }

    /**
     * Reads the curve: the minimum-HFR position, refined by a parabola through its two
     * neighbours when the minimum is properly bracketed.
     *
     * A parabola is the right model near the bottom of a defocus curve regardless of the star
     * profile — HFR grows roughly linearly with defocus on either side, so the vertex of a fit
     * through three points beats the sampled minimum by roughly half a step.
     */
    fun analyse(
        samples: List<FocusSample>,
        minStars: Int = MIN_STARS,
        flatTolerance: Double = 0.05,
    ): FocusCurve {
        val valid = samples.filter { it.hfr != null && it.hfr > 0.0 && it.starCount >= minStars }
        if (valid.size < 3) {
            val fallback = valid.minByOrNull { it.hfr!! }
            return FocusCurve(
                samples = samples,
                bestDiopters = fallback?.diopters ?: 0f,
                bestHfr = fallback?.hfr ?: Double.NaN,
                verdict = FocusVerdict.TOO_FEW_STARS,
                interpolated = false,
                note = "only ${valid.size} of ${samples.size} positions had ${minStars}+ stars — " +
                    "cloud, a bright sky, or the lens is so far out that no star is detectable",
            )
        }

        // Sort by position so "neighbour" means neighbour in focus, not in sweep order.
        val ordered = valid.sortedBy { it.diopters }
        val hfrs = ordered.map { it.hfr!! }
        val bestIndex = hfrs.indices.minByOrNull { hfrs[it] }!!
        val best = ordered[bestIndex]
        val worst = hfrs.max()

        if ((worst - hfrs[bestIndex]) / hfrs[bestIndex] < flatTolerance) {
            return FocusCurve(
                samples = samples,
                bestDiopters = best.diopters,
                bestHfr = best.hfr!!,
                verdict = FocusVerdict.FLAT,
                interpolated = false,
                note = "HFR varies by less than ${(flatTolerance * 100).toInt()}% across the " +
                    "sweep — either the range is too narrow to see the curve, or nothing here " +
                    "is in focus",
            )
        }

        if (bestIndex == 0 || bestIndex == ordered.lastIndex) {
            return FocusCurve(
                samples = samples,
                bestDiopters = best.diopters,
                bestHfr = best.hfr!!,
                verdict = FocusVerdict.MINIMUM_AT_EDGE,
                interpolated = false,
                note = if (best.diopters <= 0f) {
                    "best HFR is at 0.0 dioptres, the infinity stop — the true focus may be " +
                        "past it, which is as good as this lens gets"
                } else {
                    "best HFR is at the end of the swept range — widen the sweep to bracket it"
                },
            )
        }

        val vertex = parabolaVertex(
            ordered[bestIndex - 1].diopters.toDouble(), hfrs[bestIndex - 1],
            best.diopters.toDouble(), hfrs[bestIndex],
            ordered[bestIndex + 1].diopters.toDouble(), hfrs[bestIndex + 1],
        )

        val interpolated = vertex != null
        return FocusCurve(
            samples = samples,
            bestDiopters = (vertex ?: best.diopters.toDouble()).toFloat(),
            bestHfr = best.hfr!!,
            verdict = FocusVerdict.CLEAR_MINIMUM,
            interpolated = interpolated,
            note = if (interpolated) {
                "minimum bracketed; position interpolated between samples"
            } else {
                "minimum bracketed, but the three points near it do not fit a curve — using the " +
                    "sampled position"
            },
        )
    }

    /**
     * Vertex of the parabola through three points, or null when it is not a usable minimum
     * (points collinear, curve opening downwards, or a vertex outside the bracket — all of
     * which mean the sampled minimum is the honest answer).
     */
    internal fun parabolaVertex(
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        x3: Double, y3: Double,
    ): Double? {
        if (x1 == x2 || x2 == x3 || x1 == x3) return null
        val slope12 = (y2 - y1) / (x2 - x1)
        val slope23 = (y3 - y2) / (x3 - x2)
        val curvature = (slope23 - slope12) / (x3 - x1)
        if (curvature <= 0.0) return null
        val vertex = (x1 + x2) / 2.0 - slope12 / (2.0 * curvature)
        if (vertex < min(x1, x3) || vertex > max(x1, x3)) return null
        if (vertex.isNaN() || abs(vertex) > 1e6) return null
        return vertex
    }
}
