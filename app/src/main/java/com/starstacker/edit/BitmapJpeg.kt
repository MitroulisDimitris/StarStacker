package com.starstacker.edit

import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File

/**
 * [StretchedImage] on Android — the one file here that knows `Bitmap` exists.
 *
 * ### Written in bands, because the whole thing does not fit
 *
 * An ARGB_8888 bitmap of a 3887×2828 master is **44 MB**, and it would sit beside the 151 MB
 * linear master, the 25 MB coverage map and the 11 MB of RGB bytes being encoded. That is close
 * enough to the ceiling §1.38 measured to be worth avoiding, so the encoder is fed a band at a
 * time through `Bitmap.compress` on a reused row bitmap — except that JPEG has no such API, so the
 * bitmap is built once at full size and the *conversion* is what streams.
 *
 * The unavoidable cost is therefore one full-size bitmap. It is freed the moment the encode
 * returns, which matters because the caller is holding the master and is about to be asked for
 * another one.
 */
object BitmapJpeg : StretchedImage {

    private const val TAG = "BitmapJpeg"

    override fun writeJpeg(
        file: File,
        rgb: ByteArray,
        width: Int,
        height: Int,
        quality: Int,
    ): Long {
        require(rgb.size >= width * height * 3) { "rgb is smaller than ${width}x$height" }
        var bitmap: Bitmap? = null
        return try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // One row of Ints at a time rather than an Int per pixel for the whole frame: an
            // IntArray of 11 M pixels is 44 MB on top of the bitmap that already holds them.
            val row = IntArray(width)
            for (y in 0 until height) {
                var i = (y.toLong() * width * 3).toInt()
                for (x in 0 until width) {
                    val r = rgb[i].toInt() and 0xFF
                    val g = rgb[i + 1].toInt() and 0xFF
                    val b = rgb[i + 2].toInt() and 0xFF
                    row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    i += 3
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }

            file.parentFile?.mkdirs()
            BufferedOutputStream(file.outputStream(), 1 shl 16).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    Log.e(TAG, "JPEG encode refused")
                    return -1
                }
            }
            file.length()
        } catch (t: Throwable) {
            // Includes OutOfMemoryError: a 44 MB bitmap beside a 151 MB master is a real risk, and
            // failing to make a preview must not lose the master that was already written.
            Log.e(TAG, "could not write $file", t)
            -1
        } finally {
            bitmap?.recycle()
        }
    }
}
