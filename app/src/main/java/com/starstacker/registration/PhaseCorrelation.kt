package com.starstacker.registration

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * T-4.7 — finding the shift between two frames without using stars.
 *
 * ### Why this exists, measured rather than supposed
 *
 * Session `2026-09-06_0118` — a crescent moon over a harbour — had **34 of 67 lights rejected** as
 * unregisterable, and whole-image phase correlation puts every one of those 67 frames at a shift of
 * exactly **(0, 0)**. The session was aligned to the pixel and there was nothing to fail at.
 *
 * `AsterismMatcher` works by matching *triangles of point sources*, and that scene has none: the
 * detector's brightest returns all sit inside one ~30 px blob (the moon), and only **34% of
 * detections are stable to 3 px** between frames because most of the rest are shimmering water.
 * Detections grew 1297 → 1668 as glare thickened and RANSAC lost its quorum. DeepSkyStacker fails
 * on the same frames and fails worse — 0 of 67, and no `autosave.tif` at all.
 *
 * A method that reads the *whole image* rather than a point list cannot be defeated by a detection
 * set that reshuffles. That is the entire idea.
 *
 * ### Why phase correlation rather than plain cross-correlation
 *
 * Cross-correlation peaks where the images are brightest in common, so it drifts toward whatever is
 * bright — on a nightscape, the moon, which is the one thing in the frame that *is* moving.
 * Normalising each frequency by its own magnitude throws brightness away and keeps only phase, and
 * a pure translation is a pure phase ramp — so the answer comes back as a near-delta spike whose
 * height is a usable measure of how well the model fits.
 *
 * ### The three things that make it work in practice
 *
 * **A window.** The FFT treats the image as periodic, so a shifted scene has mismatched content at
 * opposite edges and that discontinuity produces a strong cross-shaped artefact along both axes —
 * which can beat the real peak. A Hann window tapers it away.
 *
 * **Downsampling.** A translation-only answer does not need 12.6 MP. Working at [SIZE]² costs a few
 * hundred thousand operations on a path that only runs when the primary method has already given
 * up, and sub-pixel interpolation recovers most of what the decimation cost.
 *
 * **A confidence, not just an answer.** Phase correlation always returns *something*. What
 * distinguishes a real translation from noise is that the peak stands far above everything else in
 * the surface, and [Result.peakRatio] is exactly that — which is also what keeps this from being
 * used to rescue a rotating star field, where the peak smears out and the ratio collapses.
 */
object PhaseCorrelation {

    /** Working size. A power of two, because the transform is radix-2. */
    const val SIZE = 512

    /**
     * How far the peak must stand above the rest of the surface to be believed.
     *
     * Measured against the synthetic nightscape in `PhaseCorrelationTest`, which is deliberately
     * the scene that defeats star matching — a textured landscape and one small bright object:
     *
     * | case | peak ratio |
     * |---|---|
     * | two frames of pure noise | **8.8** |
     * | a real translation, noise-free | 1 258 – 10 000 |
     *
     * Two orders of magnitude apart, so the threshold is not a delicate one. Fifty sits well clear
     * of what a mismatch produces while leaving room for a real frame degraded by cloud, and the
     * first draft's **4.0 would have accepted pure noise** — the number was guessed before it was
     * measured, and the measurement moved it by more than ten times.
     */
    const val MIN_PEAK_RATIO = 50.0

    /** Neighbourhood excluded when looking for the runner-up, so the peak's own skirt is not it. */
    private const val EXCLUSION_RADIUS = 4

    /** Reported ceiling. Past this the peak is as clean as the arithmetic can say. */
    const val MAX_PEAK_RATIO = 10_000.0

    data class Result(
        /** Shift in *source* pixels: how far the target has moved from the reference. */
        val dx: Double,
        val dy: Double,
        /** Peak height over the mean of the rest of the surface. See [MIN_PEAK_RATIO]. */
        val peakRatio: Double,
        val usable: Boolean,
    ) {
        val shiftPx: Double get() = hypot(dx, dy)

        fun describe(): String =
            "shift (%.2f, %.2f) px, peak %.1fx the surface%s".format(
                dx, dy, peakRatio, if (usable) "" else " — too weak to use",
            )
    }

    /**
     * The shift that takes [reference] onto [target].
     *
     * Both planes must be the same size. They are decimated to [SIZE]² internally, so the cost does
     * not depend on how big they are.
     */
    fun between(
        reference: FloatArray,
        target: FloatArray,
        width: Int,
        height: Int,
    ): Result {
        require(reference.size >= width * height) { "reference smaller than ${width}x$height" }
        require(target.size >= width * height) { "target smaller than ${width}x$height" }
        if (width < 2 || height < 2) return Result(0.0, 0.0, 0.0, usable = false)

        val a = prepare(reference, width, height)
        val b = prepare(target, width, height)

        // Cross-power spectrum, normalised per frequency: B · conj(A) / |B · conj(A)|.
        //
        // **B against A, not A against B**, and the order is the sign convention. `A · conj(B)`
        // peaks at the shift taking the *target* back onto the *reference*, which is the negative
        // of what every caller wants and is silent when wrong: a frame that moved right is
        // reported as having moved left, and the warp faithfully doubles the error.
        fft2(a.re, a.im, forward = true)
        fft2(b.re, b.im, forward = true)
        for (i in a.re.indices) {
            val cr = a.re[i] * b.re[i] + a.im[i] * b.im[i]
            val ci = b.im[i] * a.re[i] - b.re[i] * a.im[i]
            val mag = hypot(cr, ci)
            if (mag > 1e-12) {
                a.re[i] = cr / mag
                a.im[i] = ci / mag
            } else {
                // A frequency neither image carries says nothing about the shift. Zero rather than
                // a normalised nothing, which would be pure noise given equal weight to signal.
                a.re[i] = 0.0
                a.im[i] = 0.0
            }
        }
        fft2(a.re, a.im, forward = false)

        return peak(a.re, width, height)
    }

    private class Plane(val re: DoubleArray, val im: DoubleArray)

    /**
     * Decimates to [SIZE]², removes the mean, and applies a separable Hann window.
     *
     * The mean goes first because a constant offset is entirely in the zero frequency, where it
     * contributes nothing to the shift and everything to the normalisation.
     */
    private fun prepare(src: FloatArray, width: Int, height: Int): Plane {
        val re = DoubleArray(SIZE * SIZE)
        val im = DoubleArray(SIZE * SIZE)

        // Box-average decimation. Averaging rather than sampling, because a sampled decimation
        // aliases high frequencies down into the band the correlation actually uses.
        val sx = width.toDouble() / SIZE
        val sy = height.toDouble() / SIZE
        var sum = 0.0
        for (y in 0 until SIZE) {
            val y0 = (y * sy).toInt().coerceIn(0, height - 1)
            val y1 = ((y + 1) * sy).toInt().coerceIn(y0 + 1, height)
            for (x in 0 until SIZE) {
                val x0 = (x * sx).toInt().coerceIn(0, width - 1)
                val x1 = ((x + 1) * sx).toInt().coerceIn(x0 + 1, width)
                var acc = 0.0
                var n = 0
                for (yy in y0 until y1) {
                    val row = yy * width
                    for (xx in x0 until x1) {
                        val v = src[row + xx]
                        if (v.isFinite()) {
                            acc += v
                            n++
                        }
                    }
                }
                val value = if (n == 0) 0.0 else acc / n
                re[y * SIZE + x] = value
                sum += value
            }
        }

        val mean = sum / (SIZE * SIZE)
        val window = DoubleArray(SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / (SIZE - 1)) }
        for (y in 0 until SIZE) {
            val wy = window[y]
            for (x in 0 until SIZE) {
                re[y * SIZE + x] = (re[y * SIZE + x] - mean) * wy * window[x]
            }
        }
        return Plane(re, im)
    }

    /**
     * The correlation peak, to sub-pixel accuracy, scaled back to source pixels.
     *
     * A peak past the halfway point is a *negative* shift: the correlation surface is periodic, so
     * index `SIZE - 3` means −3, not +509. Getting this wrong does not fail loudly — it reports a
     * frame as having moved almost the width of the sensor.
     */
    private fun peak(surface: DoubleArray, width: Int, height: Int): Result {
        var best = Double.NEGATIVE_INFINITY
        var bestIndex = 0
        for (i in surface.indices) {
            if (surface[i] > best) {
                best = surface[i]
                bestIndex = i
            }
        }
        val px = bestIndex % SIZE
        val py = bestIndex / SIZE

        // How far the peak stands above everything that is not it.
        var sum = 0.0
        var count = 0
        for (y in 0 until SIZE) {
            val dy = wrapped(y - py)
            for (x in 0 until SIZE) {
                if (abs(dy) <= EXCLUSION_RADIUS && abs(wrapped(x - px)) <= EXCLUSION_RADIUS) continue
                sum += abs(surface[y * SIZE + x])
                count++
            }
        }
        val background = if (count == 0) 0.0 else sum / count
        // A *perfect* correlation leaves essentially no background at all, so a bare `best /
        // background` returns infinity there and — if the zero case is special-cased to 0.0 — the
        // best possible answer scores as the least confident one. The floor is relative to the
        // peak, so a clean delta saturates high instead of dividing by nothing.
        val ratio = when {
            best <= 1e-15 -> 0.0
            else -> (best / maxOf(background, best * 1e-4)).coerceAtMost(MAX_PEAK_RATIO)
        }

        // Parabolic interpolation through the peak and its two neighbours, per axis. The vertex of
        // a fit through three samples beats the sampled maximum by roughly half a cell, which after
        // the decimation is worth several source pixels.
        val subX = px + parabola(
            surface[py * SIZE + wrapIndex(px - 1)],
            best,
            surface[py * SIZE + wrapIndex(px + 1)],
        )
        val subY = py + parabola(
            surface[wrapIndex(py - 1) * SIZE + px],
            best,
            surface[wrapIndex(py + 1) * SIZE + px],
        )

        val scaleX = width.toDouble() / SIZE
        val scaleY = height.toDouble() / SIZE
        val dx = wrappedD(subX) * scaleX
        val dy = wrappedD(subY) * scaleY

        return Result(dx, dy, ratio, usable = ratio >= MIN_PEAK_RATIO)
    }

    /** Offset of the vertex from the centre sample, in cells, clamped to the sample it belongs to. */
    private fun parabola(left: Double, centre: Double, right: Double): Double {
        val denominator = left - 2 * centre + right
        if (abs(denominator) < 1e-15) return 0.0
        val offset = 0.5 * (left - right) / denominator
        return if (offset.isFinite() && abs(offset) <= 1.0) offset else 0.0
    }

    private fun wrapIndex(i: Int): Int = ((i % SIZE) + SIZE) % SIZE

    private fun wrapped(i: Int): Int = when {
        i > SIZE / 2 -> i - SIZE
        i < -SIZE / 2 -> i + SIZE
        else -> i
    }

    private fun wrappedD(v: Double): Double = if (v > SIZE / 2.0) v - SIZE else v

    // ------------------------------------------------------------------ the transform

    /** In-place 2D FFT of a [SIZE]² plane: every row, then every column. */
    private fun fft2(re: DoubleArray, im: DoubleArray, forward: Boolean) {
        val rowRe = DoubleArray(SIZE)
        val rowIm = DoubleArray(SIZE)

        for (y in 0 until SIZE) {
            val base = y * SIZE
            System.arraycopy(re, base, rowRe, 0, SIZE)
            System.arraycopy(im, base, rowIm, 0, SIZE)
            fft(rowRe, rowIm, forward)
            System.arraycopy(rowRe, 0, re, base, SIZE)
            System.arraycopy(rowIm, 0, im, base, SIZE)
        }
        for (x in 0 until SIZE) {
            for (y in 0 until SIZE) {
                rowRe[y] = re[y * SIZE + x]
                rowIm[y] = im[y * SIZE + x]
            }
            fft(rowRe, rowIm, forward)
            for (y in 0 until SIZE) {
                re[y * SIZE + x] = rowRe[y]
                im[y * SIZE + x] = rowIm[y]
            }
        }
        if (!forward) {
            val scale = 1.0 / (SIZE.toDouble() * SIZE)
            for (i in re.indices) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }

    /**
     * Iterative radix-2 Cooley–Tukey, in place.
     *
     * Written out rather than pulled in: the only transform this app needs is one fixed
     * power-of-two size on a fallback path, and a dependency for that would cost more to carry
     * than the twenty lines it replaces.
     */
    internal fun fft(re: DoubleArray, im: DoubleArray, forward: Boolean) {
        val n = re.size
        require(n and (n - 1) == 0) { "radix-2 needs a power of two, was $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = (if (forward) -2.0 else 2.0) * PI / len
            val wr = cos(angle)
            val wi = kotlin.math.sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vi = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nextRe = curRe * wr - curIm * wi
                    curIm = curRe * wi + curIm * wr
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Root-mean-square of a plane, for callers that want to know there is anything in it at all. */
    internal fun rms(values: FloatArray, count: Int): Double {
        var sum = 0.0
        var n = 0
        for (i in 0 until count) {
            val v = values[i]
            if (v.isFinite()) {
                sum += v.toDouble() * v
                n++
            }
        }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }
}
