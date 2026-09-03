package com.starstacker.calibration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * T-8.3 — the master flat, and the frames it must refuse.
 *
 * Building the median is four lines; everything under test here is the refusing. The reason is that
 * **every way of getting a flat wrong is silent**: a saturated flat inverts the vignette, a dim one
 * divides by noise, and one shot as the light changed bakes a gradient into every frame it ever
 * touches. None of those announce themselves in the result.
 */
class FlatFieldTest {

    private val w = 64
    private val h = 48
    private val black = 64.0
    private val white = 4095

    /**
     * A frame with a realistic `cos⁴`-ish vignette: bright in the middle, [falloff]× darker in the
     * corners, at [level] of full scale in the centre.
     */
    private fun flat(level: Double = 0.5, falloff: Double = 4.0, seed: Int = 1): ShortArray {
        val random = Random(seed)
        val peak = black + (white - black) * level
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val maxR2 = cx * cx + cy * cy
        return ShortArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / maxR2
            // 1 at the centre falling to 1/falloff at the corner.
            val gain = 1.0 / (1.0 + (falloff - 1.0) * r2)
            (black + (peak - black) * gain + random.nextInt(-3, 4)).toInt().coerceIn(0, 65535).toShort()
        }
    }

    private fun build(frames: List<ShortArray>) =
        FlatField.build(frames, w, h, black, white)

    // ------------------------------------------------------------------------------ the master

    @Test
    fun `a set of good flats makes a master normalised to one`() {
        val result = build(List(8) { flat(seed = it) })

        assertTrue(result.usable, result.describe())
        assertEquals(8, result.used)
        assertEquals(1.0, result.master!!.average().toDouble(), 0.01)
    }

    @Test
    fun `the master measures the falloff the frames actually have`() {
        val result = build(List(8) { flat(falloff = 4.0, seed = it) })
        assertEquals(4.0, result.falloff, 0.6, "measured ${result.falloff}")
    }

    @Test
    fun `the pedestal is removed before anything else`() {
        // A flat sits on the black level too. Left in, the division corrects towards the pedestal
        // rather than towards the response, and the correction is wrong by more where the signal
        // is smaller — which is the corners, which is exactly where it matters.
        val bright = build(List(6) { flat(level = 0.6, falloff = 4.0, seed = it) })
        val dim = build(List(6) { flat(level = 0.3, falloff = 4.0, seed = it + 50) })

        // Two sets at different exposures describe the same lens, so once normalised they agree.
        assertEquals(bright.falloff, dim.falloff, 0.5)
    }

    @Test
    fun `a moth in one frame does not reach the master`() {
        val frames = List(7) { flat(seed = it) }.toMutableList()
        val spoiled = frames[3].copyOf()
        for (y in 20 until 28) for (x in 20 until 28) spoiled[y * w + x] = 3000
        frames[3] = spoiled

        val result = build(frames)
        val clean = build(List(7) { flat(seed = it) })

        assertTrue(result.usable)
        // The median holds: the patch must not show in the master.
        val at = result.master!![24 * w + 24]
        assertEquals(clean.master!![24 * w + 24], at, 0.02f)
    }

    // ------------------------------------------------------------------------------- refusing

    @Test
    fun `a saturated flat is refused, because it would invert the vignette`() {
        val frames = List(6) { flat(seed = it) }.toMutableList()
        frames[2] = flat(level = 0.95, seed = 99)

        val result = build(frames)
        assertEquals(5, result.used)
        assertTrue(
            result.rejections.any { it.index == 2 && it.reason.contains("too bright") },
            result.rejections.toString(),
        )
    }

    @Test
    fun `a flat that is mostly noise is refused`() {
        val frames = List(6) { flat(seed = it) }.toMutableList()
        frames[4] = flat(level = 0.03, seed = 98)

        val result = build(frames)
        assertTrue(result.rejections.any { it.index == 4 && it.reason.contains("too dim") })
    }

    @Test
    fun `a frame shot as the light changed is refused against its fellows`() {
        // Individually fine, collectively wrong: averaging it in tilts the master towards whatever
        // it saw, and nothing downstream could tell.
        val frames = List(6) { flat(level = 0.5, seed = it) }.toMutableList()
        frames[1] = flat(level = 0.30, seed = 97)

        val result = build(frames)
        assertTrue(
            result.rejections.any { it.index == 1 && it.reason.contains("light changed") },
            result.rejections.toString(),
        )
    }

    @Test
    fun `too few usable frames produces no master at all`() {
        // Refusing beats handing back a flat built from three frames and hoping.
        val result = build(List(3) { flat(seed = it) })
        assertFalse(result.usable)
        assertNull(result.master)
        assertTrue(result.warnings.any { it.contains("at least") })
    }

    @Test
    fun `no frames is not a crash`() {
        val result = FlatField.build(emptyList(), w, h, black, white)
        assertFalse(result.usable)
        assertTrue(result.describe().contains("no usable flat"))
    }

    // -------------------------------------------------------------------------- shape warnings

    @Test
    fun `corners brighter than the centre is not a flat field and says so`() {
        // A photograph of something, or a lamp off to one side. Dividing by it would darken the
        // middle of every frame it ever touched.
        val inverted = List(6) { i ->
            val f = flat(seed = i)
            val out = ShortArray(w * h)
            // Mirror the radial profile: dark centre, bright corners.
            for (p in 0 until w * h) {
                val x = p % w
                val y = p / w
                val mx = w - 1 - x
                val my = h - 1 - y
                out[p] = f[my * w + mx]
            }
            // Swap so the *centre* is the corner value — simply invert about the mean instead.
            val mean = out.map { it.toInt() and 0xFFFF }.average()
            ShortArray(w * h) { j -> (2 * mean - (out[j].toInt() and 0xFFFF)).toInt().coerceIn(0, 65535).toShort() }
        }
        val result = build(inverted)
        assertTrue(
            result.warnings.any { it.contains("not a flat field") },
            "warnings were ${result.warnings}",
        )
    }

    @Test
    fun `uneven illumination between corners is reported`() {
        val lopsided = List(6) { i ->
            val f = flat(seed = i)
            ShortArray(w * h) { p ->
                val x = p % w
                // A ramp on top of the vignette: one side lit more than the other.
                ((f[p].toInt() and 0xFFFF) + x * 12).coerceAtMost(65535).toShort()
            }
        }
        val result = build(lopsided)
        assertTrue(
            result.warnings.any { it.contains("not even") },
            "warnings were ${result.warnings}",
        )
    }
}

/**
 * T-8.3's library — calibration that belongs to the camera rather than to the night.
 */
class CalibrationLibraryTest {

    @TempDir
    lateinit var root: File

    private val w = 32
    private val h = 24

    private fun info(camera: String = "0", width: Int = w, height: Int = h) =
        CalibrationLibrary.FlatInfo(
            cameraId = camera,
            capturedAtEpochMs = 1_700_000_000_000L,
            width = width,
            height = height,
            frames = 12,
            falloff = 3.4,
            iso = 100,
            exposureNs = 5_000_000L,
            focusDiopters = 0f,
            notes = listOf("shot against a laptop screen"),
        )

    private fun master() = FloatArray(w * h) { 1f + (it % 7) * 0.01f }

    @Test
    fun `a flat survives a round trip through the library`() {
        val pixels = master()
        assertTrue(CalibrationLibrary.save(root, info(), pixels))

        val loaded = CalibrationLibrary.flat(root, "0", w, h)
        assertNotNull(loaded)
        assertEquals(12, loaded!!.info.frames)
        assertEquals(3.4, loaded.info.falloff, 1e-6)
        for (i in pixels.indices) assertEquals(pixels[i], loaded.pixels[i], 0f, "sample $i")
    }

    @Test
    fun `the metadata can be read without the pixels`() {
        // The settings screen only needs to say whether a camera is calibrated, and a 25 MB read
        // to answer that would make opening Settings feel broken.
        CalibrationLibrary.save(root, info(), master())
        val stored = CalibrationLibrary.info(root, "0")
        assertEquals("0", stored?.cameraId)
        assertTrue(stored!!.describe().contains("3.4"))
    }

    @Test
    fun `a flat of the wrong size is refused rather than resampled`() {
        // Resampling would blend neighbouring photosites of different colours — the same error
        // T-5.2 exists to avoid, arriving from the calibration side.
        CalibrationLibrary.save(root, info(), master())
        assertNull(CalibrationLibrary.flat(root, "0", w * 2, h))
    }

    @Test
    fun `cameras are kept apart`() {
        CalibrationLibrary.save(root, info(camera = "0"), master())
        CalibrationLibrary.save(root, info(camera = "2"), FloatArray(w * h) { 0.5f })

        assertEquals(1f, CalibrationLibrary.flat(root, "0", w, h)!!.pixels[0], 1e-6f)
        assertEquals(0.5f, CalibrationLibrary.flat(root, "2", w, h)!!.pixels[0], 1e-6f)
        assertEquals(listOf("0", "2"), CalibrationLibrary.cameras(root).map { it.cameraId })
    }

    @Test
    fun `saving again replaces rather than accumulating`() {
        // A newer flat of the same lens is a better measurement of the same thing, and keeping the
        // old one would only invite the question of which to use.
        CalibrationLibrary.save(root, info(), master())
        CalibrationLibrary.save(root, info().copy(frames = 20), FloatArray(w * h) { 2f })

        assertEquals(1, CalibrationLibrary.cameras(root).size)
        assertEquals(20, CalibrationLibrary.info(root, "0")?.frames)
        assertEquals(2f, CalibrationLibrary.flat(root, "0", w, h)!!.pixels[0], 1e-6f)
    }

    @Test
    fun `an uncalibrated camera reports nothing rather than failing`() {
        assertNull(CalibrationLibrary.info(root, "7"))
        assertNull(CalibrationLibrary.flat(root, "7", w, h))
        assertTrue(CalibrationLibrary.cameras(root).isEmpty())
    }

    @Test
    fun `a camera id with awkward characters still gets its own folder`() {
        CalibrationLibrary.save(root, info(camera = "logical/2"), master())
        assertNotNull(CalibrationLibrary.flat(root, "logical/2", w, h))
    }

    @Test
    fun `deleting a flat removes it`() {
        CalibrationLibrary.save(root, info(), master())
        assertTrue(CalibrationLibrary.delete(root, "0"))
        assertNull(CalibrationLibrary.info(root, "0"))
    }
}
