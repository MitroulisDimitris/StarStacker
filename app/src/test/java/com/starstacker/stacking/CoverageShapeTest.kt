package com.starstacker.stacking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * OI-25 — what *shape* of coverage loss can produce the crop that was actually observed.
 *
 * ### Why this can be settled without the phone
 *
 * The run is gone but its two numbers survive: **683 291 deficient pixels** and a largest covered
 * rectangle of **2804×2417 at (876, 476)**, from a 4096×3072 frame stacked from 114 frames. The
 * crop is a pure function of the coverage map, so any candidate shape can be fed through the real
 * [LinearMaster.regionFor] and checked against those numbers. That turns "we do not know the
 * shape" into "the shape cannot be X", which is what constrains the mechanism.
 *
 * ### What it establishes
 *
 * **The deficiency is not a band.** A uniform ring holding all 683 291 pixels is only ~48 px thick
 * and leaves a crop of nearly 3999×2975 — three quarters of the frame more than was seen. The
 * observed crop uses **57% of the covered area**, which no ring does.
 *
 * That matters because a ring is what *geometry* produces: every frame is displaced, so a band
 * around the edge is not covered by all of them. Ruling the ring out rules out the explanation
 * that needs no new mechanism, and says the loss has long, thin, deep structure — the kind that
 * costs a rectangle far more than its own area.
 */
class CoverageShapeTest {

    private val width = 4096
    private val height = 3072
    private val frames = 114

    /** The numbers the 2026-09-04 run actually produced. */
    private val observedDeficient = 683_291
    private val observedRegion = LinearMaster.Region(876, 476, 2804, 2417)

    private fun fullCoverage() = ShortArray(width * height) { frames.toShort() }

    private fun regionOf(coverage: ShortArray): LinearMaster.Region =
        LinearMaster.regionFor(
            master = FloatArray(0), // unread when a coverage map is supplied
            width = width,
            height = height,
            crop = LinearMaster.Crop.COMMON_AREA,
            coverage = coverage,
            frames = frames,
        )

    private fun deficientCount(coverage: ShortArray): Int =
        coverage.count { it.toInt() < frames }

    private fun ring(coverage: ShortArray, thickness: Int) {
        for (y in 0 until height) {
            val edgeRow = y < thickness || y >= height - thickness
            for (x in 0 until width) {
                if (edgeRow || x < thickness || x >= width - thickness) {
                    coverage[y * width + x] = (frames - 1).toShort()
                }
            }
        }
    }

    @Test
    fun `the observed crop is exactly what four lines at those positions would give`() {
        // Not a claim that the deficiency *is* four lines — a claim about arithmetic. The crop's
        // own edges name the first excluded column and row on each side, and a deficiency reaching
        // them reproduces the observed rectangle to the pixel.
        val coverage = fullCoverage()
        for (y in 0 until height) {
            coverage[y * width + 875] = 0
            coverage[y * width + 3680] = 0
        }
        for (x in 0 until width) {
            coverage[475 * width + x] = 0
            coverage[2893 * width + x] = 0
        }

        val region = regionOf(coverage)
        assertEquals(observedRegion, region)

        // And it costs almost nothing in pixels: two columns and two rows.
        val cost = deficientCount(coverage)
        assertTrue(
            cost < observedDeficient / 20,
            "$cost pixels — under 5% of the $observedDeficient actually deficient",
        )
    }

    /**
     * The result that rules out the explanation needing no new mechanism.
     */
    @Test
    fun `a uniform ring holding every deficient pixel leaves a far larger crop`() {
        // 2t(W+H) - 4t^2 = 683291 gives t = 48.
        val coverage = fullCoverage()
        ring(coverage, thickness = 48)

        val count = deficientCount(coverage)
        assertEquals(observedDeficient.toDouble(), count.toDouble(), observedDeficient * 0.02,
            "the ring should hold about the observed number of deficient pixels")

        val region = regionOf(coverage)
        assertEquals(width - 96, region.width)
        assertEquals(height - 96, region.height)

        // The observed crop is a little over half this. A band cannot produce it.
        assertTrue(
            region.pixels > observedRegion.pixels * 1.7,
            "a ring gives ${region.pixels}, against the ${observedRegion.pixels} observed",
        )
    }

    @Test
    fun `the observed crop packs the covered area far worse than any band can`() {
        val covered = width.toLong() * height - observedDeficient
        val efficiency = observedRegion.pixels.toDouble() / covered
        assertTrue(
            efficiency < 0.60,
            "the observed crop uses %.0f%% of the covered area".format(efficiency * 100),
        )

        // A ring's crop, by contrast, uses essentially all of it — that is what "a band is
        // geometry" means quantitatively.
        val coverage = fullCoverage()
        ring(coverage, thickness = 48)
        val ringEfficiency = regionOf(coverage).pixels.toDouble() /
            (width.toLong() * height - deficientCount(coverage))
        assertTrue(
            ringEfficiency > 0.95,
            "a ring should waste almost nothing, wasted %.0f%%".format((1 - ringEfficiency) * 100),
        )
    }

    /**
     * The other shape worth excluding: scattered single pixels, which is how the bounding box
     * `(0,0)-(4095,3071)` was once read.
     */
    @Test
    fun `scattered deficiency destroys the crop entirely rather than shrinking it`() {
        val coverage = fullCoverage()
        val step = (width.toLong() * height / observedDeficient).toInt()
        var scattered = 0
        var i = 0
        while (i < width * height) {
            coverage[i] = 0
            scattered++
            i += step
        }

        val region = regionOf(coverage)
        // Uniformly scattered holes leave only thin slivers — nothing like a 2804x2417 rectangle.
        assertTrue(
            region.pixels < observedRegion.pixels / 10,
            "scattered $scattered pixels left ${region.describe()}, which is far too large " +
                "if the deficiency were really scattered",
        )
    }

    /** The rule itself, which is brittle whatever the shape turns out to be. */
    @Test
    fun `one frame short of the full count is enough to exclude a pixel`() {
        val coverage = fullCoverage()
        // A single pixel that 113 of 114 frames reached.
        coverage[(height / 2) * width + width / 2] = (frames - 1).toShort()

        val region = regionOf(coverage)
        assertTrue(
            region.pixels < width.toLong() * height,
            "one deficient pixel in the middle should already cost the crop something",
        )
    }
}
