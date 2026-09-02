package com.starstacker.diag

import com.starstacker.stacking.Combine
import com.starstacker.stacking.TiledStacker
import kotlin.random.Random

/**
 * T-5.4's profile, driven from `adb` — the measurement §12.1 requires before JNI is even discussed.
 *
 * The rule that governs this file is **"build in Kotlin, profile, and only then consider the single
 * sanctioned JNI exception"**, and §1.31 is why it is worth taking seriously: the OpenCV dependency
 * was taken for the warp, and the profile afterwards showed the Kotlin alternative would have been
 * comfortable too. A number gathered before the decision is worth more than the same number
 * gathered after it.
 *
 * ### Why this cannot be answered on the desktop
 *
 * The combiner is pure Kotlin and its *correctness* is settled in a JVM test, where it belongs. Its
 * *cost* is not: this is a tight scalar loop over a few hundred floats, run tens of millions of
 * times, and the ratio between a desktop JIT with unlimited thermal headroom and a phone's
 * big-core-then-throttle behaviour is exactly the thing being asked about. So the arithmetic is
 * verified off-device and the clock is read on it.
 *
 * ### What is being timed
 *
 * The combine pass alone, on the shape of data a real stack presents: a quantised background, a
 * scattering of stars, and roughly one pixel in five hundred carrying an intruder. Data shape
 * matters here in a way it does not for the warp, because the branches differ — a patch of
 * background where more than half the samples share a value takes the zero-MAD path, and a pixel
 * with nothing wrong with it leaves the loop after one pass.
 *
 * Reported per megapixel of *output*, three combines to the pixel, so it lines up with the
 * 13–15 ms/megapixel §1.31 measured for the warp and can be added to it.
 *
 * Needs no camera. It rides the `--es diag` harness because that is where on-device measurements
 * live, not because it wants hardware.
 */
object CombineCheck {

    /** Pixels of sample data held at once. At 150 frames this is 9.8 MB — a tile's worth, roughly. */
    private const val POOL_PIXELS = 16_384

    /** Frame counts worth knowing: a short test session, a normal night, a long one. */
    private val FRAME_COUNTS = intArrayOf(20, 50, 150)

    fun run(log: (String) -> Unit) {
        log("combine: profiling T-5.4 against §12.1's rule — Kotlin first, measure, then decide")

        for (frames in FRAME_COUNTS) {
            val pool = syntheticSamples(frames)
            val scratch = FloatArray(frames)

            val clipped = measure(pool, frames, scratch, Combine.SigmaClip())
            val averaged = measure(pool, frames, scratch, TiledStacker.Combiner.Mean)

            // What the rejection actually did on this data, so a suspiciously fast run can be told
            // from a fast one — a clip that rejects nothing is not cheap, it is broken.
            val counted = Combine.SigmaClip()
            combineAll(pool, frames, scratch, counted)

            log(
                "combine: %3d frames — sigma-clip %.1f ms/MP, mean %.1f ms/MP (%.1fx), %s"
                    .format(frames, clipped, averaged, clipped / averaged, counted.stats.describe()),
            )
            log(
                "combine: %3d frames — a 12.6 MP master is %.1f s of combining, on one core"
                    .format(frames, clipped * 12.6 / 1000),
            )
        }

        log("combine: the warp pass is 13-15 ms/MP (§1.31); add the two for the stack's real cost")
        log(
            "combine: if this is uncomfortable, the first lever is threads, not JNI — the loop is " +
                "per-pixel independent and the phone has eight cores",
        )
    }

    /**
     * Milliseconds per megapixel of output, three combines to the pixel.
     *
     * The pool is walked several times over rather than measured once, so the figure is a steady
     * state rather than a cache miss. Warmed first: the first pass through a Kotlin loop this hot
     * is measuring the interpreter, not the code.
     */
    private fun measure(
        pool: FloatArray,
        frames: Int,
        scratch: FloatArray,
        combiner: TiledStacker.Combiner,
    ): Double {
        repeat(2) { combineAll(pool, frames, scratch, combiner) }

        val rounds = 6
        val start = System.nanoTime()
        repeat(rounds) { combineAll(pool, frames, scratch, combiner) }
        val elapsedMs = (System.nanoTime() - start) / 1e6

        // Three combines make one output pixel, which is the unit §1.31 reports in.
        val megapixels = POOL_PIXELS.toDouble() * rounds / 3.0 / 1_000_000
        return elapsedMs / megapixels
    }

    /**
     * One pass over the pool. Copies each pixel's samples into [scratch] first, exactly as
     * [TiledStacker] does — the combiner is allowed to reorder what it is given, so timing it
     * against the pool directly would both destroy the data and measure a loop nobody runs.
     */
    private fun combineAll(
        pool: FloatArray,
        frames: Int,
        scratch: FloatArray,
        combiner: TiledStacker.Combiner,
    ): Float {
        var sink = 0f
        for (p in 0 until POOL_PIXELS) {
            val base = p * frames
            for (i in 0 until frames) scratch[i] = pool[base + i]
            // Accumulated so the whole loop cannot be optimised away as dead code.
            sink += combiner.combine(scratch, frames)
        }
        return sink
    }

    /**
     * Sample data shaped like a real stack rather than like a benchmark.
     *
     * A flat random field would take one branch for every pixel and report a number no session
     * would ever see. This has the three populations the combiner actually meets: background at the
     * quantisation floor where the MAD is often exactly zero, stars where it is not, and intruders.
     */
    private fun syntheticSamples(frames: Int): FloatArray {
        val random = Random(20260901)
        val pool = FloatArray(POOL_PIXELS * frames)

        for (p in 0 until POOL_PIXELS) {
            val base = p * frames
            // One pixel in fifty is a star or its skirt; the rest is background.
            val star = if (random.nextInt(50) == 0) random.nextInt(200, 20_000) else 0
            val level = 1_000 + star
            // Read noise plus shot noise, quantised to whole ADU as the sensor delivers it.
            val spread = (8 + Math.sqrt(level.toDouble()) * 0.6).toInt().coerceAtLeast(1)

            for (i in 0 until frames) {
                pool[base + i] = (level + random.nextInt(-spread, spread + 1)).toFloat()
            }
            // One pixel in five hundred is crossed by something, in one frame of the stack.
            if (random.nextInt(500) == 0) {
                pool[base + random.nextInt(frames)] = (level + random.nextInt(3_000, 40_000)).toFloat()
            }
        }
        return pool
    }
}
