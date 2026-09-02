package com.starstacker.edit

import com.starstacker.imaging.Autostretch
import com.starstacker.stacking.TiledStacker

/**
 * T-7.1–T-7.4 / FR-8.1 steps 5–8 — the linear master made viewable.
 *
 * ### What this is for
 *
 * The linear master is the measurement and it is unlookable: the sky sits a few ADU above the dark
 * and the stars are three orders of magnitude above that, so shown as-is it is a black rectangle
 * with a scatter of white dots. The requirements call the stretch *"the entire beginner payoff"*,
 * and they are right — everything before this produces a file for Siril, and this produces a
 * picture.
 *
 * **The linear master is never touched** (FR-8.2). This reads it and writes somewhere else; the
 * stretch is destructive and irreversible, so the thing it is derived from has to survive it. Every
 * run starts again from the linear data, which is what makes the strength slider a slider rather
 * than a ratchet.
 *
 * ### The order, and why each step has to be where it is
 *
 * 1. **Gradient removal** ([Gradient]) — first, because everything after it reads the background
 *    and a ramp is not one number. The stretch would flatten itself against the ramp's spread and
 *    the colour balance would read a background that is three different colours across the frame.
 * 2. **Background neutralisation** — the channels' backgrounds are equalised, which is what makes
 *    the *sky* grey. Light pollution is orange; without this the stretch lifts an orange fog.
 * 3. **Colour balance** — the channels' bright ends are equalised, which is what makes the *stars*
 *    white. Two separate operations because they fix two ends of the range: neutralising alone
 *    leaves coloured stars, balancing alone leaves a coloured sky.
 * 4. **Autostretch** ([Autostretch]) — one stretch measured on luminance and applied to all three
 *    channels. Measuring per channel would be easier and would undo step 3, because a per-channel
 *    stretch equalises the channels by construction and throws away the colour that was just
 *    balanced.
 * 5. **Saturation** — last, because it operates on the stretched values a person will see, and a
 *    boost applied to linear data would be invisible in the shadows and violent in the highlights.
 *
 * ### One slider
 *
 * FR-8.3 asks for a single **strength** control, and it moves the two things that actually change
 * how the picture reads: how far the background is lifted, and how far the colour is pushed. The
 * expert affordance underneath exposes the polynomial degree, the stretch's own two points and the
 * saturation separately — one tap deeper, per the requirements.
 */
object AutoEdit {

    /**
     * @param strength 0–1, FR-8.3's one slider. 0 is a conservative, dark, nearly-neutral
     *   rendering; 1 is as far as this is willing to push. The default is deliberately not 1: an
     *   auto-edit that goes to its own limit leaves the slider with nowhere useful to go.
     * @param gradientDegree 0 disables gradient removal entirely, for anyone who wants to do it
     *   themselves or whose target fills the frame.
     */
    data class Settings(
        val strength: Double = DEFAULT_STRENGTH,
        val gradientDegree: Int = Gradient.DEFAULT_DEGREE,
        val saturation: Double? = null,
        val targetBackground: Double? = null,
        val shadowsClip: Double = Autostretch.DEFAULT_SHADOWS_CLIP,
    ) {
        /** Where the sky lands after the stretch. Darker is more conservative. */
        val background: Double
            get() = targetBackground ?: (MIN_BACKGROUND + strength * (MAX_BACKGROUND - MIN_BACKGROUND))

        /** 1.0 leaves colour alone; the ceiling is mild on purpose (FR-8.1 step 8). */
        val saturationBoost: Double
            get() = saturation ?: (1.0 + strength * MAX_SATURATION_BOOST)

        fun describe(): String = buildString {
            append("strength %.2f".format(strength))
            append(if (gradientDegree > 0) " · gradient deg $gradientDegree" else " · no gradient")
            append(" · background %.2f".format(background))
            append(" · saturation %.2f".format(saturationBoost))
        }
    }

    /** What the edit did, so the result can be argued with rather than merely looked at. */
    data class Report(
        val gradient: List<String>,
        val backgroundAdu: DoubleArray,
        val stretch: Autostretch.Stretch,
        val settings: Settings,
    ) {
        fun describe(): String = buildString {
            append(settings.describe())
            append(" · sky ")
            append(backgroundAdu.joinToString("/") { "%.1f".format(it) })
            append(" ADU")
            if (gradient.isNotEmpty()) append(" · ").append(gradient.joinToString(", "))
        }

        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * Renders [master] to 8-bit RGB.
     *
     * @param master three interleaved channels of linear float, **not modified**.
     * @return `width × height × 3` bytes, and the report of what was done to get them.
     */
    fun render(
        master: FloatArray,
        width: Int,
        height: Int,
        settings: Settings = Settings(),
    ): Pair<ByteArray, Report> {
        val count = width * height * TiledStacker.CHANNELS
        require(master.size >= count) { "master is smaller than ${width}x$height" }
        // A working copy, because FR-8.2 makes the linear master sacred and every step below is
        // destructive. A caller that already holds a copy should use [renderInPlace] instead — at
        // 12.6 MP this array is 151 MB, and making two of them is what failed the first run.
        return renderInPlace(master.copyOf(count), width, height, settings)
    }

    /**
     * [render] on a buffer the caller has given up.
     *
     * **[work] is modified.** For anyone who already holds a copy of the linear data — the stack
     * does, because it crops the master before rendering — this halves the peak memory, and on a
     * 512 MB heap holding a 151 MB master that is the difference between a preview and an
     * `OutOfMemoryError`.
     */
    fun renderInPlace(
        work: FloatArray,
        width: Int,
        height: Int,
        settings: Settings = Settings(),
    ): Pair<ByteArray, Report> {
        val channels = TiledStacker.CHANNELS
        val count = width * height * channels
        require(work.size >= count) { "buffer is smaller than ${width}x$height" }

        val notes = mutableListOf<String>()
        if (settings.gradientDegree > 0) {
            for (c in 0 until channels) {
                val model = Gradient.fit(
                    plane = work,
                    width = width,
                    height = height,
                    offset = c,
                    stride = channels,
                    degree = settings.gradientDegree,
                )
                if (model == null) {
                    notes += "channel $c: no gradient model"
                    continue
                }
                Gradient.subtract(work, width, height, model, offset = c, stride = channels)
                notes += "channel $c: ${model.describe()}"
            }
        }

        val backgrounds = DoubleArray(channels) { c -> percentile(work, count, c, channels, SKY_PERCENTILE) }
        val highlights = DoubleArray(channels) { c -> percentile(work, count, c, channels, HIGHLIGHT_PERCENTILE) }

        neutralise(work, count, channels, backgrounds)
        balance(work, count, channels, backgrounds, highlights)

        // One stretch, from luminance, applied to all three — see the class note on why this is not
        // measured per channel.
        val luminance = FloatArray(width * height)
        for (p in 0 until width * height) {
            val i = p * channels
            luminance[p] = ((work[i] + work[i + 1] + work[i + 2]) / 3f)
        }
        val stretch = Autostretch.measure(
            plane = luminance,
            black = 0.0,
            white = percentile(work, count, 0, 1, WHITE_PERCENTILE).coerceAtLeast(1.0),
            shadowsClip = settings.shadowsClip,
            targetBackground = settings.background,
        )

        val out = ByteArray(count)
        val boost = settings.saturationBoost
        for (p in 0 until width * height) {
            val i = p * channels
            var r = stretch.applyAdu(work[i].toDouble())
            var g = stretch.applyAdu(work[i + 1].toDouble())
            var b = stretch.applyAdu(work[i + 2].toDouble())

            if (boost != 1.0) {
                // Rec. 709 luminance, so the boost changes colour without changing brightness.
                val y = LUMA_R * r + LUMA_G * g + LUMA_B * b
                r = y + (r - y) * boost
                g = y + (g - y) * boost
                b = y + (b - y) * boost
            }

            out[i] = to8(r)
            out[i + 1] = to8(g)
            out[i + 2] = to8(b)
        }

        return out to Report(notes, backgrounds, stretch, settings)
    }

    /**
     * Shifts each channel so the *sky* is grey.
     *
     * Light pollution is orange, so the red background sits well above the blue; lifting that with
     * a stretch produces an orange fog behind everything. Subtracting each channel's own background
     * puts all three at the same place, and the colour that is left belongs to the objects.
     *
     * A small pedestal is added back rather than landing on zero, because the stretch that follows
     * clips at the shadow point and a background at exactly zero would take half the noise with it.
     */
    private fun neutralise(work: FloatArray, count: Int, channels: Int, backgrounds: DoubleArray) {
        val target = backgrounds.average()
        for (c in 0 until channels) {
            val shift = (target - backgrounds[c]).toFloat()
            if (shift == 0f) continue
            var i = c
            while (i < count) {
                work[i] += shift
                i += channels
            }
        }
    }

    /**
     * Scales each channel so the *stars* are white.
     *
     * The bright end of a star field is broadly neutral — stars are not all white, but a frame full
     * of them averages close to it — so equalising a high percentile across the channels is a rough
     * white balance that needs no reference and no camera profile. FR-8.1 step 6 asks for exactly
     * "rough", and Phase 6's intrinsics are where a real one would come from.
     *
     * Applied about the background rather than about zero, so it changes the *colour of the signal*
     * and leaves the neutralised sky where step 2 put it.
     */
    private fun balance(
        work: FloatArray,
        count: Int,
        channels: Int,
        backgrounds: DoubleArray,
        highlights: DoubleArray,
    ) {
        val base = backgrounds.average()
        val spans = DoubleArray(channels) { highlights[it] - backgrounds[it] }
        if (spans.any { it <= 0.0 }) return
        val target = spans.average()

        for (c in 0 until channels) {
            val gain = (target / spans[c]).coerceIn(MIN_GAIN, MAX_GAIN)
            if (gain == 1.0) continue
            var i = c
            while (i < count) {
                work[i] = (base + (work[i] - base) * gain).toFloat()
                i += channels
            }
        }
    }

    /**
     * The p-th value of one channel, by sampling rather than by sorting 12.6 million floats.
     *
     * Every threshold here is a broad statistic — where the sky is, where the bright end is — and a
     * stride of 17 settles those far tighter than the stretch can render.
     */
    private fun percentile(
        plane: FloatArray,
        count: Int,
        offset: Int,
        stride: Int,
        p: Double,
    ): Double {
        val step = stride * SAMPLE_STRIDE
        var n = 0
        var i = offset
        while (i < count) { n++; i += step }
        if (n == 0) return 0.0

        val samples = DoubleArray(n)
        var k = 0
        i = offset
        while (i < count) {
            val v = plane[i]
            samples[k++] = if (v.isFinite()) v.toDouble() else 0.0
            i += step
        }
        java.util.Arrays.sort(samples)
        return samples[((n - 1) * p).toInt().coerceIn(0, n - 1)]
    }

    private fun to8(v: Double): Byte =
        (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()

    /** Not 1.0: an auto-edit at its own limit leaves the slider nowhere useful to go. */
    const val DEFAULT_STRENGTH = 0.55

    private const val MIN_BACKGROUND = 0.08
    private const val MAX_BACKGROUND = 0.35
    private const val MAX_SATURATION_BOOST = 0.6

    /** The sky: low enough to be background, high enough not to be the darkest corner. */
    private const val SKY_PERCENTILE = 0.30

    /** The bright end, for the balance. Not the maximum, which is one hot star or one cosmic ray. */
    private const val HIGHLIGHT_PERCENTILE = 0.9995

    /** Where the stretch's white point goes. Above this is deliberately allowed to clip. */
    private const val WHITE_PERCENTILE = 0.99995

    /** A rough balance may not invent or destroy a channel. */
    private const val MIN_GAIN = 0.25
    private const val MAX_GAIN = 4.0

    private const val SAMPLE_STRIDE = 17

    private const val LUMA_R = 0.2126
    private const val LUMA_G = 0.7152
    private const val LUMA_B = 0.0722
}
