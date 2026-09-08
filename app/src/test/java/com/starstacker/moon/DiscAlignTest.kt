package com.starstacker.moon

import com.starstacker.synth.SyntheticMoon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-11.5 — alignment on the disc.
 *
 * The bar is set by what star matching could not do on 2026-09-06: 34 of 67 frames rejected on a
 * scene that was aligned to the pixel. These tests use frames that a triangle matcher would have
 * nothing to work with — one bright blob and no point sources — and require every one to register.
 */
class DiscAlignTest {

    private fun disc(frame: SyntheticMoon.Frame) = Disc.find(
        frame.pixels, frame.width, frame.height, SyntheticMoon.WHITE_LEVEL.toDouble(),
    )

    private fun frameAt(x: Double, y: Double, seed: Int = 31, blur: Double = 0.0) =
        SyntheticMoon.render(centreX = x, centreY = y, seed = seed, blurSigma = blur)

    @Test
    fun `a pure translation is recovered to a fraction of a pixel`() {
        val reference = frameAt(128.0, 96.0)
        val shifted = frameAt(134.0, 91.0)

        val result = DiscAlign.match(
            disc(reference), disc(shifted), reference.width, reference.height,
        )
        assertTrue(result is DiscAlign.Result.Matched, "should have matched: $result")
        val matched = result as DiscAlign.Result.Matched
        assertEquals(6.0, matched.dx, 0.3)
        assertEquals(-5.0, matched.dy, 0.3)
        assertEquals(7.81, matched.shiftPx, 0.3)
    }

    @Test
    fun `a frame identical to the reference registers at zero`() {
        val reference = frameAt(120.0, 90.0)
        val result = DiscAlign.match(disc(reference), disc(reference), 256, 192)
        val matched = result as DiscAlign.Result.Matched
        assertEquals(0.0, matched.dx, 1e-9)
        assertEquals(0.0, matched.dy, 1e-9)
    }

    /** The whole point: a frame with no stars in it still registers. */
    @Test
    fun `a sequence of drifting frames all register`() {
        val reference = frameAt(100.0, 96.0)
        val refDisc = disc(reference)

        // 26 px over the run, which is the drift §1.45 measured on a real session.
        for (step in 1..10) {
            val moved = frameAt(100.0 + step * 2.6, 96.0 - step * 0.8, blur = 0.5)
            val result = DiscAlign.match(refDisc, disc(moved), moved.width, moved.height)
            assertTrue(
                result is DiscAlign.Result.Matched,
                "frame $step failed: ${(result as? DiscAlign.Result.Rejected)?.reason}",
            )
            val matched = result as DiscAlign.Result.Matched
            assertEquals(step * 2.6, matched.dx, 0.5, "frame $step dx")
            assertEquals(-step * 0.8, matched.dy, 0.5, "frame $step dy")
        }
    }

    @Test
    fun `a missing disc is rejected with a reason rather than registered at zero`() {
        val reference = frameAt(128.0, 96.0)
        val empty = SyntheticMoon.render(radiusPx = 0.0, craters = 0, noiseAdu = 4.0)

        val result = DiscAlign.match(disc(reference), disc(empty), 256, 192)
        val rejected = result as DiscAlign.Result.Rejected
        assertTrue(rejected.reason.contains("no disc"), rejected.reason)
    }

    @Test
    fun `a detection that changed size is distrusted`() {
        // Cloud eating the limb, or the threshold catching something else entirely.
        val reference = SyntheticMoon.render(radiusPx = 40.0, seed = 2)
        val muchSmaller = SyntheticMoon.render(radiusPx = 12.0, seed = 2)

        val result = DiscAlign.match(disc(reference), disc(muchSmaller), 256, 192)
        val rejected = result as DiscAlign.Result.Rejected
        assertTrue(rejected.reason.contains("area"), rejected.reason)
    }

    @Test
    fun `the transform carries no rotation and the shift the match found`() {
        val matched = DiscAlign.Result.Matched(dx = 12.5, dy = -3.25, shiftPx = 12.9)
        val transform = DiscAlign.asTransform(matched, 4096, 3072)

        assertEquals(0.0, transform.rotationDeg, "translation only, by construction")
        assertEquals(12.5, transform.dx)
        assertEquals(-3.25, transform.dy)
        assertEquals(2048.0, transform.centreX)
        assertEquals(1536.0, transform.centreY)
    }

    // --- the drift fit, which T-11.10 will need and which measures the night's true rate ---

    @Test
    fun `a straight drift is recovered exactly`() {
        // 0.6 px/s in x, -0.2 in y, sampled every two seconds.
        val samples = (0..10).map { i ->
            val t = i * 2000L
            Triple(t, 100.0 + 0.0006 * t, 50.0 - 0.0002 * t)
        }
        val at = checkNotNull(DiscAlign.fitDrift(samples, 10_000L))
        assertEquals(106.0, at.first, 1e-6)
        assertEquals(48.0, at.second, 1e-6)
    }

    @Test
    fun `the fit averages out seeing noise rather than trusting two points`() {
        // The same drift, with a deliberately wild first and last sample. A two-point slope would
        // inherit all of that error; least squares over eleven samples should not.
        val samples = (0..10).map { i ->
            val t = i * 2000L
            val jitter = when (i) {
                0 -> 4.0
                10 -> -4.0
                else -> 0.0
            }
            Triple(t, 100.0 + 0.0006 * t + jitter, 50.0)
        }
        val at = checkNotNull(DiscAlign.fitDrift(samples, 10_000L))
        // The midpoint is where a least-squares line is least affected by symmetric end errors.
        assertEquals(106.0, at.first, 0.5)
    }

    @Test
    fun `a fit needs two distinct times`() {
        assertNull(DiscAlign.fitDrift(listOf(Triple(0L, 1.0, 1.0)), 0L))
        assertNull(
            DiscAlign.fitDrift(
                listOf(Triple(5L, 1.0, 1.0), Triple(5L, 2.0, 2.0)), 5L,
            ),
            "two samples at the same instant define no slope",
        )
    }
}
