package com.starstacker.dng

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * T-1.6. Built on synthetic files rather than a captured DNG so the suite stays fast and needs
 * no 25 MB fixture — but the synthetic files mirror exactly what §1.6 measured on the device:
 * uncompressed, 16-bit, CFA in IFD0, one strip per row.
 */
class DngReaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `reads a device-shaped file - one strip per row, CFA in IFD0`() {
        val pixels = intArrayOf(
            10, 20, 30, 40,
            50, 60, 70, 80,
            90, 100, 110, 120,
        )
        val file = writeDng(width = 4, height = 3, samples = pixels, rowsPerStrip = 1)

        val image = DngReader.read(file)

        assertEquals(4, image.metadata.width)
        assertEquals(3, image.metadata.height)
        assertEquals(16, image.metadata.bitsPerSample)
        assertEquals(1, image.metadata.compression)
        assertEquals(1, image.metadata.rowsPerStrip)
        assertEquals(3, image.metadata.stripCount)
        assertEquals(pixels.toList(), image.pixels.map { it.toInt() and 0xFFFF })
        assertEquals(70, image.sample(2, 1))
    }

    @Test
    fun `reads multi-row strips too`() {
        val pixels = IntArray(16) { it * 3 }
        val file = writeDng(width = 4, height = 4, samples = pixels, rowsPerStrip = 2)

        val image = DngReader.read(file)

        assertEquals(2, image.metadata.stripCount)
        assertEquals(pixels.toList(), image.pixels.map { it.toInt() and 0xFFFF })
    }

    @Test
    fun `samples above 32767 survive the signed ShortArray round trip`() {
        // 10-bit data does not reach here, but a 14- or 16-bit sensor would, and a sign
        // extension bug would silently invert the brightest stars in the frame.
        val pixels = intArrayOf(0, 1023, 40000, 65535)
        val file = writeDng(width = 4, height = 1, samples = pixels, rowsPerStrip = 1)

        val image = DngReader.read(file)

        assertEquals(listOf(0, 1023, 40000, 65535), image.pixels.map { it.toInt() and 0xFFFF })
    }

    @Test
    fun `reads big-endian files`() {
        val pixels = intArrayOf(1, 2, 3, 4)
        val file = writeDng(width = 2, height = 2, samples = pixels, rowsPerStrip = 1, le = false)

        val image = DngReader.read(file)

        assertEquals(pixels.toList(), image.pixels.map { it.toInt() and 0xFFFF })
    }

    @Test
    fun `parses the metadata the pipeline depends on`() {
        val file = writeDng(
            width = 2, height = 2, samples = intArrayOf(1, 2, 3, 4), rowsPerStrip = 1,
            blackLevel = 64.0, whiteLevel = 1023, iso = 800, exposureSeconds = 10.0,
            cfaCodes = intArrayOf(1, 0, 2, 1), model = "A059P-Nothing-Nothing",
        )

        val meta = DngReader.readMetadata(file)

        assertEquals("GRBG", meta.cfaPattern?.name)
        assertEquals(2, meta.cfaPattern?.cols)
        assertEquals(listOf(64.0), meta.blackLevels)
        assertEquals(1023, meta.whiteLevel)
        assertEquals(800, meta.isoSpeed)
        assertEquals(10.0, meta.exposureSeconds!!, 1e-9)
        assertEquals("A059P-Nothing-Nothing", meta.uniqueCameraModel)
    }

    @Test
    fun `a compressed file fails loudly and names the reason`() {
        val file = writeDng(
            width = 2, height = 2, samples = intArrayOf(1, 2, 3, 4), rowsPerStrip = 1,
            compression = 7,
        )

        val e = assertThrows<DngParseException> { DngReader.read(file) }
        assertTrue(e.message!!.contains("compression 7"), e.message)
        assertTrue(e.message!!.contains("lossless-JPEG"), e.message)
    }

    @Test
    fun `a demosaiced file is rejected rather than misread as CFA`() {
        val file = writeDng(
            width = 2, height = 2, samples = intArrayOf(1, 2, 3, 4), rowsPerStrip = 1,
            photometric = 2, // RGB
        )

        // Rejected during IFD selection rather than metadata parsing: having searched the
        // SubIFDs too, "no CFA image anywhere" is the more useful thing to say.
        val e = assertThrows<DngParseException> { DngReader.read(file) }
        assertTrue(e.message!!.contains("no CFA image found"), e.message)
    }

    @Test
    fun `a truncated strip table is caught instead of shearing the image`() {
        val file = writeDng(
            width = 4, height = 3, samples = IntArray(12) { it }, rowsPerStrip = 1,
            dropLastStrip = true,
        )

        val e = assertThrows<DngParseException> { DngReader.read(file) }
        assertTrue(e.message!!.contains("needs"), e.message)
    }

    @Test
    fun `not a TIFF at all`() {
        val file = File(tempDir.toFile(), "junk.dng").apply { writeBytes(ByteArray(64)) }
        assertThrows<DngParseException> { DngReader.read(file) }
    }

    @Test
    fun `absent optional tags read as null rather than throwing`() {
        val file = writeDng(width = 2, height = 2, samples = intArrayOf(1, 2, 3, 4), rowsPerStrip = 1)
        val meta = DngReader.readMetadata(file)
        assertNull(meta.whiteLevel)
        assertNull(meta.isoSpeed)
        assertNull(meta.uniqueCameraModel)
        assertTrue(meta.blackLevels.isEmpty())
    }

    // ---- synthetic DNG writer ------------------------------------------------------

    /**
     * Writes a minimal but structurally valid TIFF/DNG. Layout: header, IFD0, out-of-line tag
     * payloads, then pixel strips.
     */
    @Suppress("LongParameterList")
    // ------------------------------------------------------------------ T-5.3 row ranges

    @Test
    fun `a row range decodes to the same values the whole frame gives`() {
        // The property the tiled stacker depends on: reading rows 4..7 must be indistinguishable
        // from reading everything and slicing. If it were not, a stack would be built from frames
        // that quietly disagreed with themselves at every tile boundary.
        val w = 6
        val h = 12
        val samples = IntArray(w * h) { it * 3 }
        val file = writeDng(w, h, samples, rowsPerStrip = 1)

        val whole = DngReader.read(file).pixels
        DngReader.Rows(file).use { rows ->
            val buffer = ShortArray(4 * w)
            assertEquals(4, rows.read(fromRow = 4, rowCount = 4, into = buffer))
            for (i in 0 until 4 * w) {
                assertEquals(whole[4 * w + i], buffer[i]) { "row range differs at $i" }
            }
        }
    }

    @Test
    fun `every row range of a frame reassembles into the whole frame`() {
        // Stronger than the single case: walk the frame in tiles and rebuild it. Off-by-one errors
        // in the strip arithmetic survive one lucky range and not this.
        val w = 5
        val h = 17
        val samples = IntArray(w * h) { (it * 7) % 900 }
        val file = writeDng(w, h, samples, rowsPerStrip = 1)
        val whole = DngReader.read(file).pixels

        DngReader.Rows(file).use { rows ->
            val rebuilt = ShortArray(w * h)
            val buffer = ShortArray(4 * w)
            var row = 0
            while (row < h) {
                val got = rows.read(row, 4, buffer)
                for (i in 0 until got * w) rebuilt[row * w + i] = buffer[i]
                row += got
            }
            assertTrue(whole.contentEquals(rebuilt)) { "the frame did not survive being tiled" }
        }
    }

    @Test
    fun `a range that runs off the bottom returns what exists rather than overrunning`() {
        // The last tile of every frame hits this. Returning the count rather than filling the
        // buffer is what stops the caller stacking whatever the buffer held last time.
        val w = 4
        val h = 10
        val file = writeDng(w, h, IntArray(w * h) { it }, rowsPerStrip = 1)

        DngReader.Rows(file).use { rows ->
            val buffer = ShortArray(6 * w)
            assertEquals(2, rows.read(fromRow = 8, rowCount = 6, into = buffer))
            assertEquals(0, rows.read(fromRow = 10, rowCount = 4, into = buffer))
        }
    }

    @Test
    fun `multi-row strips still serve a single row correctly`() {
        // A file with fatter strips is legal and this device does not write one, but a frame that
        // came back from a PC might. The strip is decoded whole and the wanted row copied out,
        // which is correct and merely less efficient — the caller can see rowsPerStrip and judge.
        val w = 4
        val h = 12
        val samples = IntArray(w * h) { it * 2 }
        val file = writeDng(w, h, samples, rowsPerStrip = 4)
        val whole = DngReader.read(file).pixels

        DngReader.Rows(file).use { rows ->
            assertEquals(4, rows.rowsPerStrip)
            val buffer = ShortArray(w)
            assertEquals(1, rows.read(fromRow = 6, rowCount = 1, into = buffer))
            for (x in 0 until w) assertEquals(whole[6 * w + x], buffer[x]) { "row 6 col $x" }
        }
    }

    @Test
    fun `metadata is available without reading any pixels`() {
        val w = 8
        val h = 8
        val file = writeDng(w, h, IntArray(w * h), rowsPerStrip = 1, iso = 1600)
        DngReader.Rows(file).use { rows ->
            assertEquals(w, rows.metadata.width)
            assertEquals(h, rows.metadata.height)
            assertEquals(1600, rows.metadata.isoSpeed)
        }
    }

    @Test
    fun `a buffer too small for the request is refused rather than half filled`() {
        val w = 6
        val file = writeDng(w, 8, IntArray(w * 8), rowsPerStrip = 1)
        DngReader.Rows(file).use { rows ->
            assertThrows(IllegalArgumentException::class.java) {
                rows.read(0, 4, ShortArray(2 * w))
            }
        }
    }

    private fun writeDng(
        width: Int,
        height: Int,
        samples: IntArray,
        rowsPerStrip: Int,
        le: Boolean = true,
        compression: Int = 1,
        photometric: Int = 32803,
        blackLevel: Double? = null,
        whiteLevel: Int? = null,
        iso: Int? = null,
        exposureSeconds: Double? = null,
        cfaCodes: IntArray? = null,
        model: String? = null,
        dropLastStrip: Boolean = false,
    ): File = TestDng.write(
        file = File(tempDir.toFile(), "synthetic.dng"),
        width = width,
        height = height,
        samples = samples,
        rowsPerStrip = rowsPerStrip,
        le = le,
        compression = compression,
        photometric = photometric,
        blackLevel = blackLevel,
        whiteLevel = whiteLevel,
        iso = iso,
        exposureSeconds = exposureSeconds,
        cfaCodes = cfaCodes,
        model = model,
        dropLastStrip = dropLastStrip,
    )
}
