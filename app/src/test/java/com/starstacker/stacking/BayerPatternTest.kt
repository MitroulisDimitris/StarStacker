package com.starstacker.stacking

import com.starstacker.stars.CfaBinner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * T-5.1 — the one part of the OpenCV wrapper a JVM test can reach.
 *
 * The native library cannot load off-device, so `warpToReference` and `debayer` are verified by
 * `--es diag warp` instead. What *is* checkable here is the mapping between how a DNG names its
 * Bayer pattern and how OpenCV names the same pattern — and that is worth checking precisely
 * because **the two conventions disagree**. OpenCV names its codes after the second row's second
 * pixel; `CFAPattern` names the first row's first. Get it wrong and red and blue swap in the
 * master, which nothing in a linear astro frame makes obvious.
 */
class BayerPatternTest {

    @Test
    fun `the reference device's GRBG maps to OpenCV's BayerGB`() {
        // The Nothing (3a) Pro reports GRBG (§1.5). This single case matters most, being the only
        // pattern this app has ever actually seen.
        val codes = CfaBinner.codesFor("GRBG")
        assertEquals(Resample.BayerPattern.GRBG, Resample.BayerPattern.of(codes))
    }

    @Test
    fun `every arrangement CfaBinner knows has a mapping`() {
        listOf("RGGB", "GRBG", "GBRG", "BGGR").forEach { name ->
            val pattern = Resample.BayerPattern.of(CfaBinner.codesFor(name))
            assertEquals(name, pattern?.name) { "$name did not map to itself" }
        }
    }

    @Test
    fun `the four patterns use four different OpenCV codes`() {
        val codes = Resample.BayerPattern.entries.map { it.openCvCode }
        assertEquals(codes.size, codes.distinct().size) { "two patterns share a code: $codes" }
    }

    @Test
    fun `the mapping is offset from the naive one, which is the whole point`() {
        // If someone "simplifies" this to obvious name-matching, this fails: GRBG is BayerGB, not
        // BayerGR. The offset is the convention difference, not a typo.
        assertNotEquals(
            org.opencv.imgproc.Imgproc.COLOR_BayerGR2RGB,
            Resample.BayerPattern.GRBG.openCvCode,
        )
        assertEquals(
            org.opencv.imgproc.Imgproc.COLOR_BayerGB2RGB,
            Resample.BayerPattern.GRBG.openCvCode,
        )
    }

    @Test
    fun `an unknown arrangement has no mapping rather than a guess`() {
        assertNull(Resample.BayerPattern.of(listOf(0, 0, 0, 0)))
        assertNull(Resample.BayerPattern.of(listOf(1, 0, 2)))
    }
}
