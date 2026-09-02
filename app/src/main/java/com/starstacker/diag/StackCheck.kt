package com.starstacker.diag

import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionLog
import com.starstacker.stacking.Combine
import com.starstacker.stacking.DngFrameSource
import com.starstacker.stacking.LinearMaster
import com.starstacker.stacking.Resample
import com.starstacker.stacking.TiledStacker
import java.io.File

/**
 * Phase 3 end to end on real files — the first time the stacking chain meets a DNG it did not
 * invent.
 *
 * Everything below this has tests and none of them prove the thing that matters here. T-5.2's
 * arithmetic, T-5.3's tiling and T-5.4's rejection are all verified against synthetic frames in
 * the JVM, where OpenCV cannot load and a DNG does not exist. What no desktop test can answer:
 * whether `DngReader.Rows` walks a strip table `DngCreator` actually wrote, whether the transforms
 * in `session.json` still mean what registration meant at 03:00, and what a full stack costs on a
 * phone that is warming up while it runs.
 *
 * ### What it reports, and why each number is here
 *
 * - **What was opened, and what was skipped.** A stack that quietly drops half a night is worse
 *   than one that refuses, so [DngFrameSource.skipped] is printed in full rather than counted.
 * - **Throughput, against §1.31's budget.** The warp pass measured 13–15 ms/megapixel, and §1.33
 *   estimated the combine at several times that without being able to check. This is the check.
 * - **What the rejection did.** §1.33's own warning: a clip that rejects nothing is not fast, it
 *   is broken, and on real sky the rate is the difference between "clean night" and "κ is eating
 *   the signal".
 * - **How much of the master no frame covered.** Uncovered pixels are NaN by design (§1.32), and
 *   a large count means the frames drifted further apart than the common area allows.
 *
 * ### What it produces
 *
 * **The linear master itself** (T-5.6), written to `master/stack_linear.tif` in the geometry the
 * user chose in Settings, with the session's provenance in its `ImageDescription` — so the result
 * is something to open in Siril rather than a column of numbers. The choices that moved pixels go
 * back into `session.json`, because a restack has to reproduce a master rather than approximate it.
 *
 * ### What it does not do
 *
 * **It reads a file-backed session root only.** A SAF-rooted session cannot be opened as a `File`,
 * which is T-0.5's outstanding piece rather than a limitation of this check — and it is reported
 * as such rather than as "no sessions found", because those are very different problems.
 */
object StackCheck {

    /**
     * @param root the file-backed session root — `getExternalFilesDir(null)/sessions`.
     * @param sessionName the folder to stack, or null for the most recent one that has frames.
     * @param crop T-5.6's boundary choice, as the user set it in Settings.
     */
    fun run(
        root: File,
        sessionName: String?,
        crop: LinearMaster.Crop = LinearMaster.Crop.DEFAULT,
        log: (String) -> Unit,
    ) {
        log("stack: Phase 3 against real DNGs, from ${root.path}")

        if (!Resample.available) {
            // The same lazy load T-5.1 measured. If it fails here, nothing below is worth reading.
            log("stack: OpenCV did not load — run --es diag warp first; that is the check for it")
            return
        }

        val session = pick(root, sessionName, log) ?: return
        log("stack: ${session.name}")

        val log0 = runCatching {
            SessionLog.decode(File(session, SessionLayout.SESSION_JSON).readText())
        }.getOrElse {
            log("stack: session.json will not parse — ${it.message}")
            return
        }
        log(
            "stack: log says ${log0.lights.size} lights, ${log0.accepted.size} accepted, " +
                "${log0.darks.size} darks",
        )

        val source = DngFrameSource.open(session, log0)
        if (source == null) {
            log("stack: nothing to stack")
            return
        }

        source.use { frames ->
            log("stack: ${frames.describe()}")
            frames.skipped.forEach { log("stack:   - $it") }

            val megapixels = frames.width.toDouble() * frames.height / 1_000_000.0
            val combiner = Combine.SigmaClip()
            val master = try {
                FloatArray(frames.width * frames.height * TiledStacker.CHANNELS)
            } catch (t: OutOfMemoryError) {
                // 151 MB for a 12.6 MP master. Worth failing legibly rather than as a stack trace,
                // because it is a real constraint and not a bug.
                log("stack: out of memory allocating the master (${"%.0f".format(megapixels * 12)} MB)")
                return
            }

            val started = System.nanoTime()
            val ok = TiledStacker(frames, Resample, combiner).stack(master) { progress ->
                // One line per tile is too many for a hundred-tile stack and none is too few for
                // something that runs for minutes.
                if (progress.tile == 1 || progress.tile % 10 == 0 || progress.tile == progress.tiles) {
                    log("stack:   tile ${progress.tile}/${progress.tiles}")
                }
            }
            val seconds = (System.nanoTime() - started) / 1e9

            if (!ok) {
                log("stack: FAILED — a tile did not complete, so the master is partial and discarded")
                return
            }

            log("stack: %.1f s for %d frames of %.1f MP".format(seconds, frames.count, megapixels))
            log(
                "stack: %.1f ms/megapixel/frame — §1.31 measured the warp alone at 13-15"
                    .format(seconds * 1000 / (megapixels * frames.count)),
            )
            log("stack: rejection — ${combiner.stats.describe()}")
            report(master, log)

            // T-5.6 — the linear master, in whichever geometry the user asked for.
            val region = LinearMaster.regionFor(master, frames.width, frames.height, crop)
            log("stack: ${crop.label} — writing ${region.describe()}")
            val target = File(File(session, SessionLayout.MASTER), LinearMaster.FILE_NAME)
            val written = runCatching {
                LinearMaster.write(
                    file = target,
                    master = master,
                    width = frames.width,
                    height = frames.height,
                    region = region,
                    description = provenance(log0, frames, combiner, crop, region),
                )
            }.getOrElse {
                log("stack: could not write the master — ${it.message}")
                return
            }
            log("stack: wrote ${target.path}")
            log("stack: %.1f MB".format(written / 1_000_000.0))

            record(session, log0, frames, combiner, crop, region, log)
        }
    }

    /**
     * Writes what produced this master back into `session.json` (FR-9.2).
     *
     * The rule is that a restack must **reproduce** a master rather than approximate it, and the
     * app's current settings cannot say how one was made — the crop mode is a preference and can
     * have been changed since. So the choices that moved pixels are recorded against the session
     * that used them.
     */
    private fun record(
        session: File,
        log0: SessionLog,
        frames: DngFrameSource,
        combiner: Combine.SigmaClip,
        crop: LinearMaster.Crop,
        region: LinearMaster.Region,
        log: (String) -> Unit,
    ) {
        val updated = log0.copy(
            info = log0.info.copy(
                stacking = mapOf(
                    "method" to Combine.Method.SIGMA_CLIP.name,
                    "crop" to crop.name,
                    "region" to region.describe(),
                    "frames" to frames.count.toString(),
                    "calibration" to frames.masters.describe(),
                    "rejection" to combiner.stats.describe(),
                    "master" to LinearMaster.FILE_NAME,
                ),
            ),
        )
        runCatching {
            File(session, SessionLayout.SESSION_JSON).writeText(updated.encode())
        }.onFailure {
            // The TIFF is already on disk and is the thing that matters; a log that would not
            // update is worth saying out loud and not worth discarding the master for.
            log("stack: master written but session.json could not be updated — ${it.message}")
        }
    }

    /**
     * What went into this master, written into the file's own `ImageDescription`.
     *
     * FR-9.2's audit trail normally lives in `session.json`, which is the right place for it and
     * the wrong place for it the moment someone copies one TIFF to a PC and opens it a month
     * later. This is the short version, travelling with the artefact.
     */
    private fun provenance(
        log: SessionLog,
        frames: DngFrameSource,
        combiner: Combine.SigmaClip,
        crop: LinearMaster.Crop,
        region: LinearMaster.Region,
    ): String = buildString {
        append("StarStacker linear master")
        append(" | session ${log.info.sessionId}")
        if (log.info.label.isNotBlank()) append(" (${log.info.label})")
        append(" | ${frames.count} frames")
        append(" | %.0f s integration".format(log.acceptedIntegrationSeconds))
        append(" | ISO ${log.info.plannedIso}")
        append(" | ${frames.masters.describe()}")
        append(" | ${Combine.Method.SIGMA_CLIP.label}, ${combiner.stats.describe()}")
        append(" | ${crop.name} ${region.describe()}")
    }

    /** What came out, in the only terms available before T-5.6 writes an image. */
    private fun report(master: FloatArray, log: (String) -> Unit) {
        var uncovered = 0L
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        var sum = 0.0
        var counted = 0L
        for (v in master) {
            // NaN is the uncovered sentinel and Kotlin sorts it above every number (§1.32), so it
            // has to be excluded explicitly or it becomes the reported maximum.
            if (v.isNaN()) {
                uncovered++
                continue
            }
            if (v < min) min = v.toDouble()
            if (v > max) max = v.toDouble()
            sum += v
            counted++
        }
        if (counted == 0L) {
            log("stack: the master is entirely uncovered — every frame missed the reference")
            return
        }
        log(
            "stack: master min %.1f mean %.1f max %.1f · %d uncovered (%.2f%%)".format(
                min, sum / counted, max, uncovered, uncovered * 100.0 / master.size,
            ),
        )
    }

    /**
     * The session to stack: the one named, or the most recent that has any lights in it.
     *
     * "Most recent" is the folder name, which sorts chronologically by construction
     * ([SessionLayout.folderName] begins with a timestamp) — no file dates involved, since a folder
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
