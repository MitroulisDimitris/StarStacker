package com.starstacker.moon

import com.starstacker.synth.SyntheticMoon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * T-11.3 — reading a lunar focus sweep.
 *
 * The sweep is driven end to end against the synthetic moon rather than against invented numbers,
 * because the thing being tested is whether *the metric and the curve reader agree* — a sweep that
 * maximises the wrong quantity still produces a tidy-looking parabola.
 */
class LunarFocusTest {

    /**
     * A sweep across true focus. Defocus is modelled as blur that grows with distance from the
     * best position, which is what a defocused lens does.
     */
    private fun sweep(
        positions: List<Float>,
        trueFocus: Float,
        blurPerDiopter: Double = 12.0,
        noiseAdu: Double = 1.0,
    ): List<LunarFocus.Sample> = positions.map { d ->
        val blur = abs(d - trueFocus) * blurPerDiopter
        val frame = SyntheticMoon.render(seed = 17, blurSigma = blur, noiseAdu = noiseAdu)
        val disc = Disc.find(
            frame.pixels, frame.width, frame.height, SyntheticMoon.WHITE_LEVEL.toDouble(),
        )
        LunarFocus.Sample(
            diopters = d,
            sharpness = Sharpness.overDisc(frame.pixels, frame.width, frame.height, disc),
            discPixels = disc?.pixelCount ?: 0,
        )
    }

    @Test
    fun `a bracketed peak is found and interpolated`() {
        val positions = listOf(0.00f, 0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f)
        val curve = LunarFocus.analyse(sweep(positions, trueFocus = 0.155f))

        assertEquals(LunarFocus.Verdict.CLEAR_PEAK, curve.verdict, curve.note)
        assertTrue(curve.usable)
        assertEquals(0.155f, curve.bestDiopters, 0.03f, "found ${curve.bestDiopters}")
    }

    @Test
    fun `interpolation beats the sampled position`() {
        // True focus deliberately placed between two samples, so the sampled best is 0.025 out and
        // a working parabola should land closer than that.
        val positions = listOf(0.00f, 0.05f, 0.10f, 0.15f, 0.20f, 0.25f)
        val samples = sweep(positions, trueFocus = 0.125f)
        val curve = LunarFocus.analyse(samples)

        val sampledBest = samples.filter { it.sharpness != null }.maxByOrNull { it.sharpness!! }!!
        if (curve.interpolated) {
            assertTrue(
                abs(curve.bestDiopters - 0.125f) <= abs(sampledBest.diopters - 0.125f),
                "interpolated ${curve.bestDiopters} should be no worse than sampled " +
                    "${sampledBest.diopters}",
            )
        }
    }

    @Test
    fun `a peak at the end of the sweep is reported rather than trusted`() {
        // True focus is past the near end, so the curve only rises — widening is the right advice.
        val positions = listOf(0.10f, 0.15f, 0.20f, 0.25f, 0.30f)
        val curve = LunarFocus.analyse(sweep(positions, trueFocus = 0.42f))

        assertEquals(LunarFocus.Verdict.PEAK_AT_EDGE, curve.verdict, curve.note)
        assertTrue(curve.note.contains("widen"), curve.note)
        assertTrue(curve.usable, "an edge peak is still the best position known")
    }

    @Test
    fun `a flat curve is called flat rather than interpolated into a false peak`() {
        // Every position at the same blur: nothing but noise separates them, and a reader that
        // fitted a parabola to that would report a confident position that means nothing.
        val positions = listOf(0.00f, 0.05f, 0.10f, 0.15f, 0.20f)
        val samples = positions.map { d ->
            val frame = SyntheticMoon.render(seed = 17, blurSigma = 2.0, noiseAdu = 1.0)
            val disc = Disc.find(
                frame.pixels, frame.width, frame.height, SyntheticMoon.WHITE_LEVEL.toDouble(),
            )
            LunarFocus.Sample(d, Sharpness.overDisc(frame.pixels, frame.width, frame.height, disc), disc?.pixelCount ?: 0)
        }
        val curve = LunarFocus.analyse(samples)
        assertEquals(LunarFocus.Verdict.FLAT, curve.verdict, curve.note)
    }

    @Test
    fun `a sweep with no disc says so rather than picking a position`() {
        val samples = listOf(0.0f, 0.1f, 0.2f).map { LunarFocus.Sample(it, 0.0, 0) }
        val curve = LunarFocus.analyse(samples)
        assertEquals(LunarFocus.Verdict.NO_DISC, curve.verdict)
        assertTrue(!curve.usable)
        assertTrue(curve.note.contains("out of frame") || curve.note.contains("cloud"), curve.note)
    }

    @Test
    fun `positions are read in order regardless of the direction the motor ran`() {
        // FocusSweep drives descending, so samples arrive high-to-low. The peak's neighbours must
        // still be its neighbours in *position*, or the parabola is fitted to the wrong three.
        val positions = listOf(0.30f, 0.25f, 0.20f, 0.15f, 0.10f, 0.05f, 0.00f)
        val curve = LunarFocus.analyse(sweep(positions, trueFocus = 0.155f))

        assertEquals(LunarFocus.Verdict.CLEAR_PEAK, curve.verdict, curve.note)
        assertEquals(0.155f, curve.bestDiopters, 0.03f)
    }
}
