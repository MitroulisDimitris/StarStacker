package com.starstacker.stacking

import com.starstacker.session.FrameKind
import com.starstacker.session.FrameRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-5.5 — the quality score, the keep-best cut, and the weighted combine.
 *
 * The property that matters most here is the boring one: **with no metrics, nothing changes.**
 * Every session shot so far has a partial log, and a weighting scheme that silently reorders a
 * stack by which fields happened to be populated would be worse than no weighting at all.
 */
class FrameQualityTest {

    private fun frame(
        index: Int,
        hfr: Double? = null,
        stars: Int? = null,
        background: Double? = null,
    ) = FrameRecord(
        index = index,
        fileName = "light_%04d.dng".format(index),
        kind = FrameKind.LIGHT,
        capturedAtEpochMs = 1000L * index,
        iso = 3200,
        exposureNs = 8_000_000_000L,
        temperatureC = 20.0,
        hfr = hfr,
        starCount = stars,
        eccentricity = null,
        backgroundAdu = background,
        accepted = true,
    )

    // ------------------------------------------------------------------------------- the score

    @Test
    fun `with no metrics every frame weighs the same`() {
        val scores = FrameQuality.score((1..4).map { frame(it) })
        assertTrue(scores.all { it.weight == 1.0 }, "got ${scores.map { it.weight }}")
    }

    @Test
    fun `a missing metric is neutral rather than a guess`() {
        // One frame has no HFR recorded. It must not be punished for the gap, only judged on what
        // is known — otherwise the score measures log completeness.
        val scores = FrameQuality.score(
            listOf(frame(1, hfr = 2.0, stars = 100), frame(2, stars = 100)),
        ).associateBy { it.index }

        assertEquals(1.0, scores.getValue(2).sharpness)
        assertEquals(1.0, scores.getValue(2).weight)
    }

    @Test
    fun `sharpness falls with the square of HFR, because that is the geometry`() {
        // A star's flux spreads over an area proportional to HFR squared, so a frame with twice
        // the HFR has a quarter of the peak signal.
        val scores = FrameQuality.score(
            listOf(frame(1, hfr = 2.0), frame(2, hfr = 4.0)),
        ).associateBy { it.index }

        assertEquals(1.0, scores.getValue(1).weight, 1e-9)
        assertEquals(0.25, scores.getValue(2).weight, 1e-9)
    }

    @Test
    fun `background weighs as its inverse, because the noise variance is the background`() {
        val scores = FrameQuality.score(
            listOf(frame(1, background = 300.0), frame(2, background = 900.0)),
        ).associateBy { it.index }

        assertEquals(1.0, scores.getValue(1).weight, 1e-9)
        assertEquals(1.0 / 3.0, scores.getValue(2).weight, 1e-9)
    }

    @Test
    fun `star count is linear, not squared`() {
        // Deliberately the softest term: haze both raises the background and hides stars, and a
        // frame should not be punished twice for one cause.
        val scores = FrameQuality.score(
            listOf(frame(1, stars = 100), frame(2, stars = 50)),
        ).associateBy { it.index }

        assertEquals(0.5, scores.getValue(2).weight, 1e-9)
    }

    @Test
    fun `the terms multiply`() {
        val scores = FrameQuality.score(
            listOf(
                frame(1, hfr = 2.0, stars = 100, background = 300.0),
                frame(2, hfr = 4.0, stars = 50, background = 600.0),
            ),
        ).associateBy { it.index }

        // 0.25 sharpness x 0.5 transparency x 0.5 darkness
        assertEquals(0.0625, scores.getValue(2).weight, 1e-9)
    }

    @Test
    fun `no weight is ever zero`() {
        // Zero is exclusion, and exclusion is the cut's job — done there it is counted and
        // reversible. Reached by arithmetic it is a frame that silently contributed nothing.
        val scores = FrameQuality.score(
            listOf(frame(1, hfr = 1.0, stars = 500, background = 10.0), frame(2, hfr = 900.0, stars = 1, background = 60000.0)),
        )
        assertTrue(scores.all { it.weight >= FrameQuality.MIN_WEIGHT })
        assertTrue(scores.all { it.weight > 0.0 })
    }

    @Test
    fun `nonsense metrics do not produce a frame worth more than the best one`() {
        val scores = FrameQuality.score(
            listOf(frame(1, hfr = 2.0), frame(2, hfr = 0.0), frame(3, hfr = Double.NaN)),
        )
        assertTrue(scores.all { it.weight <= 1.0 }, "got ${scores.map { it.weight }}")
    }

    // ---------------------------------------------------------------------------- the keep cut

    @Test
    fun `keeping the best 95 percent of twenty drops the worst one`() {
        val frames = (1..20).map { frame(it, hfr = 2.0 + it * 0.1) }
        val kept = FrameQuality.keepBest(FrameQuality.score(frames), 95)

        assertEquals(19, kept.size)
        // Frame 20 has the worst HFR, so it is the one that goes.
        assertTrue(kept.none { it.index == 20 })
    }

    @Test
    fun `a short session keeps everything, because the count rounds up`() {
        // ceil(0.95 x 3) = 3. The cut exists to drop the occasional ruined frame from a long run;
        // a session where one frame is a third of the data cannot afford to lose it.
        val frames = (1..3).map { frame(it, hfr = 2.0 + it) }
        assertEquals(3, FrameQuality.keepBest(FrameQuality.score(frames), 95).size)
    }

    @Test
    fun `a hundred percent keeps everything`() {
        val frames = (1..20).map { frame(it, hfr = 2.0 + it * 0.1) }
        assertEquals(20, FrameQuality.keepBest(FrameQuality.score(frames), 100).size)
    }

    @Test
    fun `the survivors come back in capture order, not in quality order`() {
        // The caller pairs these with files and transforms, and reordering by quality would
        // silently change which frame is the reference.
        // Four frames at 70%: ceil(2.8) = 3, so exactly the worst one goes. Three frames would
        // have kept everything — ceil(2.1) = 3 — which is the rounding rule working, not a bug.
        val frames = listOf(
            frame(1, hfr = 9.0),
            frame(2, hfr = 2.0),
            frame(3, hfr = 3.0),
            frame(4, hfr = 4.0),
        )
        val kept = FrameQuality.keepBest(FrameQuality.score(frames), 70)

        assertEquals(listOf(2, 3, 4), kept.map { it.index })
    }

    @Test
    fun `ties break reproducibly, since a restack must reproduce a master`() {
        val frames = (1..10).map { frame(it, hfr = 2.0) }
        val first = FrameQuality.keepBest(FrameQuality.score(frames), 50).map { it.index }
        val again = FrameQuality.keepBest(FrameQuality.score(frames), 50).map { it.index }

        assertEquals(first, again)
        assertEquals(listOf(1, 2, 3, 4, 5), first)
    }

    @Test
    fun `an empty session is not a crash`() {
        assertTrue(FrameQuality.keepBest(emptyList(), 95).isEmpty())
        assertTrue(FrameQuality.score(emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------------- the weighted combine

    @Test
    fun `a weighted mean leans towards the frame that is worth more`() {
        val samples = floatArrayOf(100f, 200f)
        val frames = intArrayOf(0, 1)
        val weights = floatArrayOf(3f, 1f)

        assertEquals(125.0, Combine.weightedMeanOf(samples, frames, 2, weights), 1e-9)
    }

    @Test
    fun `equal weights are the plain mean`() {
        val samples = floatArrayOf(100f, 200f, 300f)
        val frames = intArrayOf(0, 1, 2)
        assertEquals(
            Combine.meanOf(samples, 3),
            Combine.weightedMeanOf(samples, frames, 3, floatArrayOf(1f, 1f, 1f)),
            1e-9,
        )
    }

    @Test
    fun `a sample keeps its own frame's weight through the clip's reordering`() {
        // The whole risk in T-5.5. SigmaClip selects a median (which partitions the array) and
        // then compacts the survivors, so a weight applied by position would end up on whichever
        // frame happened to land in that slot.
        val values = floatArrayOf(500f, 100f, 300f, 200f, 400f)
        val frames = intArrayOf(0, 1, 2, 3, 4)
        // Weight the frame holding 100 far above the rest.
        val weights = floatArrayOf(0.01f, 100f, 0.01f, 0.01f, 0.01f)

        val clipped = Combine.SigmaClip().combineWeighted(values, frames, 5, weights)

        // Nothing is an outlier here, so all five survive and the answer is dominated by 100.
        assertTrue(clipped in 100f..110f, "got $clipped — the weight followed the wrong sample")
    }

    @Test
    fun `weighting does not change which samples are rejected`() {
        // Rejection is unweighted by design: a satellite is an outlier whatever the quality of the
        // frame it crossed. The clipped set must be identical however the frames are weighted.
        fun run(weights: FloatArray): Float {
            val values = floatArrayOf(100f, 101f, 99f, 100f, 102f, 98f, 9000f)
            val frames = IntArray(7) { it }
            return Combine.SigmaClip().combineWeighted(values, frames, 7, weights)
        }

        val even = run(FloatArray(7) { 1f })
        // The satellite's own frame weighted heavily: it must still be thrown out.
        val skewed = run(FloatArray(7) { if (it == 6) 50f else 1f })

        assertTrue(even < 200f, "the satellite survived the even run")
        assertTrue(skewed < 200f, "weighting a rejected frame must not resurrect it")
    }

    @Test
    fun `an unweighted clip and a clip with equal weights agree`() {
        val a = floatArrayOf(100f, 101f, 99f, 100f, 102f, 98f, 9000f)
        val b = a.copyOf()
        val plain = Combine.SigmaClip().combine(a, 7)
        val weighted = Combine.SigmaClip().combineWeighted(b, IntArray(7) { it }, 7, FloatArray(7) { 1f })

        assertEquals(plain, weighted, 1e-4f)
    }
}
