package com.starstacker.stacking

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

        val source = DngFrameSource.open(sessionDir, log, settings)
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
            val target = File(File(sessionDir, SessionLayout.MASTER), LinearMaster.FILE_NAME)
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

            record(log, frames, stacker, region)

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
            )
        }
    }

    /**
     * The rejection counters, summed across the workers that produced them.
     *
     * Each core combines with its own [Combine.SigmaClip] — the class is stateful — so the rate a
     * stack reports has to be reassembled, or it would describe one core's share of the frame.
     */
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
    private fun record(
        log: SessionLog,
        frames: DngFrameSource,
        stacker: TiledStacker,
        region: LinearMaster.Region,
    ) {
        val stacking = settings.toMap() + buildMap {
            put("region", region.describe())
            put("frames", frames.count.toString())
            put("calibration", frames.masters.describe())
            put("master", LinearMaster.FILE_NAME)
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
