package com.starstacker.edit

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * T-7.1 / FR-8.1 step 5 — the light-pollution gradient, modelled and subtracted.
 *
 * ### Why this is first, and why the requirements call it non-negotiable
 *
 * A phone shoots from wherever the person is standing, which is usually under a sky that is
 * brighter on one side than the other — a town on the horizon, a streetlight behind a hedge, the
 * moon out of frame. The result is a background that ramps across the image, and it is *far*
 * larger than the signal: the gradient here is tens of ADU across a frame whose faintest real
 * structure is a fraction of one.
 *
 * Everything after this is ruined by it. The autostretch works from the frame's median and MAD, so
 * a ramp inflates the spread and flattens the curve; the colour balance reads a background that is
 * not one colour; and the eye sees the ramp before it sees the sky. **Removing it is what makes the
 * stretch about the stars rather than about the streetlight.**
 *
 * ### A low-order polynomial, fitted to background samples
 *
 * The model is a polynomial in `x` and `y` — degree 1 is a plane, degree 2 adds curvature for a
 * glow in a corner. Deliberately **low order**: the gradient is a smooth, large-scale thing, and a
 * model flexible enough to follow the sky is also flexible enough to follow a galaxy and subtract
 * it. Degree is the one control an expert gets here, and the default is 2.
 *
 * ### The trap: fitting to the signal instead of the sky
 *
 * The samples must describe the *background*, and a naive grid of tile means describes the
 * background plus whatever was in the tile. Three things keep it honest:
 *
 * - **A low percentile within each tile, not the mean.** Stars and nebulosity only ever push a
 *   tile *up*, so the lower quartile of a tile is background wherever the tile is not wholly
 *   filled with signal.
 * - **Rejection against the fitted surface, iterated.** A tile sitting well above the model is
 *   signal, and it is dropped and the model refitted without it. Above, not either side: the
 *   asymmetry is the physics.
 * - **A refusal to extrapolate.** If too few tiles survive, no model is returned at all and the
 *   image is left alone. A gradient removal that has lost its footing does not fail quietly — it
 *   invents a surface and subtracts a target.
 */
object Gradient {

    /**
     * A fitted background surface, in the image's own units.
     *
     * Kept as coefficients rather than a raster so it can be reported, compared between runs, and
     * subtracted at any resolution.
     */
    data class Model(
        val degree: Int,
        val coefficients: DoubleArray,
        val width: Int,
        val height: Int,
        /** Tiles that survived rejection, and how many there were to begin with. */
        val used: Int,
        val offered: Int,
    ) {
        /** The modelled background at a pixel. */
        fun at(x: Int, y: Int): Double {
            // Normalised to [-1, 1] so the powers stay conditioned — a degree-2 fit in raw pixel
            // coordinates has terms of order 10^7 beside terms of order 1, and the normal
            // equations lose their precision to it.
            val nx = if (width > 1) 2.0 * x / (width - 1) - 1.0 else 0.0
            val ny = if (height > 1) 2.0 * y / (height - 1) - 1.0 else 0.0
            var value = 0.0
            var i = 0
            for (py in 0..degree) {
                for (px in 0..degree - py) {
                    value += coefficients[i++] * power(nx, px) * power(ny, py)
                }
            }
            return value
        }

        fun describe(): String =
            "degree $degree, $used of $offered tiles"

        override fun equals(other: Any?): Boolean =
            other is Model && degree == other.degree && coefficients.contentEquals(other.coefficients)

        override fun hashCode(): Int = 31 * degree + coefficients.contentHashCode()
    }

    /**
     * Fits a background model to one channel.
     *
     * @param plane interleaved samples; [stride] is the distance between samples of this channel,
     *   and [offset] where it starts. Lets the fit read one channel of an RGB master in place.
     * @return null when too little of the frame looks like background to fit anything — see the
     *   class note on refusing to extrapolate.
     */
    fun fit(
        plane: FloatArray,
        width: Int,
        height: Int,
        offset: Int = 0,
        stride: Int = 1,
        degree: Int = DEFAULT_DEGREE,
        tiles: Int = DEFAULT_TILES,
    ): Model? {
        require(degree in 1..MAX_DEGREE) { "degree $degree is outside 1..$MAX_DEGREE" }
        if (width <= 1 || height <= 1) return null

        val samples = backgroundSamples(plane, width, height, offset, stride, tiles)
        val terms = termCount(degree)
        if (samples.size < terms * MIN_TILES_PER_TERM) return null

        var kept = samples
        var coefficients = solve(kept, degree, width, height) ?: return null

        // Two rejection passes. The first model is pulled up by whatever signal is in the frame;
        // dropping the tiles it fits worst and refitting is what separates a sky from a subject.
        repeat(REJECTION_PASSES) {
            val residuals = kept.map { it.value - evaluate(coefficients, degree, it.x, it.y, width, height) }
            val sigma = robustSigma(residuals)
            if (sigma <= 0.0) return@repeat
            // Only tiles *above* the surface are dropped: light adds, so a tile below the model is
            // background that happens to be dark, and a tile above it may be a galaxy.
            val survivors = kept.filterIndexed { i, _ -> residuals[i] <= REJECTION_SIGMA * sigma }
            if (survivors.size < terms * MIN_TILES_PER_TERM) return@repeat
            kept = survivors
            coefficients = solve(kept, degree, width, height) ?: return@repeat
        }

        return Model(degree, coefficients, width, height, kept.size, samples.size)
    }

    /**
     * Subtracts [model] from one channel in place, preserving the overall level.
     *
     * **The mean of the model is added back**, so the image keeps the brightness it had. Removing
     * the surface outright would drag the background to zero, which is a different operation — and
     * one that would break the autostretch that follows, since it works from the background level.
     */
    fun subtract(
        plane: FloatArray,
        width: Int,
        height: Int,
        model: Model,
        offset: Int = 0,
        stride: Int = 1,
    ) {
        var level = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) level += model.at(x, y)
        }
        level /= (width.toLong() * height).toDouble()

        for (y in 0 until height) {
            val row = y.toLong() * width
            for (x in 0 until width) {
                val i = offset + ((row + x) * stride).toInt()
                plane[i] = (plane[i] - model.at(x, y) + level).toFloat()
            }
        }
    }

    // --------------------------------------------------------------------------------- internals

    private data class Sample(val x: Int, val y: Int, val value: Double)

    /**
     * One background estimate per tile: the [BACKGROUND_PERCENTILE] of the tile's samples.
     *
     * A percentile rather than a mean or a median because signal is one-sided. The lower quartile
     * of a tile containing a few stars is still sky; its mean is not.
     */
    private fun backgroundSamples(
        plane: FloatArray,
        width: Int,
        height: Int,
        offset: Int,
        stride: Int,
        tiles: Int,
    ): List<Sample> {
        // The grid has to adapt to the frame, not the other way round. Twenty-four tiles across a
        // 12 MP master is 19 000 samples each; across a 96-pixel test frame it is twelve, below the
        // floor, and *every* tile is rejected — the fit then refuses on a frame with a perfectly
        // good gradient in it. Cap the grid so a tile always holds enough to take a percentile of.
        val maxAcross = sqrt(width.toDouble() * height / MIN_TILE_SAMPLES).toInt()
        val across = tiles.coerceIn(2, maxAcross.coerceAtLeast(2))
        val tileW = (width + across - 1) / across
        val tileH = (height + across - 1) / across
        if (tileW <= 0 || tileH <= 0) return emptyList()

        val out = mutableListOf<Sample>()
        val scratch = DoubleArray(tileW * tileH)
        var top = 0
        while (top < height) {
            var left = 0
            while (left < width) {
                var n = 0
                var y = top
                while (y < minOf(top + tileH, height)) {
                    val row = y.toLong() * width
                    var x = left
                    while (x < minOf(left + tileW, width)) {
                        val v = plane[offset + ((row + x) * stride).toInt()]
                        if (v.isFinite()) scratch[n++] = v.toDouble()
                        x++
                    }
                    y++
                }
                if (n >= MIN_TILE_SAMPLES) {
                    java.util.Arrays.sort(scratch, 0, n)
                    val at = ((n - 1) * BACKGROUND_PERCENTILE).toInt().coerceIn(0, n - 1)
                    out += Sample(
                        x = left + minOf(tileW, width - left) / 2,
                        y = top + minOf(tileH, height - top) / 2,
                        value = scratch[at],
                    )
                }
                left += tileW
            }
            top += tileH
        }
        return out
    }

    private fun termCount(degree: Int): Int = (degree + 1) * (degree + 2) / 2

    private fun power(v: Double, p: Int): Double {
        var r = 1.0
        repeat(p) { r *= v }
        return r
    }

    private fun basis(x: Int, y: Int, degree: Int, width: Int, height: Int): DoubleArray {
        val nx = if (width > 1) 2.0 * x / (width - 1) - 1.0 else 0.0
        val ny = if (height > 1) 2.0 * y / (height - 1) - 1.0 else 0.0
        val terms = DoubleArray(termCount(degree))
        var i = 0
        for (py in 0..degree) {
            for (px in 0..degree - py) {
                terms[i++] = power(nx, px) * power(ny, py)
            }
        }
        return terms
    }

    private fun evaluate(
        coefficients: DoubleArray,
        degree: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Double {
        val terms = basis(x, y, degree, width, height)
        var v = 0.0
        for (i in terms.indices) v += coefficients[i] * terms[i]
        return v
    }

    /** Least squares by normal equations and Gaussian elimination. Six unknowns at degree 2. */
    private fun solve(samples: List<Sample>, degree: Int, width: Int, height: Int): DoubleArray? {
        val n = termCount(degree)
        val a = Array(n) { DoubleArray(n + 1) }
        for (s in samples) {
            val t = basis(s.x, s.y, degree, width, height)
            for (i in 0 until n) {
                for (j in 0 until n) a[i][j] += t[i] * t[j]
                a[i][n] += t[i] * s.value
            }
        }

        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < SINGULAR) return null
            val swap = a[col]; a[col] = a[pivot]; a[pivot] = swap
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col..n) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    /** MAD-derived, because the residuals being measured contain the outliers being looked for. */
    private fun robustSigma(residuals: List<Double>): Double {
        if (residuals.isEmpty()) return 0.0
        val sorted = residuals.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = residuals.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        if (mad > 0.0) return MAD_TO_SIGMA * mad
        // A perfectly flat set of residuals has no spread to reject against, and a zero threshold
        // would drop every tile above the median. Fall back to the plain deviation.
        val mean = residuals.average()
        val variance = residuals.sumOf { (it - mean) * (it - mean) } / residuals.size
        return sqrt(variance)
    }

    /**
     * Fourth order, and that is the lens rather than a preference.
     *
     * A phone's background is dominated by **vignetting**, not by the streetlight: measured on the
     * first real session, the sky reads **21.2 ADU at the centre and 4.8 in the corners** — a
     * fourfold radial falloff on a sky whose own noise is 6 ADU. Lens falloff follows the `cos⁴`
     * law, so a fourth-order surface is the lowest one that can actually describe it, and the
     * residuals say so plainly:
     *
     * | degree | residual rms | peak to peak |
     * |---|---|---|
     * | 1 | 4.74 ADU | 19.4 |
     * | 2 | 1.73 ADU | 8.5 |
     * | 3 | 1.24 ADU | 7.9 |
     * | **4** | **0.55 ADU** | **4.0** |
     *
     * Degree 2 leaves 8.5 ADU of structure under a 12 ADU sky, which is most of the picture. The
     * step at 4 is not the fit getting lucky with more freedom; it is the order at which the model
     * can finally hold the shape the physics makes.
     *
     * **The proper fix is a flat field**, which measures the falloff instead of inferring it — that
     * is Phase 6's T-8.3, and until it exists this is what stands in. A flat would also let this
     * drop back to the low order the *sky* actually needs.
     */
    const val DEFAULT_DEGREE = 4

    /**
     * Fourth order is also the ceiling, because past the `cos⁴` the freedom stops buying background
     * and starts buying whatever is in the frame. A fifth-order surface can follow a galaxy.
     */
    const val MAX_DEGREE = 4

    /** Tiles across the frame. 24 gives ~576 background samples for six unknowns. */
    const val DEFAULT_TILES = 24

    /** The lower quartile of a tile is sky wherever the tile is not entirely signal. */
    private const val BACKGROUND_PERCENTILE = 0.25

    private const val REJECTION_PASSES = 2
    private const val REJECTION_SIGMA = 2.5
    private const val MIN_TILES_PER_TERM = 3
    private const val MIN_TILE_SAMPLES = 16
    private const val MAD_TO_SIGMA = 1.4826
    private const val SINGULAR = 1e-12
}
