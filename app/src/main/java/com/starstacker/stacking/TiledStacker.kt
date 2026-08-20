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
    private val combiner: Combiner = Combiner.Mean,
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

    /** How N samples of one pixel become one. T-5.4 replaces [Mean] with sigma clipping. */
    fun interface Combiner {
        /** @param count how many of [samples] are valid; the rest are frames that did not cover. */
        fun combine(samples: FloatArray, count: Int): Float

        companion object {
            /**
             * The plain mean — correct, and deliberately naive: it keeps satellites, aircraft and
             * cosmic rays, which is exactly what T-5.4's sigma clipping is for. Shipping it first
             * means the machinery can be proven before the statistics are argued about.
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
     */
    fun stack(master: FloatArray, onProgress: (Progress) -> Unit = {}): Boolean {
        val w = frames.width
        val h = frames.height
        require(master.size >= w * h * CHANNELS) { "master needs ${w * h * CHANNELS} floats" }
        if (frames.count == 0) return false

        val tileRows = tileRowsFor(w, frames.count, memoryBudgetBytes)
        val margin = marginFor()
        val bandRows = tileRows + 2 * margin

        // Every buffer this loop will ever need, allocated once (FR-12.2). Nothing below allocates.
        val cfa = ShortArray(w * bandRows)
        val calibrated = FloatArray(w * bandRows)
        val calibratedShorts = ShortArray(w * bandRows)
        val colour = FloatArray(w * bandRows * CHANNELS)
        val warped = FloatArray(w * bandRows * CHANNELS)
        val samples = FloatArray(frames.count)

        val tiles = (h + tileRows - 1) / tileRows
        var tile = 0
        var top = 0
        while (top < h) {
            val rows = minOf(tileRows, h - top)
            if (!stackTile(top, rows, margin, bandRows, cfa, calibrated, calibratedShorts, colour, warped, samples, master)) {
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

        for (f in 0 until frames.count) {
            val band = sourceRowsFor(top, rows, margin, h)
            val got = frames.rows(f, band.first, band.second, cfa)
            if (got <= 0) continue

            // 1-2. Calibrate on CFA, before debayer (FR-8.1, T-5.2).
            Calibration.apply(cfa, frames.masters, frames.blackLevel, calibrated, frames.cfaCodes)
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
                        counts[p] = n + 1
                    }
                }
            }
        }

        // Combine and write.
        for (p in 0 until tilePixels) {
            val n = counts[p]
            for (i in 0 until n) samples[i] = store[p * frames.count + i]
            master[(top * w * CHANNELS) + p] = combiner.combine(samples, n)
        }
        return true
    }

    // Lazily sized once, then reused across every tile (FR-12.2).
    private var store: FloatArray = FloatArray(0)
    private var counts: IntArray = IntArray(0)

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
        fun tileRowsFor(width: Int, frames: Int, budgetBytes: Long): Int {
            if (width <= 0 || frames <= 0) return 1
            val perRow = width.toLong() * CHANNELS * frames * 4
            return (budgetBytes / perRow).toInt().coerceIn(1, MAX_TILE_ROWS)
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
         */
        fun sourceRowsFor(top: Int, rows: Int, margin: Int, height: Int): Pair<Int, Int> {
            val first = (top - margin).coerceAtLeast(0)
            val last = (top + rows + margin).coerceAtMost(height)
            return Pair(first, (last - first).coerceAtLeast(0))
        }

        private const val MAX_TILE_ROWS = 512
        private const val DEFAULT_MARGIN_ROWS = 160
    }
}
