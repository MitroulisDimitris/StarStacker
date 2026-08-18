package com.starstacker.stars

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * T-3.14 / FR-7.4 / **D-18** — the live preview stack: a capped running mean of aligned, binned
 * frames, autostretched for display, with no rejection logic of its own.
 *
 * ### What it is for, and what that rules out
 *
 * §14.4's reasoning: **framing confidence is the job.** The user needs to see that something is
 * accumulating and that it looks like the sky they aimed at. It is not the stack — that is Phase
 * 3, at full resolution, after the session, while the phone cools (FR-7.4).
 *
 * Everything here follows from the thermal argument. Warping and accumulating 12 MP frames during
 * capture heats the sensor, which raises dark current, which degrades the frames still being
 * taken — a preview that damages its own subject. So this runs on the ~1 MP plane the analysis
 * already produced, downsamples it again, and does one pass of arithmetic per frame.
 *
 * ### Why the mean is capped
 *
 * A true running mean of N frames converges: by frame 100 a new frame moves the image by 1%, and
 * the preview stops visibly responding just as a session gets long enough for the user to want
 * reassurance. Capping the divisor at [cap] turns it into an exponential mean once the cap is
 * reached, so the newest frames always carry weight and the display keeps breathing. It also makes
 * memory O(1) in session length rather than O(N).
 *
 * **No rejection logic of its own** (D-18): the gate has already decided, and a second opinion
 * living here would be a second thing to explain when the two disagree.
 */
class PreviewStack(
    val width: Int,
    val height: Int,
    /** Effective depth. Past this the mean becomes exponential — see the class note. */
    private val cap: Int = DEFAULT_CAP,
) {

    private val mean = FloatArray(width * height)
    private val scratch = FloatArray(width * height)
    private val argb = IntArray(width * height)
    private val stats = FloatArray((width * height + STATS_STRIDE - 1) / STATS_STRIDE)

    var depth: Int = 0
        private set

    /** True once there is anything worth showing. */
    val hasImage: Boolean get() = depth > 0

    /**
     * The accumulated mean at one preview pixel, in the plane's own units.
     *
     * Exposed for tests and diagnostics: the mean and the stretch fail in different ways, and a
     * test that can only see [toArgb] cannot tell which of the two is wrong.
     */
    fun meanAt(x: Int, y: Int): Float = mean[y * width + x]

    /**
     * Folds one frame in, shifted by ([dx], [dy]) **analysis-plane pixels** so it lands on top of
     * the frames already there.
     *
     * Pixels the shift moves in from outside the frame have no data behind them. They keep the
     * mean they already had rather than being filled with zero, because a black wedge crawling in
     * from one edge is a preview artefact the user would reasonably read as a real gradient.
     */
    fun add(plane: FloatArray, planeWidth: Int, planeHeight: Int, dx: Double, dy: Double) {
        require(planeWidth > 0 && planeHeight > 0) { "empty plane" }
        downsampleInto(plane, planeWidth, planeHeight, dx, dy)

        // Capped running mean: the divisor stops growing, so late frames keep their influence.
        depth++
        val divisor = minOf(depth, cap).toFloat()
        for (i in mean.indices) {
            val sample = scratch[i]
            if (sample.isNaN()) continue // outside the shifted frame — leave what is there
            mean[i] += (sample - mean[i]) / divisor
        }
    }

    /**
     * Box-averages [plane] down to preview size while applying the shift, in one pass.
     *
     * Nearest-source rather than bilinear: this is a preview at a quarter of a plane that was
     * itself binned from the sensor, and a half-pixel of interpolation error is invisible against
     * the field rotation this cannot correct at all.
     */
    private fun downsampleInto(
        plane: FloatArray,
        planeWidth: Int,
        planeHeight: Int,
        dx: Double,
        dy: Double,
    ) {
        val scaleX = planeWidth.toDouble() / width
        val scaleY = planeHeight.toDouble() / height
        val boxX = maxOf(1, scaleX.roundToInt())
        val boxY = maxOf(1, scaleY.roundToInt())
        val offsetX = dx.roundToInt()
        val offsetY = dy.roundToInt()

        for (py in 0 until height) {
            val srcY0 = (py * scaleY).toInt() + offsetY
            val row = py * width
            for (px in 0 until width) {
                val srcX0 = (px * scaleX).toInt() + offsetX
                var sum = 0.0
                var n = 0
                for (by in 0 until boxY) {
                    val sy = srcY0 + by
                    if (sy < 0 || sy >= planeHeight) continue
                    val base = sy * planeWidth
                    for (bx in 0 until boxX) {
                        val sx = srcX0 + bx
                        if (sx < 0 || sx >= planeWidth) continue
                        sum += plane[base + sx]
                        n++
                    }
                }
                scratch[row + px] = if (n == 0) Float.NaN else (sum / n).toFloat()
            }
        }
    }

    /**
     * The stack as ARGB for display, autostretched.
     *
     * A linear astro frame shown linearly is a black rectangle — the sky sits a few hundred ADU
     * above zero in a 1023-ADU range and the stars are a handful of pixels. The stretch is the
     * difference between "the app is broken" and "the app is working", so it is not optional and
     * not a setting.
     *
     * Shadows are clipped a little below the sky background and the midtone transfer function then
     * lifts what is left, which is the same shape as the autostretch every desktop tool applies by
     * default. Returns null before any frame has landed.
     */
    fun toArgb(shadowSigma: Double = 2.0, targetBackground: Double = 0.25): IntArray? {
        if (depth == 0) return null

        val background = median(mean)
        val sigma = madSigma(mean, background)
        val high = (mean.maxOrNull() ?: 0f).toDouble()

        // A frame with no noise at all has MAD = 0, which puts the shadow clip exactly on the
        // background and renders every pixel black. Flat frames are real — a covered lens, a
        // saturated sky — and answering them with a black rectangle looks like a broken preview
        // rather than a flat one. Falling back to the full range shows what little there is.
        val low = if (sigma > 1e-6) {
            background - shadowSigma * sigma
        } else {
            (mean.minOrNull() ?: 0f).toDouble()
        }
        val span = (high - low).takeIf { it > 1e-6 } ?: 1.0

        // Midtone so that the sky background itself lands at targetBackground once normalised.
        val normalisedBackground = ((background - low) / span).coerceIn(1e-4, 0.9999)
        val midtone = midtoneFor(normalisedBackground, targetBackground)

        for (i in mean.indices) {
            val normalised = ((mean[i] - low) / span).coerceIn(0.0, 1.0)
            val stretched = mtf(normalised, midtone)
            val v = (stretched * 255.0).roundToInt().coerceIn(0, 255)
            argb[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return argb
    }

    /**
     * The midtone transfer function used by every astro package's autostretch.
     *
     * `m` is the input level that is mapped to 0.5; below it the curve lifts, above it compresses.
     */
    private fun mtf(x: Double, m: Double): Double = when {
        x <= 0.0 -> 0.0
        x >= 1.0 -> 1.0
        else -> ((m - 1.0) * x) / (((2.0 * m) - 1.0) * x - m)
    }

    /**
     * The `m` that sends [background] to [target]. Inverting the MTF for m is closed form:
     *
     *     t = ((m-1)b) / ((2m-1)b - m)   =>   m = b(t-1) / (2tb - t - b)
     */
    private fun midtoneFor(background: Double, target: Double): Double {
        val denominator = 2.0 * target * background - target - background
        if (abs(denominator) < 1e-9) return 0.5
        return (background * (target - 1.0) / denominator).coerceIn(1e-4, 0.9999)
    }

    /**
     * Median and MAD are taken on a **strided subsample**, not the whole image.
     *
     * D-18's thermal argument applies to the stretch as much as the stacking: two full sorts of
     * 196k floats per frame, for the whole session, is real work that competes with capture. A
     * median over ~12k evenly spread pixels is indistinguishable from one over all of them for
     * this purpose — the quantity being estimated is the sky background, which is the most
     * abundant thing in the frame by a wide margin.
     *
     * Both share one preallocated buffer; allocating two per frame is exactly FR-12.2's warning.
     */
    private fun sampleCount(): Int = (mean.size + STATS_STRIDE - 1) / STATS_STRIDE

    private fun median(values: FloatArray): Double {
        var n = 0
        var i = 0
        while (i < values.size) {
            stats[n++] = values[i]
            i += STATS_STRIDE
        }
        java.util.Arrays.sort(stats, 0, n)
        return stats[n / 2].toDouble()
    }

    /** MAD-derived sigma — robust against the stars, which are the outliers here by definition. */
    private fun madSigma(values: FloatArray, centre: Double): Double {
        val c = centre.toFloat()
        var n = 0
        var i = 0
        while (i < values.size) {
            stats[n++] = abs(values[i] - c)
            i += STATS_STRIDE
        }
        java.util.Arrays.sort(stats, 0, n)
        return stats[n / 2] * 1.4826
    }

    companion object {
        /**
         * Frames of effective depth. Deep enough that the noise visibly drops early in a session,
         * shallow enough that the preview still responds an hour in.
         */
        const val DEFAULT_CAP = 32

        /**
         * Every Nth pixel feeds the median and MAD. 16 leaves ~12k samples of a 512x384 preview,
         * which is far more than a background estimate needs.
         */
        const val STATS_STRIDE = 16

        /** Preview size. Small on purpose — D-18's thermal argument is the whole design. */
        const val WIDTH = 512
        const val HEIGHT = 384
    }
}
