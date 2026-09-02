package com.starstacker.stacking

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * T-5.6 / FR-8.2 — the linear master, written to disk as a 32-bit float TIFF.
 *
 * ### Why this file is the sacred one
 *
 * FR-8.2: *"the linear stack is saved separately from the stretched output"*. Stretching is
 * destructive and irreversible — an autostretch throws away the relationship between the numbers
 * that makes the data measurable — so the linear stack is the artefact and the JPEG is a rendering
 * of it. It is also what goes into Siril or PixInsight, which is what makes **T-5.7** possible at
 * all: a stack that cannot leave the phone cannot be compared to anything.
 *
 * Everything before this produced a `FloatArray` that nothing could look at.
 *
 * ### Float, and the two tags that decide whether anyone can read it
 *
 * The data is linear ADU with no ceiling at 65 535 — a 150-frame stack of a bright star sums well
 * past it — and it carries negative values on purpose (T-5.2 keeps them, because clamping biases
 * the background upward). Neither survives an integer format, so the output is IEEE float.
 *
 * Two tags carry that: `BitsPerSample = 32` and **`SampleFormat = 3`**. The second is the one that
 * matters and the one a hand-rolled writer forgets, because a file missing it is not rejected —
 * the default is *unsigned integer*, so the reader reinterprets the float bit patterns as
 * enormous integers and shows noise. It fails as a wrong picture rather than as an error.
 *
 * ### One strip, and streaming
 *
 * A 12.6 MP master in three channels is **151 MB**. Building that as a `ByteArray` beside the
 * `FloatArray` it came from would need 300 MB at the moment of writing, on a phone that is already
 * holding the masters — so rows are converted into a small reusable buffer and pushed straight at
 * the stream.
 *
 * Single-strip, because the alternative buys nothing here: strips exist so a reader can seek to
 * part of an image, and every tool this file is written for reads the whole thing. It keeps the
 * header arithmetic to something that can be verified by eye, which is worth more than a
 * convention.
 */
object LinearMaster {

    /**
     * What to do with the ragged border where the frames did not all overlap.
     *
     * The session drifts and the field rotates, so the frames stop covering the same sky and the
     * usable region shrinks from the edges inwards (T-4.5). Every stack therefore has a boundary
     * question, and there is no answer that suits both people who might ask it — hence a choice
     * rather than a decision.
     *
     * **This is also the answer to what the uncovered pixels contain**, which is otherwise a
     * separate and worse question. [COMMON_AREA] has none by construction. [FULL_FRAME] keeps them
     * as `NaN`, which is the honest marker (§1.32: zero would claim a darkness nobody measured) and
     * is exactly what someone choosing that mode is asking to be handed.
     */
    enum class Crop(val label: String, val summary: String) {

        /**
         * Trim to the largest rectangle every frame covered.
         *
         * What a desktop stacker does, and the right default: the border is not faint data, it is
         * *fewer frames* — a strip that one frame in twenty contributed to, at a twentieth of the
         * depth and with none of the rejection working. Left in, it survives the autostretch as a
         * bright noisy frame around the image, and it is the first thing anyone would crop off by
         * hand.
         */
        COMMON_AREA(
            "Trim to overlap",
            "Crops to the largest rectangle every frame covered. Loses a few percent at the edges " +
                "and leaves no partial-depth border.",
        ),

        /**
         * Keep the reference frame's full geometry, uncovered pixels and all.
         *
         * For anyone who wants to do their own thing with the border, or who needs the master to
         * line up pixel-for-pixel with the subs it came from — a crop silently changes the
         * coordinate system, which matters if the frames are going to be re-registered against
         * something else later.
         */
        FULL_FRAME(
            "Keep full frame",
            "Writes the whole reference frame. The edges carry fewer frames, and pixels no frame " +
                "covered are NaN.",
        ),
        ;

        companion object {
            val DEFAULT = COMMON_AREA

            fun of(name: String?): Crop =
                entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }

    /** A rectangle of the master, in reference-frame pixels. */
    data class Region(val left: Int, val top: Int, val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height

        fun describe(): String = "${width}x$height at ($left, $top)"
    }

    /**
     * The region [crop] asks for.
     *
     * For [Crop.COMMON_AREA] this is the largest axis-aligned rectangle containing no uncovered
     * pixel. **Computed from the master itself rather than from `CommonArea`'s polygon**, and that
     * is a simplification worth stating: T-4.5 tracks the exact intersection of the frame
     * footprints, which is a convex polygon, and a TIFF has to be a rectangle — so the polygon
     * would need a largest-inscribed-rectangle step anyway. The finished master already marks
     * every uncovered pixel as `NaN`, so the mask is free, it is exact with respect to what
     * actually happened rather than to what the transforms predicted, and it catches interior gaps
     * that a footprint intersection cannot see at all.
     *
     * **[coverage] is what makes this mean what it says.** Without it the test is "was this pixel
     * NaN", and a pixel is only NaN when *no* frame reached it — but the reference frame covers the
     * whole reference by definition, so nothing is ever NaN and the crop keeps everything. The
     * first real session proved it: 3.72° of rotation, and `0.00% uncovered`. With the per-pixel
     * frame count the test becomes "did every frame reach this", which is the sentence the mode's
     * own name makes.
     *
     * Falls back to the full frame if nothing was covered, because a zero-pixel TIFF helps nobody
     * and the caller is told either way.
     */
    fun regionFor(
        master: FloatArray,
        width: Int,
        height: Int,
        crop: Crop,
        coverage: ShortArray? = null,
        frames: Int = 0,
    ): Region {
        val whole = Region(0, 0, width, height)
        if (crop == Crop.FULL_FRAME) return whole
        return largestCoveredRectangle(master, width, height, coverage, frames) ?: whole
    }

    /**
     * The largest all-covered axis-aligned rectangle, by the standard histogram scan.
     *
     * Each row is turned into a histogram of "how many covered rows reach up from here", and the
     * largest rectangle under a histogram is found with one monotonic stack per row. That is
     * `O(width × height)` overall — one pass over a 12.6 MP mask, against the alternative of
     * testing candidate rectangles, which is not affordable at this size.
     */
    private fun largestCoveredRectangle(
        master: FloatArray,
        width: Int,
        height: Int,
        coverage: ShortArray?,
        frames: Int,
    ): Region? {
        if (width <= 0 || height <= 0) return null
        // Full coverage where it is known. A pixel one frame reached is not uncovered — it is a
        // hundred times shallower than the rest of the master, with none of the rejection working,
        // and it is exactly the partial-depth border this mode exists to remove.
        val required = if (coverage != null && frames > 0) frames else 0
        val heights = IntArray(width + 1)
        // One extra slot, held at height 0, so the stack is guaranteed to drain at the end of every
        // row without a second loop saying so.
        val stack = IntArray(width + 2)

        var best = 0L
        var bestRegion: Region? = null

        for (y in 0 until height) {
            val rowBase = y.toLong() * width * TiledStacker.CHANNELS
            for (x in 0 until width) {
                val covered = if (required > 0) {
                    coverage!![y * width + x].toInt() >= required
                } else {
                    // No coverage map: fall back to "some frame reached it", which is all a NaN
                    // mask can say. Channel 0 speaks for the pixel, since coverage is decided
                    // before the debayer splits it into three.
                    !master[(rowBase + x.toLong() * TiledStacker.CHANNELS).toInt()].isNaN()
                }
                heights[x] = if (covered) heights[x] + 1 else 0
            }

            var top = 0
            for (x in 0..width) {
                val h = heights[x]
                while (top > 0 && heights[stack[top - 1]] >= h) {
                    val popped = stack[--top]
                    val left = if (top == 0) 0 else stack[top - 1] + 1
                    val rectWidth = x - left
                    val rectHeight = heights[popped]
                    val area = rectWidth.toLong() * rectHeight
                    if (area > best) {
                        best = area
                        bestRegion = Region(left, y - rectHeight + 1, rectWidth, rectHeight)
                    }
                }
                stack[top++] = x
            }
        }
        return if (best > 0L) bestRegion else null
    }

    /**
     * Writes [region] of [master] as a 32-bit float RGB TIFF.
     *
     * @param description goes in `ImageDescription`, so the file carries its own provenance —
     *   FR-9.2's audit trail travels with the artefact rather than only in the folder it came
     *   from, which matters the moment someone copies one TIFF to a PC.
     * @return the bytes written.
     */
    fun write(
        file: File,
        master: FloatArray,
        width: Int,
        height: Int,
        region: Region = Region(0, 0, width, height),
        description: String = "",
    ): Long {
        require(region.left >= 0 && region.top >= 0) { "negative origin" }
        require(region.width > 0 && region.height > 0) { "empty region" }
        require(region.left + region.width <= width) { "region runs past the frame's width" }
        require(region.top + region.height <= height) { "region runs past the frame's height" }
        require(master.size >= width.toLong() * height * TiledStacker.CHANNELS) {
            "master is smaller than ${width}x$height"
        }

        file.parentFile?.mkdirs()
        BufferedOutputStream(file.outputStream(), STREAM_BUFFER).use { out ->
            val bytes = writeTo(out, master, width, region, description)
            out.flush()
            return bytes
        }
    }

    private fun writeTo(
        out: OutputStream,
        master: FloatArray,
        frameWidth: Int,
        region: Region,
        description: String,
    ): Long {
        val channels = TiledStacker.CHANNELS
        val bytesPerRow = region.width.toLong() * channels * 4
        val pixelBytes = bytesPerRow * region.height

        // TIFF type 2 is NUL-terminated ASCII, and the terminator counts towards the length.
        val descriptionBytes = (description.take(MAX_DESCRIPTION) + NUL_TERMINATOR)
            .toByteArray(Charsets.US_ASCII)
        val softwareBytes = (SOFTWARE + NUL_TERMINATOR).toByteArray(Charsets.US_ASCII)
        val bitsBytes = shortsOf(32, 32, 32)
        val sampleFormatBytes = shortsOf(FLOAT_SAMPLES, FLOAT_SAMPLES, FLOAT_SAMPLES)

        // Tags ascending, as TIFF requires — a reader is entitled to binary-search them.
        val tagCount = 13
        val ifdOffset = 8
        val payloadBase = ifdOffset + 2 + tagCount * 12 + 4

        // Anything of four bytes or fewer lives inside the entry; anything larger goes after the
        // IFD and is referenced by offset. Getting that backwards for a short value writes a byte
        // offset where the reader expects the value itself, which is how an ImageDescription of
        // three characters produces a file nothing will open.
        val payload = java.io.ByteArrayOutputStream()
        fun place(bytes: ByteArray): Long {
            val at = payloadBase + payload.size()
            payload.write(bytes)
            if (payload.size() % 2 != 0) payload.write(0) // keep the next value word-aligned
            return at.toLong()
        }

        val entries = java.io.ByteArrayOutputStream()
        fun u16(to: java.io.ByteArrayOutputStream, v: Int) {
            to.write(v and 0xFF)
            to.write((v ushr 8) and 0xFF)
        }
        fun u32(to: java.io.ByteArrayOutputStream, v: Long) {
            to.write((v and 0xFF).toInt())
            to.write(((v ushr 8) and 0xFF).toInt())
            to.write(((v ushr 16) and 0xFF).toInt())
            to.write(((v ushr 24) and 0xFF).toInt())
        }

        /** A tag whose value is a single number, always inline. */
        fun scalar(tag: Int, type: Int, value: Long) {
            u16(entries, tag)
            u16(entries, type)
            u32(entries, 1)
            // A SHORT of count 1 sits in the *low* half of the four value bytes, not the high.
            if (type == SHORT) {
                u16(entries, value.toInt())
                u16(entries, 0)
            } else {
                u32(entries, value)
            }
        }

        /** A tag whose value is a byte run: inline when it fits, by offset when it does not. */
        fun run(tag: Int, type: Int, count: Int, bytes: ByteArray) {
            u16(entries, tag)
            u16(entries, type)
            u32(entries, count.toLong())
            if (bytes.size <= 4) {
                entries.write(bytes)
                repeat(4 - bytes.size) { entries.write(0) }
            } else {
                u32(entries, place(bytes))
            }
        }

        scalar(TAG_IMAGE_WIDTH, LONG, region.width.toLong())
        scalar(TAG_IMAGE_LENGTH, LONG, region.height.toLong())
        run(TAG_BITS_PER_SAMPLE, SHORT, 3, bitsBytes)
        scalar(TAG_COMPRESSION, SHORT, COMPRESSION_NONE)
        scalar(TAG_PHOTOMETRIC, SHORT, PHOTOMETRIC_RGB)
        run(TAG_IMAGE_DESCRIPTION, ASCII, descriptionBytes.size, descriptionBytes)
        // The strip offset is not known until the payload is complete, so its four value bytes are
        // patched below rather than guessed at.
        val stripOffsetsAt = entries.size() + 8
        scalar(TAG_STRIP_OFFSETS, LONG, 0)
        scalar(TAG_SAMPLES_PER_PIXEL, SHORT, channels.toLong())
        scalar(TAG_ROWS_PER_STRIP, LONG, region.height.toLong())
        scalar(TAG_STRIP_BYTE_COUNTS, LONG, pixelBytes)
        scalar(TAG_PLANAR_CONFIG, SHORT, PLANAR_CHUNKY)
        run(TAG_SOFTWARE, ASCII, softwareBytes.size, softwareBytes)
        // 339, last because it is the highest tag number and essential because without it the
        // reader assumes unsigned integers — see the class note.
        run(TAG_SAMPLE_FORMAT, SHORT, 3, sampleFormatBytes)

        val entryBytes = entries.toByteArray()
        check(entryBytes.size == tagCount * 12) {
            "wrote ${entryBytes.size / 12} tags, declared $tagCount"
        }

        var pixelOffset = (payloadBase + payload.size()).toLong()
        if (pixelOffset % 2 != 0L) pixelOffset++
        entryBytes[stripOffsetsAt] = (pixelOffset and 0xFF).toByte()
        entryBytes[stripOffsetsAt + 1] = ((pixelOffset ushr 8) and 0xFF).toByte()
        entryBytes[stripOffsetsAt + 2] = ((pixelOffset ushr 16) and 0xFF).toByte()
        entryBytes[stripOffsetsAt + 3] = ((pixelOffset ushr 24) and 0xFF).toByte()

        val header = java.io.ByteArrayOutputStream(pixelOffset.toInt())
        // Little-endian, magic 42, first IFD at byte 8.
        header.write('I'.code)
        header.write('I'.code)
        u16(header, 42)
        u32(header, ifdOffset.toLong())
        u16(header, tagCount)
        header.write(entryBytes)
        u32(header, 0) // no next IFD
        payload.writeTo(header)
        while (header.size() < pixelOffset) header.write(0)
        header.writeTo(out)

        // Rows, converted through one reusable buffer. Nothing below allocates.
        val row = ByteArray(bytesPerRow.toInt())
        for (y in 0 until region.height) {
            val srcRow = (region.top + y).toLong() * frameWidth * channels
            var w = 0
            for (x in 0 until region.width) {
                val src = (srcRow + (region.left + x).toLong() * channels).toInt()
                for (c in 0 until channels) {
                    // Raw bits, not `toBits`: NaN must survive as the NaN the stack produced
                    // rather than being collapsed to a canonical one.
                    val bits = java.lang.Float.floatToRawIntBits(master[src + c])
                    row[w++] = (bits and 0xFF).toByte()
                    row[w++] = ((bits ushr 8) and 0xFF).toByte()
                    row[w++] = ((bits ushr 16) and 0xFF).toByte()
                    row[w++] = ((bits ushr 24) and 0xFF).toByte()
                }
            }
            out.write(row)
        }

        return pixelOffset + pixelBytes
    }

    /** Little-endian SHORTs, for the two tags that carry one per channel. */
    private fun shortsOf(vararg values: Int): ByteArray {
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** A linear master read back off disk, possibly decimated — see [read]. */
    class Image(val pixels: FloatArray, val width: Int, val height: Int, val step: Int) {
        val description: String get() = "${width}x$height" + if (step > 1) " (1 in $step)" else ""
    }

    /**
     * Reads a master this object wrote.
     *
     * ### Why the app reads its own output
     *
     * FR-8.3 wants the auto-edit re-runnable from the linear master at any time, so that moving a
     * slider costs seconds rather than the thirteen minutes of a restack. That only works if the
     * linear data can be got back without the frames it came from — which is the whole point of
     * FR-8.2 keeping it, and it is no use kept if nothing can open it.
     *
     * ### Decimated, because the slider has to keep up
     *
     * At full size this is 132 MB and every re-render walks all of it. [maxWidth] takes every
     * *n*-th pixel instead, and a 970-pixel-wide copy is 8 MB and re-renders in a blink — ample for
     * a phone screen, and the full-size render is done once when the answer is settled.
     *
     * **Decimation, not averaging.** Averaging would be a better image and a worse *preview*: it
     * would quietly reduce the noise, so the stretch measured on it would not be the stretch the
     * full-size render needs, and the slider would lie about what it was setting.
     *
     * @return null if the file is not a master this object wrote. Deliberately strict — there is
     *   exactly one producer, and a tolerant reader here would be inventing support for files that
     *   do not exist.
     */
    fun read(file: File, maxWidth: Int = 0): Image? {
        if (!file.isFile) return null
        return RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(HEADER_SCAN.coerceAtMost(raf.length().toInt()))
            raf.readFully(header)
            val buffer = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (buffer.getShort(0).toInt() != 0x4949 || buffer.getShort(2).toInt() != 42) return null

            val ifd = buffer.getInt(4)
            if (ifd + 2 > header.size) return null
            val entries = buffer.getShort(ifd).toInt()
            val tags = HashMap<Int, Long>()
            val counts = HashMap<Int, Int>()
            for (i in 0 until entries) {
                val at = ifd + 2 + i * 12
                if (at + 12 > header.size) return null
                val tag = buffer.getShort(at).toInt() and 0xFFFF
                val type = buffer.getShort(at + 2).toInt()
                counts[tag] = buffer.getInt(at + 4)
                tags[tag] = if (type == SHORT) {
                    (buffer.getShort(at + 8).toInt() and 0xFFFF).toLong()
                } else {
                    buffer.getInt(at + 8).toLong() and 0xFFFFFFFFL
                }
            }

            val width = tags[TAG_IMAGE_WIDTH]?.toInt() ?: return null
            val height = tags[TAG_IMAGE_LENGTH]?.toInt() ?: return null
            val start = tags[TAG_STRIP_OFFSETS] ?: return null
            if (width <= 0 || height <= 0) return null
            // The three that say this is ours: uncompressed, three channels, and — the one that
            // matters — IEEE float rather than the integers TIFF assumes by default.
            if (tags[TAG_COMPRESSION]?.toInt() != COMPRESSION_NONE.toInt()) return null
            if (tags[TAG_SAMPLES_PER_PIXEL]?.toInt() != TiledStacker.CHANNELS) return null
            if (!isFloat(buffer, tags, counts, header.size)) return null

            val step = stepFor(width, maxWidth)
            val outW = (width + step - 1) / step
            val outH = (height + step - 1) / step
            val rowBytes = width.toLong() * TiledStacker.CHANNELS * 4
            val row = ByteArray(rowBytes.toInt())
            val floats = FloatArray(width * TiledStacker.CHANNELS)
            val out = FloatArray(outW * outH * TiledStacker.CHANNELS)

            for (oy in 0 until outH) {
                raf.seek(start + (oy.toLong() * step) * rowBytes)
                raf.readFully(row)
                java.nio.ByteBuffer.wrap(row)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                    .get(floats)
                for (ox in 0 until outW) {
                    val from = ox * step * TiledStacker.CHANNELS
                    val to = (oy * outW + ox) * TiledStacker.CHANNELS
                    out[to] = floats[from]
                    out[to + 1] = floats[from + 1]
                    out[to + 2] = floats[from + 2]
                }
            }
            Image(out, outW, outH, step)
        }
    }

    /** `SampleFormat = 3` on every channel — the tag [write] exists to get right. */
    private fun isFloat(
        buffer: java.nio.ByteBuffer,
        tags: Map<Int, Long>,
        counts: Map<Int, Int>,
        headerSize: Int,
    ): Boolean {
        val count = counts[TAG_SAMPLE_FORMAT] ?: return false
        val value = tags[TAG_SAMPLE_FORMAT] ?: return false
        // Three SHORTs do not fit in the entry, so the value is an offset into the payload.
        if (count * 2 <= 4) return value.toInt() == FLOAT_SAMPLES
        val at = value.toInt()
        if (at + count * 2 > headerSize) return false
        for (i in 0 until count) {
            if ((buffer.getShort(at + i * 2).toInt() and 0xFFFF) != FLOAT_SAMPLES) return false
        }
        return true
    }

    private fun stepFor(width: Int, maxWidth: Int): Int {
        if (maxWidth <= 0 || width <= maxWidth) return 1
        return (width + maxWidth - 1) / maxWidth
    }

    /** Enough for the header and its payload; the pixels start a few hundred bytes in. */
    private const val HEADER_SCAN = 4096

    // TIFF tag numbers and the handful of type codes used here.
    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_IMAGE_DESCRIPTION = 270
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_PLANAR_CONFIG = 284
    private const val TAG_SOFTWARE = 305
    private const val TAG_SAMPLE_FORMAT = 339

    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4

    private const val COMPRESSION_NONE = 1L
    private const val PHOTOMETRIC_RGB = 2L
    private const val PLANAR_CHUNKY = 1L

    /** IEEE floating point. The value that decides whether this file reads as data or as noise. */
    private const val FLOAT_SAMPLES = 3

    /**
     * The ASCII terminator, written as an escape rather than as a literal NUL in the source.
     * A raw NUL in a `.kt` file makes ripgrep treat the whole file as binary and skip it,
     * which `DngReader.kt` demonstrates.
     */
    private const val NUL_TERMINATOR = "\u0000"

    private const val MAX_DESCRIPTION = 1024
    private const val SOFTWARE = "StarStacker"
    private const val STREAM_BUFFER = 1 shl 16

    /** FR-9.1's name for it, under the session's `master/`. */
    const val FILE_NAME = "stack_linear.tif"
}
