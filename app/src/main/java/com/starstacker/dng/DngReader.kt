package com.starstacker.dng

import java.io.File
import java.io.RandomAccessFile

/**
 * T-1.6 / D-13 — a minimal TIFF/DNG reader for the files this app writes.
 *
 * Exists because FR-10.1 decouples capture from stacking: a session stacked the next morning
 * must read its frames back off disk, and Android offers no API that returns CFA data from a
 * DNG. §12.1's "RAW decoding not needed" holds only for the capture half of the app.
 *
 * Scope is deliberately narrow — uncompressed 16-bit CFA strips, which is what `DngCreator`
 * produces (measured: §1.6). Anything else fails loudly rather than returning plausible
 * nonsense, because a silently misparsed frame corrupts a stack without leaving a trace.
 *
 * No Android dependencies, so it is unit-testable on the JVM.
 */
class DngParseException(message: String) : Exception(message)

/** CFA colour codes as stored in the CFAPattern tag: 0 = red, 1 = green, 2 = blue. */
data class CfaPattern(val cols: Int, val rows: Int, val codes: List<Int>) {
    val name: String
        get() = codes.joinToString("") {
            when (it) {
                0 -> "R"; 1 -> "G"; 2 -> "B"; else -> "?"
            }
        }
}

data class DngMetadata(
    val width: Int,
    val height: Int,
    val bitsPerSample: Int,
    val compression: Int,
    val photometricInterpretation: Int,
    val rowsPerStrip: Int,
    val stripCount: Int,
    val cfaPattern: CfaPattern?,
    /** Per-CFA-channel black levels, in the same order as the CFA pattern. */
    val blackLevels: List<Double>,
    val whiteLevel: Int?,
    /** top, left, bottom, right — the region containing real image data. */
    val activeArea: List<Int>?,
    val isoSpeed: Int?,
    val exposureSeconds: Double?,
    val uniqueCameraModel: String?,
) {
    val pixelCount: Int get() = width * height
}

/** Metadata plus the CFA plane, one unsigned 16-bit sample per pixel, row-major. */
class DngImage(val metadata: DngMetadata, val pixels: ShortArray) {
    /** Sample at (x, y) widened to an Int, since ShortArray holds these as signed. */
    fun sample(x: Int, y: Int): Int = pixels[y * metadata.width + x].toInt() and 0xFFFF
}

object DngReader {

    // TIFF / DNG tags used here. Everything else in the file is ignored.
    private const val NEW_SUBFILE_TYPE = 254
    private const val IMAGE_WIDTH = 256
    private const val IMAGE_LENGTH = 257
    private const val BITS_PER_SAMPLE = 258
    private const val COMPRESSION = 259
    private const val PHOTOMETRIC = 262
    private const val STRIP_OFFSETS = 273
    private const val ROWS_PER_STRIP = 278
    private const val STRIP_BYTE_COUNTS = 279
    private const val SUB_IFDS = 330
    private const val EXPOSURE_TIME = 33434
    private const val ISO_SPEED_RATINGS = 34855
    private const val CFA_REPEAT_PATTERN_DIM = 33421
    private const val CFA_PATTERN = 33422
    private const val UNIQUE_CAMERA_MODEL = 50708
    private const val BLACK_LEVEL = 50714
    private const val WHITE_LEVEL = 50717
    private const val ACTIVE_AREA = 50829

    private const val PHOTOMETRIC_CFA = 32803
    private const val COMPRESSION_NONE = 1
    private const val SUPPORTED_BITS = 16

    fun readMetadata(file: File): DngMetadata =
        RandomAccessFile(file, "r").use { raf ->
            val tiff = TiffFile(raf)
            metadataOf(tiff, tiff.rawIfd())
        }

    fun read(file: File): DngImage =
        RandomAccessFile(file, "r").use { raf ->
            val tiff = TiffFile(raf)
            val ifd = tiff.rawIfd()
            val meta = metadataOf(tiff, ifd)
            DngImage(meta, readStrips(tiff, ifd, meta))
        }

    private fun metadataOf(tiff: TiffFile, ifd: Ifd): DngMetadata {
        val width = tiff.intAt(ifd, IMAGE_WIDTH)
            ?: throw DngParseException("no ImageWidth")
        val height = tiff.intAt(ifd, IMAGE_LENGTH)
            ?: throw DngParseException("no ImageLength")
        val bits = tiff.intAt(ifd, BITS_PER_SAMPLE) ?: SUPPORTED_BITS
        val compression = tiff.intAt(ifd, COMPRESSION) ?: COMPRESSION_NONE
        val photometric = tiff.intAt(ifd, PHOTOMETRIC) ?: -1

        if (compression != COMPRESSION_NONE) {
            throw DngParseException(
                "compression $compression is not supported — only uncompressed (1). " +
                    "A compressed DNG needs a lossless-JPEG decoder (see plan OI-1)",
            )
        }
        if (bits != SUPPORTED_BITS) {
            throw DngParseException("$bits bits per sample is not supported — only 16")
        }
        if (photometric != PHOTOMETRIC_CFA) {
            throw DngParseException(
                "photometric interpretation $photometric is not CFA ($PHOTOMETRIC_CFA) — " +
                    "this is not an undemosaiced raw frame",
            )
        }

        val cfaDim = tiff.intsAt(ifd, CFA_REPEAT_PATTERN_DIM)
        val cfaCodes = tiff.intsAt(ifd, CFA_PATTERN)
        val cfa = if (cfaDim != null && cfaCodes != null && cfaDim.size >= 2) {
            CfaPattern(cols = cfaDim[0], rows = cfaDim[1], codes = cfaCodes)
        } else {
            null
        }

        return DngMetadata(
            width = width,
            height = height,
            bitsPerSample = bits,
            compression = compression,
            photometricInterpretation = photometric,
            rowsPerStrip = tiff.intAt(ifd, ROWS_PER_STRIP) ?: height,
            stripCount = ifd.entries[STRIP_OFFSETS]?.count ?: 0,
            cfaPattern = cfa,
            blackLevels = tiff.doublesAt(ifd, BLACK_LEVEL) ?: emptyList(),
            whiteLevel = tiff.intAt(ifd, WHITE_LEVEL),
            activeArea = tiff.intsAt(ifd, ACTIVE_AREA),
            isoSpeed = tiff.intAt(ifd, ISO_SPEED_RATINGS),
            exposureSeconds = tiff.doublesAt(ifd, EXPOSURE_TIME)?.firstOrNull(),
            uniqueCameraModel = tiff.stringAt(ifd, UNIQUE_CAMERA_MODEL),
        )
    }

    /**
     * `DngCreator` writes one strip per row (measured: 3072 strips of 8192 bytes), so the strip
     * table must actually be walked — assuming a single contiguous blob would work by accident
     * on this device only if the strips happened to be laid out in order.
     */
    private fun readStrips(tiff: TiffFile, ifd: Ifd, meta: DngMetadata): ShortArray {
        val offsets = tiff.longsAt(ifd, STRIP_OFFSETS)
            ?: throw DngParseException("no StripOffsets")
        val counts = tiff.longsAt(ifd, STRIP_BYTE_COUNTS)
            ?: throw DngParseException("no StripByteCounts")
        if (offsets.size != counts.size) {
            throw DngParseException(
                "StripOffsets (${offsets.size}) and StripByteCounts (${counts.size}) disagree",
            )
        }

        val pixels = ShortArray(meta.pixelCount)
        val bytesPerRow = meta.width * 2
        var pixelIndex = 0
        val buffer = ByteArray(counts.maxOrNull()?.toInt() ?: 0)

        for (i in offsets.indices) {
            val length = counts[i].toInt()
            tiff.readFully(offsets[i], buffer, length)

            val samples = length / 2
            if (pixelIndex + samples > pixels.size) {
                throw DngParseException(
                    "strip data overruns the image: strip $i would write past " +
                        "${meta.width}x${meta.height}",
                )
            }
            // Sample order follows the file's byte order, not the platform's.
            if (tiff.littleEndian) {
                for (s in 0 until samples) {
                    val lo = buffer[s * 2].toInt() and 0xFF
                    val hi = buffer[s * 2 + 1].toInt() and 0xFF
                    pixels[pixelIndex + s] = ((hi shl 8) or lo).toShort()
                }
            } else {
                for (s in 0 until samples) {
                    val hi = buffer[s * 2].toInt() and 0xFF
                    val lo = buffer[s * 2 + 1].toInt() and 0xFF
                    pixels[pixelIndex + s] = ((hi shl 8) or lo).toShort()
                }
            }
            pixelIndex += samples

            if (length % bytesPerRow != 0 && meta.rowsPerStrip * bytesPerRow != length) {
                // Not fatal — a final short strip is legal — but worth failing on a mismatch
                // that would silently shear the image.
                if (i != offsets.lastIndex) {
                    throw DngParseException(
                        "strip $i is $length bytes, not a whole number of ${bytesPerRow}-byte rows",
                    )
                }
            }
        }

        if (pixelIndex != pixels.size) {
            throw DngParseException(
                "read $pixelIndex samples but the image needs ${pixels.size}",
            )
        }
        return pixels
    }

    // ---- TIFF plumbing --------------------------------------------------------------

    private class Entry(val tag: Int, val type: Int, val count: Int, val payloadOffset: Long)

    private class Ifd(val entries: Map<Int, Entry>)

    private class TiffFile(private val raf: RandomAccessFile) {
        val littleEndian: Boolean
        private val ifd0Offset: Long

        init {
            val header = ByteArray(8)
            raf.seek(0)
            raf.readFully(header)
            littleEndian = when {
                header[0] == 0x49.toByte() && header[1] == 0x49.toByte() -> true
                header[0] == 0x4D.toByte() && header[1] == 0x4D.toByte() -> false
                else -> throw DngParseException("not a TIFF: bad byte-order mark")
            }
            val magic = u16(header, 2)
            if (magic != 42) throw DngParseException("not a TIFF: magic $magic, expected 42")
            ifd0Offset = u32(header, 4)
        }

        /**
         * The IFD holding the CFA data. `DngCreator` puts it in IFD0 (§1.6), but the DNG spec
         * allows a thumbnail in IFD0 with the raw frame in a SubIFD, so both are handled.
         */
        fun rawIfd(): Ifd {
            val ifd0 = readIfd(ifd0Offset)
            if (intAt(ifd0, PHOTOMETRIC) == PHOTOMETRIC_CFA) return ifd0

            val subOffsets = longsAt(ifd0, SUB_IFDS) ?: LongArray(0)
            for (offset in subOffsets) {
                val sub = readIfd(offset)
                if (intAt(sub, PHOTOMETRIC) == PHOTOMETRIC_CFA &&
                    (intAt(sub, NEW_SUBFILE_TYPE) ?: 0) == 0
                ) {
                    return sub
                }
            }
            throw DngParseException("no CFA image found in IFD0 or any SubIFD")
        }

        private fun readIfd(offset: Long): Ifd {
            val countBytes = ByteArray(2)
            raf.seek(offset)
            raf.readFully(countBytes)
            val n = u16(countBytes, 0)

            val table = ByteArray(n * 12)
            raf.readFully(table)

            val entries = HashMap<Int, Entry>(n * 2)
            for (i in 0 until n) {
                val base = i * 12
                val tag = u16(table, base)
                val type = u16(table, base + 2)
                val count = u32(table, base + 4).toInt()
                val size = typeSize(type) * count
                val payload = if (size <= 4) {
                    offset + 2 + base + 8
                } else {
                    u32(table, base + 8)
                }
                entries[tag] = Entry(tag, type, count, payload)
            }
            return Ifd(entries)
        }

        fun readFully(offset: Long, into: ByteArray, length: Int) {
            raf.seek(offset)
            raf.readFully(into, 0, length)
        }

        private fun payload(entry: Entry): ByteArray {
            val bytes = ByteArray(typeSize(entry.type) * entry.count)
            raf.seek(entry.payloadOffset)
            raf.readFully(bytes)
            return bytes
        }

        fun longsAt(ifd: Ifd, tag: Int): LongArray? {
            val e = ifd.entries[tag] ?: return null
            val bytes = payload(e)
            val out = LongArray(e.count)
            for (i in 0 until e.count) {
                out[i] = when (e.type) {
                    TYPE_BYTE, TYPE_UNDEFINED -> (bytes[i].toLong() and 0xFF)
                    TYPE_SHORT -> u16(bytes, i * 2).toLong()
                    TYPE_LONG -> u32(bytes, i * 4)
                    else -> throw DngParseException("tag $tag has non-integer type ${e.type}")
                }
            }
            return out
        }

        fun intsAt(ifd: Ifd, tag: Int): List<Int>? = longsAt(ifd, tag)?.map { it.toInt() }

        fun intAt(ifd: Ifd, tag: Int): Int? = longsAt(ifd, tag)?.firstOrNull()?.toInt()

        /** Handles RATIONAL, which is how DNG stores black level and exposure time. */
        fun doublesAt(ifd: Ifd, tag: Int): List<Double>? {
            val e = ifd.entries[tag] ?: return null
            if (e.type != TYPE_RATIONAL && e.type != TYPE_SRATIONAL) {
                return longsAt(ifd, tag)?.map { it.toDouble() }
            }
            val bytes = payload(e)
            return (0 until e.count).map { i ->
                val numerator = u32(bytes, i * 8)
                val denominator = u32(bytes, i * 8 + 4)
                if (denominator == 0L) 0.0 else numerator.toDouble() / denominator
            }
        }

        fun stringAt(ifd: Ifd, tag: Int): String? {
            val e = ifd.entries[tag] ?: return null
            if (e.type != TYPE_ASCII) return null
            return String(payload(e), Charsets.US_ASCII).trimEnd(' ')
        }

        private fun u16(b: ByteArray, o: Int): Int {
            val a = b[o].toInt() and 0xFF
            val c = b[o + 1].toInt() and 0xFF
            return if (littleEndian) (c shl 8) or a else (a shl 8) or c
        }

        private fun u32(b: ByteArray, o: Int): Long {
            val b0 = (b[o].toInt() and 0xFF).toLong()
            val b1 = (b[o + 1].toInt() and 0xFF).toLong()
            val b2 = (b[o + 2].toInt() and 0xFF).toLong()
            val b3 = (b[o + 3].toInt() and 0xFF).toLong()
            return if (littleEndian) {
                (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
        }
    }

    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_SRATIONAL = 10

    private fun typeSize(type: Int): Int = when (type) {
        TYPE_BYTE, TYPE_ASCII, 6, TYPE_UNDEFINED -> 1
        TYPE_SHORT, 8 -> 2
        TYPE_LONG, 9, 11 -> 4
        TYPE_RATIONAL, TYPE_SRATIONAL, 12 -> 8
        else -> throw DngParseException("unknown TIFF type $type")
    }
}
