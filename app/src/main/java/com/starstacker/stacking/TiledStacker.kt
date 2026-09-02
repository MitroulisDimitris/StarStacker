package com.starstacker.stacking

import com.starstacker.registration.RigidTransform

/**
 * T-5.3 / FR-7.6 — the tiled accumulator: load tile T across all N frames, combine, write, advance.
 *
 * ### Why tiles, and what the constraint actually is
 *
 * FR-7.6 calls this **memory-bound, not compute-bound**, and the arithmetic says why. A finished
 * 12.6 MP master in three channels is 151 MB of `FloatArray` before anything else exists — and
 * sigma clipping (T-5.4) needs *every frame's* value for a pixel at once to decide which to reject,
 * so a whole-frame approach would want `12.6M × 150 × 4` bytes: **7.5 GB**. Neither number is
 * available on a phone.
 *
 * Tiling turns that into a knob. The sample buffer is `tilePixels × frames`, so the tile is chosen
 * to fit a budget rather than picked for looking tidy — see [tileRowsFor]. What comes out is the
 * same master either way; what changes is whether the process survives producing it.
 *
 * ### The order, and where the tile lives
 *
 * Per FR-8.1: calibrate on CFA, repair hot pixels, debayer, warp into the reference, accumulate.
 * The **tile is a band of the output** — the reference frame's rows — and every frame is asked for
 * the source rows that band needs. Under rotation a band of output maps to a slightly taller,
 * sheared band of input, so [sourceRowsFor] adds the margin that rotation demands rather than
 * assuming rows line up. They do not, and a stack built as though they did loses a sliver at every
 * tile boundary — invisible per tile, and a set of faint horizontal seams across the master.
 *
 * ### Everything is injected, for two separate reasons
 *
 * [Frames] hides where pixels come from, so the machinery can be driven by synthetic data in a JVM
 * test and by `DngReader.Rows` in the app. [Resampler] hides the warp and debayer, and that one is
 * not merely tidiness: **OpenCV cannot load off-device** (§1.31), so without it none of this loop
 * could be tested anywhere but on a phone.
 */
class TiledStacker(
    private val frames: Frames,
    private val resampler: Resampler,
    // T-5.4's sigma-clipped mean, per instance because it carries a scratch buffer and its own
    // rejection counters. A shared default would have every stack writing into one set of stats.
    private val combiner: Combiner = Combine.SigmaClip(),
    private val memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET,
) {
    /** Where frames come from. One implementation reads DNGs; the test's makes them up. */
    interface Frames {
        val count: Int
        val width: Int
        val height: Int

        /** The 2×2 CFA pattern, row-major: `0 = red, 1 = green, 2 = blue`. */
        val cfaCodes: List<Int>

        /** Registration's answer for frame [index], or null if it is the reference. */
        fun transform(index: Int): RigidTransform?

        /** Calibration masters, already built. Absent masters are pass-through (T-5.2). */
        val masters: Calibration.Masters

        val blackLevel: Double

        /**
         * Decodes rows `[fromRow, fromRow + rowCount)` of frame [index] into [into].
         * @return rows actually written, fewer than asked at the bottom edge.
         */
        fun rows(index: Int, fromRow: Int, rowCount: Int, into: ShortArray): Int

        /**
         * T-5.5 — how much frame [index] counts for, in `(0, 1]`.
         *
         * Defaults to 1 for every frame, which is the unweighted stack and the path everything
         * before T-5.5 took. A source that returns 1 everywhere costs nothing extra: the loop
         * checks once, up front, and never allocates the parallel index array that weighting
         * needs.
         */
        fun weight(index: Int): Float = 1f
    }

    /**
     * The two operations that need OpenCV, behind an interface so the loop above does not.
     *
     * See §1.31: the native library is an Android `.so`, so a JVM test cannot call it. Injecting it
     * is the difference between a stacking loop with tests and one verifiable only on a phone.
     */
    interface Resampler {
        /** CFA band → three interleaved float channels, same dimensions. */
        fun debayer(cfa: ShortArray, width: Int, height: Int, cfaCodes: List<Int>, out: FloatArray): Boolean

        /**
         * Carries [src] back into reference coordinates. [rowOffset] is the band's first row in
         * whole-frame coordinates, because a transform is expressed against the frame and a band
         * does not know where it sits.
         */
        fun warpBand(
            src: FloatArray,
            width: Int,
            height: Int,
            channels: Int,
            rowOffset: Int,
            transform: RigidTransform,
            out: FloatArray,
        ): Boolean
    }

    /**
     * How N samples of one pixel become one. [Combine] holds the implementations; the default is
     * T-5.4's sigma-clipped mean.
     *
     * The contract has two halves the implementations rely on. **Only `samples[0, count)` is
     * valid** — the tail is whatever the previous pixel left there, and a combiner that reads past
     * `count` produces a plausible master that is wrong everywhere the coverage was partial. And
     * **the array is scratch**: a combiner may reorder it, which is what lets a median be selected
     * rather than sorted out of a copy, 37.8 million times per master.
     */
    fun interface Combiner {
        /**
         * @param samples the pixel's values, one per frame that covered it. Finite — the gather
         *   loop drops non-finite values and the uncovered sentinel — and free to be reordered.
         * @param count how many of [samples] are valid; the rest are frames that did not cover.
         */
        fun combine(samples: FloatArray, count: Int): Float

        companion object {
            /**
             * The plain mean — correct, and deliberately naive: it keeps satellites, aircraft and
             * cosmic rays, which is exactly what [Combine.SigmaClip] is for. It stays because it is
             * the deepest possible result on clean data, because it is what a stack compared
             * against a desktop tool's "average" setting needs, and because the tests that check
             * the *machinery* should not have rejection running underneath them.
             */
            val Mean = Combiner { samples, count ->
                if (count == 0) {
                    Float.NaN
                } else {
                    var sum = 0.0
                    for (i in 0 until count) sum += samples[i]
                    (sum / count).toFloat()
                }
            }
        }
    }

    data class Progress(val tile: Int, val tiles: Int, val rowsDone: Int, val rows: Int)

    /**
     * Stacks everything into [master], three interleaved channels at full frame size.
     *
     * @return false if any tile failed. A partial master is worse than none: it looks like an
     *   image and is wrong in a band.
     *
     * @param cancelled consulted **before each tile**, so a stack stops within one tile of being
     *   asked rather than at the end. That distinction is the whole value of the parameter: a
     *   150-frame stack is minutes, and the reason someone presses cancel is usually that the
     *   phone is hot — finishing the work and then discarding it answers neither the request nor
     *   the reason for it. A cancelled stack returns false, like any other incomplete one; the
     *   caller knows which it asked for.
     */
    fun stack(
        master: FloatArray,
        cancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): Boolean {
        val w = frames.width
        val h = frames.height
        require(master.size >= w * h * CHANNELS) { "master needs ${w * h * CHANNELS} floats" }
        if (frames.count == 0) return false

        // T-5.5, resolved before the tile height because the tile height depends on it: weighting
        // costs a parallel index array the size of the sample store, so it halves the tile.
        // Decided once for the whole stack rather than per pixel, and a stack with nothing to
        // weight — every stack before T-5.5, and every session whose log has no quality metrics —
        // pays neither the memory nor the branch.
        weights = FloatArray(frames.count) { frames.weight(it) }
        weighted = combiner is Combine.Weighted && weights.any { it != 1f }

        val tileRows = tileRowsFor(w, frames.count, memoryBudgetBytes, weighted)
        val margin = marginFor()
        val bandRows = tileRows + 2 * margin

        // Every buffer this loop will ever need, allocated once (FR-12.2). Nothing below allocates.
        val cfa = ShortArray(w * bandRows)
        val calibrated = FloatArray(w * bandRows)
        val calibratedShorts = ShortArray(w * bandRows)
        val colour = FloatArray(w * bandRows * CHANNELS)
        val warped = FloatArray(w * bandRows * CHANNELS)
        val samples = FloatArray(frames.count)
        val sampleFrames = if (weighted) IntArray(frames.count) else null

        val tiles = (h + tileRows - 1) / tileRows
        var tile = 0
        var top = 0
        while (top < h) {
            if (cancelled()) return false
            val rows = minOf(tileRows, h - top)
            if (!stackTile(top, rows, margin, bandRows, cfa, calibrated, calibratedShorts, colour, warped, samples, sampleFrames, master)) {
                return false
            }
            tile++
            top += rows
            onProgress(Progress(tile, tiles, top, h))
        }
        return true
    }

    private fun stackTile(
        top: Int,
        rows: Int,
        margin: Int,
        bandRows: Int,
        cfa: ShortArray,
        calibrated: FloatArray,
        calibratedShorts: ShortArray,
        colour: FloatArray,
        warped: FloatArray,
        samples: FloatArray,
        sampleFrames: IntArray?,
        master: FloatArray,
    ): Boolean {
        val w = frames.width
        val h = frames.height

        // Per-pixel sample lists for this tile, one entry per frame that covered it.
        val tilePixels = rows * w * CHANNELS
        // `tileStore` has already bounded `tilePixels * frames` to fit an Int, so the flat index
        // below cannot overflow — the check lives there rather than in this loop, which runs once
        // per pixel per frame and should carry no arithmetic it does not need.
        val store = tileStore(tilePixels)
        val counts = tileCounts(tilePixels)
        java.util.Arrays.fill(counts, 0)
        // Parallel to `store`, and only when weighting is on. A sample's value is useless for
        // weighting without knowing which frame produced it, and the combiner reorders both.
        val origin = if (weighted) tileOrigin(tilePixels) else null

        for (f in 0 until frames.count) {
            val band = sourceRowsFor(top, rows, margin, h)
            val got = frames.rows(f, band.first, band.second, cfa)
            if (got <= 0) continue

            // 1-2. Calibrate on CFA, before debayer (FR-8.1, T-5.2). The band's origin goes with
            // it: the masters are whole-frame and this is one band of the light.
            Calibration.apply(
                cfa,
                frames.masters,
                frames.blackLevel,
                calibrated,
                frames.cfaCodes,
                band.first,
                got,
            )
            // The debayer takes integers; calibration produced floats that may be negative. Rounding
            // back is lossy by well under an ADU and keeps the CFA path honest — the alternative is
            // a float demosaic this app does not have.
            for (i in 0 until w * got) {
                calibratedShorts[i] = calibrated[i].toInt().coerceIn(0, 65535).toShort()
            }

            // 3. Debayer.
            if (!resampler.debayer(calibratedShorts, w, got, frames.cfaCodes, colour)) return false

            // 4. Into reference coordinates.
            val source: FloatArray = frames.transform(f)?.let { t ->
                if (!resampler.warpBand(colour, w, got, CHANNELS, band.first, t, warped)) {
                    return false
                }
                warped
            } ?: colour

            // Gather this frame's contribution to the tile's rows.
            val skip = top - band.first
            for (r in 0 until rows) {
                val srcRow = r + skip
                if (srcRow < 0 || srcRow >= got) continue
                for (c in 0 until w * CHANNELS) {
                    val v = source[srcRow * w * CHANNELS + c]
                    if (!v.isFinite() || v == Resample.UNCOVERED.toFloat()) continue
                    val p = r * w * CHANNELS + c
                    val n = counts[p]
                    if (n < frames.count) {
                        store[p * frames.count + n] = v
                        origin?.set(p * frames.count + n, f)
                        counts[p] = n + 1
                    }
                }
            }
        }

        // Combine and write.
        for (p in 0 until tilePixels) {
            val n = counts[p]
            for (i in 0 until n) samples[i] = store[p * frames.count + i]
            master[(top * w * CHANNELS) + p] = if (origin != null && sampleFrames != null) {
                for (i in 0 until n) sampleFrames[i] = origin[p * frames.count + i]
                (combiner as Combine.Weighted)
                    .combineWeighted(samples, sampleFrames, n, weights)
            } else {
                combiner.combine(samples, n)
            }
        }
        return true
    }

    // Lazily sized once, then reused across every tile (FR-12.2).
    private var store: FloatArray = FloatArray(0)
    private var counts: IntArray = IntArray(0)
    private var origin: IntArray = IntArray(0)

    /** T-5.5, resolved once per stack in [stack]. */
    private var weights: FloatArray = FloatArray(0)
    private var weighted: Boolean = false

    private fun tileStore(tilePixels: Int): FloatArray {
        val needed = tilePixels.toLong() * frames.count
        require(needed <= Int.MAX_VALUE) { "tile too large for one array" }
        if (store.size < needed) store = FloatArray(needed.toInt())
        return store
    }

    private fun tileCounts(tilePixels: Int): IntArray {
        if (counts.size < tilePixels) counts = IntArray(tilePixels)
        return counts
    }

    /**
     * The frame each stored sample came from, parallel to [tileStore].
     *
     * The same size as the sample store in elements, so turning weighting on **doubles** the
     * tile's memory. [tileRowsFor] is told about it, so what changes is the tile height rather
     * than the budget — the same relationship as "more frames means thinner tiles", arriving
     * through a different door.
     */
    private fun tileOrigin(tilePixels: Int): IntArray {
        val needed = tilePixels.toLong() * frames.count
        if (origin.size < needed) origin = IntArray(needed.toInt())
        return origin
    }

    companion object {
        const val CHANNELS = 3

        /** 96 MB of samples. Generous enough for a real stack, small enough to survive one. */
        const val DEFAULT_MEMORY_BUDGET = 96L * 1024 * 1024

        /**
         * How many output rows fit in the budget, given the frame count.
         *
         * The sample buffer is the binding constraint: `rows × width × channels × frames × 4`
         * bytes. **More frames means thinner tiles**, which is the relationship worth stating —
         * a 20-frame test session and a 200-frame night do not stack the same way, and a tile size
         * chosen for one silently exceeds the budget for the other.
         */
        fun tileRowsFor(
            width: Int,
            frames: Int,
            budgetBytes: Long,
            weighted: Boolean = false,
        ): Int {
            if (width <= 0 || frames <= 0) return 1
            // Four bytes a sample, and four more for the frame index when T-5.5 is weighting.
            val bytesPerSample = if (weighted) 8 else 4
            val perRow = width.toLong() * CHANNELS * frames * bytesPerSample
            return (budgetBytes / perRow).coerceIn(1L, MAX_TILE_ROWS.toLong()).toInt()
        }

        /**
         * Extra source rows fetched either side of a tile, so rotation cannot shear a seam into it.
         *
         * A band of output rows maps, under rotation, to a band of input that is taller and
         * sheared: a point at the far edge of a 4096-wide frame moves `2048 × sin θ` rows for a
         * rotation of θ. At the 2–3° a session reaches that is over a hundred rows, so the margin
         * is not a rounding allowance — it is the width of the frame times the angle.
         *
         * Fixed here rather than computed per tile because the transforms are not known to this
         * function, and a margin that is too generous costs a little I/O while one that is too
         * small costs a seam in every tile. The asymmetry decides it.
         */
        private fun marginFor(): Int = DEFAULT_MARGIN_ROWS

        /**
         * The source band a tile needs: the tile's rows plus [margin] either side, clipped to the
         * frame. Returns `(firstRow, rowCount)`.
         *
         * **The first row is always even, and that is a correctness requirement rather than
         * alignment tidiness.** A band is handed to the debayer with the *frame's* CFA pattern, so
         * a band starting on an odd row presents the second row of the 2×2 as though it were the
         * first — GRBG read as RGGB. Red and blue come back swapped, and only for the tiles that
         * happened to start odd, so the master gets bands of wrong colour rather than a wrong
         * colour anyone would notice at once.
         *
         * [tileRowsFor] returns whatever the memory budget allows, which is odd about half the
         * time, and `top` then alternates parity down the frame. Snapping here rather than forcing
         * even tiles keeps the budget arithmetic honest and costs at most one extra row of I/O.
         */
        fun sourceRowsFor(top: Int, rows: Int, margin: Int, height: Int): Pair<Int, Int> {
            val first = (top - margin).coerceAtLeast(0) and 1.inv()
            val last = (top + rows + margin).coerceAtMost(height)
            return Pair(first, (last - first).coerceAtLeast(0))
        }

        private const val MAX_TILE_ROWS = 512
        private const val DEFAULT_MARGIN_ROWS = 160
    }
}
