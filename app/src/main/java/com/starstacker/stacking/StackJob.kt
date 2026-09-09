package com.starstacker.stacking

import com.starstacker.edit.AutoEdit
import com.starstacker.edit.StretchedImage
import com.starstacker.session.ExposureSet
import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionLog
import java.io.File

/**
 * T-6.4 — stacking one session, start to finish, with no Android in it.
 *
 * ### Why this is separate from the service that runs it
 *
 * The service's job is the *platform* problem: a foreground type, a notification, a wake lock, a
 * six-hour budget, an Activity that may not exist. This is the *work*. Keeping them apart is what
 * makes the work testable — a `Resampler` is injected exactly as `TiledStacker` injects one
 * (§1.31: OpenCV cannot load off-device), so the whole chain from a folder of DNGs to a written
 * TIFF runs in a JVM test.
 *
 * It is also what lets `StackCheck` and the service share one implementation. The diagnostic ran
 * first and would otherwise have drifted into a second, slightly different pipeline — which is the
 * failure mode where the thing you measured is not the thing that ships.
 *
 * ### Cancellation is checked, not thrown
 *
 * [run] takes a `cancelled` predicate and consults it between tiles rather than relying on thread
 * interruption. A stack holds a 151 MB master and a set of open file descriptors; unwinding it
 * from an exception thrown at an arbitrary point is how a cancelled job leaves a half-written TIFF
 * that looks like a real one. Checking at a boundary means the job always stops somewhere it can
 * describe.
 *
 * **A cancelled or failed run writes nothing.** A partial master is worse than no master: it is a
 * plausible image that is wrong in a band, and nothing downstream could tell.
 */
class StackJob(
    private val sessionDir: File,
    private val settings: StackSettings,
    private val resampler: TiledStacker.Resampler,
    /**
     * T-7.6 — where the stretched preview goes. Injected for the same reason [resampler] is: the
     * encoder is `android.graphics.Bitmap` and the pipeline that feeds it is pure Kotlin. Null
     * writes the linear master and no preview, which is what a JVM test wants.
     */
    private val stretched: StretchedImage? = null,
    /** T-8.3 — the per-camera calibration library, or null to use only the session's own frames. */
    private val calibrationRoot: File? = null,
    /**
     * T-11.9 — which half of a bracketed session to stack, or null for an ordinary session.
     *
     * Two sets 14 stops apart live in one folder, and stacking them together produces nothing
     * usable: the moon frames contribute black where the ground is, and the ground frames a
     * clipped blob where the moon is. Each set gets its own run and its own master file, and
     * [BracketedStack] puts them back together.
     */
    private val exposureSet: ExposureSet? = null,
) {

    /** Where a run has got to. Coarse on purpose — see [Progress.percent]. */
    enum class State { PREPARING, STACKING, WRITING, DONE, FAILED, CANCELLED }

    data class Progress(
        val state: State,
        val sessionName: String,
        val tile: Int = 0,
        val tiles: Int = 0,
        val message: String = "",
    ) {
        /**
         * Whole-run progress, 0–100.
         *
         * Tiles are the only part with a knowable denominator, so they carry the middle of the
         * range and the two ends are nominal. A bar that sits at 0 through a minute of opening a
         * hundred DNGs reads as a hang.
         */
        val percent: Int
            get() = when (state) {
                State.PREPARING -> 2
                State.STACKING -> if (tiles <= 0) 5 else 5 + (85 * tile / tiles)
                State.WRITING -> 92
                State.DONE -> 100
                State.FAILED, State.CANCELLED -> 0
            }

        val finished: Boolean
            get() = state == State.DONE || state == State.FAILED || state == State.CANCELLED
    }

    data class Result(
        val state: State,
        val masterFile: File? = null,
        val bytesWritten: Long = 0,
        val region: LinearMaster.Region? = null,
        val elapsedSeconds: Double = 0.0,
        val frames: Int = 0,
        /** Everything the run declined to use, and why. Never silent. */
        val notes: List<String> = emptyList(),
        /** What the rejection did, when the method was one that rejects. */
        val rejection: String? = null,
        val stats: MasterStats? = null,
        /** T-7.x's stretched JPEG, when one could be made. */
        val previewFile: File? = null,
        /** What the auto-edit did, for the log and the audit trail. */
        val edit: String? = null,
        val error: String? = null,
    ) {
        val succeeded: Boolean get() = state == State.DONE
    }

    /**
     * A summary of the master, taken in one pass before it is written and before the array is let
     * go — 151 MB is not something to hand back to a caller so it can compute a mean.
     */
    data class MasterStats(
        val min: Double,
        val mean: Double,
        val max: Double,
        val uncovered: Long,
        val samples: Long,
    ) {
        val uncoveredFraction: Double
            get() = if (samples == 0L) 0.0 else uncovered.toDouble() / samples

        fun describe(): String =
            if (samples == uncovered) {
                "entirely uncovered - every frame missed the reference"
            } else {
                "min %.1f mean %.1f max %.1f, %.2f%% uncovered"
                    .format(min, mean, max, uncoveredFraction * 100)
            }

        companion object {
            /**
             * NaN is the uncovered sentinel, and Kotlin orders it above every number (§1.32), so
             * it has to be excluded explicitly or it becomes the reported maximum.
             */
            fun of(master: FloatArray): MasterStats {
                var uncovered = 0L
                var min = Double.MAX_VALUE
                var max = -Double.MAX_VALUE
                var sum = 0.0
                var counted = 0L
                for (v in master) {
                    if (v.isNaN()) {
                        uncovered++
                        continue
                    }
                    if (v < min) min = v.toDouble()
                    if (v > max) max = v.toDouble()
                    sum += v
                    counted++
                }
                return MasterStats(
                    min = if (counted == 0L) Double.NaN else min,
                    mean = if (counted == 0L) Double.NaN else sum / counted,
                    max = if (counted == 0L) Double.NaN else max,
                    uncovered = uncovered,
                    samples = master.size.toLong(),
                )
            }
        }
    }

    val sessionName: String get() = sessionDir.name

    /**
     * Runs the stack.
     *
     * @param cancelled consulted between tiles; returning true stops the run and writes nothing.
     * @param onProgress called on the calling thread, often. The caller decides what to do with it.
     */
    fun run(
        cancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val name = sessionName
        onProgress(Progress(State.PREPARING, name, message = "Reading the session"))

        val log = runCatching {
            SessionLog.decode(File(sessionDir, SessionLayout.SESSION_JSON).readText())
        }.getOrElse {
            return failed(name, "session.json will not parse: ${it.message}", onProgress)
        }

        val source = DngFrameSource.open(
            sessionDir, log, settings, calibrationRoot = calibrationRoot,
            exposureSet = exposureSet,
        )
            ?: return failed(name, "no frames to stack", onProgress)

        source.use { frames ->
            val notes = frames.skipped.toMutableList()
            if (frames.count == 0) {
                return failed(name, "no frames to stack", onProgress, notes)
            }

            val master = try {
                FloatArray(frames.width * frames.height * TiledStacker.CHANNELS)
            } catch (t: OutOfMemoryError) {

                // 151 MB for a 12.6 MP master, and a real constraint rather than a bug. Reported
                // rather than thrown, because the queue behind this should carry on.
                return failed(name, "not enough memory for a ${frames.width}x${frames.height} master", onProgress, notes)
            }

            onProgress(
                Progress(State.STACKING, name, message = "Registering ${frames.count} frames"),
            )

            val coverageMap = if (settings.crop == LinearMaster.Crop.COMMON_AREA) {
                runCatching { ShortArray(frames.width * frames.height) }.getOrNull()
            } else {
                null
            }
            val started = System.nanoTime()
            val stacker = TiledStacker(
                frames = frames,
                resampler = resampler,
                // A factory: the combine runs on every core and each worker needs its own, since
                // `SigmaClip` carries a scratch buffer and its own counters.
                combiner = { settings.combiner() },
                // §1.38's registered intermediate lives beside the frames it came from, so the
                // space it takes is visible rather than hidden in app-private storage.
                scratchDirectory = sessionDir,
            )
            val completed = stacker.stack(
                master = master,
                // Per-pixel frame counts, so the crop can mean "every frame reached this" rather
                // than "something did" — 25 MB against a 151 MB master.
                coverage = coverageMap,
                cancelled = cancelled,
            ) { p ->
                val what = when (p.phase) {
                    TiledStacker.Phase.REGISTER -> "Registering frame ${p.tile} of ${p.tiles}"
                    TiledStacker.Phase.COMBINE -> "Combining tile ${p.tile} of ${p.tiles}"
                }
                onProgress(Progress(State.STACKING, name, p.tile, p.tiles, what))
            }
            val elapsed = (System.nanoTime() - started) / 1e9

            if (cancelled()) {
                onProgress(Progress(State.CANCELLED, name, message = "Cancelled"))
                return Result(State.CANCELLED, notes = notes, frames = frames.count)
            }
            if (!completed) {
                return failed(name, "a tile did not complete", onProgress, notes)
            }

            onProgress(Progress(State.WRITING, name, message = "Writing the master"))

            val stats = MasterStats.of(master)
            val region = LinearMaster.regionFor(
                master, frames.width, frames.height, settings.crop, coverageMap, frames.count,
            )
            val target = File(File(sessionDir, SessionLayout.MASTER), masterFileName())
            val written = runCatching {
                LinearMaster.write(
                    file = target,
                    master = master,
                    width = frames.width,
                    height = frames.height,
                    region = region,
                    description = provenance(log, frames, stacker, region),
                )
            }.getOrElse {
                return failed(name, "could not write the master: ${it.message}", onProgress, notes)
            }

            // OI-25 — where coverage is being lost. Reasoning about this on paper has now failed
            // twice, so the run says what it actually saw.
            coverageMap?.let { notes += coverageNote(it, frames.width, frames.height, frames.count) }
            // Two non-finite counts, and the gap between them is the measurement. The first is
            // what calibration produced; the second is what the gather saw. They could never have
            // agreed before the pedestal fix, because the integer round-trip turned every NaN into
            // a zero in between — which is why "0 non-finite" was not the exoneration it looked.
            notes += "calibration produced ${stacker.calibrationNonFinite} non-finite value(s)"
            notes += "dropped samples: ${stacker.skippedNonFinite} non-finite, " +
                "${stacker.skippedSentinel} sentinel, ${stacker.nearSentinel} near-sentinel kept"

            // The tile buffers are 192 MB and the stack is over; the auto-edit below needs the
            // room far more than the stacker needs to keep them (§1.41).
            stacker.release()

            // T-7.x — the picture, from the linear master that has just been written. Deliberately
            // after it: FR-8.2 makes the linear result the artefact, so a failure to render a
            // preview must not cost the thing the session was actually for.
            val preview = renderPreview(master, frames, region, notes, onProgress, name)

            record(log, frames, stacker, region, preview)

            onProgress(Progress(State.DONE, name, message = "Done"))
            return Result(
                state = State.DONE,
                masterFile = target,
                bytesWritten = written,
                region = region,
                elapsedSeconds = elapsed,
                frames = frames.count,
                notes = notes,
                rejection = rejectionOf(stacker),
                stats = stats,
                previewFile = preview?.first,
                edit = preview?.second?.describe(),
            )
        }
    }

    /**
     * The rejection counters, summed across the workers that produced them.
     *
     * Each core combines with its own [Combine.SigmaClip] — the class is stateful — so the rate a
     * stack reports has to be reassembled, or it would describe one core's share of the frame.
     */
    /**
     * Where this run's master goes.
     *
     * An ordinary session keeps `LinearMaster.FILE_NAME` exactly as it was, so nothing that reads
     * a existing session needs to know this feature exists. Only a bracketed half is named apart,
     * because two of them share one folder.
     */
    private fun masterFileName(): String = when (exposureSet) {
        null -> LinearMaster.FILE_NAME
        else -> LinearMaster.FILE_NAME.replace(".tif", "_${exposureSet.label}.tif")
    }

    private fun rejectionOf(stacker: TiledStacker): String? {
        val stats = stacker.workers.filterIsInstance<Combine.SigmaClip>().map { it.stats }
        if (stats.isEmpty()) return null
        val total = Combine.SigmaClip.Stats()
        stats.forEach { total.add(it) }
        return total.describe()
    }

    private fun failed(
        name: String,
        why: String,
        onProgress: (Progress) -> Unit,
        notes: List<String> = emptyList(),
    ): Result {
        onProgress(Progress(State.FAILED, name, message = why))
        return Result(State.FAILED, notes = notes, error = why)
    }

    /**
     * What produced this master, written back into `session.json` (FR-9.2).
     *
     * A restack must reproduce a master rather than approximate it, and the app's settings cannot
     * say how an existing one was made — they are a default and can have been changed since. So
     * the values that moved pixels are recorded against the session that used them.
     */
    /**
     * Renders the stretched preview, cropped to the same region the master was.
     *
     * Returns null rather than failing the run. The linear master is on disk by this point and is
     * what FR-8.2 calls sacred; a preview that would not render is a missing convenience, not a
     * lost night — and the reason is put in the notes rather than swallowed.
     */
    private fun renderPreview(
        master: FloatArray,
        frames: DngFrameSource,
        region: LinearMaster.Region,
        notes: MutableList<String>,
        onProgress: (Progress) -> Unit,
        name: String,
    ): Pair<File, AutoEdit.Report>? {
        val encoder = stretched ?: return null
        onProgress(Progress(State.WRITING, name, message = "Rendering the preview"))

        return runCatching {
            // The edit works on the cropped region, so the picture matches the master rather than
            // carrying the partial-depth border the crop exists to remove.
            // One copy, not two: the crop already owns its data, so the edit runs in place on it.
            val cropped = crop(master, frames.width, region)
            val (rgb, report) = AutoEdit.renderInPlace(cropped, region.width, region.height)
            val file = File(File(sessionDir, SessionLayout.MASTER), StretchedImage.FILE_NAME)
            val bytes = encoder.writeJpeg(file, rgb, region.width, region.height)
            if (bytes <= 0) {
                notes += "the preview could not be encoded"
                null
            } else {
                notes += "preview: ${report.describe()}"
                file to report
            }
        }.getOrElse {
            notes += "the preview failed: ${it.message ?: it::class.simpleName}"
            null
        }
    }

    /**
     * What the coverage map looks like, for OI-25 — **shaped**, not just counted.
     *
     * ### Why the previous version could not settle the question
     *
     * It reported a total, a minimum and a bounding box. On the run that mattered those said
     * "683 291 pixels short, box (0,0)-(4095,3071)", which is compatible with a thin ring, a deep
     * wedge, and scattered holes alike — a ring has exactly that bounding box, and reading it as
     * "scattered everywhere" was one of the wrong turns this issue has already taken.
     *
     * The distinction decides the mechanism. **A ring** is geometry: every frame is displaced, so
     * a band around the edge is not covered by all of them, and the crop should lose about twice
     * the ring's thickness in each dimension. **A wedge or a spur** is not geometry — it is one
     * frame, or one region, losing samples for a reason of its own, and it costs the crop far more
     * than its own area because the largest inscribed rectangle has to clear it entirely.
     *
     * On the 2026-09-04 run the crop gave up **5 805 644** pixels to a deficient set of **683 291**
     * — a factor of eight. That is the signature of a spur, not a ring, and this reports the number
     * that says so directly: how far in from each edge the deficiency reaches, as a median across
     * the edge and as a worst case.
     */
    private fun coverageNote(coverage: ShortArray, width: Int, height: Int, frames: Int): String {
        fun covered(x: Int, y: Int) = coverage[y * width + x].toInt() >= frames

        var full = 0L
        var zero = 0L
        var min = Int.MAX_VALUE
        for (i in 0 until width * height) {
            val n = coverage[i].toInt()
            if (n < min) min = n
            if (n >= frames) full++ else if (n == 0) zero++
        }
        val short = width.toLong() * height - full
        if (short == 0L) {
            return "coverage: every pixel saw all $frames frames"
        }

        // How far the deficiency reaches in from each edge, per row and per column.
        val leftRun = IntArray(height)
        val rightRun = IntArray(height)
        for (y in 0 until height) {
            var x = 0
            while (x < width && !covered(x, y)) x++
            leftRun[y] = x
            if (x == width) {
                // The whole row is deficient; a trailing run would double-count it.
                rightRun[y] = 0
                continue
            }
            var r = width - 1
            while (r >= 0 && !covered(r, y)) r--
            rightRun[y] = width - 1 - r
        }
        val topRun = IntArray(width)
        val bottomRun = IntArray(width)
        for (x in 0 until width) {
            var y = 0
            while (y < height && !covered(x, y)) y++
            topRun[x] = y
            if (y == height) {
                bottomRun[x] = 0
                continue
            }
            var b = height - 1
            while (b >= 0 && !covered(x, b)) b--
            bottomRun[x] = height - 1 - b
        }

        // Deficient pixels that are *not* part of an edge run: real holes rather than a border.
        var interior = 0L
        for (y in 0 until height) {
            val from = leftRun[y]
            val until = width - rightRun[y]
            for (x in from until until) {
                if (!covered(x, y)) interior++
            }
        }

        fun describe(name: String, runs: IntArray): String {
            val sorted = runs.clone().also { it.sort() }
            val median = sorted[sorted.size / 2]
            val worst = sorted.last()
            val at = runs.indexOfFirst { it == worst }
            return "$name median $median, worst $worst at ${if (name == "left" || name == "right") "row" else "column"} $at"
        }

        return buildString {
            append("coverage: %.1f%% of pixels saw all %d frames".format(100.0 * full / (width.toLong() * height), frames))
            append(", %d short".format(short))
            if (zero > 0) append(", $zero saw none")
            append(", min $min")
            append("\n  edge insets: ")
            append(describe("left", leftRun)).append(" | ")
            append(describe("right", rightRun)).append(" | ")
            append(describe("top", topRun)).append(" | ")
            append(describe("bottom", bottomRun))
            append("\n  $interior deficient pixel(s) are interior — not part of any edge run")
            // The line that decides it: a ring's worst inset is close to its median, a spur's is
            // many times it.
            val medians = listOf(leftRun, rightRun, topRun, bottomRun)
                .map { it.clone().also { c -> c.sort() }[it.size / 2] }
            val worsts = listOf(leftRun, rightRun, topRun, bottomRun).map { it.max() }
            val ratio = if (medians.max() == 0) Double.NaN else worsts.max().toDouble() / medians.max()
            append(
                "\n  worst inset is %.1fx the thickest median — %s".format(
                    ratio,
                    when {
                        ratio.isNaN() -> "no edge band at all, so the loss is interior"
                        ratio < 2.0 -> "a band, which is the geometry of displaced frames"
                        else -> "a spur, which geometry does not explain"
                    },
                ),
            )
        }
    }

    /** The region of [master], as its own array. */
    private fun crop(master: FloatArray, frameWidth: Int, region: LinearMaster.Region): FloatArray {
        val channels = TiledStacker.CHANNELS
        // Always a copy, even for a full-frame region. The caller edits this in place, and handing
        // back the master itself would scribble on the very thing FR-8.2 calls sacred — the file is
        // already written, but the array is still the caller's.
        val out = FloatArray(region.width * region.height * channels)
        for (y in 0 until region.height) {
            val src = ((region.top + y).toLong() * frameWidth + region.left).toInt() * channels
            master.copyInto(out, y * region.width * channels, src, src + region.width * channels)
        }
        return out
    }

    private fun record(
        log: SessionLog,
        frames: DngFrameSource,
        stacker: TiledStacker,
        region: LinearMaster.Region,
        preview: Pair<File, AutoEdit.Report>?,
    ) {
        val stacking = settings.toMap() + buildMap {
            put("region", region.describe())
            put("frames", frames.count.toString())
            put("calibration", frames.masters.describe())
            put("master", LinearMaster.FILE_NAME)
            preview?.let {
                put("preview", StretchedImage.FILE_NAME)
                put("edit", it.second.describe())
            }
            put("stackedAt", System.currentTimeMillis().toString())
            rejectionOf(stacker)?.let { put("rejection", it) }
        }
        runCatching {
            File(sessionDir, SessionLayout.SESSION_JSON)
                .writeText(log.copy(info = log.info.copy(stacking = stacking)).encode())
        }
        // Deliberately not fatal. The TIFF is on disk and is the thing that matters; losing the
        // log entry costs reproducibility, not the night's work.
    }

    /**
     * The short version of the audit trail, written into the TIFF's own `ImageDescription`.
     *
     * `session.json` is the right home for FR-9.2's trail and the wrong one the moment somebody
     * copies a single TIFF to a PC and opens it a month later.
     */
    private fun provenance(
        log: SessionLog,
        frames: DngFrameSource,
        stacker: TiledStacker,
        region: LinearMaster.Region,
    ): String = buildString {
        append("StarStacker linear master")
        append(" | session ${log.info.sessionId}")
        if (log.info.label.isNotBlank()) append(" (${log.info.label})")
        append(" | ${frames.count} frames")
        append(" | %.0f s integration".format(log.acceptedIntegrationSeconds))
        append(" | ISO ${log.info.plannedIso}")
        append(" | ${frames.masters.describe()}")
        append(" | ${settings.describe()}")
        rejectionOf(stacker)?.let { append(" | $it") }
        append(" | ${region.describe()}")
    }
}
