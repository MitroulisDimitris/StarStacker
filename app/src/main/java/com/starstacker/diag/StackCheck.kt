package com.starstacker.diag

import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionLog
import com.starstacker.stacking.Resample
import com.starstacker.stacking.StackJob
import com.starstacker.stacking.StackSettings
import java.io.File

/**
 * Phase 3 end to end on real files, driven from `adb` — the first time the stacking chain meets a
 * DNG it did not invent.
 *
 * ### What only a phone can answer
 *
 * T-5.2's arithmetic, T-5.3's tiling, T-5.4's rejection and T-5.6's writer all have JVM tests, and
 * none of them prove the thing that matters here. OpenCV cannot load off-device (§1.31), a DNG
 * written by `DngCreator` does not exist in a test, and the cost of the whole chain on a phone
 * that is warming up while it runs is not a question a desktop JIT can answer.
 *
 * So this measures: whether `DngReader.Rows` walks a strip table the camera actually wrote,
 * whether OpenCV's band warp agrees with the stub the loop was proven against, what the chain
 * costs against §1.31's 13–15 ms/megapixel for the warp alone, and what the rejection did on real
 * sky — where §1.33's warning applies, that a clip rejecting nothing is not fast but broken.
 *
 * ### It runs the same code the button runs
 *
 * The pipeline lives in [StackJob], and this is a reporting wrapper around it. That is deliberate:
 * a diagnostic with its own copy of the pipeline measures something that is not what ships, and
 * the two drift the first time one is fixed. Since T-6.4 the app has a **Stack** button, so this
 * exists for the numbers rather than for reachability.
 *
 * **It reads a file-backed session root only.** A SAF-rooted session cannot be opened as a `File`
 * — T-0.5's outstanding `ParcelFileDescriptor` piece — and that is reported as itself rather than
 * as "no sessions found", because those send you looking in very different places.
 */
object StackCheck {

    /**
     * @param root the file-backed session root — `SessionRoot.fileRoot`.
     * @param sessionName the folder to stack, or null for the most recent one with a log.
     * @param settings the same value the stacking screen would pass.
     */
    fun run(
        root: File,
        sessionName: String?,
        settings: StackSettings = StackSettings(),
        log: (String) -> Unit,
    ) {
        log("stack: Phase 3 against real DNGs, from ${root.path}")

        if (!Resample.available) {
            // The same lazy load T-5.1 measured. If it fails, nothing below is worth reading.
            log("stack: OpenCV did not load — run --es diag warp first; that is the check for it")
            return
        }

        val session = pick(root, sessionName, log) ?: return
        log("stack: ${session.name} · ${settings.describe()}")

        // Read purely to report what the log claims, before the job reads it again for real. The
        // two numbers disagreeing is itself a finding.
        runCatching {
            SessionLog.decode(File(session, SessionLayout.SESSION_JSON).readText())
        }.onSuccess {
            log(
                "stack: log says ${it.lights.size} lights, ${it.accepted.size} accepted, " +
                    "${it.darks.size} darks",
            )
        }

        val result = StackJob(session, settings, Resample).run(
            onProgress = { progress ->
                // One line per tile is too many for a hundred-tile stack, and none is too few for
                // something that runs for minutes.
                val worthSaying = progress.state != StackJob.State.STACKING ||
                    progress.tile == 1 ||
                    progress.tile % 10 == 0 ||
                    progress.tile == progress.tiles
                if (worthSaying) log("stack:   ${progress.message}")
            },
        )

        result.notes.forEach { log("stack:   - $it") }

        if (!result.succeeded) {
            log("stack: ${result.state} — ${result.error ?: "no reason given"}")
            return
        }

        val megapixels = result.region?.let { it.pixels / 1_000_000.0 } ?: 0.0
        log("stack: %.1f s for %d frames".format(result.elapsedSeconds, result.frames))
        if (megapixels > 0 && result.frames > 0) {
            log(
                "stack: %.1f ms/megapixel/frame — §1.31 measured the warp alone at 13-15".format(
                    result.elapsedSeconds * 1000 / (megapixels * result.frames),
                ),
            )
        }
        result.rejection?.let { log("stack: rejection — $it") }
        result.stats?.let { log("stack: master ${it.describe()}") }
        log("stack: wrote ${result.masterFile?.path}")
        log("stack: %.1f MB · ${result.region?.describe()}".format(result.bytesWritten / 1e6))
    }

    /**
     * The session to stack: the one named, or the most recent that carries a log.
     *
     * "Most recent" is by folder name, which sorts chronologically by construction
     * ([SessionLayout.folderName] begins with a timestamp) — not by file date, since a folder
     * copied back from a PC carries whatever mtime the copy gave it.
     */
    private fun pick(root: File, sessionName: String?, log: (String) -> Unit): File? {
        if (sessionName != null) {
            val named = File(root, sessionName)
            if (!named.isDirectory) {
                log("stack: no session called '$sessionName' under ${root.path}")
                return null
            }
            return named
        }
        val candidates = root.listFiles { f -> f.isDirectory }
            ?.filter { File(it, SessionLayout.SESSION_JSON).isFile }
            ?.sortedByDescending { it.name }
            .orEmpty()
        if (candidates.isEmpty()) {
            log("stack: no sessions under ${root.path}")
            log("stack: if the session root is a folder you picked, it is SAF-backed and this")
            log("stack: check cannot open it as a file — that is T-0.5's remaining piece")
            return null
        }
        return candidates.first()
    }
}
