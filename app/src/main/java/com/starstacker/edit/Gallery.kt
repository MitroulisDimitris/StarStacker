package com.starstacker.edit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * T-7.6 / FR-9.3 — the finished picture, published where a phone keeps pictures.
 *
 * ### Why this is a requirement and not a convenience
 *
 * FR-9.4 is blunt: *"the user must never have to hunt for where the output went"*. Until now the
 * JPEG lands in the session folder, which is app-private storage on this device — invisible to the
 * gallery, invisible over USB, and gone if the app is uninstalled. Someone who has just waited
 * thirteen minutes for a stack should find it in the same place as every other photograph they
 * took, not by browsing to `Android/data`.
 *
 * ### One copy, and the session folder keeps its own
 *
 * The session folder stays the record — FR-9.1 puts `stack_stretched.jpg` in `master/` and a
 * restack overwrites it — and this is a *publication* of it. Two copies is the right answer rather
 * than an oversight: the gallery copy is the user's, to keep, share or delete, and deleting it must
 * not damage the session it came from.
 *
 * ### No permission, deliberately
 *
 * `MediaStore` inserts into the app's own collection need no runtime permission from API 29, which
 * is below `minSdk`. Asking for `WRITE_EXTERNAL_STORAGE` to save a photograph would be asking for
 * the whole library to save one file, and T-0.4's rule is that every permission has to earn itself.
 */
object Gallery {

    private const val TAG = "Gallery"

    /** The album the phone's gallery will show these under. */
    const val ALBUM = "StarStacker"

    /**
     * Publishes [jpeg] to the shared image collection.
     *
     * @param displayName what the gallery shows. The session's own name, so a person who shot four
     *   things in a night can tell them apart without opening them.
     * @return the new item, or null if the insert failed — which is reported and never fatal: the
     *   file is already safe in the session folder, and losing the convenience copy is not losing
     *   the picture.
     */
    fun publish(context: Context, jpeg: File, displayName: String): Uri? {
        if (!jpeg.isFile) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM",
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Hidden from the gallery until the bytes are all there. Without it a scanner can
                // pick up a half-written file and cache a truncated thumbnail that never refreshes.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
        if (uri == null) {
            Log.e(TAG, "MediaStore refused the insert")
            return null
        }

        return runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                jpeg.inputStream().use { it.copyTo(out) }
            } ?: error("no output stream for $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            uri
        }.getOrElse {
            Log.e(TAG, "could not write $uri", it)
            // A pending row nothing ever finished is an invisible orphan; take it back out.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
