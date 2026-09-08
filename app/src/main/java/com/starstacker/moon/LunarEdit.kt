package com.starstacker.moon

import com.starstacker.edit.AutoEdit
import kotlin.math.roundToInt

/**
 * T-11.7 — the edit a lunar master wants, which is nearly the opposite of a deep-sky one.
 *
 * ### Three of the deep-sky pipeline's four steps are wrong here
 *
 * **Gradient removal must be off.** T-7.1 fits a degree-4 polynomial to the sky background,
 * justified by the cos⁴ vignetting law (§1.41). A lunar frame has no sky background to model: it is
 * black except for one bright object, so the fit has nothing to constrain it and the safest thing
 * it can do is nothing. `gradientDegree = 0` is already supported by [AutoEdit.Settings], so this
 * is a preset rather than new code.
 *
 * **The stretch must be mild.** T-7.3's autostretch lifts the sky to
 * [AutoEdit.Settings.background] — 0.08 to 0.35 — because a faint nebula lives just above the sky
 * and has to be dragged into view. The moon does not: metered at 70% of white it is *already* where
 * it should be, and lifting the background would turn black sky grey while amplifying read noise
 * across 99.97% of the frame. So the target background is near zero and the shadows clip harder.
 *
 * **Saturation must not be boosted.** The moon is very nearly grey. What colour it has is real —
 * mineral differences, the blue of titanium-rich maria — but it is subtle, and a boost tuned to
 * make an emission nebula sing turns lunar noise into confetti. Left at 1.0.
 *
 * ### And the one step it wants that deep sky does not
 *
 * **Sharpening.** Deep sky never sharpens automatically: stars are point sources, and sharpening
 * point sources makes rings. An extended object with real high-frequency detail is the case where
 * unsharp masking earns its place, and after a lucky-imaging stack there is genuine detail under
 * the residual blur to recover.
 *
 * [unsharpMask] is deliberately conservative and deliberately last. It runs on the 8-bit render
 * rather than the linear master because FR-8.2 keeps the master sacred, and because sharpening is
 * a presentation choice that should be re-doable without re-stacking.
 */
object LunarEdit {

    /** Where the sky lands. Not zero — a pure-black background hides the limb's faint edge. */
    const val TARGET_BACKGROUND = 0.02

    /**
     * Harder than the deep-sky default of −2.8.
     *
     * The clip is in units of sigma below the background, so a harder number throws away more of
     * the noise floor. On a nebula that risks clipping real faint signal; on a lunar frame there is
     * nothing down there but read noise, and keeping it only makes the sky look dirty.
     */
    const val SHADOWS_CLIP = -1.6

    /** Unsharp mask defaults: gentle, because a stack of soft frames cannot be sharpened into focus. */
    const val DEFAULT_AMOUNT = 0.6
    const val DEFAULT_RADIUS = 2

    /**
     * The edit settings for a lunar master.
     *
     * @param strength FR-8.3's slider, kept so the user still has one control. It moves the
     *   stretch only — the gradient stays off and saturation stays at 1.0 at every position,
     *   because those two are wrong for this subject rather than merely strong.
     */
    fun settings(strength: Double = AutoEdit.DEFAULT_STRENGTH): AutoEdit.Settings =
        AutoEdit.Settings(
            strength = strength.coerceIn(0.0, 1.0),
            gradientDegree = 0,
            saturation = 1.0,
            targetBackground = TARGET_BACKGROUND + strength.coerceIn(0.0, 1.0) * TARGET_BACKGROUND,
            shadowsClip = SHADOWS_CLIP,
        )

    /**
     * Unsharp mask over 8-bit interleaved RGB, in place.
     *
     * `out = in + amount × (in − blur(in))`, with a box blur of radius [radius] standing in for a
     * Gaussian. A box blur is visibly worse than a Gaussian for large radii; at radius 2 the
     * difference is under a quantisation step, and it is separable, integer and fast enough to run
     * on every slider move.
     *
     * @param amount 0 disables. Above ~1.5 the limb starts to ring, which on the moon looks like a
     *   halo and is the classic over-sharpened planetary image.
     */
    fun unsharpMask(
        rgb: ByteArray,
        width: Int,
        height: Int,
        amount: Double = DEFAULT_AMOUNT,
        radius: Int = DEFAULT_RADIUS,
    ) {
        if (amount <= 0.0 || radius <= 0) return
        require(rgb.size >= width * height * CHANNELS) { "rgb is smaller than ${width}x$height" }
        if (width < 2 * radius + 1 || height < 2 * radius + 1) return

        // One channel at a time: the blur is separable and this keeps the scratch buffers to two
        // rows-worth per channel rather than three full-size RGB copies.
        val plane = IntArray(width * height)
        val blurred = IntArray(width * height)
        for (c in 0 until CHANNELS) {
            var i = c
            for (p in 0 until width * height) {
                plane[p] = rgb[i].toInt() and 0xFF
                i += CHANNELS
            }
            boxBlur(plane, blurred, width, height, radius)
            i = c
            for (p in 0 until width * height) {
                val sharpened = plane[p] + amount * (plane[p] - blurred[p])
                rgb[i] = sharpened.roundToInt().coerceIn(0, 255).toByte()
                i += CHANNELS
            }
        }
    }

    /** Separable box blur, horizontal then vertical, with edges clamped rather than wrapped. */
    private fun boxBlur(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val window = 2 * radius + 1
        val row = IntArray(width)

        for (y in 0 until height) {
            val base = y * width
            // Running sum, so the cost is per pixel rather than per pixel per window.
            var sum = 0
            for (x in -radius..radius) sum += src[base + x.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                row[x] = sum / window
                val out = (x - radius).coerceIn(0, width - 1)
                val into = (x + radius + 1).coerceIn(0, width - 1)
                sum += src[base + into] - src[base + out]
            }
            System.arraycopy(row, 0, dst, base, width)
        }

        val column = IntArray(height)
        for (x in 0 until width) {
            var sum = 0
            for (y in -radius..radius) sum += dst[y.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                column[y] = sum / window
                val out = (y - radius).coerceIn(0, height - 1)
                val into = (y + radius + 1).coerceIn(0, height - 1)
                sum += dst[into * width + x] - dst[out * width + x]
            }
            for (y in 0 until height) dst[y * width + x] = column[y]
        }
    }

    private const val CHANNELS = 3
}
