package com.starstacker.calibration

import kotlin.math.abs

/**
 * T-8.3 / FR-4.1.3 — building a master flat, and refusing the frames that would poison one.
 *
 * ### Why this is the largest lever on image quality
 *
 * Measured on the first real session (§1.41): the sky reads **21.2 ADU at the centre and 4.8 in the
 * corners**. That fourfold falloff is the lens, and it sits on a sky only 12 ADU above the dark, so
 * it is not a subtlety — it is most of the picture. T-7.1's polynomial takes the residual to 4 ADU
 * peak-to-peak and cannot do better, because past the `cos⁴` the extra freedom starts describing
 * the subject instead of the lens.
 *
 * A flat *measures* the falloff instead of inferring it, and it is the only thing that can.
 *
 * ### On CFA data, per photosite
 *
 * Same reason T-5.2 calibrates before debayer: response is a property of one photosite, and the
 * four in a Bayer cell do not share it. A flat built after debayer would apply each correction to a
 * blend of sites that never shared a gain.
 *
 * ### Median, and normalised to one
 *
 * Median across frames because a flat session picks up a moth, a passing cloud edge, a finger. And
 * normalised so the mean is 1, which makes the division a pure gain correction that changes the
 * shape of the frame without changing its overall level — `Calibration.Masters.of` normalises too,
 * so this is belt and braces, but a stored flat whose values sit around 1.0 is one a person can
 * open and understand.
 *
 * ### The validity checks are the point of the class
 *
 * Building the median is four lines. Everything else here is refusing to build one from frames that
 * would make the master worse than no master at all, and **the failure mode is silent**: a flat
 * built from saturated frames divides the corners by a number that is too small and *inverts* the
 * vignette, and nothing downstream can tell.
 */
object FlatField {

    /** Why a frame was refused, in the words the user needs rather than the ones the code uses. */
    data class Rejection(val index: Int, val reason: String)

    data class Result(
        /** Normalised to a mean of 1, at full sensor resolution, CFA. Null if none were usable. */
        val master: FloatArray?,
        val used: Int,
        val offered: Int,
        val rejections: List<Rejection>,
        /** Centre brightness over corner brightness — how much vignetting the lens actually has. */
        val falloff: Double,
        val warnings: List<String>,
    ) {
        val usable: Boolean get() = master != null

        fun describe(): String = buildString {
            if (!usable) {
                append("no usable flat from $offered frames")
                return@buildString
            }
            append("$used of $offered frames")
            append(" · %.2f× falloff".format(falloff))
            if (rejections.isNotEmpty()) append(" · ${rejections.size} rejected")
            if (warnings.isNotEmpty()) append(" · ${warnings.size} warnings")
        }

        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * Combines flat frames into a master.
     *
     * @param frames raw CFA planes, all `width × height`.
     * @param blackLevel the sensor pedestal, removed before anything else — a flat sits on it too,
     *   and dividing by an un-pedestalled flat corrects towards the pedestal rather than towards
     *   the response.
     * @param whiteLevel full scale, for the saturation check. Zero means "not reported", and the
     *   check falls back to the 16-bit ceiling rather than being skipped.
     */
    fun build(
        frames: List<ShortArray>,
        width: Int,
        height: Int,
        blackLevel: Double,
        whiteLevel: Int,
    ): Result {
        val count = width * height
        if (frames.isEmpty() || count <= 0) {
            return Result(null, 0, frames.size, emptyList(), 0.0, listOf("no frames"))
        }

        val full = (if (whiteLevel > blackLevel) whiteLevel.toDouble() else DEFAULT_WHITE) - blackLevel
        val rejections = mutableListOf<Rejection>()
        val levels = DoubleArray(frames.size)

        // Pass one: each frame on its own merits.
        frames.forEachIndexed { i, frame ->
            if (frame.size < count) {
                rejections += Rejection(i, "smaller than ${width}x$height")
                return@forEachIndexed
            }
            val sampled = sample(frame, count, SAMPLE_STRIDE)
            val level = percentile(sampled, 0.5) - blackLevel
            // Saturation is judged at the **bright end, not the middle**. A vignetted flat has a
            // median far below its centre — at 4× falloff the median is under half the peak — so a
            // frame whose centre is already clipping passes a median test comfortably. And the
            // clipped centre is precisely what inverts the vignette.
            val peak = percentile(sampled, PEAK_PERCENTILE) - blackLevel
            levels[i] = level
            when {
                peak / full >= MAX_PEAK -> rejections += Rejection(
                    i,
                    "too bright — the centre is at %.0f%% of full scale, and a clipped flat inverts the vignette"
                        .format(peak / full * 100),
                )
                level / full <= MIN_LEVEL -> rejections += Rejection(
                    i,
                    "too dim — %.0f%% of full scale, mostly noise".format(level / full * 100),
                )
            }
        }

        // Pass two: against each other. A frame shot as the light changed is individually fine and
        // collectively wrong, and averaging it in tilts the master towards whatever it saw.
        val kept = frames.indices.filter { i -> rejections.none { it.index == i } }
        if (kept.isNotEmpty()) {
            val typical = kept.map { levels[it] }.sorted()[kept.size / 2]
            kept.forEach { i ->
                if (typical > 0 && abs(levels[i] - typical) / typical > LEVEL_TOLERANCE) {
                    rejections += Rejection(
                        i,
                        "%.0f%% off the others — the light changed".format(
                            100 * (levels[i] - typical) / typical,
                        ),
                    )
                }
            }
        }

        val usable = frames.indices.filter { i -> rejections.none { it.index == i } }
        if (usable.size < MIN_FRAMES) {
            return Result(
                null, usable.size, frames.size, rejections, 0.0,
                listOf("need at least $MIN_FRAMES usable frames, got ${usable.size}"),
            )
        }

        // The median, per photosite, with the pedestal already gone.
        val master = FloatArray(count)
        val samples = DoubleArray(usable.size)
        for (p in 0 until count) {
            usable.forEachIndexed { k, i ->
                samples[k] = (frames[i][p].toInt() and 0xFFFF) - blackLevel
            }
            samples.sort()
            val mid = samples.size / 2
            master[p] = (
                if (samples.size % 2 == 1) samples[mid] else (samples[mid - 1] + samples[mid]) / 2
                ).toFloat()
        }

        val warnings = mutableListOf<String>()
        val falloff = falloffOf(master, width, height, warnings)
        normalise(master, count, warnings)

        return Result(master, usable.size, frames.size, rejections, falloff, warnings)
    }

    /**
     * Centre brightness over corner brightness, and a sanity check on the shape.
     *
     * **Vignetting always darkens the corners**, so a master whose corners are *brighter* than its
     * centre is not a flat — it is a photograph of something, or a light source off to one side.
     * That is worth saying out loud rather than dividing by.
     */
    private fun falloffOf(
        master: FloatArray,
        width: Int,
        height: Int,
        warnings: MutableList<String>,
    ): Double {
        val box = (minOf(width, height) / 10).coerceAtLeast(1)
        val centre = patchMean(master, width, width / 2 - box / 2, height / 2 - box / 2, box)
        val corners = listOf(
            patchMean(master, width, 0, 0, box),
            patchMean(master, width, width - box, 0, box),
            patchMean(master, width, 0, height - box, box),
            patchMean(master, width, width - box, height - box, box),
        )
        val corner = corners.average()
        if (corner <= 0.0 || centre <= 0.0) {
            warnings += "the flat has no signal in the centre or the corners"
            return 0.0
        }
        val ratio = centre / corner
        when {
            ratio < 1.0 ->
                warnings += "the corners are brighter than the centre — this is not a flat field"
            ratio > MAX_FALLOFF ->
                warnings += ("%.1f× falloff is more than a lens does — the light source is falling " +
                    "off, not the camera. A panel held close does this: the frame's centre is the " +
                    "nearest part of it and the corners are further away and at an angle.")
                    .format(ratio)
        }
        // A corner that differs wildly from its opposite means the illumination was one-sided,
        // which bakes a gradient into every frame the flat is ever applied to.
        val spread = (corners.max() - corners.min()) / corner
        if (spread > CORNER_SPREAD) {
            warnings += "%.0f%% difference between corners — the light was not even"
                .format(spread * 100)
        }
        return ratio
    }

    private fun patchMean(master: FloatArray, width: Int, left: Int, top: Int, box: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in top until top + box) {
            for (x in left until left + box) {
                val i = y * width + x
                if (i in master.indices) { sum += master[i]; n++ }
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** Mean of 1, so dividing by it is a pure gain correction. */
    private fun normalise(master: FloatArray, count: Int, warnings: MutableList<String>) {
        var sum = 0.0
        var n = 0
        for (i in 0 until count) {
            val v = master[i]
            if (v.isFinite() && v > 0f) { sum += v; n++ }
        }
        if (n == 0 || sum <= 0.0) {
            warnings += "the flat is empty after the pedestal was removed"
            return
        }
        val mean = (sum / n).toFloat()
        for (i in 0 until count) master[i] = master[i] / mean
    }

    /** Sampled and sorted once, because both statistics are broad ones over twelve million pixels. */
    private fun sample(frame: ShortArray, count: Int, stride: Int): DoubleArray {
        val n = (count + stride - 1) / stride
        val samples = DoubleArray(n)
        var k = 0
        var i = 0
        while (i < count && k < n) {
            samples[k++] = (frame[i].toInt() and 0xFFFF).toDouble()
            i += stride
        }
        return samples.copyOf(k).also { java.util.Arrays.sort(it) }
    }

    private fun percentile(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
    }

    /**
     * Above this at the **bright end** a flat is heading for saturation, and a saturated flat is
     * worse than none: the clipped centre reads lower than it should relative to the corners, so
     * dividing by it *inverts* the vignette and brightens the middle of every frame it touches.
     */
    private const val MAX_PEAK = 0.85

    /** Not the very maximum, which is one hot pixel rather than the state of the frame. */
    private const val PEAK_PERCENTILE = 0.999

    /** Below this the flat is mostly read noise, and dividing by noise adds noise. */
    private const val MIN_LEVEL = 0.10

    /** How far a frame may sit from its fellows before it is the light rather than the lens. */
    private const val LEVEL_TOLERANCE = 0.15

    /** Enough for the median to mean anything. */
    const val MIN_FRAMES = 5

    /** Three stops of falloff. More than a lens does; something is in the way. */
    private const val MAX_FALLOFF = 8.0

    /** Corners differing by more than this means one-sided illumination. */
    private const val CORNER_SPREAD = 0.25

    private const val DEFAULT_WHITE = 65535.0
    private const val SAMPLE_STRIDE = 37
}
