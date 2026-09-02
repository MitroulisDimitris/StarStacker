package com.starstacker.dng

import java.io.File

/**
 * Writes the DNGs the tests read, so the suite needs no 25 MB fixture and no device.
 *
 * The files mirror exactly what 1.6 measured on the reference phone: uncompressed, 16-bit, CFA in
 * IFD0, one strip per row. That last property is what [DngReader.Rows] rests on, so a fixture that
 * did not have it would be testing a file shape this app never meets.
 *
 * Shared between [DngReaderTest] and the stacking tests, which need several named frames in a
 * session layout rather than one file called `synthetic.dng`.
 */
object TestDng {

    fun write(
        file: File,
        width: Int,
        height: Int,
        samples: IntArray,
        rowsPerStrip: Int,
        le: Boolean = true,
        compression: Int = 1,
        photometric: Int = 32803,
        blackLevel: Double? = null,
        /** Per-CFA-channel pedestals; takes precedence over [blackLevel] when given. */
        blackLevels: List<Double>? = null,
        whiteLevel: Int? = null,
        iso: Int? = null,
        exposureSeconds: Double? = null,
        cfaCodes: IntArray? = null,
        model: String? = null,
        dropLastStrip: Boolean = false,
    ): File {
        val stripRows = (height + rowsPerStrip - 1) / rowsPerStrip
        val stripCount = if (dropLastStrip) stripRows - 1 else stripRows
        val bytesPerRow = width * 2

        // Tag list, built in ascending tag order as TIFF requires.
        data class Tag(val tag: Int, val type: Int, val values: List<Number>)

        val tags = buildList {
            add(Tag(256, 4, listOf(width)))
            add(Tag(257, 4, listOf(height)))
            add(Tag(258, 3, listOf(16)))
            add(Tag(259, 3, listOf(compression)))
            add(Tag(262, 3, listOf(photometric)))
            add(Tag(273, 4, List(stripCount) { 0 })) // offsets patched below
            add(Tag(277, 3, listOf(1)))
            add(Tag(278, 4, listOf(rowsPerStrip)))
            add(
                Tag(
                    279, 4,
                    List(stripCount) { i ->
                        val rows = minOf(rowsPerStrip, height - i * rowsPerStrip)
                        rows * bytesPerRow
                    },
                ),
            )
            if (cfaCodes != null) {
                add(Tag(33421, 3, listOf(2, 2)))
                add(Tag(33422, 1, cfaCodes.toList()))
            }
            if (exposureSeconds != null) add(Tag(33434, 5, listOf(exposureSeconds)))
            if (iso != null) add(Tag(34855, 3, listOf(iso)))
            if (model != null) add(Tag(50708, 2, model.toList().map { it.code }))
            val black = blackLevels ?: blackLevel?.let { listOf(it) }
            if (black != null) add(Tag(50714, 5, black))
            if (whiteLevel != null) add(Tag(50717, 3, listOf(whiteLevel)))
        }.sortedBy { it.tag }

        fun typeSize(t: Int) = when (t) {
            1, 2 -> 1; 3 -> 2; 4 -> 4; 5 -> 8; else -> error("type $t")
        }

        val ifdOffset = 8
        val ifdBytes = 2 + tags.size * 12 + 4
        var payloadCursor = ifdOffset + ifdBytes
        val payloadOffsets = HashMap<Int, Int>()
        for (t in tags) {
            val size = typeSize(t.type) * t.values.size + if (t.type == 2) 1 else 0
            if (size > 4) {
                payloadOffsets[t.tag] = payloadCursor
                payloadCursor += size
            }
        }
        val pixelStart = payloadCursor
        val totalSize = pixelStart + stripCount * rowsPerStrip * bytesPerRow

        val out = ByteArray(totalSize + 16)
        var p = 0
        fun putU16(v: Int) {
            if (le) { out[p] = v.toByte(); out[p + 1] = (v shr 8).toByte() } else {
                out[p] = (v shr 8).toByte(); out[p + 1] = v.toByte()
            }
            p += 2
        }
        fun putU32(v: Long) {
            if (le) {
                out[p] = v.toByte(); out[p + 1] = (v shr 8).toByte()
                out[p + 2] = (v shr 16).toByte(); out[p + 3] = (v shr 24).toByte()
            } else {
                out[p] = (v shr 24).toByte(); out[p + 1] = (v shr 16).toByte()
                out[p + 2] = (v shr 8).toByte(); out[p + 3] = v.toByte()
            }
            p += 4
        }

        // Header
        val bom = if (le) 0x49 else 0x4D
        out[0] = bom.toByte(); out[1] = bom.toByte()
        p = 2; putU16(42); putU32(ifdOffset.toLong())

        // Strip offsets are known now.
        val stripOffsets = List(stripCount) { i -> pixelStart + i * rowsPerStrip * bytesPerRow }

        fun writeValues(t: Tag, at: Int) {
            val saved = p
            p = at
            for (v in t.values) {
                when (t.type) {
                    1 -> { out[p] = v.toInt().toByte(); p += 1 }
                    2 -> { out[p] = v.toInt().toByte(); p += 1 }
                    3 -> putU16(v.toInt())
                    4 -> putU32(v.toLong())
                    5 -> {
                        // Encode with a denominator that keeps the numerator integral.
                        val d = 1_000_000L
                        putU32((v.toDouble() * d).toLong()); putU32(d)
                    }
                }
            }
            if (t.type == 2) { out[p] = 0; p += 1 }
            p = saved
        }

        p = ifdOffset
        putU16(tags.size)
        for (t in tags) {
            val values = if (t.tag == 273) stripOffsets else t.values
            val effective = t.copy(values = values)
            putU16(t.tag); putU16(t.type); putU32(values.size.toLong())
            val size = typeSize(t.type) * values.size + if (t.type == 2) 1 else 0
            if (size <= 4) {
                writeValues(effective, p)
                p += 4
            } else {
                val off = payloadOffsets.getValue(t.tag)
                putU32(off.toLong())
                writeValues(effective, off)
            }
        }
        putU32(0) // no next IFD

        // Pixels
        p = pixelStart
        for (i in 0 until stripCount * rowsPerStrip * width) {
            putU16(if (i < samples.size) samples[i] else 0)
        }

        file.parentFile?.mkdirs()
        file.writeBytes(out.copyOf(totalSize))
        return file
    }
}
