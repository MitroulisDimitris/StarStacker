package com.starstacker.stacking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * T-5.4 — sigma clipping, and the arithmetic that decides whether it does anything.
 *
 * Every figure asserted below is worked out by hand rather than recorded from a run, because a
 * combiner is the one component whose output cannot be checked by looking at it: a stack with the
 * rejection silently disabled looks exactly like a stack with it working, right up until a
 * satellite goes over. So the tests are arranged around the *decisions* — which sample is thrown
 * away, and on the strength of which estimate — rather than around a finished number.
 *
 * The data is deterministic and small on purpose. A random-noise test would pass on an
 * implementation that rejected nothing, since κ = 3 rejects almost nothing from clean Gaussian data
 * by design.
 */
class CombineTest {

    /** Nine tightly clustered samples, mean exactly 100, plus one satellite. */
    private fun clusterOfNinePlusSatellite() =
        floatArrayOf(99f, 99f, 100f, 100f, 100f, 100f, 100f, 101f, 101f, 120f)

    // ------------------------------------------------------------------ why the seed is robust

    @Test
    fun `a mean-seeded clip keeps the satellite that a median-seeded clip removes`() {
        // The whole reason [Combine.SigmaClip] defaults to median/MAD, on one set of numbers.
        //
        // Mean/SD: mean 102.0, SD 6.36, so the bounds are [82.9, 121.1] and 120 sits inside them.
        // The outlier is part of the spread being used to judge it, and it wins.
        //
        // Median/MAD: median 100, MAD 0.5, sigma 0.74, bounds [97.8, 102.2]. The satellite is
        // nowhere near, because neither statistic moved when it arrived.
        val robust = Combine.SigmaClip()
        val classic = Combine.SigmaClip(robustSeed = false)

        val fromRobust = robust.combine(clusterOfNinePlusSatellite(), 10)
        val fromClassic = classic.combine(clusterOfNinePlusSatellite(), 10)

        assertEquals(100.0f, fromRobust, 1e-4f) { "the satellite survived the robust clip" }
        assertEquals(1L, robust.stats.rejected)

        assertEquals(102.0f, fromClassic, 1e-4f)
        assertEquals(0L, classic.stats.rejected) { "mean/SD should have kept it — that is the point" }
    }

    @Test
    fun `a mean-seeded clip cannot reject a lone outlier below eleven samples, whatever it is`() {
        // Not a tuning accident but arithmetic, and worth pinning because it decides the default.
        // For n-1 samples at one value and a single outlier d away, the mean/SD clip rejects it iff
        // (n-1) > 3*sqrt(n) — independent of d. That crosses at n = 11, so a ten-frame stack cannot
        // remove a satellite of *any* brightness this way, and no choice of exposure would help.
        val ten = floatArrayOf(99f, 99f, 100f, 100f, 100f, 100f, 100f, 101f, 101f, 120f)
        val thirteen = floatArrayOf(
            99f, 99f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 101f, 101f, 120f,
        )

        val atTen = Combine.SigmaClip(robustSeed = false)
        val atThirteen = Combine.SigmaClip(robustSeed = false)

        assertEquals(102.0f, atTen.combine(ten, 10), 1e-4f)
        assertEquals(0L, atTen.stats.rejected) { "below the crossing it is kept" }

        assertEquals(100.0f, atThirteen.combine(thirteen, 13), 1e-4f)
        assertEquals(1L, atThirteen.stats.rejected) { "above the crossing the same outlier goes" }
    }

    @Test
    fun `a brighter satellite does not make the mean-seeded clip work`() {
        // The corollary, stated separately because it is the counter-intuitive half: making the
        // intruder ten times brighter inflates the SD by the same factor and changes nothing.
        for (satellite in floatArrayOf(120f, 1_000f, 60_000f)) {
            val data = floatArrayOf(99f, 99f, 100f, 100f, 100f, 100f, 100f, 101f, 101f, satellite)
            val clip = Combine.SigmaClip(robustSeed = false)
            clip.combine(data, 10)
            assertEquals(0L, clip.stats.rejected) { "a satellite at $satellite was rejected at n=10" }
        }
    }

    // ------------------------------------------------------------------ the two ordinary edges

    @Test
    fun `a zero MAD is a quantised background, not a reason to clip everything`() {
        // Nineteen identical samples and one satellite. More than half the samples share a value,
        // so the MAD is exactly zero — and a naive implementation would then have bounds of
        // [100, 100] and reject everything that is not precisely the median, which is a mode filter
        // wearing a clip's clothes. Or, with a `sigma > 0` guard and no fallback, would reject
        // nothing at all and average the satellite in at 101.0.
        val data = FloatArray(20) { if (it == 19) 120f else 100f }
        val clip = Combine.SigmaClip()

        val result = clip.combine(data, 20)

        assertEquals(100.0f, result, 1e-4f) { "expected the satellite gone; 101.0 means it stayed" }
        assertEquals(1L, clip.stats.degenerateSpread) { "the zero MAD was not noticed" }
        assertEquals(1L, clip.stats.rejected)
    }

    @Test
    fun `the zero-MAD fallback is the mean deviation, because the standard deviation would fail`() {
        // Five samples, four identical, one wild. The MAD is zero, so the fallback decides.
        // Standard deviation about the median would be 150, giving bounds of +/- 450 and keeping
        // the outlier — the same n <= 10 weakness the robust seed exists to avoid, reintroduced by
        // the back door. The mean absolute deviation is 60, sigma 75.2, bounds +/- 225.6, and 400
        // goes.
        val data = floatArrayOf(100f, 100f, 100f, 100f, 400f)
        val clip = Combine.SigmaClip()

        assertEquals(100.0f, clip.combine(data, 5), 1e-4f) { "160.0 means the SD fallback was used" }
        assertEquals(1L, clip.stats.degenerateSpread)
        assertEquals(1L, clip.stats.rejected)
    }

    @Test
    fun `too few samples to judge means the median, not a guess`() {
        // Four samples is below the floor: a single satellite is a quarter of the data, and any
        // spread estimated from it is describing the intruder. The median still does the right
        // thing here, and says so in the counters rather than pretending it clipped.
        val clip = Combine.SigmaClip()

        assertEquals(100.0f, clip.combine(floatArrayOf(100f, 100f, 100f, 400f), 4), 1e-4f)
        assertEquals(1L, clip.stats.belowFloor)
        assertEquals(0L, clip.stats.rejected) { "nothing should have been clipped below the floor" }
    }

    @Test
    fun `the fallback below the floor is continuous, not a change of character`() {
        // One and two samples: the median *is* the mean, so nothing lurches at the threshold.
        val clip = Combine.SigmaClip()
        assertEquals(37.5f, clip.combine(floatArrayOf(37.5f), 1), 1e-4f)
        assertEquals(150.0f, clip.combine(floatArrayOf(100f, 200f), 2), 1e-4f)
    }

    @Test
    fun `no samples is not-a-number, because no frame measured that pixel`() {
        // Writing zero would be a claim that it was dark, which the stretch would believe.
        val clip = Combine.SigmaClip()
        assertTrue(clip.combine(FloatArray(8), 0).isNaN())
        assertEquals(1L, clip.stats.uncovered)
        assertTrue(Combine.Median.combine(FloatArray(8), 0).isNaN())
        assertTrue(TiledStacker.Combiner.Mean.combine(FloatArray(8), 0).isNaN())
    }

    // ------------------------------------------------------------------ the two floors are different

    @Test
    fun `six frames with two satellites lose both`() {
        // The case that forced [minSurvivors] to be a separate and much smaller number than
        // [minSamples]. There is plenty of data to estimate a spread from — six samples, median
        // 102.5, MAD 2.0, bounds [93.6, 111.4] — and the pass correctly identifies both intruders.
        // A survivor floor set to minSamples would decline it for leaving only four, and the master
        // would keep two satellites out of misplaced caution.
        val clip = Combine.SigmaClip()
        val result = clip.combine(floatArrayOf(100f, 101f, 102f, 103f, 500f, 501f), 6)

        assertEquals(101.5f, result, 1e-4f) { "234.5 means the pass was declined" }
        assertEquals(2L, clip.stats.rejected)
        assertEquals(0L, clip.stats.floorHit)
    }

    @Test
    fun `a pass that would leave two samples is declined`() {
        // What the survivor floor is actually for: a runaway, where each pass tightens sigma around
        // the last set until whichever pair happened to agree becomes the "measurement". Provoked
        // here with a deliberately absurd kappa, since the robust seed does not produce one on its
        // own.
        val clip = Combine.SigmaClip(kappaLow = 0.1f, kappaHigh = 0.1f)
        val result = clip.combine(floatArrayOf(1f, 2f, 3f, 100f, 101f, 102f), 6)

        assertEquals(51.5f, result, 1e-4f) { "the set should be untouched when the pass is refused" }
        assertEquals(1L, clip.stats.floorHit)
        assertEquals(0L, clip.stats.rejected)
    }

    // ------------------------------------------------------------------ asymmetry

    @Test
    fun `kappa is separable because the intruders are all positive`() {
        // Satellites, aircraft, meteors and cosmic rays all *added* light to one frame. Nothing
        // physical is symmetrical about the background, so a session under a flight path can clip
        // hard above and loosely below. Same data, one dim outlier and one bright.
        val data = floatArrayOf(60f, 99f, 99f, 100f, 100f, 100f, 100f, 100f, 101f, 101f, 140f)

        val looseBelow = Combine.SigmaClip(kappaLow = 100f, kappaHigh = 3f)
        val looseAbove = Combine.SigmaClip(kappaLow = 3f, kappaHigh = 100f)

        // Bright one rejected, dim one kept: the mean falls below the cluster.
        assertEquals(96.0f, looseBelow.combine(data.copyOf(), 11), 1e-4f)
        assertEquals(1L, looseBelow.stats.rejected)

        // Dim one rejected, bright one kept: the mean rises above it.
        assertEquals(104.0f, looseAbove.combine(data.copyOf(), 11), 1e-4f)
        assertEquals(1L, looseAbove.stats.rejected)
    }

    // ------------------------------------------------------------------ the contract with the caller

    @Test
    fun `whatever lies past count is not part of the pixel`() {
        // The buffer is sized to the frame count and only its prefix is refilled per pixel, so the
        // tail is the previous pixel's data. Reading it would produce a master that is wrong
        // *only* where coverage was partial — which is the frame edges, where nobody looks first.
        val padded = floatArrayOf(
            99f, 99f, 100f, 100f, 100f, 100f, 100f, 101f, 101f, 120f,
            1e9f, 1e9f, 1e9f,
        )
        val clip = Combine.SigmaClip()

        assertEquals(100.0f, clip.combine(padded, 10), 1e-4f)
        assertEquals(10L, clip.stats.samples) { "the tail was counted" }
    }

    @Test
    fun `the answer does not depend on the order the samples arrived in`() {
        // Frames reach a tile in whatever order the loop walks them, and a pixel near the edge of
        // the common area sees a different subset again. A selection that let order through would
        // make the master depend on the session's frame numbering.
        val base = listOf(99f, 99f, 100f, 100f, 100f, 100f, 100f, 101f, 101f, 120f)
        val reference = Combine.SigmaClip().combine(base.toFloatArray(), 10)
        val random = Random(20260821)

        repeat(50) {
            val shuffled = base.shuffled(random).toFloatArray()
            assertEquals(reference, Combine.SigmaClip().combine(shuffled, 10), 1e-4f)
        }
    }

    @Test
    fun `a smaller pixel after a larger one does not see the leftovers`() {
        // The MAD scratch buffer is grown once and reused. Sizing it to the frame count and then
        // reading `count` entries is right; reading `buffer.size` would mix a previous pixel's
        // deviations into this one's spread.
        val shared = Combine.SigmaClip()
        shared.combine(FloatArray(40) { if (it < 39) 100f else 5000f }, 40)
        val afterLarge = shared.combine(floatArrayOf(10f, 10f, 10f, 10f, 10f, 900f), 6)

        val fresh = Combine.SigmaClip().combine(floatArrayOf(10f, 10f, 10f, 10f, 10f, 900f), 6)
        assertEquals(fresh, afterLarge, 1e-4f)
        assertEquals(10.0f, afterLarge, 1e-4f)
    }

    // ------------------------------------------------------------------ the other methods

    @Test
    fun `each method does what its name says on the same satellite`() {
        val data = { clusterOfNinePlusSatellite() }

        // The satellite is 1/10th of the mean's answer and none of the others'.
        assertEquals(102.0f, Combine.of(Combine.Method.MEAN).combine(data(), 10), 1e-4f)
        assertEquals(100.0f, Combine.of(Combine.Method.MEDIAN).combine(data(), 10), 1e-4f)
        assertEquals(100.0f, Combine.of(Combine.Method.SIGMA_CLIP).combine(data(), 10), 1e-4f)
        assertEquals(102.0f, Combine.of(Combine.Method.KAPPA_SIGMA).combine(data(), 10), 1e-4f)
    }

    @Test
    fun `every method has a fresh instance, since the default one carries counters`() {
        // Two stacks sharing a combiner would share a scratch buffer and a rejection tally.
        val first = Combine.of(Combine.Method.SIGMA_CLIP)
        val second = Combine.of(Combine.Method.SIGMA_CLIP)
        assertNotEquals(first, second)
    }

    // ------------------------------------------------------------------ selection

    @Test
    fun `the median is the middle, and the mean of the middle two`() {
        assertEquals(3.0, Combine.medianOf(floatArrayOf(5f, 1f, 3f), 3), 1e-9)
        assertEquals(2.5, Combine.medianOf(floatArrayOf(4f, 1f, 3f, 2f), 4), 1e-9)
        assertEquals(7.0, Combine.medianOf(floatArrayOf(7f), 1), 1e-9)
        assertTrue(Combine.medianOf(FloatArray(4), 0).isNaN())
    }

    @Test
    fun `an all-equal range is the common case, not the pathological one`() {
        // A quantised patch of background sky is exactly this, and it is why the selection uses a
        // Hoare partition: Lomuto's degenerates to O(n^2) here, on the data the app sees most.
        val flat = FloatArray(199) { 42f }
        assertEquals(42.0, Combine.medianOf(flat, 199), 1e-9)

        val mostlyFlat = FloatArray(200) { if (it == 0) 9000f else 42f }
        assertEquals(42.0, Combine.medianOf(mostlyFlat, 200), 1e-9)
    }

    @Test
    fun `the median ignores the tail past count, like everything else here`() {
        assertEquals(2.0, Combine.medianOf(floatArrayOf(1f, 2f, 3f, 999f, 999f), 3), 1e-9)
    }

    @Test
    fun `selection agrees with a sort, over a hundred random arrays`() {
        // The cheap check that the partition is right: a sort is the obvious implementation this
        // one exists to be faster than, so it makes a good oracle.
        val random = Random(1832)
        repeat(100) {
            val n = 1 + random.nextInt(64)
            val values = FloatArray(n) { random.nextInt(-50, 50).toFloat() }
            val sorted = values.copyOf().also { it.sort() }
            val expected =
                if (n % 2 == 1) sorted[n / 2].toDouble()
                else (sorted[n / 2 - 1].toDouble() + sorted[n / 2]) / 2.0
            assertEquals(expected, Combine.medianOf(values, n), 1e-9) { "n=$n" }
        }
    }

    // ------------------------------------------------------------------ what it reports

    @Test
    fun `the counters describe what the rejection did`() {
        val clip = Combine.SigmaClip()
        repeat(4) { clip.combine(clusterOfNinePlusSatellite(), 10) }
        clip.combine(FloatArray(4) { 100f }, 4)
        clip.combine(FloatArray(4), 0)

        assertEquals(6L, clip.stats.pixels)
        assertEquals(44L, clip.stats.samples)
        assertEquals(4L, clip.stats.rejected)
        assertEquals(1L, clip.stats.belowFloor)
        assertEquals(1L, clip.stats.uncovered)
        assertEquals(4.0 / 44.0, clip.stats.rejectionRate, 1e-9)
        assertTrue(clip.stats.describe().contains("9.091% rejected")) { clip.stats.describe() }
    }

    @Test
    fun `a clean stack rejects almost nothing, which is what kappa equals three means`() {
        // The other half of the claim: rejection has to be quiet on data with nothing wrong with
        // it, or it is throwing away the depth the session was shot for. 200 pixels of Gaussian
        // noise, 30 frames each, no intruders.
        //
        // **Measured: 0.88%**, against the 0.27% a single 3-sigma pass would take from a Gaussian.
        // The excess is the iteration nibbling — each pass recomputes sigma over an already
        // truncated set, so the bounds tighten slightly even when nothing was wrong. The cost of
        // losing 0.88% of the samples is 0.44% more noise in the master, which is not a number
        // worth trading rejection for. The bound below is set well clear of it so this test fails
        // on a change of behaviour rather than on the seed.
        val random = Random(4242)
        val clip = Combine.SigmaClip()
        repeat(200) {
            val frame = FloatArray(30) { (100.0 + gaussian(random) * 5.0).toFloat() }
            clip.combine(frame, 30)
        }

        assertTrue(clip.stats.rejectionRate < 0.02) {
            "clean data lost ${"%.2f".format(clip.stats.rejectionRate * 100)}% of its samples"
        }
    }

    private fun gaussian(random: Random): Double {
        // Box-Muller; kotlin.random has no nextGaussian and the test should not depend on the JDK's.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
