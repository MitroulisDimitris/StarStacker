package com.starstacker.moon

import com.starstacker.synth.SyntheticMoon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-11.3 / T-11.6 — the sharpness metric, and the one property both tasks stand on:
 * **a sharper frame must score higher than a softer one, and nothing else may outrank it.**
 *
 * The failure mode being guarded against is specific and easy to write by accident. Sensor noise
 * is high-frequency, so any sharpness metric measured over a mostly-black lunar frame ranks the
 * *noisiest* frame as the sharpest — which in a lucky-imaging cut means keeping the worst 20% and
 * discarding the best.
 */
class SharpnessTest {

    private fun measure(frame: SyntheticMoon.Frame): Double {
        val disc = Disc.find(
            frame.pixels, frame.width, frame.height, SyntheticMoon.WHITE_LEVEL.toDouble(),
        )
        return Sharpness.overDisc(frame.pixels, frame.width, frame.height, disc)
    }

    @Test
    fun `sharpness falls monotonically as blur increases`() {
        val blurs = listOf(0.0, 1.0, 2.0, 3.5, 5.0)
        val scores = blurs.map { measure(SyntheticMoon.render(seed = 21, blurSigma = it, noiseAdu = 1.0)) }

        for (i in 1 until scores.size) {
            assertTrue(
                scores[i] < scores[i - 1],
                "blur ${blurs[i]} scored ${scores[i]}, not below blur ${blurs[i - 1]}'s ${scores[i - 1]}",
            )
        }
        assertTrue(scores.first() > 2 * scores.last(), "the metric should discriminate strongly")
    }

    /**
     * The regression the class note describes: measured over the whole frame, noise wins.
     *
     * **The disc has to be small for this to bite, and small is the real case.** Normalising by
     * the region's variance turns out to protect a *large* disc on its own — with the moon filling
     * a tenth of the frame, the disc dominates the variance and divides the noise back out. At the
     * sizes moon mode actually deals with it does not: the plan's own arithmetic puts the moon at
     * 75 px on the reference device's longest lens, which is **0.03% of a 12 MP frame**. The disc
     * here is 0.4% of the frame — still fifteen times more generous than reality — and the metric
     * already ranks the wrong frame first.
     *
     * So this test asserts both halves: that the bug is real when the region is wrong, and that
     * [Sharpness.overDisc] does not have it.
     */
    @Test
    fun `measuring the whole frame ranks noise as sharpness, and the disc region does not`() {
        val small = 8.0
        val sharpNoisy = SyntheticMoon.render(
            seed = 4, radiusPx = small, craters = 3, blurSigma = 0.0, noiseAdu = 1.0,
        )
        val softNoisy = SyntheticMoon.render(
            seed = 4, radiusPx = small, craters = 3, blurSigma = 4.0, noiseAdu = 8.0,
        )

        // Whole frame: the soft-but-noisy frame wins, because 99% of the frame is sky and noise is
        // the only high-frequency content in it.
        val wholeSharp = Sharpness.laplacianVariance(
            sharpNoisy.pixels, sharpNoisy.width, sharpNoisy.height,
        )
        val wholeSoft = Sharpness.laplacianVariance(
            softNoisy.pixels, softNoisy.width, softNoisy.height,
        )
        assertTrue(
            wholeSoft > wholeSharp,
            "the premise of this test failed: whole-frame gave $wholeSoft for the soft frame " +
                "against $wholeSharp for the sharp one",
        )

        // Over the disc: the sharp frame wins, which is the answer lucky imaging needs.
        assertTrue(
            measure(sharpNoisy) > measure(softNoisy),
            "over the disc, the sharp frame must win: ${measure(sharpNoisy)} vs ${measure(softNoisy)}",
        )
    }

    @Test
    fun `brightness alone does not change the score`() {
        // The normalisation earns its place here: two identical frames at different exposures are
        // equally sharp, and a metric that scaled with contrast would rank the brighter one first
        // across a run through varying transparency.
        val dim = SyntheticMoon.render(seed = 9, peakAdu = 300.0, noiseAdu = 0.0)
        val bright = SyntheticMoon.render(seed = 9, peakAdu = 900.0, noiseAdu = 0.0)

        val a = measure(dim)
        val b = measure(bright)
        assertEquals(a, b, a * 0.15, "scores $a and $b should be within 15%")
    }

    @Test
    fun `a frame with no disc scores zero rather than ranking first`() {
        val empty = SyntheticMoon.render(radiusPx = 0.0, craters = 0, noiseAdu = 6.0)
        assertEquals(0.0, measure(empty), 1e-12)
    }

    @Test
    fun `gradient energy agrees with the Laplacian on which frame is sharper`() {
        // They are different metrics for different jobs; they must not disagree about direction.
        val sharp = SyntheticMoon.render(seed = 13, blurSigma = 0.5, noiseAdu = 1.0)
        val soft = SyntheticMoon.render(seed = 13, blurSigma = 4.0, noiseAdu = 1.0)

        fun box(f: SyntheticMoon.Frame): IntArray {
            val d = checkNotNull(
                Disc.find(f.pixels, f.width, f.height, SyntheticMoon.WHITE_LEVEL.toDouble()),
            )
            return d.boxWithMargin(Sharpness.LIMB_MARGIN, f.width, f.height)
        }

        val bs = box(sharp)
        val bf = box(soft)
        val gSharp = Sharpness.gradientEnergy(sharp.pixels, sharp.width, sharp.height, bs[0], bs[1], bs[2], bs[3])
        val gSoft = Sharpness.gradientEnergy(soft.pixels, soft.width, soft.height, bf[0], bf[1], bf[2], bf[3])
        assertTrue(gSharp > gSoft, "gradient energy: $gSharp should beat $gSoft")
    }

    @Test
    fun `a flat region scores zero rather than dividing by zero`() {
        val flat = DoubleArray(64 * 64) { 500.0 }
        assertEquals(0.0, Sharpness.laplacianVariance(flat, 64, 64), 1e-12)
        assertEquals(0.0, Sharpness.gradientEnergy(flat, 64, 64), 1e-12)
    }

    @Test
    fun `a region too small to hold the kernel returns zero rather than throwing`() {
        val tiny = DoubleArray(4 * 4) { it.toDouble() }
        assertEquals(0.0, Sharpness.laplacianVariance(tiny, 4, 4, 1, 1, 2, 2), 1e-12)
    }
}
