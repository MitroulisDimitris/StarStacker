package com.starstacker.stacking

import android.util.Log
import com.starstacker.registration.RigidTransform
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * T-5.1 / **D-7** — the only file in this app that knows OpenCV exists.
 *
 * ### Why it is wrapped rather than used directly
 *
 * D-7 sanctions OpenCV *at Phase 3* and T-5.1 scopes it to **warp and transform primitives only**.
 * A scope like that is a sentence in a document until something enforces it, and what enforces it
 * is that `org.opencv` is imported here and nowhere else. The rest of the codebase speaks
 * `FloatArray`, `ShortArray` and [RigidTransform]; if the dependency ever has to go, the reversal
 * is this file plus its call sites, which is exactly the cost the plan claims for it.
 *
 * It also stops the familiar drift where "we have OpenCV anyway" quietly becomes the answer to
 * detection, thresholding and statistics — all of which are Kotlin here by deliberate choice
 * (§12.1), and all of which are already built and tested.
 *
 * ### Loaded lazily, so shooting never pays for it
 *
 * `initLocal()` maps tens of megabytes of native library. Capture, framing, focus and registration
 * have no use for any of it, and a session is the one part of this app that runs for 45 minutes on
 * a battery. So the library loads on **first stacking call** rather than at startup, and
 * [available] is the honest answer to "can this device do it" rather than an assumption.
 */
object Resample : TiledStacker.Resampler {

    /**
     * True once the native library is mapped. **Attempting the load is the only way to know** —
     * a missing or mismatched `.so` fails here rather than at the first pixel, which is where a
     * stacking service can still report it instead of dying mid-frame.
     */
    val available: Boolean by lazy {
        runCatching { OpenCVLoader.initLocal() }
            .onFailure { Log.e(TAG, "OpenCV failed to load", it) }
            .getOrDefault(false)
            .also { Log.i(TAG, if (it) "OpenCV ${'$'}{org.opencv.core.Core.VERSION} ready" else "OpenCV unavailable") }
    }

    /**
     * Resamples [src] so that a frame is carried **back into reference coordinates**.
     *
     * [transform] is what registration produced: it maps **reference → this frame**. Bringing the
     * frame home is therefore the *inverse*, which is what `WARP_INVERSE_MAP` asks OpenCV for —
     * given a matrix in the reference→frame direction, it walks each destination pixel through the
     * matrix to find its source. Handing it the already-inverted matrix instead would work and
     * would invert twice the day someone "simplified" it.
     *
     * Getting the direction wrong does not throw. It produces a stack that drifts the wrong way and
     * looks like poor tracking — the same class of silent error as §1.27's minus sign and §1.28's
     * coordinate composition, and the reason the direction is spelled out here rather than implied.
     *
     * @param border what to write where the frame does not cover the reference. `NaN` would poison
     *   the accumulator's arithmetic; zero would be a real measurement of darkness that never
     *   happened. The caller gets [uncovered] to mask with instead.
     */
    fun warpToReference(
        src: FloatArray,
        width: Int,
        height: Int,
        transform: RigidTransform,
        dst: FloatArray,
        border: Double = UNCOVERED,
    ): Boolean {
        require(src.size >= width * height) { "source smaller than ${width}x$height" }
        require(dst.size >= width * height) { "destination smaller than ${width}x$height" }
        if (!available) return false

        val source = Mat(height, width, CvType.CV_32F)
        val warped = Mat(height, width, CvType.CV_32F)
        val matrix = Mat(2, 3, CvType.CV_64F)
        try {
            source.put(0, 0, src)
            val m = transform.toMatrix()
            // Row-major 2x3: [ a b tx ; c d ty ]. RigidTransform.toMatrix returns
            // [a, b, c, d, tx, ty] with x' = a*x + b*y + tx, so the two orders differ and this is
            // the one place that has to know it.
            matrix.put(0, 0, m[0], m[1], m[4], m[2], m[3], m[5])

            Imgproc.warpAffine(
                source,
                warped,
                matrix,
                source.size(),
                Imgproc.INTER_CUBIC or Imgproc.WARP_INVERSE_MAP,
                org.opencv.core.Core.BORDER_CONSTANT,
                Scalar(border),
            )
            warped.get(0, 0, dst)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "warp failed", t)
            return false
        } finally {
            source.release()
            warped.release()
            matrix.release()
        }
    }

    // ------------------------------------------------------- TiledStacker.Resampler, T-5.3's seam

    /**
     * [TiledStacker.Resampler]'s debayer: the same call, taking the CFA codes the frame reported
     * rather than a pattern already looked up.
     *
     * **This assumes the band starts on an even row**, which is [TiledStacker.sourceRowsFor]'s
     * job — a band beginning on an odd row is the frame's pattern with its rows swapped, and would
     * demosaic as a different arrangement entirely.
     */
    override fun debayer(
        cfa: ShortArray,
        width: Int,
        height: Int,
        cfaCodes: List<Int>,
        out: FloatArray,
    ): Boolean {
        val pattern = BayerPattern.of(cfaCodes)
        if (pattern == null) {
            Log.e(TAG, "unrecognised CFA arrangement $cfaCodes")
            return false
        }
        return debayer(cfa, width, height, pattern, out)
    }

    /**
     * [warpToReference] for one band of a frame, into a separate output band.
     *
     * ### Two row offsets, not one, and that is the fix from §1.38
     *
     * The source band has to be *taller* than the output: under rotation the rows feeding output
     * row `k` come from a range spread across `width × sin θ` rows. While a tile warped its own
     * band into itself the two origins were the same number, and the destination was as tall as
     * the source — so a loop producing 8 output rows warped all 328 rows of the band it had read
     * and threw away 320 of them. Measured at 41× the necessary work.
     *
     * Separating them makes the warp cost proportional to the **output**, which is what it should
     * always have been.
     *
     * ### The row offsets, worked through
     *
     * A transform is expressed against the **whole frame**; a band does not know where it sits.
     * Warping a band with the frame's matrix unchanged would treat the band's first row as row
     * zero, so every tile but the first would be shifted by its own offset — a master built out of
     * bands that each drifted a different distance.
     *
     * Output row `k` is whole-frame row `k + dstTop`; the value it wants sits at `T` of that, and
     * within the source band that is `T_y(...) − srcTop`. Expanding the affine form:
     *
     * ```
     * T_x(x, k + dstTop) = a·x + b·k + (tx + b·dstTop)
     * T_y(x, k + dstTop) = c·x + d·k + (ty + d·dstTop − srcTop)
     * ```
     *
     * So only the translation moves, and **the two offsets enter differently**: `dstTop` through
     * the rotation, `srcTop` as a plain subtraction. Setting them equal recovers the old
     * `(d − 1)·r` form, which is why that expression looked like a typo and was not. Getting either
     * wrong leaves a stack that is right at the top of every tile and slides towards the bottom.
     */
    override fun warpBand(
        src: FloatArray,
        width: Int,
        srcRows: Int,
        srcTop: Int,
        channels: Int,
        dstRows: Int,
        dstTop: Int,
        transform: RigidTransform,
        out: FloatArray,
    ): Boolean {
        require(src.size >= width * srcRows * channels) { "source smaller than ${width}x$srcRows" }
        require(out.size >= width * dstRows * channels) { "destination smaller than ${width}x$dstRows" }
        if (!available) return false

        val type = CvType.CV_32FC(channels)
        val source = Mat(srcRows, width, type)
        val warped = Mat(dstRows, width, type)
        val matrix = Mat(2, 3, CvType.CV_64F)
        try {
            source.put(0, 0, src)
            val m = bandMatrix(transform, dstTop, srcTop)
            matrix.put(0, 0, m[0], m[1], m[2], m[3], m[4], m[5])

            Imgproc.warpAffine(
                source,
                warped,
                matrix,
                // The *destination* size, which is the whole point: the source band is tall enough
                // to cover the rotation and the output is only the rows being produced. Passing
                // the source size here is what made the old loop warp 328 rows to use 8.
                warped.size(),
                Imgproc.INTER_CUBIC or Imgproc.WARP_INVERSE_MAP,
                org.opencv.core.Core.BORDER_CONSTANT,
                Scalar(UNCOVERED, UNCOVERED, UNCOVERED, UNCOVERED),
            )
            warped.get(0, 0, out)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "band warp failed", t)
            return false
        } finally {
            source.release()
            warped.release()
            matrix.release()
        }
    }

    /**
     * The warp matrix for a band starting at [rowOffset], in OpenCV's row-major 2×3 order:
     * `[a, b, tx, c, d, ty]`.
     *
     * Separated from [warpBand] so it can be tested without OpenCV, which is the only reason it is
     * a function rather than four lines inline. The arithmetic is worked through on [warpBand]; the
     * short version is that both the source and destination bands cover the same rows, so the
     * translation absorbs `b·r` in x and `(d − 1)·r` in y — the `−1` being the step back into band
     * coordinates, and the half that a reader will assume is a typo.
     */
    fun bandMatrix(transform: RigidTransform, dstTop: Int, srcTop: Int = dstTop): DoubleArray {
        val m = transform.toMatrix()
        val d = dstTop.toDouble()
        val srcRow = srcTop.toDouble()
        // Output row k is whole-frame row (dstTop + k); the value wanted lives at T(that), which
        // in the source band sits at T_y(...) - srcTop. The two offsets were the same number while
        // a tile warped its own band into itself; the warp-once pass reads a tall source band and
        // writes a short output tile, so they part company.
        return doubleArrayOf(
            m[0], m[1], m[4] + m[1] * d,
            m[2], m[3], m[5] + m[3] * d - srcRow,
        )
    }

    /**
     * Demosaics a CFA frame to three planes.
     *
     * **Bilinear, not edge-aware, and that is a choice rather than an omission.** OpenCV's `_VNG`
     * and `_EA` variants are built for terrestrial edges, and they work by interpolating along
     * detected gradients — which *correlates the noise between neighbouring pixels*. T-5.4's
     * sigma-clipped mean assumes pixels are independent samples, so a clever demosaic would quietly
     * degrade the rejection that does the actual work of stacking. Stars are point sources on a
     * dark field; there are no edges here worth being clever about.
     *
     * Revisit only with a measurement: same subs, both kernels, compare the master's SNR and star
     * FWHM (T-5.7's method).
     */
    fun debayer(
        cfa: ShortArray,
        width: Int,
        height: Int,
        pattern: BayerPattern,
        dst: FloatArray,
    ): Boolean {
        require(cfa.size >= width * height) { "source smaller than ${width}x$height" }
        require(dst.size >= width * height * 3) { "destination needs three planes" }
        if (!available) return false

        val source = Mat(height, width, CvType.CV_16UC1)
        val rgb = Mat()
        val floats = Mat()
        try {
            source.put(0, 0, cfa)
            Imgproc.cvtColor(source, rgb, pattern.openCvCode)
            rgb.convertTo(floats, CvType.CV_32F)
            floats.get(0, 0, dst)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "debayer failed", t)
            return false
        } finally {
            source.release()
            rgb.release()
            floats.release()
        }
    }

    /**
     * The four Bayer arrangements, mapped to OpenCV's names.
     *
     * **OpenCV names its Bayer codes after the second row's second pixel**, not the first row's
     * first, which is the opposite of how `CFAPattern` in a DNG is written and how
     * [com.starstacker.stars.CfaBinner] reads it. So `GRBG` in the DNG is `BayerGB` to OpenCV. The
     * mapping is stated here once, with that warning attached, because getting it wrong swaps red
     * and blue in the master and nothing in a linear astro frame makes that obvious.
     */
    enum class BayerPattern(val openCvCode: Int) {
        RGGB(Imgproc.COLOR_BayerBG2RGB),
        GRBG(Imgproc.COLOR_BayerGB2RGB),
        GBRG(Imgproc.COLOR_BayerGR2RGB),
        BGGR(Imgproc.COLOR_BayerRG2RGB),
        ;

        companion object {
            /** From `CfaBinner`'s codes: `0 = red, 1 = green, 2 = blue`, row-major over 2x2. */
            fun of(cfaCodes: List<Int>): BayerPattern? = when (cfaCodes) {
                listOf(0, 1, 1, 2) -> RGGB
                listOf(1, 0, 2, 1) -> GRBG
                listOf(1, 2, 0, 1) -> GBRG
                listOf(2, 1, 1, 0) -> BGGR
                else -> null
            }
        }
    }

    /**
     * Written where a warped frame does not cover the reference.
     *
     * Deliberately a value no sensor can produce. Zero would be indistinguishable from a genuinely
     * dark pixel and would drag the mean down at every frame edge; `NaN` propagates through the
     * accumulator's arithmetic and destroys the tile rather than one pixel of it.
     */
    const val UNCOVERED = -1.0

    private const val TAG = "Resample"
}
