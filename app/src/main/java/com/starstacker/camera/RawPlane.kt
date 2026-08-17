package com.starstacker.camera

import android.media.Image

/**
 * Getting the CFA samples out of a `RAW_SENSOR` image.
 *
 * The buffer is not necessarily tightly packed — the HAL is free to pad rows — so the row stride
 * has to be honoured. Assuming it is packed does not fail loudly; it shears the frame by a few
 * pixels per row, which looks like a diagonal smear and would be diagnosed as anything but a
 * stride bug.
 */
object RawPlane {

    /**
     * Copies the CFA plane into [into] when a buffer of the right size is supplied, otherwise
     * allocates one.
     *
     * The framing loop reuses buffers because a 12 MP RAW frame is 25 MB and it takes one every
     * second or two; allocating per frame would spend more time in GC than in star detection
     * (FR-12.2).
     *
     * Reads through a duplicate of the plane buffer, so a caller can still hand the same image
     * to `DngCreator` afterwards with its position untouched.
     */
    fun copy(image: Image, into: ShortArray? = null): ShortArray {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val out = if (into != null && into.size >= width * height) into else ShortArray(width * height)
        val row = ByteArray(rowStride)
        var index = 0
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            val available = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, available)
            for (x in 0 until width) {
                val at = x * pixelStride
                val lo = row[at].toInt() and 0xFF
                val hi = row[at + 1].toInt() and 0xFF
                out[index++] = ((hi shl 8) or lo).toShort()
            }
        }
        return out
    }
}
