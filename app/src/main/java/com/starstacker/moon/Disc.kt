package com.starstacker.moon

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finding the lunar disc in a frame — the region every other part of moon mode works on.
 *
 * ### Why one detector serves four tasks
 *
 * T-11.1 meters the *disc*, not the frame, because the frame is mostly black sky and a whole-frame
 * histogram would say "far too dark" about a correctly exposed moon. T-11.3 focuses on the disc,
 * T-11.5 aligns on it, and T-11.6 measures sharpness over it. All four need the same answer to the
 * same question, so it is computed once.
 *
 * ### The detection, and why it is this simple
 *
 * A lunar frame is a bright convex blob on a black background — no star field, no gradient, and
 * nothing else above the noise. So a threshold partway between the background and the peak
 * separates it cleanly, and there is no need for the deblending a star detector does.
 *
 * The threshold is a *fraction of the way from background to peak* rather than an absolute level,
 * so it holds for a crescent as well as a full moon and does not need to know the exposure. The
 * background is taken as a low percentile of the whole frame, which on a lunar frame is sky.
 *
 * ### What it deliberately does not do
 *
 * It does not fit a circle or find the limb. A crescent is not a circle, a clipped disc blooms past
 * its true limb, and T-11.5 needs a *centre* rather than an edge — so the centroid of the lit area
 * is both easier and more robust than anything geometric. The cost is that the centroid of a
 * crescent is not the centre of the moon, which does not matter: alignment only needs the same
 * point measured the same way in every frame.
 */
object Disc {

    /** Where the threshold sits between background and peak. */
    const val THRESHOLD_FRACTION = 0.25

    /** The percentile taken as background. Low enough to be sky even on a frame full of moon. */
    const val BACKGROUND_PERCENTILE = 0.10

    /** Below this many pixels, a detection is a noise spike rather than a moon. */
    const val MIN_AREA_PX = 12

    /**
     * How far above the background the peak must stand, in noise sigmas, to be a real object.
     *
     * **This is the guard that matters, and area is not a substitute for it.** On a frame of pure
     * noise the background (10th percentile) sits ~1.3σ below the median and the peak ~4σ above
     * it, so a threshold a quarter of the way between them lands *at* the median and roughly half
     * the frame passes — a detection covering 48% of the image, with a centroid at the middle of
     * nothing. An area floor cannot catch that, because a moon filling the frame is exactly what
     * *disc* mode is aiming for and would be rejected alongside it.
     *
     * Contrast separates them cleanly: pure noise gives a peak about 5σ above background, while a
     * disc metered anywhere near correctly gives hundreds. Eight is well clear of the first and
     * still admits a badly underexposed probe, which the metering loop has to be able to see in
     * order to correct it.
     */
    const val MIN_PEAK_SIGMA = 8.0

    /**
     * A found disc.
     *
     * @param centroidX intensity-weighted, in pixels, so it moves smoothly rather than in steps
     * @param pixelCount how many pixels were above the threshold
     * @param peakAdu the brightest pixel inside it
     * @param clippedPixels how many sat at the white level — T-11.1's clipping test
     */
    data class Found(
        val centroidX: Double,
        val centroidY: Double,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val pixelCount: Int,
        val peakAdu: Double,
        val backgroundAdu: Double,
        val clippedPixels: Int,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1

        /** The diameter a circle of this area would have — a size that a crescent cannot inflate. */
        val equivalentDiameterPx: Double get() = 2.0 * sqrt(pixelCount / Math.PI)

        fun describe(): String =
            "disc %d px at (%.1f, %.1f), box %dx%d, peak %.0f over %.0f, %d clipped".format(
                pixelCount, centroidX, centroidY, width, height, peakAdu, backgroundAdu,
                clippedPixels,
            )

        /**
         * The bounding box grown by [margin] and clipped to the frame.
         *
         * Sharpness and focus want a little sky around the limb, because the limb *is* the
         * high-frequency detail they are measuring and a box that cuts it off measures less of it.
         */
        fun boxWithMargin(margin: Int, width: Int, height: Int): IntArray = intArrayOf(
            (left - margin).coerceAtLeast(0),
            (top - margin).coerceAtLeast(0),
            (right + margin).coerceAtMost(width - 1),
            (bottom + margin).coerceAtMost(height - 1),
        )
    }

    /**
     * Finds the disc in a single-channel image.
     *
     * @param pixels row-major, [width] x [height]
     * @param whiteLevel used only to count clipped pixels; pass the sensor's white level
     * @return null when nothing bright enough and big enough was found
     */
    fun find(
        pixels: DoubleArray,
        width: Int,
        height: Int,
        whiteLevel: Double,
    ): Found? {
        require(pixels.size >= width * height) { "pixel array too small for ${width}x$height" }
        if (width <= 0 || height <= 0) return null

        val stats = statistics(pixels, width * height)
        val background = stats.percentile(BACKGROUND_PERCENTILE)
        var peak = Double.NEGATIVE_INFINITY
        for (i in 0 until width * height) {
            val v = pixels[i]
            if (v > peak) peak = v
        }
        if (!peak.isFinite() || peak <= background) return null

        // Nothing stands out far enough from the noise to be an object. See [MIN_PEAK_SIGMA].
        if (stats.sigma > 0.0 && (peak - background) < MIN_PEAK_SIGMA * stats.sigma) return null

        val threshold = background + THRESHOLD_FRACTION * (peak - background)

        var sumX = 0.0
        var sumY = 0.0
        var sumWeight = 0.0
        var count = 0
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        var clipped = 0

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val v = pixels[row + x]
                if (v < threshold) continue
                // Weighted by how far above the threshold the pixel is, not by its raw value: on a
                // clipped disc every lit pixel would otherwise weigh the same and the centroid
                // would drift toward whichever side happened to be larger.
                val w = v - threshold
                sumX += x * w
                sumY += y * w
                sumWeight += w
                count++
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
                if (v >= whiteLevel) clipped++
            }
        }

        if (count < MIN_AREA_PX) return null
        // A fully clipped disc has every lit pixel at exactly the white level, so `v - threshold`
        // is constant and the weighting degenerates to a plain area centroid. That is correct
        // rather than a fallback: with no intensity information left, area is all there is.
        if (sumWeight <= 0.0) return null

        return Found(
            centroidX = sumX / sumWeight,
            centroidY = sumY / sumWeight,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            pixelCount = count,
            peakAdu = peak,
            backgroundAdu = background,
            clippedPixels = clipped,
        )
    }

    /**
     * A sorted subsample of the frame, serving the background percentile and the noise estimate
     * from one sort.
     *
     * Subsampled because a percentile of the sky does not need every pixel, and a 12 MP frame
     * sorted in full would cost more than the detection it feeds.
     */
    internal class Statistics(private val sorted: DoubleArray) {
        fun percentile(fraction: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val index = (fraction * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }

        /**
         * Noise sigma, from the median absolute deviation.
         *
         * MAD rather than the standard deviation because the frame contains a large bright object,
         * and a standard deviation would be measuring the moon rather than the sky. The median of
         * a lunar frame is sky by a wide margin, so the MAD about it is the sky's own scatter.
         */
        val sigma: Double by lazy {
            if (sorted.size < 4) return@lazy 0.0
            val median = percentile(0.5)
            val deviations = DoubleArray(sorted.size) { kotlin.math.abs(sorted[it] - median) }
            deviations.sort()
            MAD_TO_SIGMA * deviations[deviations.size / 2]
        }
    }

    internal fun statistics(pixels: DoubleArray, count: Int): Statistics {
        if (count <= 0) return Statistics(DoubleArray(0))
        val stride = maxOf(1, count / SAMPLE_TARGET)
        val sampled = DoubleArray((count + stride - 1) / stride)
        var n = 0
        var i = 0
        while (i < count && n < sampled.size) {
            sampled[n++] = pixels[i]
            i += stride
        }
        val slice = sampled.copyOf(n)
        slice.sort()
        return Statistics(slice)
    }

    /** Converts a median absolute deviation to a Gaussian sigma. */
    private const val MAD_TO_SIGMA = 1.4826

    private const val SAMPLE_TARGET = 20_000
}
