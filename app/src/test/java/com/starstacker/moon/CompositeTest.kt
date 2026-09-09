package com.starstacker.moon

import com.starstacker.stacking.TiledStacker
import com.starstacker.synth.SyntheticMoon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/** T-11.11 — the merge, and the layer that is worth more than the merge. */
class CompositeTest {

    private val width = 256
    private val height = 192
    private val channels = TiledStacker.CHANNELS

    /** A ground master: an even, moderately bright landscape with no moon in it. */
    private fun ground(value: Float = 0.2f) = FloatArray(width * height * channels) { value }

    /** A moon master: black sky with a bright disc, as a linear interleaved master. */
    private fun moonMaster(x: Double = 128.0, y: Double = 96.0, radius: Double = 30.0): FloatArray {
        val frame = SyntheticMoon.render(
            width = width, height = height, centreX = x, centreY = y,
            radiusPx = radius, peakAdu = 700.0, noiseAdu = 0.0, clip = false,
        )
        val out = FloatArray(width * height * channels)
        for (i in 0 until width * height) {
            // Black-subtracted and scaled to the 0-1 the linear master works in.
            val v = ((frame.pixels[i] - SyntheticMoon.BLACK_LEVEL) / 1000.0).toFloat()
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    private fun disc(x: Double = 128.0, y: Double = 96.0, radius: Double = 30.0): Disc.Found {
        val frame = SyntheticMoon.render(
            width = width, height = height, centreX = x, centreY = y,
            radiusPx = radius, peakAdu = 700.0, noiseAdu = 0.0,
        )
        return checkNotNull(
            Disc.find(frame.pixels, width, height, SyntheticMoon.WHITE_LEVEL.toDouble()),
        )
    }

    private fun at(master: FloatArray, x: Int, y: Int, c: Int = 0) =
        master[(y * width + x) * channels + c]

    @Test
    fun `the moon lands where the shift says, not where it was`() {
        val g = ground()
        val m = moonMaster(x = 100.0, y = 96.0)
        val d = disc(x = 100.0, y = 96.0)

        // The ground master says the moon belongs 30 px to the right.
        val report = Composite.merge(g, m, width, height, d, shiftX = 30.0, shiftY = 0.0)

        assertEquals(130.0, report.centreX, 2.0)
        // Bright at the new position...
        assertTrue(at(g, 130, 96) > 0.4f, "moon should be at 130, was ${at(g, 130, 96)}")
        // ...and the ground untouched where it used to be.
        assertEquals(0.2f, at(g, 60, 96), 1e-6f, "outside the disc the ground must survive")
    }

    @Test
    fun `the ground survives everywhere outside the feather`() {
        val g = ground()
        val before = g.copyOf()
        val d = disc()
        Composite.merge(g, moonMaster(), width, height, d, 0.0, 0.0)

        val radius = d.equivalentDiameterPx / 2
        val outer = radius + maxOf(Composite.MIN_FEATHER_PX, radius * Composite.FEATHER_FRACTION)
        var checked = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (hypot(x - d.centroidX, y - d.centroidY) <= outer + 1) continue
                assertEquals(
                    before[(y * width + x) * channels], at(g, x, y), 1e-9f,
                    "ground changed at ($x, $y), which is outside the mask",
                )
                checked++
            }
        }
        assertTrue(checked > 40_000, "the test should have checked most of the frame, did $checked")
    }

    @Test
    fun `the feather is a gradient, not a step`() {
        val g = ground()
        val d = disc()
        val report = Composite.merge(g, moonMaster(), width, height, d, 0.0, 0.0)

        val cx = report.centreX.toInt()
        val cy = report.centreY.toInt()
        val r = report.discRadiusPx.toInt()

        // Walk outward from just past the limb and require the values to be strictly between the
        // pure-moon and pure-ground answers — which is what a feather is.
        val insideLimb = at(g, cx, cy)
        val farOutside = at(g, cx + r + 40, cy)
        val inFeather = at(g, cx + r + 1, cy)

        assertTrue(insideLimb > farOutside, "the disc must be brighter than the ground")
        assertTrue(
            inFeather < insideLimb && inFeather != farOutside,
            "feather sample $inFeather should sit between $farOutside and $insideLimb",
        )
    }

    @Test
    fun `the brightness difference between the layers is preserved, not corrected`() {
        // The two were exposed 14 stops apart on purpose. "Fixing" that undoes the bracketing.
        val g = ground(value = 0.2f)
        val d = disc()
        Composite.merge(g, moonMaster(), width, height, d, 0.0, 0.0)

        assertTrue(
            at(g, 128, 96) > 0.5f,
            "the moon must stay far brighter than the 0.2 ground, was ${at(g, 128, 96)}",
        )
    }

    @Test
    fun `a scale multiplies the moon layer without touching the ground`() {
        val a = ground()
        val b = ground()
        val d = disc()
        Composite.merge(a, moonMaster(), width, height, d, 0.0, 0.0, scale = 1.0)
        Composite.merge(b, moonMaster(), width, height, d, 0.0, 0.0, scale = 0.5)

        assertTrue(at(b, 128, 96) < at(a, 128, 96), "scale should dim the moon")
        assertEquals(at(a, 10, 10), at(b, 10, 10), 1e-9f, "the ground is untouched either way")
    }

    @Test
    fun `NaN in the ground master is treated as empty rather than poisoning the merge`() {
        // The crop leaves NaN where no frame reached. A merge that arithmetic'd with it would
        // spread NaN across the whole disc.
        val g = ground()
        for (i in g.indices) g[i] = Float.NaN
        val d = disc()
        Composite.merge(g, moonMaster(), width, height, d, 0.0, 0.0)

        assertTrue(at(g, 128, 96).isFinite(), "the moon should still land on an empty ground")
    }

    // ----------------------------------------------------------------- the layer

    @Test
    fun `the aligned layer moves the disc and marks the rest uncovered`() {
        val layer = Composite.alignedLayer(moonMaster(x = 100.0), width, height, 30.0, 0.0)

        // The disc has moved to 130.
        assertTrue(layer[(96 * width + 130) * channels] > 0.4f)
        // The strip the shift vacated carries the sentinel LinearMaster already writes, so the
        // existing writer and reader need no changes.
        assertTrue(layer[(96 * width + 5) * channels].isNaN(), "uncovered pixels must be NaN")
    }

    @Test
    fun `a zero shift reproduces the master exactly`() {
        val m = moonMaster()
        val layer = Composite.alignedLayer(m, width, height, 0.0, 0.0)
        for (i in m.indices) {
            assertEquals(m[i], layer[i], 0f, "differed at $i")
        }
    }

    @Test
    fun `the report says what was done`() {
        val d = disc()
        val report = Composite.merge(ground(), moonMaster(), width, height, d, 4.0, -2.0)
        assertTrue(report.pixelsReplaced > 1000, "replaced ${report.pixelsReplaced}")
        assertTrue(report.featherPx >= Composite.MIN_FEATHER_PX)
        assertTrue(report.note.contains("Layers are written alongside"), report.note)
    }
}
