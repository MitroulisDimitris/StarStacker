package com.starstacker.stars

import com.starstacker.registration.RigidTransform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/**
 * T-4.6 — the coordinate composition, which is the whole risk in this task.
 *
 * Three systems meet: preview pixels, the binned analysis plane, and sensor coordinates. Getting
 * the conversion between them wrong does not throw and does not look like an error — it produces a
 * preview that is slightly soft, which is indistinguishable from poor focus, poor seeing, or a
 * stack that is working correctly on a mediocre night. So the composition is checked against the
 * transform it is supposed to reproduce, rather than against itself.
 */
class PlaneMappingTest {

    private val sensorW = 4096
    private val sensorH = 3072
    private val bin = 4
    private val planeW = sensorW / bin
    private val planeH = sensorH / bin
    private val previewW = PreviewStack.WIDTH
    private val previewH = PreviewStack.HEIGHT
    private val scaleX = planeW.toDouble() / previewW
    private val scaleY = planeH.toDouble() / previewH
    private val binOffset = (bin - 1) / 2.0

    private val centreX = (sensorW - 1) / 2.0
    private val centreY = (sensorH - 1) / 2.0

    /** Plane coordinate → sensor coordinate, exactly as `BinnedPlane` defines it. */
    private fun toSensor(v: Double) = v * bin + binOffset

    /**
     * What the mapping must reproduce: take a preview pixel to its place in the reference plane,
     * up into sensor space, through the registration transform, and back down.
     */
    private fun expected(t: RigidTransform, px: Int, py: Int): Pair<Double, Double> {
        val planeRefX = px * scaleX
        val planeRefY = py * scaleY
        val (sx, sy) = t.apply(toSensor(planeRefX), toSensor(planeRefY))
        return Pair((sx - binOffset) / bin, (sy - binOffset) / bin)
    }

    private fun check(t: RigidTransform, tolerance: Double = 1e-9) {
        val mapping = PlaneMapping.fromSensorMatrix(
            t.toMatrix(), scaleX, scaleY, bin, binOffset,
        )
        listOf(0 to 0, 100 to 50, 511 to 383, 256 to 192, 40 to 300).forEach { (px, py) ->
            val (ex, ey) = expected(t, px, py)
            assertEquals(ex, mapping.sourceX(px, py), tolerance) { "x at ($px, $py)" }
            assertEquals(ey, mapping.sourceY(px, py), tolerance) { "y at ($px, $py)" }
        }
    }

    @Test
    fun `it reproduces a pure translation through all three coordinate systems`() {
        check(RigidTransform(0.0, 96.0, -48.0, centreX, centreY))
    }

    @Test
    fun `it reproduces a pure rotation`() {
        check(RigidTransform(3.5, 0.0, 0.0, centreX, centreY))
    }

    @Test
    fun `it reproduces rotation and translation together`() {
        check(RigidTransform(-2.25, 40.0, 75.0, centreX, centreY))
    }

    @Test
    fun `an identity transform is the plain downsample`() {
        // The reference frame's own case. If these disagreed, the very first frame would be laid
        // down offset from every frame that follows it.
        val identity = PlaneMapping.fromSensorMatrix(
            RigidTransform(0.0, 0.0, 0.0, centreX, centreY).toMatrix(),
            scaleX, scaleY, bin, binOffset,
        )
        val plain = PlaneMapping.scaling(scaleX, scaleY)
        listOf(0 to 0, 300 to 200, 511 to 383).forEach { (px, py) ->
            assertEquals(plain.sourceX(px, py), identity.sourceX(px, py), 1e-9)
            assertEquals(plain.sourceY(px, py), identity.sourceY(px, py), 1e-9)
        }
    }

    @Test
    fun `a sensor translation is divided by the bin factor, not applied raw`() {
        // The mistake this guards is the obvious one: the transform is measured in sensor pixels
        // and the preview samples a plane binned 4x, so applying the shift unscaled would move
        // the frame four times too far. It would still look like alignment — just bad alignment.
        val shift = 400.0
        val mapping = PlaneMapping.fromSensorMatrix(
            RigidTransform(0.0, shift, 0.0, centreX, centreY).toMatrix(),
            scaleX, scaleY, bin, binOffset,
        )
        val plain = PlaneMapping.scaling(scaleX, scaleY)
        val moved = mapping.sourceX(200, 100) - plain.sourceX(200, 100)
        assertEquals(shift / bin, moved, 1e-9)
    }

    @Test
    fun `the bin offset correction is small but not zero`() {
        // A naive tx/bin drops the binOffset*(a+b-1) term. Under rotation it is a fraction of a
        // pixel — which is precisely the size of error that survives every visual check and still
        // smears a hundred-frame stack.
        val t = RigidTransform(6.0, 0.0, 0.0, centreX, centreY)
        val correct = PlaneMapping.fromSensorMatrix(t.toMatrix(), scaleX, scaleY, bin, binOffset)
        val naive = t.toMatrix().let { m ->
            PlaneMapping(
                a = m[0] * scaleX, b = m[1] * scaleY, tx = m[4] / bin,
                c = m[2] * scaleX, d = m[3] * scaleY, ty = m[5] / bin,
            )
        }
        val dx = correct.sourceX(256, 192) - naive.sourceX(256, 192)
        val dy = correct.sourceY(256, 192) - naive.sourceY(256, 192)
        val difference = hypot(dx, dy)
        assertTrue(difference > 1e-6) { "the correction did nothing" }
        assertTrue(difference < 1.0) { "the correction should be sub-pixel, was $difference" }
    }

    @Test
    fun `an unrotated frame maps rows to rows`() {
        // A sanity property that a transposed matrix would break loudly: with no rotation, the
        // source y must not depend on the preview x.
        val mapping = PlaneMapping.fromSensorMatrix(
            RigidTransform(0.0, 10.0, 20.0, centreX, centreY).toMatrix(),
            scaleX, scaleY, bin, binOffset,
        )
        assertEquals(mapping.sourceY(0, 100), mapping.sourceY(500, 100), 1e-9)
        assertEquals(mapping.sourceX(100, 0), mapping.sourceX(100, 380), 1e-9)
    }

    @Test
    fun `rotation makes the source depend on both axes`() {
        // And the converse: with rotation it must. A mapping that ignored the cross terms would
        // silently degrade to the translation-only behaviour this task exists to replace.
        val mapping = PlaneMapping.fromSensorMatrix(
            RigidTransform(5.0, 0.0, 0.0, centreX, centreY).toMatrix(),
            scaleX, scaleY, bin, binOffset,
        )
        assertTrue(kotlin.math.abs(mapping.sourceY(0, 100) - mapping.sourceY(500, 100)) > 1.0)
        assertTrue(kotlin.math.abs(mapping.sourceX(100, 0) - mapping.sourceX(100, 380)) > 1.0)
    }
}
