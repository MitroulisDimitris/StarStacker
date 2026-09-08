package com.starstacker.moon

/**
 * T-11.3 and T-11.6 — how sharp a lunar frame is, written once and used twice.
 *
 * ### Why HFR cannot do this job
 *
 * The deep-sky pipeline measures focus and frame quality by **half-flux radius** on detected stars:
 * a star is a point source, so how wide it lands is exactly how badly the system is blurring. A
 * lunar frame has no point sources. The detector finds the limb and a few crater rims, and
 * whatever HFR it reports is a statement about crater sizes rather than about focus.
 *
 * What *is* meaningful on an extended object is contrast at high spatial frequencies: a sharp frame
 * has a crisp terminator and hard crater shadows, and a soft one does not. Both tasks want the same
 * number, so this is the same metric behind focus (T-11.3) and the keep-best cut (T-11.6).
 *
 * ### Two metrics, and when each is right
 *
 * [laplacianVariance] is the standard focus measure: the variance of a second-derivative response,
 * which peaks sharply at best focus. It is the more selective of the two and the right choice for
 * *ranking frames that differ only by seeing* — which is precisely lucky imaging.
 *
 * [gradientEnergy] sums squared first differences. It is broader, less sensitive to single-pixel
 * noise, and better behaved during a focus *sweep*, where frames far from focus give a Laplacian
 * response so flat that the curve has nothing to climb.
 *
 * ### Both are normalised, and that is not cosmetic
 *
 * A raw Laplacian variance scales with the square of image contrast, so a frame metered slightly
 * brighter scores higher without being sharper. During a focus sweep the exposure is fixed and it
 * does not matter; across a lucky-imaging run of hundreds of frames through varying transparency it
 * does. Both metrics therefore divide by the region's own variance, making them a measure of
 * *structure* rather than of brightness.
 *
 * ### Why the region matters more than the metric
 *
 * On a frame that is 99.97% black sky, a whole-frame sharpness measure is dominated by sensor noise
 * — and noise is high-frequency, so a *noisier* frame scores as sharper. Every function here takes
 * a region, and callers pass [Disc]'s bounding box. This is the single biggest correctness issue in
 * the metric and it is a caller's responsibility, so [overDisc] exists to make the right thing the
 * easy thing.
 */
object Sharpness {

    /** Pixels of sky kept around the limb, which is itself the sharpest edge in the frame. */
    const val LIMB_MARGIN = 8

    /**
     * Variance of the 4-neighbour Laplacian over a region, normalised by the region's variance.
     *
     * The kernel is `[[0,1,0],[1,-4,1],[0,1,0]]`. Border pixels of the region are skipped rather
     * than reflected: a reflected edge invents structure, and a region that is one pixel smaller
     * costs nothing.
     */
    fun laplacianVariance(
        pixels: DoubleArray,
        width: Int,
        height: Int,
        left: Int = 0,
        top: Int = 0,
        right: Int = width - 1,
        bottom: Int = height - 1,
    ): Double {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(0, width - 1)
        val b = bottom.coerceIn(0, height - 1)
        if (r - l < 2 || b - t < 2) return 0.0

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in t + 1 until b) {
            val row = y * width
            for (x in l + 1 until r) {
                val i = row + x
                val lap = pixels[i - 1] + pixels[i + 1] + pixels[i - width] + pixels[i + width] -
                    4.0 * pixels[i]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n < 2) return 0.0
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        return normalise(variance, pixels, width, l, t, r, b)
    }

    /**
     * Mean squared first difference over a region, normalised by the region's variance.
     *
     * Broader and steadier than the Laplacian, which is what a focus sweep needs: far from focus
     * the Laplacian response is nearly flat and gives the sweep nothing to climb, while the
     * gradient still slopes toward the peak.
     */
    fun gradientEnergy(
        pixels: DoubleArray,
        width: Int,
        height: Int,
        left: Int = 0,
        top: Int = 0,
        right: Int = width - 1,
        bottom: Int = height - 1,
    ): Double {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(0, width - 1)
        val b = bottom.coerceIn(0, height - 1)
        if (r - l < 1 || b - t < 1) return 0.0

        var sum = 0.0
        var n = 0
        for (y in t until b) {
            val row = y * width
            for (x in l until r) {
                val i = row + x
                val dx = pixels[i + 1] - pixels[i]
                val dy = pixels[i + width] - pixels[i]
                sum += dx * dx + dy * dy
                n++
            }
        }
        if (n == 0) return 0.0
        return normalise(sum / n, pixels, width, l, t, r, b)
    }

    /**
     * The metric T-11.6 feeds to the keep-best cut, measured where it means something.
     *
     * Returns 0 when no disc was found, which is the honest answer: a frame with no moon in it is
     * not a sharp frame, and scoring it on whole-frame noise would rank it first.
     */
    fun overDisc(
        pixels: DoubleArray,
        width: Int,
        height: Int,
        disc: Disc.Found?,
        margin: Int = LIMB_MARGIN,
    ): Double {
        if (disc == null) return 0.0
        val box = disc.boxWithMargin(margin, width, height)
        return laplacianVariance(pixels, width, height, box[0], box[1], box[2], box[3])
    }

    /**
     * Divides by the region's own variance, so the result measures structure and not brightness.
     *
     * A flat region has zero variance and no structure to measure; returning 0 rather than
     * dividing by it keeps a black frame from scoring infinitely sharp.
     */
    private fun normalise(
        raw: Double,
        pixels: DoubleArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Double {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in top..bottom) {
            val row = y * width
            for (x in left..right) {
                val v = pixels[row + x]
                sum += v
                sumSq += v * v
                n++
            }
        }
        if (n < 2) return 0.0
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        if (variance <= 0.0 || !variance.isFinite()) return 0.0
        val value = raw / variance
        return if (value.isFinite()) value else 0.0
    }
}
