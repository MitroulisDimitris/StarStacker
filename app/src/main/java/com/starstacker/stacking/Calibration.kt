package com.starstacker.stacking

/**
 * T-5.2 / FR-8.1 steps 1–2 — `(light − dark) / normalised_flat`, then hot pixel correction.
 *
 * ### On CFA data, before debayer, and that ordering is not stylistic
 *
 * Dark current and flat response are properties of **one photosite**. Debayering first would
 * average each photosite together with its neighbours, so the correction would then be applied to a
 * blend of three or four sites that never shared a dark current or a gain. The error is small,
 * systematic and impossible to remove later — which is the worst combination, because it looks like
 * a slightly noisier stack rather than a bug.
 *
 * ### Every master is optional, and absence is pass-through
 *
 * Nothing here requires a calibration library to exist. FR-3.1.1's Functional tier shoots with no
 * calibration at all, a session can take its own darks and never see a flat, and Phase 6 is where
 * the library arrives. So each master is independently absent-able and the pipeline degrades one
 * step at a time rather than refusing to run.
 *
 * ### Three details that decide whether the numbers mean anything
 *
 * **The black pedestal has to go exactly once.** A raw frame sits on a black level — 64 ADU on the
 * reference sensor — so that noise can swing below the floor without being clipped. A dark frame
 * carries that pedestal too, so subtracting a dark removes the pedestal *and* the dark current
 * together. With no dark, the pedestal must be subtracted explicitly instead, or the flat division
 * that follows operates on a signal that is 64 ADU too high everywhere and quietly reshapes the
 * background. [apply] handles both cases; what it will not do is subtract it twice.
 *
 * **Negatives are kept.** After dark subtraction a starless pixel scatters either side of zero, and
 * clamping the negative half at zero would bias the mean *upward* — by more where the noise is
 * larger, which is to say non-uniformly across the frame. That bias then survives averaging, so it
 * lands in the master as a raised, uneven background: exactly what FR-8.1 step 5 then has to fight.
 * A pipeline that clips here is fighting itself.
 *
 * **A flat near zero is a hole, not a gain.** Dividing by a photosite that saw almost no light
 * produces an enormous number from a measurement that carries no information. Such pixels are
 * treated as bad rather than amplified.
 *
 * ### The masters are whole-frame; the light may be a band of one
 *
 * [TiledStacker] never holds a whole frame — it works a band of rows at a time, because sigma
 * clipping over 150 frames of 12.6 MP would want 7.5 GB. So [apply] takes the band's origin and
 * indexes the masters at it, while [Masters] stays whole-frame: a dark is a property of the sensor
 * and does not want re-slicing per tile.
 *
 * This was originally written frame-at-a-time and the tiled loop called it with a band, which threw
 * on the first real frame and could not have done anything else — the arithmetic had no way to know
 * where the band sat. It went unnoticed because the loop's tests use a 24-row frame and the band
 * margin is 160 rows, so every test band was the whole frame. A fixture smaller than the margin
 * cannot exercise banding at all.
 */
object Calibration {

    /**
     * The masters, all optional, all in ADU at full sensor resolution.
     *
     * Built through [of] so the flat is normalised exactly once, at construction. A caller who
     * normalises by hand and a caller who forgets are otherwise indistinguishable until the master
     * comes out with a gradient.
     */
    class Masters private constructor(
        val width: Int,
        val height: Int,
        val dark: FloatArray?,
        /** Already divided by its own mean, so the average gain is 1. */
        val flat: FloatArray?,
        val hotPixels: IntArray?,
    ) {
        val hasDark: Boolean get() = dark != null
        val hasFlat: Boolean get() = flat != null
        val hasHotPixels: Boolean get() = hotPixels?.isNotEmpty() == true

        /** What was actually applied, for `session.json` — a restack must know (FR-9.2). */
        fun describe(): String = buildString {
            append(if (hasDark) "dark" else "no dark")
            append(if (hasFlat) " · flat" else " · no flat")
            if (hasHotPixels) append(" · ${hotPixels!!.size} hot pixels")
        }

        companion object {
            fun of(
                width: Int,
                height: Int,
                dark: FloatArray? = null,
                rawFlat: FloatArray? = null,
                hotPixels: IntArray? = null,
            ): Masters {
                require(width > 0 && height > 0) { "empty frame" }
                dark?.let { require(it.size >= width * height) { "dark is smaller than the frame" } }
                rawFlat?.let { require(it.size >= width * height) { "flat is smaller than the frame" } }
                return Masters(width, height, dark, rawFlat?.let { normalise(it, width * height) }, hotPixels)
            }

            /**
             * Scales a flat so its mean is 1, making the division a pure gain correction.
             *
             * The mean is taken over **finite, positive** samples only. A dead column reads zero
             * and would otherwise drag the mean down, inflating every other pixel by way of
             * apology.
             */
            private fun normalise(flat: FloatArray, count: Int): FloatArray {
                var sum = 0.0
                var n = 0
                for (i in 0 until count) {
                    val v = flat[i]
                    if (v.isFinite() && v > 0f) { sum += v; n++ }
                }
                if (n == 0 || sum <= 0.0) return FloatArray(count) { 1f }
                val mean = (sum / n).toFloat()
                return FloatArray(count) { flat[it] / mean }
            }
        }
    }

    /**
     * Applies the masters to one CFA frame.
     *
     * @param blackLevel the sensor's pedestal, used **only when there is no dark** — see the class
     *   note. Passing it alongside a dark would subtract it twice.
     * @param cfaCodes the 2×2 pattern, needed by the hot-pixel step so it repairs a photosite from
     *   its own colour rather than its neighbours'.
     * @param out receives the result, in ADU above zero. May be the caller's reused buffer
     *   (FR-12.2); it is fully written.
     * @param fromRow the band's first row in **whole-frame** coordinates. See the note below.
     * @param rowCount how many rows [light] holds. Defaults to the whole frame.
     */
    fun apply(
        light: ShortArray,
        masters: Masters,
        blackLevel: Double,
        out: FloatArray,
        cfaCodes: List<Int> = DEFAULT_CFA,
        fromRow: Int = 0,
        rowCount: Int = masters.height,
    ) {
        val width = masters.width
        require(fromRow >= 0) { "negative row" }
        require(rowCount >= 0) { "negative row count" }
        require(fromRow + rowCount <= masters.height) {
            "band [$fromRow, ${fromRow + rowCount}) runs past the master's ${masters.height} rows"
        }
        val count = width * rowCount
        require(light.size >= count) { "light is smaller than ${width}x$rowCount" }
        require(out.size >= count) { "output is smaller than ${width}x$rowCount" }

        val dark = masters.dark
        val flat = masters.flat
        // With a dark, the pedestal leaves with it. Without, it has to go explicitly — and it must
        // happen before the flat division, which assumes a signal measured from zero.
        val pedestal = if (dark == null) blackLevel.toFloat() else 0f
        // The masters are whole-frame and the light is one band of it, so every master lookup is
        // shifted by the band's origin. Getting this wrong does not throw — it applies row 0's
        // dark current to row 900, which looks like a stack with a faint horizontal structure.
        val offset = fromRow * width

        for (i in 0 until count) {
            var value = (light[i].toInt() and 0xFFFF).toFloat() - pedestal
            if (dark != null) value -= dark[offset + i]
            if (flat != null) {
                val gain = flat[offset + i]
                // A photosite that saw almost nothing carries no gain information; dividing by it
                // manufactures an enormous value out of noise.
                value = if (gain.isFinite() && gain > MIN_FLAT_GAIN) value / gain else Float.NaN
            }
            out[i] = value
        }

        masters.hotPixels?.let { repairHotPixels(out, width, rowCount, fromRow, it, cfaCodes) }
    }

    /**
     * Replaces known-bad photosites with the median of their **same-colour** neighbours.
     *
     * Same-colour is the whole point, and on a Bayer grid that means stepping **two** pixels, not
     * one: the four nearest sites of the same colour sit at ±2 in x and y. Repairing a green
     * photosite from the red and blue beside it would replace one bad pixel with a plausible-looking
     * wrong one — worse than leaving it, because the gate and the stack would then both trust it.
     *
     * A median rather than a mean, because a hot pixel's neighbour is sometimes also hot: clusters
     * are common, and a mean would carry the neighbour's fault into the repair.
     *
     * **The list is in whole-frame indices and the plane is one band**, so entries outside the band
     * are skipped and the rest are translated. A repair within two rows of a band edge sees fewer
     * neighbours than it would in a whole frame; that is correct rather than merely tolerable,
     * because [TiledStacker] discards the band's outer margin and only the interior reaches the
     * master. The parity of the step is unaffected — ±2 lands on the same colour wherever the band
     * begins, which is why this step does not care about the band's CFA phase and the debayer does.
     */
    private fun repairHotPixels(
        plane: FloatArray,
        width: Int,
        rowCount: Int,
        fromRow: Int,
        hotPixels: IntArray,
        cfaCodes: List<Int>,
    ) {
        require(cfaCodes.size == 4) { "expected a 2x2 CFA pattern" }
        val neighbours = FloatArray(4)
        val firstIndex = fromRow * width
        val lastIndex = firstIndex + rowCount * width
        hotPixels.forEach { absolute ->
            if (absolute < firstIndex || absolute >= lastIndex) return@forEach
            val index = absolute - firstIndex
            val x = index % width
            val y = index / width
            var n = 0
            // Two pixels away in each direction lands on the same colour in every Bayer layout.
            if (x >= 2) neighbours[n++] = plane[index - 2]
            if (x < width - 2) neighbours[n++] = plane[index + 2]
            if (y >= 2) neighbours[n++] = plane[index - 2 * width]
            if (y < rowCount - 2) neighbours[n++] = plane[index + 2 * width]
            if (n == 0) return@forEach

            val usable = FloatArray(n) { neighbours[it] }.filter { it.isFinite() }.sorted()
            if (usable.isEmpty()) return@forEach
            plane[index] = if (usable.size % 2 == 1) {
                usable[usable.size / 2]
            } else {
                (usable[usable.size / 2 - 1] + usable[usable.size / 2]) / 2f
            }
        }
    }

    /**
     * Combines a session's dark frames into a master, per pixel, by median.
     *
     * **Median rather than mean**, because a dark frame's job is to measure what the sensor adds in
     * the absence of light, and cosmic ray hits and the occasional light leak are exactly the
     * outliers a mean would fold into the answer. With a session's usual 15–30 darks the median is
     * only slightly noisier than the mean and immune to both.
     *
     * The frames must be **temperature-matched to the lights** (**D-16**): dark current roughly
     * doubles every 6–7 °C, so a master built at 22 °C over-subtracts from lights shot at 35 °C.
     * That matching is the calibration library's job in Phase 6; this only combines what it is
     * given, which is why the session shoots its own darks at the end of the run, while the phone
     * is still at the temperature it worked at.
     */
    fun masterDark(frames: List<ShortArray>, count: Int): FloatArray? {
        if (frames.isEmpty()) return null
        val out = FloatArray(count)
        masterDarkInto(frames, count, out, 0)
        return out
    }

    /**
     * [masterDark] writing one band into a whole-frame master at [offset].
     *
     * This exists because the whole-frame form cannot be used on a real session: one 12.6 MP dark
     * is 25 MB as a `ShortArray`, so holding a session's twenty at once to take a median down the
     * stack is half a gigabyte. Read a band from each dark instead and the peak is the band times
     * the count — the same trick, and for the same reason, as the stacking loop itself.
     *
     * The output stays whole-frame (12.6 M floats, 50 MB) because that is what [Masters] holds and
     * what [apply] indexes into; it is the input side that had to be bounded.
     */
    fun masterDarkInto(frames: List<ShortArray>, count: Int, out: FloatArray, offset: Int) {
        if (frames.isEmpty()) return
        frames.forEach { require(it.size >= count) { "a dark frame is smaller than the others" } }
        require(out.size >= offset + count) { "master is smaller than the band it is given" }
        val samples = FloatArray(frames.size)
        for (i in 0 until count) {
            frames.forEachIndexed { f, frame -> samples[f] = (frame[i].toInt() and 0xFFFF).toFloat() }
            samples.sort()
            val mid = samples.size / 2
            out[offset + i] =
                if (samples.size % 2 == 1) samples[mid] else (samples[mid - 1] + samples[mid]) / 2f
        }
    }

    /**
     * Photosites whose dark level stands far enough above the frame to be counted as hot.
     *
     * The threshold is relative to the master's own median and spread, not absolute: what counts as
     * hot depends on ISO, exposure and temperature, and a fixed ADU limit would find thousands of
     * pixels on a warm 30 s dark and none on a cool 1 s one.
     *
     * Spread is estimated by MAD rather than standard deviation — the distribution being measured
     * is contaminated by precisely the outliers being looked for, and a standard deviation computed
     * over hot pixels is inflated by them until it can no longer see them.
     */
    fun hotPixelsFrom(dark: FloatArray, count: Int, sigma: Double = DEFAULT_HOT_SIGMA): IntArray {
        if (count <= 0) return IntArray(0)
        val sorted = FloatArray(count) { dark[it] }.also { it.sort() }
        val median = sorted[count / 2]
        val deviations = FloatArray(count) { kotlin.math.abs(dark[it] - median) }.also { it.sort() }
        val mad = deviations[count / 2]
        // MAD to sigma for a normal distribution. A perfectly uniform dark has a MAD of zero, and
        // a threshold of zero would call every pixel above the median hot.
        val spread = (mad * 1.4826).coerceAtLeast(MIN_SPREAD_ADU)
        val limit = median + sigma * spread
        return (0 until count).filter { dark[it] > limit }.toIntArray()
    }

    /** GRBG — the reference device's, and the only pattern this app has met (§1.5). */
    private val DEFAULT_CFA = listOf(1, 0, 2, 1)

    /** Below this a flat pixel is a hole rather than a gain. See the class note. */
    private const val MIN_FLAT_GAIN = 0.05f

    /** How far above the dark's own spread a photosite must sit to count as hot. */
    const val DEFAULT_HOT_SIGMA = 8.0

    /** Floor on the estimated spread, so a flat dark does not make every pixel an outlier. */
    private const val MIN_SPREAD_ADU = 0.5
}
