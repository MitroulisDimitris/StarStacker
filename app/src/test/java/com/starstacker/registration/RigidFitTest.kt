package com.starstacker.registration

import com.starstacker.synth.SyntheticSky
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * T-4.3 — the rigid fit and its outlier rejection.
 *
 * The tests are built around the two ways this can fail, which are not symmetric. Missing a good
 * frame costs one exposure out of a hundred and fifty. **Accepting a wrong transform silently
 * corrupts the stack**, because every later stage takes the transform on trust and there is no
 * stage that could notice. So most of what follows is about the second kind.
 */
class RigidFitTest {

    private val centreX = 255.5
    private val centreY = 191.5
    private val tolerance = RigidFit.DEFAULT_TOLERANCE_PX

    private fun field(count: Int, seed: Int): List<AsterismMatcher.Detection> {
        val rng = Random(seed)
        return List(count) {
            AsterismMatcher.Detection(
                x = 20.0 + rng.nextDouble() * 472.0,
                y = 20.0 + rng.nextDouble() * 344.0,
                flux = 100.0 + rng.nextDouble() * 900.0,
            )
        }
    }

    private fun move(
        stars: List<AsterismMatcher.Detection>,
        rotationDeg: Double = 0.0,
        dx: Double = 0.0,
        dy: Double = 0.0,
    ): List<AsterismMatcher.Detection> {
        val t = Math.toRadians(rotationDeg)
        return stars.map {
            val ox = it.x - centreX
            val oy = it.y - centreY
            it.copy(
                x = centreX + ox * cos(t) - oy * sin(t) + dx,
                y = centreY + ox * sin(t) + oy * cos(t) + dy,
            )
        }
    }

    private fun identityPairs(n: Int) =
        (0 until n).map { AsterismMatcher.Correspondence(it, it, votes = 200) }

    // ------------------------------------------------------------------ the fit itself

    @Test
    fun `it recovers a pure translation exactly`() {
        val ref = field(20, seed = 1)
        val moved = move(ref, dx = 31.0, dy = -17.0)

        val result = RigidFit.fit(ref, moved, identityPairs(20), centreX, centreY)

        assertTrue(result.succeeded)
        assertEquals(0.0, result.transform!!.rotationDeg, 1e-9)
        assertEquals(31.0, result.transform!!.dx, 1e-9)
        assertEquals(-17.0, result.transform!!.dy, 1e-9)
        assertEquals(0.0, result.residualRmsPx, 1e-9)
    }

    @Test
    fun `it recovers a rotation and a translation exactly`() {
        val ref = field(20, seed = 2)
        val moved = move(ref, rotationDeg = 8.25, dx = 12.0, dy = 40.0)

        val result = RigidFit.fit(ref, moved, identityPairs(20), centreX, centreY)

        assertTrue(result.succeeded)
        assertEquals(8.25, result.transform!!.rotationDeg, 1e-9)
        assertEquals(12.0, result.transform!!.dx, 1e-9)
        assertEquals(40.0, result.transform!!.dy, 1e-9)
    }

    @Test
    fun `it averages centroid noise down rather than chasing it`() {
        // The reason the final refit uses every inlier instead of the two that found them: twenty
        // pairs carry a twentieth of the noise variance of two.
        val rng = Random(5)
        val ref = field(24, seed = 3)
        val moved = move(ref, rotationDeg = 5.0, dx = 20.0, dy = -9.0).map {
            it.copy(x = it.x + rng.nextDouble(-0.3, 0.3), y = it.y + rng.nextDouble(-0.3, 0.3))
        }

        val result = RigidFit.fit(ref, moved, identityPairs(24), centreX, centreY)

        assertTrue(result.succeeded)
        // The recovered transform is far better than any individual measurement of it.
        assertEquals(5.0, result.transform!!.rotationDeg, 0.05)
        assertEquals(20.0, result.transform!!.dx, 0.15)
        assertEquals(-9.0, result.transform!!.dy, 0.15)
        assertTrue(result.residualRmsPx < 0.3) { "rms ${result.residualRmsPx}" }
    }

    // ------------------------------------------------------------------ outliers

    @Test
    fun `one outlier does not move the answer`() {
        // A plain least-squares fit would be dragged by this; squared error makes a star 200 px
        // out pull ten thousand times harder than a good one a pixel out.
        val ref = field(20, seed = 4)
        val moved = move(ref, rotationDeg = 6.0, dx = 25.0, dy = 15.0).toMutableList()
        moved[7] = moved[7].copy(x = moved[7].x + 180.0, y = moved[7].y - 120.0)

        val result = RigidFit.fit(ref, moved, identityPairs(20), centreX, centreY)

        assertTrue(result.succeeded)
        assertEquals(6.0, result.transform!!.rotationDeg, 1e-6)
        assertEquals(25.0, result.transform!!.dx, 1e-6)
        assertEquals(19, result.inlierCount)
        assertTrue(result.inliers.none { it.referenceIndex == 7 }) { "the outlier was kept" }
    }

    @Test
    fun `it survives a third of the pairs being wrong`() {
        val rng = Random(6)
        val ref = field(30, seed = 7)
        val moved = move(ref, rotationDeg = -4.0, dx = 40.0, dy = 22.0).toMutableList()
        val corrupted = (0 until 30).shuffled(Random(11)).take(10)
        corrupted.forEach { i ->
            moved[i] = moved[i].copy(
                x = rng.nextDouble(20.0, 490.0),
                y = rng.nextDouble(20.0, 360.0),
            )
        }

        val result = RigidFit.fit(ref, moved, identityPairs(30), centreX, centreY)

        assertTrue(result.succeeded)
        assertEquals(-4.0, result.transform!!.rotationDeg, 1e-4)
        assertEquals(40.0, result.transform!!.dx, 1e-3)
        assertTrue(result.inlierCount >= 20) { "kept only ${result.inlierCount} of 20 good pairs" }
        assertTrue(result.inliers.none { it.referenceIndex in corrupted }) { "an outlier survived" }
    }

    @Test
    fun `correspondences that fit no transform are refused`() {
        // The failure that matters. Pairs that agree with each other but with no rigid transform
        // must produce nothing — a confident wrong answer here corrupts the stack silently,
        // because every later stage takes the transform on trust.
        val rng = Random(8)
        val ref = field(20, seed = 9)
        val nonsense = field(20, seed = 10).map {
            it.copy(x = rng.nextDouble(20.0, 490.0), y = rng.nextDouble(20.0, 360.0))
        }

        val result = RigidFit.fit(ref, nonsense, identityPairs(20), centreX, centreY)

        // Either it fails outright, or it finds a handful of coincidental agreements — but never
        // enough to look like a registered frame.
        assertTrue(!result.succeeded || result.inlierCount <= 5) {
            "fitted ${result.inlierCount} of 20 random pairs"
        }
    }

    @Test
    fun `too few pairs is a failure, not a fit`() {
        val ref = field(10, seed = 12)
        val moved = move(ref, dx = 5.0)
        val result = RigidFit.fit(ref, moved, identityPairs(2), centreX, centreY)
        assertFalse(result.succeeded)
        assertNull(result.transform)
    }

    @Test
    fun `stars all in one place determine no rotation and are refused`() {
        // A degenerate set: returning zero rotation would be a guess wearing a measurement's
        // clothes, and downstream cannot tell the difference.
        val same = List(6) { AsterismMatcher.Detection(100.0, 100.0, 500.0) }
        assertNull(RigidFit.leastSquares(same, same, identityPairs(6), centreX, centreY))
    }

    // ------------------------------------------------------------------ the seed

    @Test
    fun `a good seed is used and then improved on`() {
        // "Refining the seed": scored like any hypothesis, then refitted on its own supporters, so
        // the answer is better than the seed that found it.
        val ref = field(20, seed = 13)
        val moved = move(ref, rotationDeg = 0.5, dx = 9.0, dy = -4.0)
        val seed = SkyDrift.Seed(rotationDeg = 0.45, dx = 8.4, dy = -3.6, trustworthy = true)

        val result = RigidFit.fit(ref, moved, identityPairs(20), centreX, centreY, seed)

        assertTrue(result.succeeded)
        assertEquals(20, result.inlierCount)
        assertEquals(0.5, result.transform!!.rotationDeg, 1e-6)
        assertEquals(9.0, result.transform!!.dx, 1e-6)
    }

    @Test
    fun `a wrong seed is scored and discarded like any other guess`() {
        val ref = field(20, seed = 14)
        val moved = move(ref, rotationDeg = 6.0, dx = 30.0, dy = 12.0)
        val stale = SkyDrift.Seed(rotationDeg = -20.0, dx = -150.0, dy = 90.0, trustworthy = true)

        val result = RigidFit.fit(ref, moved, identityPairs(20), centreX, centreY, stale)

        assertTrue(result.succeeded)
        assertEquals(6.0, result.transform!!.rotationDeg, 1e-6)
        assertEquals(20, result.inlierCount)
    }

    // ------------------------------------------------------------------ properties

    @Test
    fun `the same input always gives the same answer`() {
        // RANSAC samples randomly, so without a fixed generator a frame could stack today and be
        // rejected tomorrow on identical data — a bug nobody can reproduce.
        val ref = field(24, seed = 15)
        val moved = move(ref, rotationDeg = 3.0, dx = 14.0, dy = 6.0)
        val a = RigidFit.fit(ref, moved, identityPairs(24), centreX, centreY)
        val b = RigidFit.fit(ref, moved, identityPairs(24), centreX, centreY)

        assertEquals(a.transform, b.transform)
        assertEquals(a.inlierCount, b.inlierCount)
    }

    @Test
    fun `the inverse undoes the transform`() {
        // What stacking applies to bring a frame home, so it has to be exactly right.
        val t = RigidTransform(7.5, 22.0, -13.0, centreX, centreY)
        val inverse = t.inverse()
        listOf(0.0 to 0.0, 100.0 to 50.0, 480.0 to 300.0).forEach { (x, y) ->
            val (px, py) = t.apply(x, y)
            val (bx, by) = inverse.apply(px, py)
            assertEquals(x, bx, 1e-9)
            assertEquals(y, by, 1e-9)
        }
    }

    @Test
    fun `the matrix form agrees with the transform it describes`() {
        // FR-9.2 stores these six numbers per frame, and a restack rebuilds the alignment from
        // them alone — so they have to mean the same thing as the object they came from.
        val t = RigidTransform(-11.0, 8.0, 19.0, centreX, centreY)
        val m = t.toMatrix()
        listOf(0.0 to 0.0, 130.0 to 70.0, 400.0 to 350.0).forEach { (x, y) ->
            val (px, py) = t.apply(x, y)
            assertEquals(px, m[0] * x + m[1] * y + m[4], 1e-9)
            assertEquals(py, m[2] * x + m[3] * y + m[5], 1e-9)
        }
    }

    @Test
    fun `the matrix round-trips back into a transform that maps points the same way`() {
        // The other half of the trip through session.json, and the one that had no reader until
        // T-5.3 needed to stack a folder the morning after.
        val t = RigidTransform(-11.0, 8.0, 19.0, centreX, centreY)
        val back = RigidTransform.fromMatrix(t.toMatrix())!!

        // Deliberately not field equality: `fromMatrix` re-expresses the map about the origin,
        // which is a different parameterisation of the same transform. Points are what matter.
        assertEquals(0.0, back.centreX, 1e-12)
        listOf(0.0 to 0.0, 130.0 to 70.0, 400.0 to 350.0, -50.0 to 900.0).forEach { (x, y) ->
            val (ex, ey) = t.apply(x, y)
            val (ax, ay) = back.apply(x, y)
            assertEquals(ex, ax, 1e-9)
            assertEquals(ey, ay, 1e-9)
        }
    }

    @Test
    fun `a matrix that is not a rotation is refused rather than flattened`() {
        // A folder that has been to a PC and back can carry anything (FR-10.6.4). Keeping the
        // rotation and dropping a scale would misregister every frame by a growing amount towards
        // the edges — soft corners, and no complaint anywhere.
        assertNull(RigidTransform.fromMatrix(listOf(1.05, 0.0, 0.0, 1.05, 2.0, 1.0)))
        // A shear: a = d and the columns are unit, but b != -c.
        assertNull(RigidTransform.fromMatrix(listOf(1.0, 0.3, 0.3, 1.0, 0.0, 0.0)))
        assertNull(RigidTransform.fromMatrix(listOf(1.0, 0.0, 0.0, 1.0)))
        assertNull(RigidTransform.fromMatrix(null))
        assertNull(RigidTransform.fromMatrix(listOf(1.0, 0.0, 0.0, 1.0, Double.NaN, 0.0)))
    }

    @Test
    fun `every angle survives the round trip, including the ones that wrap`() {
        // atan2 comes back in (-180, 180], so a transform stored at 190 degrees returns as -170.
        // That is the same rotation and has to behave like it.
        listOf(-179.9, -90.0, -0.001, 0.0, 0.001, 90.0, 179.9, 190.0, 359.0).forEach { angle ->
            val t = RigidTransform(angle, 3.0, -4.0, centreX, centreY)
            val back = RigidTransform.fromMatrix(t.toMatrix())!!
            val (ex, ey) = t.apply(123.0, 456.0)
            val (ax, ay) = back.apply(123.0, 456.0)
            assertEquals(ex, ax, 1e-8, "$angle degrees")
            assertEquals(ey, ay, 1e-8, "$angle degrees")
        }
    }

    @Test
    fun `it stops early when the correspondences are clean`() {
        // The adaptive bound: with no outliers, two or three samples prove the answer, and
        // grinding out four hundred would be work done once per frame for nothing.
        val ref = field(24, seed = 16)
        val moved = move(ref, rotationDeg = 2.0, dx = 10.0, dy = 5.0)
        val result = RigidFit.fit(ref, moved, identityPairs(24), centreX, centreY)
        assertTrue(result.attempted < 20) { "took ${result.attempted} attempts on clean input" }
    }

    // ------------------------------------------------------------------ end to end

    @Test
    fun `render, detect, match and fit recovers the truth`() {
        // The whole of Phase 2 so far, against ground truth: two rendered frames with a known
        // transform, through binning, detection, asterism matching and the fit. Every stage has
        // its own tests; this one exists because they also have to agree with each other.
        val sky = SyntheticSky()
        val stars = sky.field(count = 40, seed = 31)
        val truth = SyntheticSky.Transform(rotationDeg = 2.5, dx = 21.0, dy = -13.0)

        val a = detections(sky, sky.render(stars, 7.4, seed = 1))
        val b = detections(sky, sky.render(stars, 7.4, truth, seed = 2))
        val cx = (sky.width - 1) / 2.0
        val cy = (sky.height - 1) / 2.0

        val match = AsterismMatcher.match(
            a, b, frameWidth = sky.width.toDouble(), frameHeight = sky.height.toDouble(),
        )
        assertTrue(match.usable)

        val result = RigidFit.fit(a, b, match.pairs, cx, cy)

        assertTrue(result.succeeded) { "the fit failed on ${match.count} correspondences" }
        // Recovered from noisy centroids of rendered stars, so not to machine precision — but to
        // a fraction of a pixel, which is what stacking needs.
        assertEquals(2.5, result.transform!!.rotationDeg, 0.15)
        assertEquals(21.0, result.transform!!.dx, 0.6)
        assertEquals(-13.0, result.transform!!.dy, 0.6)
        assertTrue(result.residualRmsPx < 1.0) { "rms ${result.residualRmsPx}" }
        assertTrue(result.inlierCount >= 8) { "only ${result.inlierCount} inliers" }
    }

    @Test
    fun `a knocked tripod shows up as a residual spike, not a bad fit`() {
        // T-4.4 detects bumps this way. A bump moves the stars *during* the exposure, so they
        // trail and their centroids scatter — the transform still fits, and what gives it away is
        // that the inliers miss by more than they should.
        val rng = Random(17)
        val ref = field(24, seed = 18)
        val steady = move(ref, rotationDeg = 1.0, dx = 6.0, dy = 3.0).map {
            it.copy(x = it.x + rng.nextDouble(-0.2, 0.2), y = it.y + rng.nextDouble(-0.2, 0.2))
        }
        val bumped = move(ref, rotationDeg = 1.0, dx = 6.0, dy = 3.0).map {
            it.copy(x = it.x + rng.nextDouble(-1.6, 1.6), y = it.y + rng.nextDouble(-1.6, 1.6))
        }

        val calm = RigidFit.fit(ref, steady, identityPairs(24), centreX, centreY)
        val shaken = RigidFit.fit(ref, bumped, identityPairs(24), centreX, centreY)

        assertTrue(calm.succeeded && shaken.succeeded)
        assertTrue(shaken.residualRmsPx > calm.residualRmsPx * 3) {
            "calm ${calm.residualRmsPx}, shaken ${shaken.residualRmsPx}"
        }
        assertTrue(abs(shaken.transform!!.rotationDeg - 1.0) < 0.2) {
            "the transform should still be broadly right, was ${shaken.transform!!.rotationDeg}"
        }
    }

    private fun detections(
        sky: SyntheticSky,
        frame: SyntheticSky.Frame,
    ): List<AsterismMatcher.Detection> {
        val plane = com.starstacker.stars.CfaBinner.binGreen(
            frame.pixels, frame.width, frame.height, sky.cfaCodes, 2,
        )
        return com.starstacker.stars.StarDetector(saturationLevel = sky.whiteLevel.toDouble())
            .detect(plane.data, plane.width, plane.height)
            .stars
            .map {
                AsterismMatcher.Detection(
                    x = plane.toSensorCoordinate(it.x),
                    y = plane.toSensorCoordinate(it.y),
                    flux = it.flux,
                )
            }
    }
}
