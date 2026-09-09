package com.starstacker.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * T-6.1 — the session list's thumbnails.
 *
 * ### Loaded on demand, and only once
 *
 * A root can hold a season of nights, and the list draws five rows. Decoding every preview up
 * front would make the pane's cost grow with the archive rather than with what is on screen, which
 * is the same mistake the scan is being measured for under OI-5.
 *
 * So [get] returns whatever is already decoded — usually nothing, the first time — and starts a
 * load for anything it has not seen. The row redraws when the load lands, which is why the slot is
 * a fixed size: a list whose rows grow as images arrive jumps under a thumb that is already moving.
 *
 * ### Why failures are cached too
 *
 * A session with no master, a preview that has been deleted, and a JPEG a PC has corrupted all
 * produce the same thing: no bitmap. Caching the *absence* is what stops the list retrying the
 * decode on every scroll — otherwise the cheapest state to be in, having no previews at all, would
 * be the most expensive to draw.
 */
class SessionThumbnails(
    private val root: File,
    private val scope: CoroutineScope,
) {
    /**
     * Folder name to bitmap. **Absent means "not looked at"; present-and-null means "there is
     * none"** — the distinction that keeps a missing preview from being decoded again every frame.
     */
    private val cache = mutableStateMapOf<String, ImageBitmap?>()

    /** Folders with a load in flight, so a scroll cannot start the same decode ten times. */
    private val loading = mutableSetOf<String>()

    fun get(summary: SessionSummary): ImageBitmap? {
        val name = summary.folderName
        if (cache.containsKey(name)) return cache[name]
        if (summary.previewFileName == null) {
            cache[name] = null
            return null
        }
        if (!loading.add(name)) return null

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(name, summary.previewFileName) }
            cache[name] = bitmap
            loading -= name
        }
        return null
    }

    /** Forgets everything, for after a rescan — a restack changes the picture behind a row. */
    fun clear() {
        cache.clear()
        loading.clear()
    }

    private fun decode(folderName: String, previewFileName: String): ImageBitmap? {
        // The name is relative to `master/`, which is what lets it survive a session going to a PC
        // and coming back under a different absolute path (see SessionSummary.previewFileName).
        val file = File(File(File(root, folderName), SessionLayout.MASTER), previewFileName)
        if (!file.isFile) return null

        return runCatching {
            // Two passes: measure, then decode subsampled. A stretched master is a full-resolution
            // JPEG — decoding one at full size to draw it 54 dp wide would allocate tens of
            // megabytes per row, which on a list is how an app dies rather than how it is slow.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return@runCatching null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
        }.getOrNull()
    }

    private companion object {
        /**
         * Roughly the widest the slot is ever drawn, in pixels.
         *
         * `inSampleSize` only halves, so this is a floor rather than a target: the decode lands
         * somewhere between this and twice it, which is close enough for a 54 dp box and costs one
         * power of two rather than a resample.
         */
        const val TARGET_WIDTH = 160
    }
}
