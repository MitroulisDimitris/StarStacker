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
    /**
     * Makes a combiner — a **factory**, not an instance, because the combine runs on every core.
     *
     * `SigmaClip` carries a scratch buffer and its own counters, so workers cannot share one; each
     * gets its own and the counters are summed at the end ([Combine.SigmaClip.Stats.add]). The
     * pixels are independent, so which worker computes which changes nothing about the answer —
     * the test for this asserts the master is *bit-identical* however many threads run it.
     */
    private val combiner: () -> Combiner = { Combine.SigmaClip() },
    private val memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET,
    /** Workers for the combine pass. One is the serial path, and the tests use it as the oracle. */
    private val threads: Int = defaultThreads(),
    /**
     * Where §1.38's registered intermediate is written. The session's own folder by default, so
     * the space it takes is visible next to the frames rather than hidden in app-private storage.
     */
    private val scratchDirectory: java.io.File? = null,
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
         * Carries [src] back into reference coordinates, from a source band into a **separate,
         * shorter output band**.
         *
         * The two origins are given separately because they are genuinely different: the source
         * band must be tall enough to cover the rotation, and the output is only the rows being
         * produced. Warping a whole tall band to use a few of its rows is what §1.38 measured at
         * 41× the necessary work.
         *
         * @param srcTop the source band's first row, in whole-frame coordinates.
         * @param dstTop the output band's first row, in whole-frame coordinates.
         */
        fun warpBand(
            src: FloatArray,
            width: Int,
            srcRows: Int,
            srcTop: Int,
            channels: Int,
            dstRows: Int,
            dstTop: Int,
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

    /** Which of the two passes is running — see [stack]. */
    enum class Phase { REGISTER, COMBINE }

    data class Progress(
        val tile: Int,
        val tiles: Int,
        val rowsDone: Int,
        val rows: Int,
        val phase: Phase = Phase.COMBINE,
    )

    /**
     * Stacks everything into [master], three interleaved channels at full frame size.
     *
     * ### Two passes, since §1.38
     *
     * **Register**, then **combine**. Every frame is calibrated, debayered and warped into
     * reference coordinates exactly once and parked in [RegisteredFrames]; the combine then walks
     * the output in tiles, reading row `y` of every registered frame — with **no margin**, because
     * by then every frame is in the same coordinate system.
     *
     * The first version did both inside the tile loop, and that is quadratic in the wrong way. A
     * source band must be taller than its tile to cover the rotation, so with a 220-row margin and
     * an 8-row tile — which is what 114 frames and the sample budget actually produced — each of
     * 384 tiles read, calibrated, debayered and warped 328 rows to yield 8. **41× amplification,
     * about 61 minutes for one stack.** The margin grows with session length and the tile shrinks
     * with frame count, so no constant fixes it.
     *
     * The cost is [RegisteredFrames]' scratch: 151 MB a frame, 17 GB for a long session. That buys
     * `frames × tiles` warps becoming `frames`.
     *
     * @return false if any tile failed, if the scratch could not be made, or if [cancelled]. A
     *   partial master is worse than none: it looks like an image and is wrong in a band.
     * @param cancelled consulted between frames and between tiles, so a stack stops within one
     *   unit of work of being asked rather than at the end — the reason someone presses cancel is
     *   usually that the phone is hot, and finishing first answers neither the request nor the
     *   reason for it.
     */
    fun stack(
        master: FloatArray,
        coverage: ShortArray? = null,
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
        // One per worker, made up front so the hot loop never allocates and so the caller can read
        // their counters back afterwards.
        workers = List(threads.coerceAtLeast(1)) { combiner() }
        // A factory that hands the same *stateful* combiner to every worker is a data race on its
        // scratch buffer and counters, and would show up as a master subtly different every run —
        // the worst thing on this codebase's list. It is an easy slip (`{ existing }` rather than
        // `{ Combine.SigmaClip() }`) and free to catch here.
        //
        // Only checked for `SigmaClip`, because it is the only combiner that carries state: `Mean`,
        // `Median` and `WeightedMean` are stateless singletons and sharing one is correct and
        // cheaper. A future stateful combiner has to be added to this condition.
        if (workers.any { it is Combine.SigmaClip }) {
            require(workers.distinctBy { System.identityHashCode(it) }.size == workers.size) {
                "the combiner factory returned the same stateful instance more than once; " +
                    "each worker needs its own"
            }
        }
        weighted = workers.first() is Combine.Weighted && weights.any { it != 1f }

        val registered = scratch.let { RegisteredFrames.create(it, w, h, frames.count) }
            ?: return false

        coverage?.let {
            require(it.size >= w * h) { "coverage needs ${w * h} entries" }
        }

        return registered.use {
            register(registered, cancelled, onProgress) &&
                combine(registered, master, coverage, cancelled, onProgress)
        }
    }

    /**
     * Pass one: every frame into reference coordinates, once.
     *
     * The band here is sized for *throughput* rather than for the sample budget — nothing is being
     * accumulated, so the only constraint is the working buffers. A tall band amortises the margin,
     * which is the cost this pass exists to pay only once.
     */
    private fun register(
        registered: RegisteredFrames,
        cancelled: () -> Boolean,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        val w = frames.width
        val h = frames.height
        val margin = marginFor()
        val outRows = registerRowsFor(w, margin)
        // Plus the row the parity snap can add: sourceRowsFor rounds the band's first row *down*
        // to an even one, so a band can be one row taller than `outRows + 2 * margin`. That never
        // fired while both numbers were even; it fires the moment either is odd, and it fires as
        // an IllegalArgumentException from the reader rather than as anything diagnosable.
        val bandRows = outRows + 2 * margin + BAND_SLACK_ROWS

        // Allocated once for the whole pass (FR-12.2).
        val cfa = ShortArray(w * bandRows)
        val calibrated = FloatArray(w * bandRows)
        val calibratedShorts = ShortArray(w * bandRows)
        val colour = FloatArray(w * bandRows * CHANNELS)
        val warped = FloatArray(w * outRows * CHANNELS)

        for (f in 0 until frames.count) {
            if (cancelled()) return false
            onProgress(Progress(f + 1, frames.count, 0, h, Phase.REGISTER))

            val transform = frames.transform(f)
            registered.writer(f).use { out ->
                var top = 0
                while (top < h) {
                    val rows = minOf(outRows, h - top)
                    val band = sourceRowsFor(top, rows, margin, h)
                    val got = frames.rows(f, band.first, band.second, cfa)
                    if (got <= 0) return false

                    // 1-2. Calibrate on CFA, before debayer (FR-8.1, T-5.2). The band's origin goes
                    // with it: the masters are whole-frame and this is one band of the light.
                    Calibration.apply(
                        cfa, frames.masters, frames.blackLevel, calibrated, frames.cfaCodes,
                        band.first, got,
                    )
                    // The debayer takes integers; calibration produced floats that may be negative.
                    // Rounding back is lossy by well under an ADU and keeps the CFA path honest.
                    for (i in 0 until w * got) {
                        calibratedShorts[i] = calibrated[i].toInt().coerceIn(0, 65535).toShort()
                    }

                    // 3. Debayer.
                    if (!resampler.debayer(calibratedShorts, w, got, frames.cfaCodes, colour)) {
                        return false
                    }

                    // 4. Into reference coordinates — only the rows being produced, which is
                    // §1.38's fix. The reference frame has no transform and needs no warp.
                    val produced: FloatArray = if (transform == null) {
                        // Its own rows sit at `top - band.first` inside the band.
                        val skip = (top - band.first) * w * CHANNELS
                        colour.copyInto(warped, 0, skip, skip + rows * w * CHANNELS)
                        warped
                    } else {
                        if (!resampler.warpBand(
                                src = colour,
                                width = w,
                                srcRows = got,
                                srcTop = band.first,
                                channels = CHANNELS,
                                dstRows = rows,
                                dstTop = top,
                                transform = transform,
                                out = warped,
                            )
                        ) {
                            return false
                        }
                        warped
                    }

                    registered.write(f, out, produced, rows)
                    top += rows
                }
            }
        }
        return true
    }

    /**
     * Pass two: combine the registered frames, tile by tile.
     *
     * **No margin anywhere in here.** Every frame is already in reference coordinates, so output
     * row `y` is row `y` of every input, and a tile reads exactly the rows it writes.
     */
    private fun combine(
        registered: RegisteredFrames,
        master: FloatArray,
        coverage: ShortArray?,
        cancelled: () -> Boolean,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        val w = frames.width
        val h = frames.height
        val tileRows = tileRowsFor(w, frames.count, memoryBudgetBytes, weighted)

        val band = FloatArray(w * tileRows * CHANNELS)
        // Per-worker scratch, so nothing in the parallel region touches a shared array.
        val samples = List(workers.size) { FloatArray(frames.count) }
        val sampleFrames = List(workers.size) { if (weighted) IntArray(frames.count) else null }

        val pool = if (workers.size > 1) {
            java.util.concurrent.Executors.newFixedThreadPool(workers.size) { r ->
                Thread(r, "stack-combine").apply { isDaemon = true }
            }
        } else {
            null
        }

        try {
            val tiles = (h + tileRows - 1) / tileRows
            var tile = 0
            var top = 0
            while (top < h) {
                if (cancelled()) return false
                val rows = minOf(tileRows, h - top)
                if (!combineTile(
                        registered, top, rows, band, samples, sampleFrames, master, coverage, pool,
                    )
                ) {
                    return false
                }
                tile++
                top += rows
                onProgress(Progress(tile, tiles, top, h, Phase.COMBINE))
            }
            return true
        } finally {
            pool?.shutdown()
        }
    }

    private fun combineTile(
        registered: RegisteredFrames,
        top: Int,
        rows: Int,
        band: FloatArray,
        samples: List<FloatArray>,
        sampleFrames: List<IntArray?>,
        master: FloatArray,
        coverage: ShortArray?,
        pool: java.util.concurrent.ExecutorService?,
    ): Boolean {
        val w = frames.width

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
            val got = registered.rows(f, top, rows, band)
            if (got <= 0) return false
            for (c in 0 until got * w * CHANNELS) {
                val v = band[c]
                if (!v.isFinite() || v == Resample.UNCOVERED.toFloat()) continue
                val n = counts[c]
                if (n < frames.count) {
                    store[c * frames.count + n] = v
                    origin?.set(c * frames.count + n, f)
                    counts[c] = n + 1
                }
            }
        }

        // Combine and write. Every pixel is independent of every other, which is what makes the
        // split safe and the answer identical however it is divided — see [workers].
        if (pool == null) {
            combineRange(0, tilePixels, top, store, counts, origin, samples[0], sampleFrames[0], workers[0], master)
        } else {
            val chunk = (tilePixels + workers.size - 1) / workers.size
            val tasks = workers.indices.map { k ->
                java.util.concurrent.Callable {
                    val from = k * chunk
                    val until = minOf(from + chunk, tilePixels)
                    if (from < until) {
                        combineRange(
                            from, until, top, store, counts, origin,
                            samples[k], sampleFrames[k], workers[k], master,
                        )
                    }
                    null
                }
            }
            // invokeAll blocks until every chunk is done, so the tile is complete before the next
            // one reuses `store`.
            pool.invokeAll(tasks).forEach { it.get() }
        }

        // How many frames actually reached each pixel. Channel 0 speaks for the pixel — coverage
        // is decided before the debayer splits it — and this is what a crop needs: a pixel one
        // frame reached is not uncovered, it is a hundred times shallower than the rest of the
        // master, and nothing about its value says so.
        if (coverage != null) {
            for (y in 0 until rows) {
                val src = y * w * CHANNELS
                val dst = (top + y) * w
                for (x in 0 until w) {
                    coverage[dst + x] = counts[src + x * CHANNELS].toShort()
                }
            }
        }
        return true
    }

    /** One worker's slice of a tile. Reads `store`, writes its own disjoint span of `master`. */
    private fun combineRange(
        from: Int,
        until: Int,
        top: Int,
        store: FloatArray,
        counts: IntArray,
        origin: IntArray?,
        samples: FloatArray,
        sampleFrames: IntArray?,
        combiner: Combiner,
        master: FloatArray,
    ) {
        val base = top * frames.width * CHANNELS
        for (p in from until until) {
            val n = counts[p]
            for (i in 0 until n) samples[i] = store[p * frames.count + i]
            master[base + p] = if (origin != null && sampleFrames != null) {
                for (i in 0 until n) sampleFrames[i] = origin[p * frames.count + i]
                (combiner as Combine.Weighted).combineWeighted(samples, sampleFrames, n, weights)
            } else {
                combiner.combine(samples, n)
            }
        }
    }

    /**
     * Extra source rows fetched either side of an output band, so rotation cannot shear a seam.
     *
     * **Measured from the session's own transforms, not assumed.** It was a constant 160, sized in
     * §1.32 for "the 2–3° a session reaches" — and the first real session reached **3.72°**,
     * displacing **219.5 rows**. A margin smaller than the displacement does not degrade, it drops
     * rows a tile needed, which is exactly the seam the margin exists to prevent.
     *
     * The exact answer is the largest row displacement anywhere in the frame, so the frame's four
     * corners are carried through every transform and the worst is taken. Corners suffice because
     * an affine map is monotonic along each axis: the extreme is always at a corner.
     *
     * Plus [INTERPOLATION_SLACK], because cubic resampling reaches beyond the pixel it lands on.
     */
    private fun marginFor(): Int = marginRowsFor(
        frames.width,
        frames.height,
        (0 until frames.count).map { frames.transform(it) },
    )

    /** Where the intermediate goes. Defaults beside the frames it came from. */
    private val scratch: java.io.File
        get() = scratchDirectory ?: java.io.File(System.getProperty("java.io.tmpdir") ?: ".")

    // Lazily sized once, then reused across every tile (FR-12.2).
    private var store: FloatArray = FloatArray(0)
    private var counts: IntArray = IntArray(0)
    private var origin: IntArray = IntArray(0)

    /**
     * Drops the tile buffers, which are the largest thing this holds.
     *
     * At the default budget the sample store and its frame-index twin are **192 MB between them**,
     * and they stay reachable for as long as the stacker does — which on the first run of T-7.x was
     * long enough to starve the auto-edit that came next and fail it with an `OutOfMemoryError`.
     * A stack is finished when it returns; what it needed to get there is not.
     *
     * Not `close()`, because this is not a resource and the object stays usable: calling [stack]
     * again simply grows them back.
     */
    fun release() {
        store = FloatArray(0)
        counts = IntArray(0)
        origin = IntArray(0)
    }

    /** T-5.5, resolved once per stack in [stack]. */
    private var weights: FloatArray = FloatArray(0)
    private var weighted: Boolean = false

    /**
     * The combiners the last [stack] used, one per worker.
     *
     * Exposed so a caller can sum their counters — the rejection rate is one of the few things a
     * stack reports about itself, and splitting the work across cores must not split the number.
     */
    var workers: List<Combiner> = emptyList()
        private set

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

        /**
         * 192 MB of samples.
         *
         * Was 96 MB, chosen before `largeHeap` existed and before anything had been measured. On
         * the first real session that gave an **8-row tile**, so the combine did 43 776 reads of
         * 393 KB — small enough that seek overhead is a real share of the cost. Doubling it halves
         * the reads and doubles their size, and the heap has the room now (§1.38).
         */
        const val DEFAULT_MEMORY_BUDGET = 192L * 1024 * 1024

        /**
         * Cores to combine on, leaving one for everything else.
         *
         * §1.33 predicted this lever and §1.38 measured it: the combine is compute-bound, per-pixel
         * independent, and was 855 s of a 1145 s stack on one core. Capped at six because the
         * little cores of a big.LITTLE phone contribute less than they cost in contention, and
         * because a stack should not make the device unusable while it runs.
         */
        fun defaultThreads(): Int =
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)

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
         * The margin a set of transforms actually needs, in rows.
         *
         * **Measured rather than assumed, since §1.38.** It was the constant 160, sized in §1.32
         * for "the 2–3° a session reaches" — and the first real session reached **3.72°**,
         * displacing **219.5 rows**. A margin below the displacement does not degrade gracefully:
         * it drops rows a tile needed, which is precisely the seam the margin exists to prevent.
         *
         * The answer is the largest row displacement anywhere in the frame. The four corners
         * suffice, because an affine map is monotonic along each axis and the extreme of a linear
         * function over a rectangle is always at a corner.
         *
         * Pure, and a companion function rather than a method, because the only fixture that could
         * exercise it through the loop would have to be thousands of rows tall — which is the
         * blind spot §1.34 and §1.38 were both found in.
         */
        fun marginRowsFor(width: Int, height: Int, transforms: List<RigidTransform?>): Int {
            var worst = 0.0
            val w = width.toDouble()
            val h = height.toDouble()
            for (t in transforms) {
                if (t == null) continue
                for (x in doubleArrayOf(0.0, w)) {
                    for (y in doubleArrayOf(0.0, h)) {
                        val (_, sy) = t.apply(x, y)
                        val shift = kotlin.math.abs(sy - y)
                        if (shift > worst) worst = shift
                    }
                }
            }
            return (kotlin.math.ceil(worst).toInt() + INTERPOLATION_SLACK)
                .coerceIn(MIN_MARGIN_ROWS, MAX_MARGIN_ROWS)
        }

        private const val MIN_MARGIN_ROWS = 4

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

        /**
         * A ceiling on the measured margin. A session that rotates far enough to need more than
         * this has bigger problems than a seam, and an unbounded margin would let one wild
         * transform in a log make every band the whole frame.
         */
        private const val MAX_MARGIN_ROWS = 512

        /** Cubic resampling reaches two pixels beyond where it lands; four is cheap insurance. */
        private const val INTERPOLATION_SLACK = 4

        /**
         * One row for [sourceRowsFor]'s even-row snap, one for luck.
         *
         * The snap moves a band's first row *down*, so the band can be a row taller than
         * `rows + 2 × margin`. Cheaper to allocate than to reason about at every call site.
         */
        private const val BAND_SLACK_ROWS = 2

        /**
         * Output rows produced per band in the register pass.
         *
         * Chosen so the margin is amortised rather than to fit a budget — nothing accumulates in
         * that pass, so the only constraint is the working buffers, and a band of `rows + 2×margin`
         * at 4096 wide is a few tens of megabytes. Kept to a multiple of the margin so the
         * amplification stays near 1: at 8× the margin, a band does 1.25 rows of work per row
         * produced.
         */
        fun registerRowsFor(width: Int, margin: Int): Int {
            val wanted = (margin * 8).coerceAtLeast(64)
            // Bounded so a huge margin cannot ask for a band larger than the buffers can hold.
            val affordable = (REGISTER_BAND_BUDGET / (width.toLong() * CHANNELS * 4)).toInt()
            // Even, so the band it produces starts on an even row and the debayer sees the frame's
            // own CFA phase — the same requirement sourceRowsFor enforces for tiles.
            val rows = wanted.coerceIn(1, affordable.coerceAtLeast(1))
            return (rows and 1.inv()).coerceAtLeast(2)
        }

        /** 64 MB of working buffers for the register pass, which accumulates nothing. */
        private const val REGISTER_BAND_BUDGET = 64L * 1024 * 1024
    }
}
