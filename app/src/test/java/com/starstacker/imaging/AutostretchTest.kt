package com.starstacker.imaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * T-2.2's display transform. The property that matters is not aesthetic: a linear sky sitting a
 * few hundred ADU above black must come out visible, the stars must come out brighter than the
 * sky, and the mapping must never fold back on itself — a non-monotonic stretch would make a
 * faint star darker than the background it sits on.
 */
class AutostretchTest {

    private fun skyFrame(
        width: Int = 128,
        height: Int = 96,
        background: Float = 180f,
        noise: Float = 6f,
        seed: Int = 7,
    ): FloatArray {
        val rng = Random(seed)
        return FloatArray(width * height) {
            background + ((rng.nextDouble() - 0.5) * 2 * noise).toFloat()
        }
    }

    @Test
    fun `the sky background lands on the target grey`() {
        val frame = skyFrame()
        val stretch = Autostretch.measure(frame, black = 64.0, white = 1023.0, stride = 1)

        val median = frame.sorted()[frame.size / 2]
        val stretched = stretch.applyAdu(median.toDouble())

        assertEquals(Autostretch.DEFAULT_TARGET_BACKGROUND, stretched, 0.01)
    }

    @Test
    fun `a linear sky is unreadable and the stretch fixes it`() {
        val frame = skyFrame()
        val median = frame.sorted()[frame.size / 2].toDouble()

        // Before: 180 ADU out of 1023 is 11% grey — nearly black on a phone at night.
        val linear = (median - 64.0) / (1023.0 - 64.0)
        assertTrue(linear < 0.15, "test premise wrong: linear background was $linear")

        val stretch = Autostretch.measure(frame, black = 64.0, white = 1023.0, stride = 1)
        assertTrue(stretch.applyAdu(median) > 0.2, "stretch failed to lift the background")
    }

    @Test
    fun `the transform is monotonic`() {
        val stretch = Autostretch.measure(skyFrame(), black = 64.0, white = 1023.0, stride = 1)
        var previous = -1.0
        var x = 0.0
        while (x <= 1.0) {
            val y = stretch.applyNormalised(x)
            assertTrue(y >= previous - 1e-12, "stretch folded back at x=$x ($previous -> $y)")
            previous = y
            x += 0.001
        }
    }

    @Test
    fun `stars stay brighter than the sky and nothing clips to white`() {
        val width = 128
        val height = 96
        val frame = skyFrame(width, height)
        frame[50 * width + 60] = 700f

        val stretch = Autostretch.measure(frame, black = 64.0, white = 1023.0, stride = 1)
        val gray = Autostretch.toGray8(frame, width, height, stretch)

        val star = gray[50 * width + 60].toInt() and 0xFF
        val sky = gray[10 * width + 10].toInt() and 0xFF
        assertTrue(star > sky + 60, "star ($star) barely stands out from sky ($sky)")
        assertTrue(star <= 255)
    }

    @Test
    fun `the midtone transfer is its own inverse in the parameter`() {
        // MTF(MTF(a, x), x) == a — the identity the midtone solve depends on.
        for (x in listOf(0.02, 0.1, 0.3, 0.7)) {
            val m = Autostretch.mtf(0.25, x)
            assertEquals(0.25, Autostretch.mtf(m, x), 1e-9, "failed at x=$x")
        }
        assertEquals(0.4, Autostretch.mtf(0.5, 0.4), 1e-12, "m=0.5 must be the identity")
    }

    @Test
    fun `a featureless frame does not divide by zero`() {
        val flat = FloatArray(64) { 500f }
        val stretch = Autostretch.measure(flat, black = 64.0, white = 1023.0, stride = 1)
        val gray = Autostretch.toGray8(flat, 8, 8, stretch)
        assertTrue(gray.all { it.toInt() and 0xFF in 0..255 })
    }

    @Test
    fun `an unreported white level falls back to the frame maximum`() {
        val frame = skyFrame()
        frame[0] = 900f
        val stretch = Autostretch.measure(frame, black = 64.0, white = 0.0, stride = 1)
        assertEquals(900.0, stretch.white, 1e-6)
        assertTrue(abs(stretch.applyAdu(900.0) - 1.0) < 1e-6)
    }
}
