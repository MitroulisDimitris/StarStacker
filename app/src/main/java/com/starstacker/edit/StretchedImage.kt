package com.starstacker.edit

import java.io.File

/**
 * T-7.6 / FR-9.1 — writing the stretched result out.
 *
 * Behind an interface for the same reason `TiledStacker.Resampler` is (§1.31): the encoder is
 * `android.graphics.Bitmap`, which does not exist in a JVM test, and everything interesting about
 * the auto-edit is the pixels rather than the container. [AutoEdit] produces bytes and is fully
 * tested; this hands them to the platform.
 */
interface StretchedImage {

    /**
     * @param rgb `width × height × 3` 8-bit samples, as [AutoEdit.render] returns them.
     * @return bytes written, or -1 if the encode failed.
     */
    fun writeJpeg(file: File, rgb: ByteArray, width: Int, height: Int, quality: Int = QUALITY): Long

    companion object {
        /**
         * 92. High enough that JPEG artefacts stay below the noise in a stretched astro frame,
         * which is a harder case than it sounds — a smooth, dark, low-contrast background is
         * exactly where 8×8 blocking shows, and this is the image a person actually looks at.
         */
        const val QUALITY = 92

        /** FR-9.1's name for it, under the session's `master/`. */
        const val FILE_NAME = "stack_stretched.jpg"
    }
}
