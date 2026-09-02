package com.starstacker.stacking

import com.starstacker.registration.RigidTransform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The band offset in `Resample.warpBand`, checked without OpenCV.
 *
 * The warp itself cannot be tested off-device (§1.31) and its direction was settled on the phone
 * in T-5.1. What this pins is the *other* half, which the device check did not cover because it
 * warped a whole frame: a band does not know where it sits in the frame, and the transform is
 * expressed against the frame.
 *
 * This is the fourth appearance of the same class of bug in this project — the inverse transform
 * in T-5.3's stub, the minus sign in §1.27, the coordinate composition in §1.28 — and every one of
 * them produced a plausible image rather than an exception. Hence a test for four lines of
 * arithmetic.
 */
class BandMatrixTest {

    private val transform = RigidTransform(
        rotationDeg = 2.5,
        dx = 11.0,
        dy = -6.0,
        centreX = 2048.0,
        centreY = 1536.0,
    )

    /** What the matrix is supposed to mean, written out the slow way. */
    private fun expected(x: Double, y: Double, rowOffset: Int): Pair<Double, Double> {
        val (sx, sy) = transform.apply(x, y + rowOffset)
        return sx to (sy - rowOffset)
    }

    private fun actual(x: Double, y: Double, rowOffset: Int): Pair<Double, Double> {
        val m = Resample.bandMatrix(transform, rowOffset)
        return (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])
    }

    @Test
    fun `a band's matrix maps band coordinates the way the whole-frame transform maps frame ones`() {
        listOf(0, 1, 160, 340, 2840).forEach { offset ->
            listOf(0.0 to 0.0, 4095.0 to 0.0, 0.0 to 419.0, 2000.0 to 250.0).forEach { (x, y) ->
                val (ex, ey) = expected(x, y, offset)
                val (ax, ay) = actual(x, y, offset)
                assertEquals(ex, ax, 1e-9, "x at offset $offset, point ($x, $y)")
                assertEquals(ey, ay, 1e-9, "y at offset $offset, point ($x, $y)")
            }
        }
    }

    @Test
    fun `at offset zero it is the frame's own matrix`() {
        val whole = transform.toMatrix()
        val band = Resample.bandMatrix(transform, 0)
        // Same six numbers, reordered from [a, b, c, d, tx, ty] into OpenCV's [a, b, tx, c, d, ty].
        assertEquals(whole[0], band[0], 1e-12)
        assertEquals(whole[1], band[1], 1e-12)
        assertEquals(whole[4], band[2], 1e-12)
        assertEquals(whole[2], band[3], 1e-12)
        assertEquals(whole[3], band[4], 1e-12)
        assertEquals(whole[5], band[5], 1e-12)
    }

    @Test
    fun `the step back into band coordinates is not forgotten`() {
        // The specific error this guards: using d*r instead of (d-1)*r. With no rotation at all,
        // d is 1, so the correct adjustment is exactly zero and the wrong one is the whole offset.
        val straight = RigidTransform(0.0, 3.0, 7.0, 2048.0, 1536.0)
        val m = Resample.bandMatrix(straight, 500)
        assertEquals(straight.toMatrix()[5], m[5], 1e-12, "a pure shift must not move with the band")
    }
}
