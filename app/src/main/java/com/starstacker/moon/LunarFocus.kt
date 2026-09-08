package com.starstacker.moon

import com.starstacker.focus.FocusSweep

/**
 * T-11.3 — focusing on the moon, where T-2.4's star sweep has nothing to measure.
 *
 * ### What is reused and what is not
 *
 * The *mechanics* of a sweep are unchanged and are not re-implemented here: `FocusSweep`'s
 * [FocusSweep.infinitySweep], [FocusSweep.localSweep] and [FocusSweep.parkPosition] are about
 * driving a voice-coil motor without hysteresis — always approaching a setpoint from the near side
 * — and a lunar target does not change any of that.
 *
 * What changes is the reading. T-2.4 **minimises** half-flux radius over detected stars; there are
 * no stars in a lunar frame, and whatever the detector finds on a crater rim is not a point source.
 * This **maximises** [Sharpness] over the disc instead. Same sweep, same motor discipline, opposite
 * direction of travel on the curve.
 *
 * ### Why the metric is measured on the disc and not the frame
 *
 * A lunar frame is nearly all black sky, and sensor noise is high-frequency: measure sharpness over
 * the whole frame and the *noisiest* position wins, which is usually the one furthest from focus.
 * Every sample here is taken over [Disc]'s bounding box, and a sample with no disc found is
 * discarded rather than scored — a position where the moon could not be detected is not a position
 * that was in focus.
 *
 * ### Why a parabola, again
 *
 * Near best focus a contrast curve is locally quadratic, exactly as a defocus curve is, so the
 * vertex of a fit through the peak and its two neighbours beats the sampled maximum by roughly half
 * a step. `FocusSweep.parabolaVertex` finds *minima*, so the sharpness is negated to reuse it
 * rather than write a second one that could disagree with the first.
 */
object LunarFocus {

    /** Below this, the sweep did not find a real peak and the curve is flat noise. */
    const val FLAT_TOLERANCE = 0.10

    /** Fewer usable samples than this cannot bracket a maximum. */
    const val MIN_SAMPLES = 3

    data class Sample(
        val diopters: Float,
        /** [Sharpness.overDisc] at this position, or null when no disc was found. */
        val sharpness: Double?,
        val discPixels: Int,
    )

    enum class Verdict {
        /** Sharpness rises and falls again — the peak is bracketed and trustworthy. */
        CLEAR_PEAK,

        /** The best position is at an end of the sweep. Widen it, or it is the hard stop. */
        PEAK_AT_EDGE,

        /** Sharpness barely varies. Either already focused, or nothing resolvable in frame. */
        FLAT,

        /** The disc was not found at enough positions to say anything. */
        NO_DISC,
    }

    data class Curve(
        val samples: List<Sample>,
        val bestDiopters: Float,
        val bestSharpness: Double,
        val verdict: Verdict,
        val interpolated: Boolean,
        val note: String,
    ) {
        val usable: Boolean get() = verdict == Verdict.CLEAR_PEAK || verdict == Verdict.PEAK_AT_EDGE
    }

    /**
     * Reads a sweep.
     *
     * @param flatTolerance the fractional spread below which the curve is called flat. A sharpness
     *   curve that moves by less than this between its best and worst position is not measuring
     *   focus, it is measuring noise.
     */
    fun analyse(
        samples: List<Sample>,
        flatTolerance: Double = FLAT_TOLERANCE,
    ): Curve {
        val valid = samples.filter { it.sharpness != null && it.sharpness > 0.0 && it.discPixels > 0 }
        if (valid.size < MIN_SAMPLES) {
            val fallback = valid.maxByOrNull { it.sharpness!! }
            return Curve(
                samples = samples,
                bestDiopters = fallback?.diopters ?: 0f,
                bestSharpness = fallback?.sharpness ?: Double.NaN,
                verdict = Verdict.NO_DISC,
                interpolated = false,
                note = "the disc was found at only ${valid.size} of ${samples.size} positions — " +
                    "cloud, or the moon is out of frame",
            )
        }

        val best = valid.maxByOrNull { it.sharpness!! }!!
        val worst = valid.minByOrNull { it.sharpness!! }!!
        val spread = if (best.sharpness!! <= 0.0) 0.0 else {
            (best.sharpness - worst.sharpness!!) / best.sharpness
        }
        if (spread < flatTolerance) {
            return Curve(
                samples = samples,
                bestDiopters = best.diopters,
                bestSharpness = best.sharpness,
                verdict = Verdict.FLAT,
                interpolated = false,
                note = "sharpness varies by only %.1f%% across the sweep — already focused, or ".format(spread * 100) +
                    "nothing in frame is resolvable",
            )
        }

        // Ordered by position so "neighbour" means what it says, whichever direction the motor ran.
        val ordered = valid.sortedBy { it.diopters }
        val peakIndex = ordered.indexOfFirst { it === best }
        if (peakIndex <= 0 || peakIndex >= ordered.size - 1) {
            return Curve(
                samples = samples,
                bestDiopters = best.diopters,
                bestSharpness = best.sharpness,
                verdict = Verdict.PEAK_AT_EDGE,
                interpolated = false,
                note = "the sharpest position is at the end of the sweep — widen it, unless that " +
                    "is the lens's hard stop",
            )
        }

        val a = ordered[peakIndex - 1]
        val b = ordered[peakIndex]
        val c = ordered[peakIndex + 1]
        // Negated because parabolaVertex finds minima. Reusing it rather than writing a maximising
        // twin keeps one implementation of the interpolation that both sweeps depend on.
        val vertex = FocusSweep.parabolaVertex(
            a.diopters.toDouble(), -a.sharpness!!,
            b.diopters.toDouble(), -b.sharpness!!,
            c.diopters.toDouble(), -c.sharpness!!,
        )

        return Curve(
            samples = samples,
            bestDiopters = vertex?.toFloat() ?: best.diopters,
            bestSharpness = best.sharpness,
            verdict = Verdict.CLEAR_PEAK,
            interpolated = vertex != null,
            note = if (vertex != null) {
                "peak bracketed and interpolated between %.4f and %.4f dioptres"
                    .format(a.diopters, c.diopters)
            } else {
                "peak bracketed; the parabola did not improve on the sampled position"
            },
        )
    }
}
