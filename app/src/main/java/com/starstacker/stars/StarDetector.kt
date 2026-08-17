package com.starstacker.stars

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * T-2.3 — star detection, centroiding, HFR and eccentricity.
 *
 * Pure Kotlin over a mono plane, no Android and no OpenCV: per §12.1 this runs once per ~15 s
 * on a ~1 MP binned frame, so performance is irrelevant and testability is not.
 *
 * Three separate features rest on this: focus (FR-6.3 drives the sweep off HFR), live quality
 * gating (FR-7.5 rejects on eccentricity and star-count collapse) and registration (FR-7.2
 * matches asterisms built from these centroids).
 */

data class Star(
    /** Sub-pixel centroid in plane coordinates. */
    val x: Double,
    val y: Double,
    /** Background-subtracted total flux. */
    val flux: Double,
    val peak: Double,
    /**
     * Half-flux radius: the flux-weighted mean radius, in pixels. The focus metric — smaller is
     * sharper — and the one number FR-6.3 puts in front of the user during a session.
     */
    val hfr: Double,
    /** 0 = round, approaching 1 = a streak. Trailing shows up here first (FR-7.5). */
    val eccentricity: Double,
    val pixelCount: Int,
    val saturated: Boolean,
)

data class FrameStars(
    /** Median background level of the frame, in ADU. */
    val background: Double,
    /** Robust noise estimate (MAD-derived sigma), in ADU. */
    val noise: Double,
    val stars: List<Star>,
    /**
     * The frame's own background is at the white level: the sensor is clipped and there is no
     * signal left to measure.
     *
     * This is a third answer, distinct from "stars found" and from "no stars found", and it has
     * to be, because the remedies are opposite. A starless dark sky means cloud and the advice is
     * to wait (FR-7.5); a clipped frame means the exposure or ISO is far too high, or the lens is
     * pointed at something lit, and waiting will not help. Measured indoors 2026-08-17, where a
     * fully saturated frame yielded 24–41 phantom "stars" with an entirely plausible HFR.
     */
    val saturatedFrame: Boolean = false,
) {
    val count: Int get() = stars.size

    val medianHfr: Double? get() = stars.map { it.hfr }.medianOrNull()

    val medianEccentricity: Double? get() = stars.map { it.eccentricity }.medianOrNull()

    private fun List<Double>.medianOrNull(): Double? {
        if (isEmpty()) return null
        val s = sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }
}

/**
 * @param thresholdSigma detection threshold above the local background
 * @param minPixels rejects single hot pixels, which a phone sensor has plenty of
 * @param maxPixels rejects the Moon and large lit cloud. Deliberately generous: a badly trailed
 *   star is long and thin, and it must still be *measured*, because a trailed frame that reports
 *   "no stars" gets diagnosed as cloud (FR-7.5) and the user is told to wait rather than to
 *   shorten the sub
 * @param saturationLevel white level; saturated stars are flagged and excluded from focus maths
 * @param backgroundTile side of the tile used to model the background gradient
 */
class StarDetector(
    private val thresholdSigma: Double = 5.0,
    private val minPixels: Int = 3,
    private val maxPixels: Int = 2000,
    private val saturationLevel: Double = Double.MAX_VALUE,
    private val backgroundTile: Int = 64,
) {

    fun detect(plane: FloatArray, width: Int, height: Int): FrameStars {
        require(plane.size >= width * height) { "plane is smaller than ${width}x$height" }

        val background = BackgroundModel.fit(plane, width, height, backgroundTile)
        val noise = estimateNoise(plane, width, height, background)

        // A clipped frame has no stars in it, only edges of the clipping. Detecting on it
        // produces phantoms rather than nothing, because a saturated background makes the noise
        // estimate collapse to zero and the threshold with it.
        if (background.medianLevel >= saturationLevel) {
            return FrameStars(
                background = background.medianLevel,
                noise = noise,
                stars = emptyList(),
                saturatedFrame = true,
            )
        }

        val threshold = max(noise * thresholdSigma, MIN_ABSOLUTE_THRESHOLD)

        val stars = segment(plane, width, height, background, threshold)
        return FrameStars(
            background = background.medianLevel,
            noise = noise,
            stars = stars,
        )
    }

    /**
     * Sigma from the median absolute deviation of the background-subtracted frame. Stars bias a
     * standard deviation badly — a rich field would inflate sigma and hide the faint stars that
     * matter most for registration.
     */
    private fun estimateNoise(
        plane: FloatArray,
        width: Int,
        height: Int,
        background: BackgroundModel,
    ): Double {
        val samples = ArrayList<Double>(min(width * height / SAMPLE_STRIDE + 1, 200_000))
        var i = 0
        while (i < width * height) {
            val y = i / width
            val x = i % width
            samples += abs(plane[i] - background.at(x, y)).toDouble()
            i += SAMPLE_STRIDE
        }
        if (samples.isEmpty()) return 1.0
        samples.sort()
        val mad = samples[samples.size / 2]
        return max(mad * MAD_TO_SIGMA, MIN_NOISE)
    }

    /**
     * Flood-fills connected pixels above threshold. Iterative rather than recursive: a bright
     * nebula region can span tens of thousands of pixels and blow a call stack.
     */
    private fun segment(
        plane: FloatArray,
        width: Int,
        height: Int,
        background: BackgroundModel,
        threshold: Double,
    ): List<Star> {
        val visited = BooleanArray(width * height)
        val stack = IntArray(STACK_CAPACITY)
        val members = ArrayList<Int>(256)
        val stars = ArrayList<Star>()

        for (start in 0 until width * height) {
            if (visited[start]) continue
            val sx = start % width
            val sy = start / width
            if (plane[start] - background.at(sx, sy) < threshold) {
                visited[start] = true
                continue
            }

            members.clear()
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var regionSize = 0

            while (sp > 0) {
                val p = stack[--sp]
                regionSize++
                // Past the cap, keep flood-filling so the whole region is consumed — stopping
                // early would leave unvisited pixels that start fresh components, and a
                // rejected cloud would come back as a handful of accepted "stars".
                if (regionSize <= maxPixels) members += p
                val px = p % width
                val py = p / width
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = px + dx
                        val ny = py + dy
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                        val n = ny * width + nx
                        if (visited[n]) continue
                        if (plane[n] - background.at(nx, ny) < threshold) {
                            visited[n] = true
                            continue
                        }
                        visited[n] = true
                        if (sp < stack.size) stack[sp++] = n
                    }
                }
            }

            if (regionSize > maxPixels || regionSize < minPixels) continue
            stars += measure(plane, width, background, members)
        }
        return stars
    }

    private fun measure(
        plane: FloatArray,
        width: Int,
        background: BackgroundModel,
        members: List<Int>,
    ): Star {
        var flux = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var peak = Double.NEGATIVE_INFINITY
        var saturated = false

        for (p in members) {
            val x = p % width
            val y = p / width
            val v = plane[p] - background.at(x, y)
            flux += v
            sumX += v * x
            sumY += v * y
            if (plane[p] > peak) peak = plane[p].toDouble()
            if (plane[p] >= saturationLevel) saturated = true
        }

        val cx = if (flux > 0) sumX / flux else members.first().let { (it % width).toDouble() }
        val cy = if (flux > 0) sumY / flux else members.first().let { (it / width).toDouble() }

        // Second moments give the shape; a trailed star is an ellipse, a focused one a circle.
        var mxx = 0.0
        var myy = 0.0
        var mxy = 0.0
        var weightedRadius = 0.0
        for (p in members) {
            val x = p % width
            val y = p / width
            val v = max(plane[p] - background.at(x, y), 0.0f).toDouble()
            val dx = x - cx
            val dy = y - cy
            mxx += v * dx * dx
            myy += v * dy * dy
            mxy += v * dx * dy
            weightedRadius += v * sqrt(dx * dx + dy * dy)
        }
        if (flux > 0) {
            mxx /= flux
            myy /= flux
            mxy /= flux
        }

        // Half-flux radius as the flux-weighted mean radius — the definition used by the
        // desktop autofocus routines this has to be comparable with.
        val hfr = if (flux > 0) weightedRadius / flux else 0.0

        val half = (mxx + myy) / 2.0
        val diff = sqrt(((mxx - myy) / 2.0) * ((mxx - myy) / 2.0) + mxy * mxy)
        val major = half + diff
        val minor = max(half - diff, 0.0)
        val eccentricity = if (major > 0) sqrt(max(1.0 - minor / major, 0.0)) else 0.0

        return Star(
            x = cx,
            y = cy,
            flux = flux,
            peak = peak,
            hfr = hfr,
            eccentricity = eccentricity,
            pixelCount = members.size,
            saturated = saturated,
        )
    }

    private companion object {
        const val MAD_TO_SIGMA = 1.4826
        const val MIN_NOISE = 1e-6

        /**
         * The detection threshold can never fall below half an ADU.
         *
         * This used to be 1e-6, as a guard against dividing by a zero noise estimate — but a
         * threshold of 1e-6 ADU does not guard anything, it detects everything. The sensor
         * quantises to whole ADU; a blob standing half a count above its local background is
         * rounding, not a star. Half an ADU is the smallest floor that is a statement about the
         * data rather than about floating point.
         */
        const val MIN_ABSOLUTE_THRESHOLD = 0.5
        const val SAMPLE_STRIDE = 7
        const val STACK_CAPACITY = 8192
    }
}

/**
 * Tiled median background with bilinear interpolation between tile centres.
 *
 * A single global level is not good enough: light pollution puts a strong gradient across a
 * phone frame (FR-8.1 removes it properly later, but detection has to cope with it now), and a
 * flat threshold would find hundreds of "stars" in the bright corner and none in the dark one.
 */
class BackgroundModel private constructor(
    private val tileMedians: DoubleArray,
    private val tilesX: Int,
    private val tilesY: Int,
    private val tile: Int,
    val medianLevel: Double,
) {

    /** Interpolated background at a pixel. */
    fun at(x: Int, y: Int): Float {
        if (tilesX == 1 && tilesY == 1) return tileMedians[0].toFloat()

        val fx = (x.toDouble() - tile / 2.0) / tile
        val fy = (y.toDouble() - tile / 2.0) / tile

        // Anchor on a pair of adjacent tiles and let tx/ty run outside [0,1] near the frame
        // edge, so the gradient is *extrapolated* rather than flattened. Clamping instead
        // leaves a residual of half a tile's worth of gradient in the outer strip — under
        // light pollution that is tens of ADU, and it detects as a crowd of phantom stars.
        val x0 = if (tilesX >= 2) fx.toInt().coerceIn(0, tilesX - 2) else 0
        val y0 = if (tilesY >= 2) fy.toInt().coerceIn(0, tilesY - 2) else 0
        val x1 = min(x0 + 1, tilesX - 1)
        val y1 = min(y0 + 1, tilesY - 1)
        val tx = fx - x0
        val ty = fy - y0

        val top = tileMedians[y0 * tilesX + x0] * (1 - tx) + tileMedians[y0 * tilesX + x1] * tx
        val bottom = tileMedians[y1 * tilesX + x0] * (1 - tx) + tileMedians[y1 * tilesX + x1] * tx
        return (top * (1 - ty) + bottom * ty).toFloat()
    }

    companion object {
        fun fit(plane: FloatArray, width: Int, height: Int, tile: Int): BackgroundModel {
            val tilesX = max(1, (width + tile - 1) / tile)
            val tilesY = max(1, (height + tile - 1) / tile)
            val medians = DoubleArray(tilesX * tilesY)
            val scratch = FloatArray(tile * tile)

            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    var n = 0
                    val x0 = tx * tile
                    val y0 = ty * tile
                    val x1 = min(x0 + tile, width)
                    val y1 = min(y0 + tile, height)
                    for (y in y0 until y1) {
                        val row = y * width
                        for (x in x0 until x1) scratch[n++] = plane[row + x]
                    }
                    medians[ty * tilesX + tx] = if (n == 0) 0.0 else median(scratch, n)
                }
            }

            val overall = medians.copyOf()
            overall.sort()
            return BackgroundModel(
                tileMedians = medians,
                tilesX = tilesX,
                tilesY = tilesY,
                tile = tile,
                medianLevel = overall[overall.size / 2],
            )
        }

        private fun median(values: FloatArray, count: Int): Double {
            val copy = values.copyOf(count)
            copy.sort()
            return if (count % 2 == 1) {
                copy[count / 2].toDouble()
            } else {
                (copy[count / 2 - 1] + copy[count / 2]) / 2.0
            }
        }
    }
}
