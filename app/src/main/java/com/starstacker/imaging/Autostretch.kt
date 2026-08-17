package com.starstacker.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MTF autostretch — the display transform for linear data (T-2.2, later T-3.14 and T-7.3).
 *
 * A linear astro frame shown as-is is a black rectangle with a few white dots: the sky sits a
 * few hundred ADU above black and the stretch that makes it legible is not a curve anyone should
 * be asked to drag in the dark. This is PixInsight's screen-transfer function — clip the shadows
 * a fixed number of sigma below the background, then apply a midtone transfer that puts the
 * background at a target grey.
 *
 * **Display only.** Nothing here ever touches data on its way to disk (FR-8.2); the stretch is
 * measured per frame and thrown away.
 */
object Autostretch {

    /** Shadow clipping point, in MAD-derived sigma below the median. PixInsight's default. */
    const val DEFAULT_SHADOWS_CLIP = -2.8

    /** Where the sky background lands after the stretch. 0.25 is dark but clearly not black. */
    const val DEFAULT_TARGET_BACKGROUND = 0.25

    private const val MAD_TO_SIGMA = 1.4826
    private const val MIN_MIDTONE = 1e-4
    private const val MAX_MIDTONE = 1.0 - 1e-4

    /**
     * A measured stretch: the shadow clipping point and midtone balance that map this frame's
     * background to [DEFAULT_TARGET_BACKGROUND].
     *
     * Keeping the parameters rather than a lookup table means the same stretch can be reapplied
     * to a later frame — which is what stops the framing preview from flickering as the stretch
     * chases the noise from one frame to the next.
     */
    data class Stretch(
        val shadows: Double,
        val midtone: Double,
        val black: Double,
        val white: Double,
        /** Frame median, normalised — the sky background before stretching. */
        val medianNormalised: Double,
        /** MAD-derived sigma, normalised. Zero means a featureless frame. */
        val sigmaNormalised: Double,
    ) {
        /** Maps one raw sample (in ADU) to [0,1]. */
        fun applyAdu(value: Double): Double = applyNormalised(normalise(value))

        fun applyNormalised(x: Double): Double {
            if (x <= shadows) return 0.0
            if (shadows >= 1.0) return 1.0
            return mtf(midtone, (x - shadows) / (1.0 - shadows))
        }

        fun normalise(value: Double): Double {
            val span = white - black
            if (span <= 0.0) return 0.0
            return ((value - black) / span).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Midtone transfer function. [m] < 0.5 lifts the shadows, > 0.5 crushes them; 0.5 is the
     * identity.
     */
    fun mtf(m: Double, x: Double): Double = when {
        x <= 0.0 -> 0.0
        x >= 1.0 -> 1.0
        m <= 0.0 -> 1.0
        m >= 1.0 -> 0.0
        else -> {
            val denominator = (2.0 * m - 1.0) * x - m
            if (denominator == 0.0) x else ((m - 1.0) * x) / denominator
        }
    }

    /**
     * Measures a stretch from the frame's own statistics.
     *
     * @param black sensor black level in ADU; [white] the white level. Both come from the DNG
     *   metadata — deriving them from the frame instead would make the stretch depend on whether
     *   a satellite happened to cross it.
     * @param stride sample every nth pixel. The statistics are of a million-pixel frame; a
     *   seventh of them settles the median to far better than the stretch can show.
     */
    fun measure(
        plane: FloatArray,
        count: Int = plane.size,
        black: Double = 0.0,
        white: Double = 0.0,
        stride: Int = 7,
        shadowsClip: Double = DEFAULT_SHADOWS_CLIP,
        targetBackground: Double = DEFAULT_TARGET_BACKGROUND,
    ): Stretch {
        require(stride >= 1) { "stride must be positive, was $stride" }
        val n = min(count, plane.size)

        // A white level at or below black means "not reported" — fall back to the frame's own
        // maximum so the preview still renders rather than coming out uniformly black.
        var top = white
        if (top <= black) {
            var maxSample = Float.NEGATIVE_INFINITY
            var i = 0
            while (i < n) {
                if (plane[i] > maxSample) maxSample = plane[i]
                i += stride
            }
            top = max(maxSample.toDouble(), black + 1.0)
        }
        val span = top - black

        if (n == 0 || span <= 0.0) {
            return Stretch(0.0, 0.5, black, top, 0.0, 0.0)
        }

        val sampleCount = (n + stride - 1) / stride
        val samples = DoubleArray(sampleCount)
        var s = 0
        var i = 0
        while (i < n && s < sampleCount) {
            samples[s++] = ((plane[i] - black) / span).coerceIn(0.0, 1.0)
            i += stride
        }
        val used = s
        val sorted = samples.copyOf(used)
        sorted.sort()
        val median = sorted[used / 2]

        for (k in 0 until used) samples[k] = abs(samples[k] - median)
        val deviations = samples.copyOf(used)
        deviations.sort()
        val sigma = deviations[used / 2] * MAD_TO_SIGMA

        // A frame with no measurable noise (a synthetic flat, or a sensor returning a constant)
        // has no scale to clip against. Leave it unstretched rather than dividing by zero.
        if (sigma <= 0.0) {
            return Stretch(0.0, 0.5, black, top, median, 0.0)
        }

        val shadows = (median + shadowsClip * sigma).coerceIn(0.0, 1.0)
        val above = median - shadows
        val midtone = if (above <= 0.0 || shadows >= 1.0) {
            0.5
        } else {
            mtf(targetBackground, above / (1.0 - shadows)).coerceIn(MIN_MIDTONE, MAX_MIDTONE)
        }

        return Stretch(
            shadows = shadows,
            midtone = midtone,
            black = black,
            white = top,
            medianNormalised = median,
            sigmaNormalised = sigma,
        )
    }

    /**
     * Applies a stretch to a whole plane, producing 8-bit grey.
     *
     * Writes into [out] when one is supplied so the framing loop can reuse a single buffer
     * across frames rather than allocating a megabyte per second (FR-12.2).
     */
    fun toGray8(
        plane: FloatArray,
        width: Int,
        height: Int,
        stretch: Stretch,
        out: ByteArray = ByteArray(width * height),
    ): ByteArray {
        require(plane.size >= width * height) { "plane is smaller than ${width}x$height" }
        require(out.size >= width * height) { "output buffer is smaller than ${width}x$height" }

        // The transform is monotonic in the input, so a 1024-entry lookup over the normalised
        // range costs one multiply per pixel instead of a division and a branch.
        val lut = IntArray(LUT_SIZE)
        for (i in 0 until LUT_SIZE) {
            val x = i.toDouble() / (LUT_SIZE - 1)
            lut[i] = (stretch.applyNormalised(x) * 255.0).toInt().coerceIn(0, 255)
        }

        val span = stretch.white - stretch.black
        val scale = if (span > 0.0) (LUT_SIZE - 1) / span else 0.0
        val black = stretch.black
        var i = 0
        val n = width * height
        while (i < n) {
            var index = ((plane[i] - black) * scale).toInt()
            if (index < 0) index = 0 else if (index >= LUT_SIZE) index = LUT_SIZE - 1
            out[i] = lut[index].toByte()
            i++
        }
        return out
    }

    private const val LUT_SIZE = 1024
}
