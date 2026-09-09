package com.starstacker.registration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * T-4.7's primitive.
 *
 * The scene generated here is deliberately the one that defeated star matching: a **textured
 * landscape** filling most of the frame, plus one bright blob. There are no point sources to build
 * a triangle from, which is the whole reason this method exists.
 */
class PhaseCorrelationTest {

    private val width = 1024
    private val height = 768


    /**
     * Deterministic value noise, so the landscape carries **broadband** detail that translates with
     * the scene.
     *
     * The first two drafts of this scene used sinusoids, and the moon beat them both. That is not a
     * quirk of the test — it is how the method works. Phase correlation normalises every frequency
     * to unit magnitude, so a feature's weight is **the number of frequencies it occupies**, not
     * its brightness or its area. Six sinusoids occupy six frequencies; one hard-edged disc occupies
     * thousands, and wins.
     *
     * A real shoreline is broadband — rocks, railings, masts, the water line — which is why the
     * real session put all 67 frames at (0, 0) instead of following the moon. The scene has to have
     * that property or the test is measuring the wrong thing.
     */
    private fun texture(x: Double, y: Double): Double {
        fun at(ix: Int, iy: Int): Double {
            var h = ix * 374761393 + iy * 668265263
            h = (h xor (h shr 13)) * 1274126177
            return (((h xor (h shr 16)) and 0x7fffffff).toDouble() / 0x7fffffff) - 0.5
        }
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val a = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val b = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return a * (1 - fy) + b * fy
    }

    /**
     * A harbour-like scene: broad structure, fine texture, and one bright object.
     *
     * @param shiftX how far the whole scene is moved, in pixels
     * @param blobOffsetX extra movement applied *only* to the bright blob, so the two-rigid-bodies
     *   case can be reproduced — the shore is static and the moon is not.
     */
    private fun scene(
        shiftX: Double = 0.0,
        shiftY: Double = 0.0,
        blobOffsetX: Double = 0.0,
        noise: Double = 0.0,
        seed: Int = 4,
    ): FloatArray {
        val out = FloatArray(width * height)
        val rng = Random(seed)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = x - shiftX
                val sy = y - shiftY
                // Broad structure *and* abundant fine detail. The fine detail is the part that
                // matters and the first draft of this scene did not have enough of it: phase
                // correlation weights every frequency equally, so it is driven by **sharp edges
                // rather than by area**, and against a landscape of smooth sinusoids one small
                // hard-edged moon won outright. A real shoreline — rocks, railings, masts, the
                // water line — is full of high-frequency structure, which is why the real session
                // put all 67 frames at (0, 0) rather than following the moon.
                var v = 40.0 +
                    25.0 * sin(sx * 0.013) * cos(sy * 0.011) +
                    12.0 * sin(sx * 0.071 + sy * 0.043) +
                    6.0 * cos(sx * 0.31) * sin(sy * 0.27) +
                    // The part that makes the landscape win: broadband detail.
                    70.0 * texture(sx, sy)
                // The horizon: a hard edge, which is most of what a real landscape gives.
                if (sy > height * 0.55) v += 60.0
                // One bright object, optionally moving on its own.
                val bx = sx - blobOffsetX - width * 0.30
                val by = sy - height * 0.22
                val r = hypot(bx, by)
                if (r < 9) v += 700.0 * (1.0 - r / 9.0)
                out[y * width + x] = (v + if (noise > 0) rng.nextDouble(-noise, noise) else 0.0).toFloat()
            }
        }
        return out
    }

    @Test
    fun `an identical frame registers at zero`() {
        val a = scene()
        val result = PhaseCorrelation.between(a, a, width, height)

        assertTrue(result.usable, result.describe())
        assertEquals(0.0, result.dx, 0.5)
        assertEquals(0.0, result.dy, 0.5)
    }

    /**
     * The measurement that motivated the whole task: every frame of `2026-09-06_0118` came back at
     * exactly (0, 0), on a session where star matching rejected 34 of 67.
     */
    @Test
    fun `a static scene with a moving bright object still registers at zero`() {
        val reference = scene(blobOffsetX = 0.0)
        // The shore has not moved; the moon has, by 26 px — the drift that session measured.
        val target = scene(blobOffsetX = 26.0)

        val result = PhaseCorrelation.between(reference, target, width, height)
        assertTrue(result.usable, result.describe())
        assertEquals(0.0, result.dx, 1.5, "the landscape is what dominates, not the blob")
        assertEquals(0.0, result.dy, 1.5)
    }

    @Test
    fun `a whole-frame translation is recovered`() {
        for ((dx, dy) in listOf(12.0 to 0.0, 0.0 to -9.0, 21.0 to 14.0, -30.0 to 17.0)) {
            val result = PhaseCorrelation.between(
                scene(), scene(shiftX = dx, shiftY = dy), width, height,
            )
            assertTrue(result.usable, "($dx, $dy): ${result.describe()}")
            assertEquals(dx, result.dx, 2.0, "dx for ($dx, $dy)")
            assertEquals(dy, result.dy, 2.0, "dy for ($dx, $dy)")
        }
    }

    /**
     * The sign convention, stated as a test because getting it backwards is silent: the answer is
     * how far the *target* has moved from the reference.
     */
    @Test
    fun `the sign says which way the target moved`() {
        val right = PhaseCorrelation.between(scene(), scene(shiftX = 18.0), width, height)
        assertTrue(right.dx > 10.0, "moving the scene right must give a positive dx: ${right.dx}")

        val left = PhaseCorrelation.between(scene(), scene(shiftX = -18.0), width, height)
        assertTrue(left.dx < -10.0, "and left a negative one: ${left.dx}")
    }

    /**
     * Negative shifts live at the far end of a periodic surface. Reading `SIZE - 3` as +509 rather
     * than −3 would report a frame as having moved almost the width of the sensor.
     */
    @Test
    fun `a negative shift is not reported as a huge positive one`() {
        val result = PhaseCorrelation.between(scene(), scene(shiftX = -6.0, shiftY = -4.0), width, height)
        assertTrue(abs(result.dx) < width / 4.0, "dx wrapped: ${result.dx}")
        assertTrue(abs(result.dy) < height / 4.0, "dy wrapped: ${result.dy}")
        assertEquals(-6.0, result.dx, 2.5)
        assertEquals(-4.0, result.dy, 2.5)
    }

    @Test
    fun `noise does not stop a real shift being found`() {
        val result = PhaseCorrelation.between(
            scene(noise = 8.0, seed = 1),
            scene(shiftX = 15.0, shiftY = -7.0, noise = 8.0, seed = 2),
            width, height,
        )
        assertTrue(result.usable, result.describe())
        assertEquals(15.0, result.dx, 2.5)
        assertEquals(-7.0, result.dy, 2.5)
    }

    /**
     * The guard that keeps this from being trusted where it should not be.
     */
    @Test
    fun `two unrelated frames are refused rather than given an answer`() {
        val a = FloatArray(width * height).also { arr ->
            val rng = Random(11)
            for (i in arr.indices) arr[i] = rng.nextDouble(0.0, 100.0).toFloat()
        }
        val b = FloatArray(width * height).also { arr ->
            val rng = Random(12)
            for (i in arr.indices) arr[i] = rng.nextDouble(0.0, 100.0).toFloat()
        }
        val result = PhaseCorrelation.between(a, b, width, height)
        assertTrue(!result.usable, "pure noise should not be usable: ${result.describe()}")
        assertTrue(result.peakRatio < PhaseCorrelation.MIN_PEAK_RATIO, result.describe())
    }

    /**
     * The honest limit, as a test: this measures translation, and a rotated field is not one.
     *
     * The plan's rule is that the fallback must not be used to rescue a rotating star field that
     * asterism matching should have handled. The peak ratio is what enforces it — under rotation
     * the delta smears and the confidence collapses.
     */
    @Test
    fun `a rotated frame gives a much weaker peak than a translated one`() {
        fun rotated(degrees: Double): FloatArray {
            val out = FloatArray(width * height)
            val t = degrees * PI / 180.0
            val cx = width / 2.0
            val cy = height / 2.0
            val base = scene()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val ox = x - cx
                    val oy = y - cy
                    val sx = (cx + ox * cos(t) + oy * sin(t)).toInt()
                    val sy = (cy - ox * sin(t) + oy * cos(t)).toInt()
                    out[y * width + x] =
                        if (sx in 0 until width && sy in 0 until height) base[sy * width + sx] else 0f
                }
            }
            return out
        }

        val translated = PhaseCorrelation.between(scene(), scene(shiftX = 14.0), width, height)
        val turned = PhaseCorrelation.between(scene(), rotated(6.0), width, height)

        assertTrue(
            turned.peakRatio < translated.peakRatio,
            "rotation %.1f should be weaker than translation %.1f"
                .format(turned.peakRatio, translated.peakRatio),
        )
    }

    // ------------------------------------------------------------------ the transform itself

    @Test
    fun `the forward transform of a delta is flat`() {
        val n = 64
        val re = DoubleArray(n).also { it[0] = 1.0 }
        val im = DoubleArray(n)
        PhaseCorrelation.fft(re, im, forward = true)
        for (i in 0 until n) {
            assertEquals(1.0, re[i], 1e-9, "bin $i")
            assertEquals(0.0, im[i], 1e-9, "bin $i")
        }
    }

    @Test
    fun `a forward transform followed by an inverse returns the input`() {
        val n = 128
        val rng = Random(7)
        val original = DoubleArray(n) { rng.nextDouble(-5.0, 5.0) }
        val re = original.copyOf()
        val im = DoubleArray(n)

        PhaseCorrelation.fft(re, im, forward = true)
        PhaseCorrelation.fft(re, im, forward = false)
        for (i in 0 until n) {
            assertEquals(original[i], re[i] / n, 1e-9, "sample $i")
        }
    }

    @Test
    fun `a pure tone lands in the bin it belongs to`() {
        val n = 64
        val bin = 5
        val re = DoubleArray(n) { cos(2.0 * PI * bin * it / n) }
        val im = DoubleArray(n)
        PhaseCorrelation.fft(re, im, forward = true)

        val magnitudes = DoubleArray(n) { hypot(re[it], im[it]) }
        val loudest = magnitudes.indices.maxByOrNull { magnitudes[it] }
        assertTrue(loudest == bin || loudest == n - bin, "landed in bin $loudest, wanted $bin")
    }

    @Test
    fun `a size that is not a power of two is refused rather than producing nonsense`() {
        val thrown = runCatching {
            PhaseCorrelation.fft(DoubleArray(48), DoubleArray(48), forward = true)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "got $thrown")
    }
}
