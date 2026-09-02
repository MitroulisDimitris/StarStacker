package com.starstacker.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.starstacker.edit.AutoEdit
import com.starstacker.edit.BitmapJpeg
import com.starstacker.edit.Gallery
import com.starstacker.edit.StretchedImage
import com.starstacker.session.SessionLayout
import com.starstacker.stacking.LinearMaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * T-7.5 / FR-8.3 — the auto-edit screen's state, and the reason a slider is affordable at all.
 *
 * ### Two resolutions, and that is the whole design
 *
 * Re-rendering a 3887×2828 master takes seconds — fine once, hopeless while a finger is moving. So
 * the linear master is loaded **decimated** to about a screen's worth of pixels, and every slider
 * movement re-renders *that*: 8 MB, a blink, and visually the same decision. Committing writes the
 * full-size JPEG once, from the full-size data, when the answer is settled.
 *
 * The preview is honest about being a preview in the one way that matters: [LinearMaster.read]
 * decimates rather than averaging, so the noise the stretch is measured against is the real noise
 * and the slider is setting what it appears to be setting.
 *
 * ### Every render starts from the linear master
 *
 * Not from the last render. FR-8.2 keeps the linear data precisely so the edit is a *view* of it
 * rather than a sequence of destructive steps, and it is what makes the slider a slider rather than
 * a ratchet — drag it back and you get exactly what you had, because nothing accumulated.
 */
class ResultController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    data class State(
        val folderName: String = "",
        val loading: Boolean = false,
        /** The decimated render at the current settings, or null while it is being made. */
        val preview: Bitmap? = null,
        /** The same data with no edit at all — FR-8.3's before/after. */
        val linear: Bitmap? = null,
        val showingBefore: Boolean = false,
        val settings: AutoEdit.Settings = AutoEdit.Settings(),
        val advanced: Boolean = false,
        val report: String? = null,
        val savedTo: String? = null,
        val busy: String? = null,
        val error: String? = null,
    ) {
        val ready: Boolean get() = preview != null
    }

    var state by mutableStateOf(State())
        private set

    private var source: LinearMaster.Image? = null
    private var sessionDir: File? = null
    private var render: Job? = null

    /**
     * Loads a session's linear master, decimated, and renders it once at the default.
     *
     * Off the main thread: this reads 132 MB from flash to keep 8 MB of it.
     */
    fun open(dir: File, defaults: AutoEdit.Settings = AutoEdit.Settings()) {
        sessionDir = dir
        state = State(folderName = dir.name, loading = true, settings = defaults)
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                LinearMaster.read(File(File(dir, SessionLayout.MASTER), LinearMaster.FILE_NAME), PREVIEW_WIDTH)
            }
            if (loaded == null) {
                state = state.copy(loading = false, error = "no linear master in this session")
                return@launch
            }
            source = loaded
            // The unedited view, once: it never changes, so re-rendering it per toggle would be
            // work done to show something already known.
            val before = withContext(Dispatchers.Default) { linearBitmap(loaded) }
            state = state.copy(loading = false, linear = before)
            rerender(defaults)
        }
    }

    /** FR-8.3's one slider. */
    fun setStrength(strength: Double) {
        rerender(state.settings.copy(strength = strength.coerceIn(0.0, 1.0)))
    }

    fun setGradientDegree(degree: Int) = rerender(state.settings.copy(gradientDegree = degree))

    fun setSaturation(value: Double?) = rerender(state.settings.copy(saturation = value))

    fun toggleBefore() {
        state = state.copy(showingBefore = !state.showingBefore)
    }

    fun toggleAdvanced() {
        state = state.copy(advanced = !state.advanced)
    }

    /**
     * Re-renders the decimated copy.
     *
     * The previous render is cancelled rather than queued: a finger crossing the slider produces
     * dozens of these, and the only one anybody wants is the last.
     */
    private fun rerender(settings: AutoEdit.Settings) {
        val image = source ?: return
        state = state.copy(settings = settings)
        render?.cancel()
        render = scope.launch {
            val (bitmap, report) = withContext(Dispatchers.Default) {
                // A copy per render, because renderInPlace consumes what it is given and the source
                // has to survive for the next movement of the slider.
                val work = image.pixels.copyOf()
                val (rgb, r) = AutoEdit.renderInPlace(work, image.width, image.height, settings)
                bitmapOf(rgb, image.width, image.height) to r
            }
            state = state.copy(preview = bitmap, report = r(report))
        }
    }

    private fun r(report: AutoEdit.Report): String = report.describe()

    /**
     * Renders at full size, writes `master/stack_stretched.jpg`, and publishes it (T-7.6).
     *
     * This is the expensive one — the full 132 MB read and edited — so it happens on a button
     * rather than on every slider movement, and it says what it is doing while it runs.
     */
    fun save() {
        val dir = sessionDir ?: return
        if (state.busy != null) return
        state = state.copy(busy = "Rendering at full size", savedTo = null, error = null)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val master = File(File(dir, SessionLayout.MASTER), LinearMaster.FILE_NAME)
                    val full = LinearMaster.read(master) ?: error("the linear master will not open")
                    val (rgb, _) = AutoEdit.renderInPlace(
                        full.pixels, full.width, full.height, state.settings,
                    )
                    val jpeg = File(File(dir, SessionLayout.MASTER), StretchedImage.FILE_NAME)
                    val bytes = BitmapJpeg.writeJpeg(jpeg, rgb, full.width, full.height)
                    if (bytes <= 0) error("the JPEG could not be written")
                    Gallery.publish(context, jpeg, dir.name)?.let { "Pictures/${Gallery.ALBUM}" }
                        ?: jpeg.path
                }
            }
            state = result.fold(
                onSuccess = { state.copy(busy = null, savedTo = it) },
                onFailure = { state.copy(busy = null, error = it.message ?: "could not save") },
            )
        }
    }

    /** The linear data with no edit — a plain normalisation, so "before" means before. */
    private fun linearBitmap(image: LinearMaster.Image): Bitmap {
        val count = image.width * image.height * 3
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until count) {
            val v = image.pixels[i]
            if (!v.isFinite()) continue
            if (v < min) min = v
            if (v > max) max = v
        }
        val span = (max - min).coerceAtLeast(1e-6f)
        val rgb = ByteArray(count)
        for (i in 0 until count) {
            val v = ((image.pixels[i] - min) / span).coerceIn(0f, 1f)
            rgb[i] = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return bitmapOf(rgb, image.width, image.height)
    }

    private fun bitmapOf(rgb: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            var i = y * width * 3
            for (x in 0 until width) {
                val r = rgb[i].toInt() and 0xFF
                val g = rgb[i + 1].toInt() and 0xFF
                val b = rgb[i + 2].toInt() and 0xFF
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i += 3
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    fun close() {
        render?.cancel()
        source = null
        sessionDir = null
        state = State()
    }

    companion object {
        /**
         * Pixels across the interactive preview.
         *
         * Wider than any phone screen, so the preview is not the limit on what can be judged, and
         * narrow enough that a render is imperceptible: 1024×745×3 floats is 9 MB against the
         * full frame's 132.
         */
        const val PREVIEW_WIDTH = 1024
    }
}
