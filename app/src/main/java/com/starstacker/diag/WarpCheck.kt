package com.starstacker.diag

import com.starstacker.registration.RigidTransform
import com.starstacker.stacking.Resample
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * T-5.1's acceptance, driven from `adb` — because **OpenCV cannot run in a JVM unit test**.
 *
 * The native library is an Android `.so`, so every other check in this project's §15 ladder is
 * unavailable to it: the wrapper's correctness can only be established on the device it will run
 * on. That is exactly the situation the `--es diag` harness exists for (§15's sixth level), and it
 * answers the three questions the dependency was taken on:
 *
 * - **does it load at all**, on this device, with this ABI filter;
 * - **is the warp pointing the right way**, which is the one error that produces a plausible stack
 *   rather than an exception — see the direction note on [Resample.warpToReference];
 * - **what does it cost per megapixel**, which is the number that should have decided the
 *   dependency in the first place and can now be checked against the Kotlin alternative.
 *
 * Timed on a tile rather than a whole frame on purpose: T-5.3 accumulates tile by tile precisely so
 * a 12.6 MP frame never has to exist twice in memory, so a full-frame measurement would be timing
 * something the design does not do.
 */
object WarpCheck {

    private const val TILE = 512

    fun run(log: (String) -> Unit) {
        log("OpenCV available: ${Resample.available}")
        if (!Resample.available) {
            log("FAILED: the native library did not load — nothing below can run")
            return
        }

        correctness(log)
        debayerOrder(log)
        throughput(log)
    }

    /**
     * The Bayer naming trap, checked on the device rather than argued about.
     *
     * OpenCV names its codes after the **second row's second pixel**; a DNG's `CFAPattern` names the
     * first row's first. So the sensor's `GRBG` is OpenCV's `BayerGB`, and the obvious-looking
     * `BayerGR` is wrong. The consequence of getting it wrong is that **red and blue swap**, and
     * nothing in a linear astro frame makes that obvious — the sky is grey, the stars are white, and
     * the error only appears after colour balance, by which point it looks like a white-balance
     * problem rather than a demosaic one.
     *
     * So: a synthetic GRBG frame with each colour at a distinct level, and a check that the levels
     * come back on the channels they went in on.
     */
    private fun debayerOrder(log: (String) -> Unit) {
        val w = 16
        val h = 16
        // GRBG: row 0 is G R G R, row 1 is B G B G.
        val red = 1000
        val green = 500
        val blue = 100
        val cfa = ShortArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                y % 2 == 0 && x % 2 == 1 -> red
                y % 2 == 1 && x % 2 == 0 -> blue
                else -> green
            }.toShort()
        }

        val out = FloatArray(w * h * 3)
        val pattern = Resample.BayerPattern.of(listOf(1, 0, 2, 1))
        if (pattern == null) {
            log("FAILED: GRBG did not map to a pattern")
            return
        }
        if (!Resample.debayer(cfa, w, h, pattern, out)) {
            log("FAILED: debayer returned false")
            return
        }

        // A pixel well inside the frame, so every interpolation neighbour exists.
        val centre = (8 * w + 8) * 3
        val r = out[centre]
        val g = out[centre + 1]
        val b = out[centre + 2]
        log("debayer: GRBG -> OpenCV code ${pattern.openCvCode}, centre reads R=%.0f G=%.0f B=%.0f".format(r, g, b))

        val correct = abs(r - red) < 120 && abs(g - green) < 120 && abs(b - blue) < 120
        val swapped = abs(r - blue) < 120 && abs(b - red) < 120
        log(
            "debayer: %s".format(
                when {
                    correct -> "PASS — channels came back on the channels they went in on"
                    swapped -> "FAIL — red and blue are swapped, the OpenCV code is the wrong one"
                    else -> "FAIL — levels do not match what went in"
                },
            ),
        )
    }

    /**
     * A dot at a known place, warped by a known transform, found again.
     *
     * The transform maps reference → frame, so warping the *frame* back should return the dot to
     * where the reference had it. If the direction were inverted the dot would land at twice the
     * offset in the opposite direction — a specific, checkable number rather than "it looks wrong".
     */
    private fun correctness(log: (String) -> Unit) {
        val w = 256
        val h = 256
        val centreX = (w - 1) / 2.0
        val centreY = (h - 1) / 2.0
        val transform = RigidTransform(rotationDeg = 5.0, dx = 12.0, dy = -7.0, centreX, centreY)

        // Where the dot sits in the reference, and where the transform says the frame will show it.
        val refX = 80.0
        val refY = 60.0
        val (frameX, frameY) = transform.apply(refX, refY)

        val frame = FloatArray(w * h)
        splat(frame, w, h, frameX, frameY)

        val out = FloatArray(w * h)
        if (!Resample.warpToReference(frame, w, h, transform, out)) {
            log("FAILED: warp returned false")
            return
        }

        val peak = brightest(out, w, h)
        val error = hypot(peak.first - refX, peak.second - refY)
        log(
            "warp: dot placed at frame (%.1f, %.1f), recovered at (%.1f, %.1f), reference is (%.1f, %.1f)"
                .format(frameX, frameY, peak.first, peak.second, refX, refY),
        )
        log("warp: error %.2f px — %s".format(error, if (error < 1.5) "PASS" else "FAIL"))

        if (error >= 1.5) {
            // The specific diagnosis for the specific mistake, so a failure names its own cause.
            val inverted = hypot(peak.first - (2 * refX - frameX), peak.second - (2 * refY - frameY))
            if (inverted < 1.5) log("warp: landed at the mirrored offset — the direction is inverted")
        }

        // Everything outside the source must be the sentinel, not zero: the accumulator has to be
        // able to tell "this frame did not cover here" from "here was dark".
        val corner = out[0]
        log(
            "warp: uncovered corner reads %.1f (expected %.1f) — %s".format(
                corner, Resample.UNCOVERED,
                if (abs(corner - Resample.UNCOVERED) < 0.01) "PASS" else "FAIL",
            ),
        )
    }

    /** Per-megapixel cost, which is the figure T-5.3's budget is built from. */
    private fun throughput(log: (String) -> Unit) {
        val src = FloatArray(TILE * TILE) { (it % 1000).toFloat() }
        val dst = FloatArray(TILE * TILE)
        val centre = (TILE - 1) / 2.0
        val transform = RigidTransform(1.5, 4.0, -3.0, centre, centre)

        repeat(3) { Resample.warpToReference(src, TILE, TILE, transform, dst) } // warm the JIT

        val runs = 20
        val start = System.nanoTime()
        repeat(runs) { Resample.warpToReference(src, TILE, TILE, transform, dst) }
        val perTileMs = (System.nanoTime() - start) / 1e6 / runs

        val megapixels = TILE.toDouble() * TILE / 1_000_000
        val perMp = perTileMs / megapixels
        log("warp: %.2f ms per %dx%d tile — %.1f ms/megapixel".format(perTileMs, TILE, TILE, perMp))
        log(
            "warp: a 12.6 MP frame is about %.0f ms, so a 150-frame stack is about %.0f s"
                .format(perMp * 12.6, perMp * 12.6 * 150 / 1000),
        )
    }

    private fun splat(plane: FloatArray, w: Int, h: Int, x: Double, y: Double) {
        for (dy in -3..3) {
            for (dx in -3..3) {
                val px = x.roundToInt() + dx
                val py = y.roundToInt() + dy
                if (px in 0 until w && py in 0 until h) {
                    val falloff = 1.0 - hypot(dx.toDouble(), dy.toDouble()) / 4.0
                    if (falloff > 0) plane[py * w + px] = (1000 * falloff).toFloat()
                }
            }
        }
    }

    /** Flux-weighted centroid of the brightest region — the same measure the detector uses. */
    private fun brightest(plane: FloatArray, w: Int, h: Int): Pair<Double, Double> {
        var best = Float.NEGATIVE_INFINITY
        var bx = 0
        var by = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = plane[y * w + x]
                if (v > best) { best = v; bx = x; by = y }
            }
        }
        var sum = 0.0
        var sx = 0.0
        var sy = 0.0
        for (y in (by - 3).coerceAtLeast(0)..(by + 3).coerceAtMost(h - 1)) {
            for (x in (bx - 3).coerceAtLeast(0)..(bx + 3).coerceAtMost(w - 1)) {
                val v = plane[y * w + x].toDouble().coerceAtLeast(0.0)
                sum += v
                sx += v * x
                sy += v * y
            }
        }
        return if (sum <= 0) Pair(bx.toDouble(), by.toDouble()) else Pair(sx / sum, sy / sum)
    }
}
