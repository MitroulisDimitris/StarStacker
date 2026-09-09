package com.starstacker.moon

import com.starstacker.synth.SyntheticMoon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-11.10 — putting the two masters in one frame, with no ephemeris.
 *
 * The scenario throughout is the real one: a **properly metered** moon master, and a ground set
 * whose long exposures have the moon as a clipped, bloomed white blob. The blob is bigger than the
 * moon, and the test data says so — a matcher that assumed the two detections were the same size
 * would refuse every real session.
 */
class MasterAlignTest {

    private val white = SyntheticMoon.WHITE_LEVEL.toDouble()

    private fun discAt(x: Double, y: Double, peak: Double, radius: Double = 40.0): Disc.Found {
        val frame = SyntheticMoon.render(
            centreX = x, centreY = y, radiusPx = radius, peakAdu = peak, seed = 5,
        )
        return checkNotNull(Disc.find(frame.pixels, frame.width, frame.height, white))
    }

    /** A properly exposed disc — 700 ADU against white 1023, nothing clipped. */
    private fun moonMaster(x: Double = 128.0, y: Double = 96.0) = discAt(x, y, peak = 700.0)

    /** The same moon in a long exposure: hard clipped, and bloomed larger than it really is. */
    private fun bloomedBlob(x: Double, y: Double) = discAt(x, y, peak = 60_000.0, radius = 52.0)

    @Test
    fun `the moon is placed where a fitted drift says it was at the reference epoch`() {
        // 0.2 px/s in x, sampled every 20 s over ten minutes: 70 px to 190 px. Kept inside the
        // frame deliberately — a disc that runs off the edge has its centroid clamped there, and
        // the fit would then be measuring the frame boundary rather than the moon.
        val samples = (0..30).map { i ->
            val t = i * 20_000L
            MasterAlign.Sample(t, bloomedBlob(70.0 + 0.0002 * t, 96.0))
        }
        val referenceEpoch = 300_000L // the middle of the run

        val result = MasterAlign.align(samples, referenceEpoch, moonMaster(), 256, 192)
        assertTrue(result is MasterAlign.Result.Matched, "rejected: $result")
        val matched = result as MasterAlign.Result.Matched

        assertTrue(matched.fromDriftFit, "thirty samples should support a fit")
        // At t = 300 s the disc is at 70 + 60 = 130.
        assertEquals(130.0, matched.moonAtEpochX, 2.0)
        assertEquals(96.0, matched.moonAtEpochY, 2.0)
        assertEquals(0.2, checkNotNull(matched.driftPxPerSec), 0.03)
    }

    /**
     * The by-product §1.45 insists on: the night's true drift rate, measured rather than assumed.
     */
    @Test
    fun `the drift rate falls out of the fit`() {
        val rate = 0.0006 // px per ms = 0.6 px/s
        val samples = (0..20).map { i ->
            val t = i * 10_000L
            MasterAlign.Sample(t, bloomedBlob(70.0 + rate * t, 96.0))
        }
        val result = MasterAlign.align(samples, 100_000L, moonMaster(), 256, 192)
        val matched = result as MasterAlign.Result.Matched
        assertEquals(0.6, checkNotNull(matched.driftPxPerSec), 0.05)
    }

    /**
     * The guard the class note is built around: a clipped blob is *larger* than the moon, and a
     * strict size match would refuse every real bracketed session.
     */
    @Test
    fun `a bloomed blob much larger than the metered disc is still matched`() {
        val samples = (0..4).map { MasterAlign.Sample(it * 1000L, bloomedBlob(128.0, 96.0)) }
        val result = MasterAlign.align(samples, 2000L, moonMaster(), 256, 192)
        assertTrue(result is MasterAlign.Result.Matched, "rejected a real bracket: $result")
    }

    @Test
    fun `a detection nothing like the moon is refused`() {
        // A tiny speck where the moon should be — a star, or a hot pixel cluster.
        val speck = discAt(128.0, 96.0, peak = 900.0, radius = 4.0)
        val samples = (0..4).map { MasterAlign.Sample(it * 1000L, speck) }
        val result = MasterAlign.align(samples, 2000L, moonMaster(x = 128.0), 256, 192)

        val rejected = result as MasterAlign.Result.Rejected
        assertTrue(rejected.reason.contains("same object"), rejected.reason)
    }

    @Test
    fun `too few samples falls back to the mean rather than fitting noise`() {
        val samples = listOf(
            MasterAlign.Sample(0L, bloomedBlob(120.0, 96.0)),
            MasterAlign.Sample(1000L, bloomedBlob(130.0, 96.0)),
        )
        val result = MasterAlign.align(samples, 500L, moonMaster(), 256, 192)
        val matched = result as MasterAlign.Result.Matched

        assertTrue(!matched.fromDriftFit, "two samples should not be fitted")
        assertEquals(125.0, matched.moonAtEpochX, 2.0, "the mean of the two")
        assertTrue(matched.note.contains("mean position"), matched.note)
    }

    @Test
    fun `no disc in the moon master is refused with a reason`() {
        val samples = listOf(MasterAlign.Sample(0L, bloomedBlob(128.0, 96.0)))
        val result = MasterAlign.align(samples, 0L, null, 256, 192)
        val rejected = result as MasterAlign.Result.Rejected
        assertTrue(rejected.reason.contains("moon master"), rejected.reason)
    }

    @Test
    fun `a moon never found in the ground frames is refused rather than guessed`() {
        val result = MasterAlign.align(emptyList(), 0L, moonMaster(), 256, 192)
        val rejected = result as MasterAlign.Result.Rejected
        assertTrue(rejected.reason.contains("not found"), rejected.reason)
    }

    @Test
    fun `the shift is the difference between where it goes and where it is`() {
        // Moon master has the disc at 128; the ground frames put it at 150.
        val samples = (0..4).map { MasterAlign.Sample(it * 1000L, bloomedBlob(150.0, 96.0)) }
        val result = MasterAlign.align(samples, 2000L, moonMaster(x = 128.0), 256, 192)
        val matched = result as MasterAlign.Result.Matched

        assertEquals(22.0, matched.dx, 2.0)
        assertEquals(0.0, matched.dy, 2.0)
    }
}
