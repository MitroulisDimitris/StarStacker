package com.starstacker.stacking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * T-5.6 — the linear master out.
 *
 * The file is parsed back byte by byte rather than through an imaging library, because the whole
 * risk here is that it is *readable but wrong*: a missing `SampleFormat` produces a perfectly
 * valid TIFF that every tool opens and every tool renders as noise. Only the tags themselves are
 * evidence of that.
 */
class LinearMasterTest {

    @TempDir
    lateinit var tempDir: Path

    private val w = 8
    private val h = 6

    /** A master with every pixel covered, channel c of pixel i holding a distinguishable value. */
    private fun covered() = FloatArray(w * h * 3) { it.toFloat() }

    // ------------------------------------------------------------------------------- the crop

    @Test
    fun `full frame keeps the whole reference frame`() {
        val region = LinearMaster.regionFor(covered(), w, h, LinearMaster.Crop.FULL_FRAME)
        assertEquals(LinearMaster.Region(0, 0, w, h), region)
    }

    @Test
    fun `an all-covered master crops to itself`() {
        val region = LinearMaster.regionFor(covered(), w, h, LinearMaster.Crop.COMMON_AREA)
        assertEquals(LinearMaster.Region(0, 0, w, h), region)
    }

    @Test
    fun `a border of uncovered pixels is trimmed off`() {
        val master = covered()
        // One ring of NaN around the edge — the shape a small drift actually produces.
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) uncover(master, x, y)
            }
        }
        val region = LinearMaster.regionFor(master, w, h, LinearMaster.Crop.COMMON_AREA)
        assertEquals(LinearMaster.Region(1, 1, w - 2, h - 2), region)
    }

    @Test
    fun `an asymmetric overlap picks the largest rectangle, not the first`() {
        val master = covered()
        // Uncovered: the top two rows, and the leftmost column. The answer is the 7x4 block, and a
        // greedy scan that stopped at the first all-covered rectangle would find something smaller.
        for (x in 0 until w) { uncover(master, x, 0); uncover(master, x, 1) }
        for (y in 0 until h) uncover(master, 0, y)

        val region = LinearMaster.regionFor(master, w, h, LinearMaster.Crop.COMMON_AREA)
        assertEquals(LinearMaster.Region(1, 2, 7, 4), region)
    }

    @Test
    fun `a wide short rectangle beats a tall narrow one when it is larger`() {
        val master = covered()
        // Leaves a full-width band of 2 rows (16 px) against a full-height column pair (12 px).
        for (y in 2 until h) {
            for (x in 2 until w) uncover(master, x, y)
        }
        val region = LinearMaster.regionFor(master, w, h, LinearMaster.Crop.COMMON_AREA)
        assertEquals(LinearMaster.Region(0, 0, 8, 2), region)
    }

    @Test
    fun `an interior hole is excluded, which a footprint polygon could not see`() {
        val master = covered()
        // A single dead pixel in the middle. CommonArea's polygon intersection has no way to know
        // about this; the NaN mask does.
        uncover(master, 4, 3)
        val region = LinearMaster.regionFor(master, w, h, LinearMaster.Crop.COMMON_AREA)
        assertTrue(region.pixels < w.toLong() * h, "the hole should have cost something")
        // Whatever rectangle is chosen, it must not contain the hole.
        assertTrue(
            4 < region.left || 4 >= region.left + region.width ||
                3 < region.top || 3 >= region.top + region.height,
            "the chosen region ${region.describe()} contains the uncovered pixel",
        )
    }

    @Test
    fun `a master no frame covered falls back to the full frame`() {
        val master = FloatArray(w * h * 3) { Float.NaN }
        val region = LinearMaster.regionFor(master, w, h, LinearMaster.Crop.COMMON_AREA)
        assertEquals(LinearMaster.Region(0, 0, w, h), region)
    }

    // ------------------------------------------------------------------------------- the file

    @Test
    fun `the tags say 32-bit IEEE float RGB, which is what makes it readable`() {
        val file = File(tempDir.toFile(), "stack_linear.tif")
        LinearMaster.write(file, covered(), w, h, description = "test")
        val tiff = Tiff(file)

        assertEquals(w.toLong(), tiff.value(256))
        assertEquals(h.toLong(), tiff.value(257))
        assertEquals(listOf(32L, 32L, 32L), tiff.values(258), "BitsPerSample")
        assertEquals(1L, tiff.value(259), "uncompressed")
        assertEquals(2L, tiff.value(262), "PhotometricInterpretation RGB")
        assertEquals(3L, tiff.value(277), "SamplesPerPixel")
        assertEquals(1L, tiff.value(284), "chunky")
        // The one that decides whether a reader sees floats or enormous integers.
        assertEquals(listOf(3L, 3L, 3L), tiff.values(339), "SampleFormat must be IEEE float")
    }

    @Test
    fun `the pixels come back exactly, in RGB order`() {
        val master = covered()
        val file = File(tempDir.toFile(), "stack_linear.tif")
        LinearMaster.write(file, master, w, h)

        val pixels = Tiff(file).floats()
        assertEquals(w * h * 3, pixels.size)
        for (i in pixels.indices) assertEquals(master[i], pixels[i], 0f, "sample $i")
    }

    @Test
    fun `a cropped write emits only the region, and takes it from the right offset`() {
        val master = covered()
        val region = LinearMaster.Region(2, 1, 4, 3)
        val file = File(tempDir.toFile(), "cropped.tif")
        LinearMaster.write(file, master, w, h, region)

        val tiff = Tiff(file)
        assertEquals(4L, tiff.value(256))
        assertEquals(3L, tiff.value(257))

        val pixels = tiff.floats()
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                for (c in 0 until 3) {
                    val expected = master[((region.top + y) * w + (region.left + x)) * 3 + c]
                    assertEquals(expected, pixels[(y * region.width + x) * 3 + c], 0f)
                }
            }
        }
    }

    @Test
    fun `NaN survives a full-frame write, because that is what the mode promises`() {
        val master = covered()
        uncover(master, 3, 2)
        val file = File(tempDir.toFile(), "full.tif")
        LinearMaster.write(file, master, w, h, LinearMaster.regionFor(master, w, h, LinearMaster.Crop.FULL_FRAME))

        val pixels = Tiff(file).floats()
        assertTrue(pixels[(2 * w + 3) * 3].isNaN(), "an uncovered pixel must not become a number")
    }

    @Test
    fun `negative values survive, since calibration deliberately keeps them`() {
        val master = FloatArray(w * h * 3) { -12.5f }
        val file = File(tempDir.toFile(), "negative.tif")
        LinearMaster.write(file, master, w, h)
        assertEquals(-12.5f, Tiff(file).floats()[0], 0f)
    }

    @Test
    fun `the description travels with the file`() {
        val file = File(tempDir.toFile(), "described.tif")
        LinearMaster.write(file, covered(), w, h, description = "42 frames, sigma clipped")
        assertEquals("42 frames, sigma clipped", Tiff(file).ascii(270))
    }

    @Test
    fun `a short description is inlined rather than written as an offset`() {
        // Four bytes or fewer live inside the tag entry. Writing an offset there instead produces
        // a file that parses and carries garbage where the text should be.
        val file = File(tempDir.toFile(), "short.tif")
        LinearMaster.write(file, covered(), w, h, description = "ab")
        assertEquals("ab", Tiff(file).ascii(270))
    }

    @Test
    fun `an empty description still produces a valid file`() {
        val file = File(tempDir.toFile(), "empty.tif")
        LinearMaster.write(file, covered(), w, h, description = "")
        assertEquals("", Tiff(file).ascii(270))
        assertEquals(w.toLong(), Tiff(file).value(256))
    }

    @Test
    fun `the reported size is the file's size`() {
        val file = File(tempDir.toFile(), "sized.tif")
        val written = LinearMaster.write(file, covered(), w, h)
        assertEquals(file.length(), written)
    }

    // --------------------------------------------------------------------------------- fixtures

    private fun uncover(master: FloatArray, x: Int, y: Int) {
        for (c in 0 until 3) master[(y * w + x) * 3 + c] = Float.NaN
    }

    /** A minimal TIFF reader, so the test reads the file rather than the writer's intentions. */
    private class Tiff(file: File) {
        private val buffer: ByteBuffer = ByteBuffer.wrap(file.readBytes())
            .order(ByteOrder.LITTLE_ENDIAN)
        private val entries = mutableMapOf<Int, Triple<Int, Int, Int>>() // tag -> type, count, at

        init {
            check(buffer.getShort(0).toInt() == 0x4949) { "not little-endian TIFF" }
            check(buffer.getShort(2).toInt() == 42) { "bad magic" }
            val ifd = buffer.getInt(4)
            val count = buffer.getShort(ifd).toInt()
            for (i in 0 until count) {
                val at = ifd + 2 + i * 12
                entries[buffer.getShort(at).toInt() and 0xFFFF] =
                    Triple(buffer.getShort(at + 2).toInt(), buffer.getInt(at + 4), at + 8)
            }
        }

        private fun sizeOf(type: Int) = when (type) { 2 -> 1; 3 -> 2; 4 -> 4; else -> error("type $type") }

        private fun dataStart(tag: Int): Int {
            val (type, count, at) = entries.getValue(tag)
            return if (sizeOf(type) * count <= 4) at else buffer.getInt(at)
        }

        fun values(tag: Int): List<Long> {
            val (type, count, _) = entries.getValue(tag)
            val start = dataStart(tag)
            return (0 until count).map { i ->
                when (type) {
                    3 -> (buffer.getShort(start + i * 2).toInt() and 0xFFFF).toLong()
                    4 -> buffer.getInt(start + i * 4).toLong() and 0xFFFFFFFFL
                    else -> error("type $type")
                }
            }
        }

        fun value(tag: Int): Long = values(tag).single()

        fun ascii(tag: Int): String {
            val (_, count, _) = entries.getValue(tag)
            val start = dataStart(tag)
            val bytes = ByteArray(count) { buffer.get(start + it) }
            return String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
        }

        fun floats(): FloatArray {
            val start = value(273).toInt()
            val bytes = value(279).toInt()
            return FloatArray(bytes / 4) { buffer.getFloat(start + it * 4) }
        }
    }
}
