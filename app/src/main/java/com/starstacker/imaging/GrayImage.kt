package com.starstacker.imaging

/**
 * An 8-bit grey raster — what the framing preview shows.
 *
 * Rotation lives here rather than in the UI because the sensor is landscape and the phone is
 * held portrait: a preview shown in sensor orientation is rotated 90° from the sky the user is
 * looking at, which makes framing actively misleading. Rotating the pixels once, off the main
 * thread, is cheaper and far less error-prone than fighting the layout to rotate a bitmap that
 * has already been measured.
 *
 * Not a data class: [pixels] is an array, and array identity semantics in `equals` are a trap.
 */
class GrayImage(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(pixels.size >= width * height) { "buffer is smaller than ${width}x$height" }
    }

    /** @param degrees clockwise; must be a multiple of 90. */
    fun rotated(degrees: Int): GrayImage {
        val turns = ((degrees / 90) % 4 + 4) % 4
        require(degrees % 90 == 0) { "rotation must be a multiple of 90°, was $degrees" }
        if (turns == 0) return this

        val outWidth = if (turns % 2 == 0) width else height
        val outHeight = if (turns % 2 == 0) height else width
        val out = ByteArray(outWidth * outHeight)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val destination = when (turns) {
                    1 -> x * outWidth + (outWidth - 1 - y)          // 90° clockwise
                    2 -> (outHeight - 1 - y) * outWidth + (outWidth - 1 - x)
                    else -> (outHeight - 1 - x) * outWidth + y      // 270° clockwise
                }
                out[destination] = pixels[row + x]
            }
        }
        return GrayImage(out, outWidth, outHeight)
    }

    /** Expands to packed ARGB for a Bitmap. Grey, so all three channels carry the same byte. */
    fun toArgb(out: IntArray = IntArray(width * height)): IntArray {
        require(out.size >= width * height) { "output is smaller than ${width}x$height" }
        val n = width * height
        var i = 0
        while (i < n) {
            val v = pixels[i].toInt() and 0xFF
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            i++
        }
        return out
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}
