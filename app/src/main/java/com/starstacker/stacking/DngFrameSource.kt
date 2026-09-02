package com.starstacker.stacking

import com.starstacker.dng.DngMetadata
import com.starstacker.dng.DngReader
import com.starstacker.registration.RigidTransform
import com.starstacker.session.FrameRecord
import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionLog
import java.io.Closeable
import java.io.File

/**
 * T-5.3 — a session folder as [TiledStacker.Frames]. The piece that turns Phase 3 from built into
 * run.
 *
 * Everything above this has been proven against synthetic frames: T-5.2's arithmetic, T-5.3's
 * tiling, T-5.4's rejection. None of it had ever met a DNG, because nothing connected the loop to
 * a folder on disk. This is that connection, and it is deliberately the only place that knows a
 * session is made of files.
 *
 * ### What gets stacked, and what the transform means
 *
 * **Accepted lights, in log order** (FR-7.5). Rejected frames stay on disk and stay out of the
 * master — D-10 keeps them because a rejection is a judgement the user may want to overturn, not
 * because a stack should include them.
 *
 * The direction of the transform is the one thing here that cannot be got wrong quietly, so it is
 * stated rather than implied. `RigidFit` fits a transform that carries a **reference** star onto
 * its match in the **target** frame — `consensus` applies it to the reference coordinate and
 * compares against the target's. [TiledStacker] wants exactly that: an output pixel `p` takes the
 * frame's value at `T(p)`. So the six numbers go from `session.json` to [TiledStacker.Frames]
 * without inversion, and the reference frame's own transform is null.
 *
 * That last part is not a special case bolted on — `LiveRegistration` reports the reference frame
 * with a null transform and the log stores the null, so the frame that defines the coordinate
 * system arrives already saying it needs no warp.
 *
 * ### Registration is stored in sensor coordinates, which is why this is a straight read
 *
 * Star detection runs on the binned preview plane, and `LiveRegistration` converts each detection
 * to full-resolution sensor coordinates *before* fitting, precisely so that a restack the next
 * morning does not have to know what the bin factor was that night. Nothing here rescales anything;
 * if that conversion were ever moved, this file would be silently wrong by a factor of two and the
 * master would be soft rather than broken.
 *
 * ### Memory, which is the reason for every awkward part of this class
 *
 * One 12.6 MP frame is 25 MB as a `ShortArray`. A session's twenty darks, held at once to take a
 * median down the stack, is half a gigabyte — so [masterDarkOf] reads a **band** from every dark
 * instead, exactly as the stacking loop reads a band from every light, and the peak is the band
 * times the count rather than the frame times the count.
 *
 * What is unavoidably resident: a master dark and a master flat at 50 MB each (whole-frame floats,
 * because [Calibration.apply] indexes into them), the loop's own sample budget, and the caller's
 * 151 MB master. That is the shape of the thing, and it is worth knowing before a 200-frame night
 * is attempted on a warm phone.
 *
 * **Every light stays open for the life of the stack.** A `DngReader.Rows` holds a descriptor and
 * a parsed strip table, and re-opening per tile would put the header parse back on the hot path
 * that `Rows` exists to keep it off — a hundred tiles times a hundred and fifty frames of it. The
 * cost is a file descriptor per frame, which is nothing against a limit of a thousand, and the
 * reason this is [Closeable] rather than a function.
 */
class DngFrameSource private constructor(
    private val readers: List<DngReader.Rows>,
    private val transforms: List<RigidTransform?>,
    private val weights: List<Float>,
    override val width: Int,
    override val height: Int,
    override val cfaCodes: List<Int>,
    override val blackLevel: Double,
    override val masters: Calibration.Masters,
    /** The files backing each index, so a master can name what went into it (FR-9.2). */
    val fileNames: List<String>,
    /** Frames left out, and why. Never silent: a stack that quietly drops half a night is worse
     *  than one that refuses. */
    val skipped: List<String>,
) : TiledStacker.Frames, Closeable {

    override val count: Int get() = readers.size

    /** The frame the others are registered against — the one with no transform of its own. */
    val referenceIndex: Int get() = transforms.indexOfFirst { it == null }

    override fun transform(index: Int): RigidTransform? = transforms[index]

    override fun rows(index: Int, fromRow: Int, rowCount: Int, into: ShortArray): Int =
        readers[index].read(fromRow, rowCount, into)

    /** T-5.5 — 1 for every frame unless the log carried enough quality metrics to say otherwise. */
    override fun weight(index: Int): Float = weights[index]

    /** True when the weights actually differ, so a caller can say whether weighting did anything. */
    val weighted: Boolean get() = weights.any { it != 1f }

    /** One line for `session.json` and the field log, in the shape the other stages use. */
    fun describe(): String = buildString {
        append("$count frames · ${width}x$height")
        append(" · ${masters.describe()}")
        if (referenceIndex >= 0) append(" · reference ${fileNames[referenceIndex]}")
        if (weighted) {
            append(" · weights %.2f-%.2f".format(weights.min(), weights.max()))
        }
        if (skipped.isNotEmpty()) append(" · ${skipped.size} skipped")
    }

    /**
     * Closes every reader. Failures are swallowed one at a time on purpose: a descriptor that will
     * not close must not prevent the other hundred and forty-nine from closing.
     */
    override fun close() {
        readers.forEach { runCatching { it.close() } }
    }

    companion object {

        /**
         * Opens a session folder for stacking.
         *
         * @param sessionDir the session's own folder — the one holding `lights/` and `session.json`.
         * @param log already parsed, because the caller generally has it: the session list read it
         *   to draw the row the user tapped.
         * @return null if there is nothing to stack. Callers get [skipped] either way, so "no
         *   frames" can be reported with its reason rather than as an empty result.
         */
        fun open(
            sessionDir: File,
            log: SessionLog,
            settings: StackSettings = StackSettings(),
            masterBudgetBytes: Long = DEFAULT_MASTER_BUDGET,
        ): DngFrameSource? {
            val skipped = mutableListOf<String>()
            val lightsDir = File(sessionDir, SessionLayout.LIGHTS)

            val accepted = log.accepted
            if (accepted.isEmpty()) {
                skipped += "no accepted light frames in the log"
                return null
            }

            // T-5.5, in this order deliberately: score every accepted frame, drop the worst by the
            // keep-best cut, then weight what is left. Scoring after the cut would rescale the
            // survivors against each other and make the best remaining frame a 1.0 by definition,
            // so the weights would depend on what had already been thrown away.
            val scores = FrameQuality.score(accepted).associateBy { it.index }
            val candidates = quality(accepted, scores, settings, skipped)

            // The first readable frame sets the geometry every other frame is measured against.
            // Taking it from the log's plan instead would trust a number nobody wrote the pixels
            // with; taking it from the first file makes a disagreement detectable.
            var reference: DngMetadata? = null
            val readers = mutableListOf<DngReader.Rows>()
            val transforms = mutableListOf<RigidTransform?>()
            val names = mutableListOf<String>()
            val weights = mutableListOf<Float>()

            for (record in candidates) {
                val file = File(lightsDir, record.fileName)
                if (!file.isFile) {
                    skipped += "${record.fileName}: not on disk"
                    continue
                }
                val rows = runCatching { DngReader.Rows(file) }.getOrElse {
                    skipped += "${record.fileName}: ${it.message ?: it::class.simpleName}"
                    continue
                }
                val metadata = rows.metadata
                val first = reference
                if (first == null) {
                    reference = metadata
                } else if (metadata.width != first.width || metadata.height != first.height) {
                    // Stacking frames of different sizes would not throw — it would read the wrong
                    // rows and produce a master that is sharp in places.
                    skipped += "${record.fileName}: ${metadata.width}x${metadata.height}, " +
                        "expected ${first.width}x${first.height}"
                    rows.close()
                    continue
                }

                // A frame whose transform will not parse is dropped rather than stacked unaligned:
                // one unregistered frame in a hundred and fifty is a faint doubled ghost of the
                // whole field, which looks like poor tracking rather than like a bad frame.
                val transform = if (record.transform == null) {
                    null
                } else {
                    RigidTransform.fromMatrix(record.transform) ?: run {
                        skipped += "${record.fileName}: transform is not a rotation"
                        rows.close()
                        continue
                    }
                }

                readers += rows
                transforms += transform
                names += record.fileName
                weights += if (settings.weightByQuality) {
                    scores[record.index]?.weight?.toFloat() ?: 1f
                } else {
                    1f
                }
            }

            val geometry = reference
            if (geometry == null || readers.isEmpty()) {
                skipped += "no light frame could be opened"
                readers.forEach { runCatching { it.close() } }
                return null
            }

            if (transforms.count { it == null } > 1) {
                // Not fatal — a session captured before registration existed has no transforms at
                // all, and stacking it unaligned is a legitimate thing to ask for. It is recorded
                // because the result will be soft and the reason should not be a mystery.
                skipped += "${transforms.count { it == null }} frames carry no transform; " +
                    "they are stacked where they lie"
            }

            val masters = mastersFor(sessionDir, log, geometry, masterBudgetBytes, skipped)

            return DngFrameSource(
                readers = readers,
                transforms = transforms,
                weights = weights,
                width = geometry.width,
                height = geometry.height,
                cfaCodes = cfaCodesOf(geometry, skipped),
                blackLevel = blackLevelOf(geometry, skipped),
                masters = masters,
                fileNames = names,
                skipped = skipped,
            )
        }

        /**
         * T-5.5's keep-best cut, and the note that says what it did.
         *
         * Reported rather than silent, for the same reason a rejected frame stays on disk
         * (**D-10**): dropping a frame is a judgement, and the user is entitled to disagree with
         * it — which they cannot do if nothing says it happened. The note names the count and the
         * worst weight kept, so "it dropped my best frame" is a checkable claim.
         */
        private fun quality(
            accepted: List<FrameRecord>,
            scores: Map<Int, FrameQuality.Score>,
            settings: StackSettings,
            notes: MutableList<String>,
        ): List<FrameRecord> {
            if (settings.keepBestPercent >= 100) return accepted
            val ranked = accepted.mapNotNull { scores[it.index] }
            val kept = FrameQuality.keepBest(ranked, settings.keepBestPercent).map { it.index }.toSet()
            if (kept.size == accepted.size) return accepted

            val dropped = accepted.filter { it.index !in kept }
            notes += "best ${settings.keepBestPercent}%: dropped ${dropped.size} of " +
                "${accepted.size} — ${dropped.joinToString { it.fileName }}"
            return accepted.filter { it.index in kept }
        }

        /**
         * The 2×2 pattern, from the file rather than from a constant.
         *
         * A DNG that does not declare one is assumed GRBG, which is this device's (§1.5) and the
         * only arrangement the app has met. The assumption is recorded rather than made silently,
         * because guessing wrong here swaps red and blue in a way that only becomes visible after
         * colour balance — §1.31's trap, arriving through the file instead of through the API.
         */
        private fun cfaCodesOf(metadata: DngMetadata, notes: MutableList<String>): List<Int> {
            val pattern = metadata.cfaPattern
            if (pattern == null || pattern.codes.size != 4) {
                notes += "no CFA pattern in the DNG; assuming GRBG"
                return DEFAULT_CFA
            }
            return pattern.codes
        }

        /**
         * The pedestal, as the one number [Calibration.apply] can take.
         *
         * DNG stores a black level **per CFA channel**, and this device writes the same value four
         * times. Where they genuinely differ the mean is used, because [TiledStacker.Frames]
         * exposes a scalar — and the note matters, since a per-channel pedestal collapsed to its
         * mean puts a small constant colour cast into the background. It only bites when there is
         * no dark; with one, the pedestal leaves with the dark and this value is never read.
         */
        private fun blackLevelOf(metadata: DngMetadata, notes: MutableList<String>): Double {
            val levels = metadata.blackLevels.filter { it.isFinite() }
            if (levels.isEmpty()) {
                notes += "no black level in the DNG; assuming 0"
                return 0.0
            }
            if (levels.distinct().size > 1) {
                notes += "black level differs per channel ($levels); using the mean"
            }
            return levels.average()
        }

        /**
         * Builds whatever masters the session's own calibration frames support.
         *
         * Every one is optional and absence is pass-through (T-5.2). A Functional-tier session
         * shoots no calibration at all, and the common case today is darks and no flats.
         */
        private fun mastersFor(
            sessionDir: File,
            log: SessionLog,
            geometry: DngMetadata,
            budgetBytes: Long,
            notes: MutableList<String>,
        ): Calibration.Masters {
            val darkFiles = log.darks
                .map { File(File(sessionDir, SessionLayout.DARKS), it.fileName) }
                .filter { it.isFile }
            val flatFiles = File(sessionDir, SessionLayout.FLATS)
                .listFiles { f -> f.isFile && f.name.endsWith(".dng", ignoreCase = true) }
                ?.sortedBy { it.name }
                .orEmpty()

            val dark = masterDarkOf(darkFiles, geometry, budgetBytes, "dark", notes)
            val flat = masterDarkOf(flatFiles, geometry, budgetBytes, "flat", notes)

            // Hot pixels come out of the master dark, so there are none without one. The threshold
            // is relative to the dark's own spread, which is why it cannot be precomputed.
            val hot = dark?.let { Calibration.hotPixelsFrom(it, geometry.width * geometry.height) }
            hot?.let { notes += "${it.size} hot pixels from the master dark" }

            return Calibration.Masters.of(
                width = geometry.width,
                height = geometry.height,
                dark = dark,
                rawFlat = flat,
                hotPixels = hot,
            )
        }

        /**
         * A median master over [files], built a band at a time.
         *
         * Used for both darks and flats — the combination is the same question in both cases
         * ("what does the sensor do, ignoring what crossed it once") and the median is the same
         * answer. The flat is normalised later, by [Calibration.Masters.of], so that it happens
         * exactly once no matter who builds one.
         *
         * The band height falls out of the budget and the frame count, the same relationship
         * [TiledStacker.tileRowsFor] states: more frames means thinner bands.
         */
        private fun masterDarkOf(
            files: List<File>,
            geometry: DngMetadata,
            budgetBytes: Long,
            kind: String,
            notes: MutableList<String>,
        ): FloatArray? {
            if (files.isEmpty()) return null

            val width = geometry.width
            val height = geometry.height
            val readers = mutableListOf<DngReader.Rows>()
            try {
                for (file in files) {
                    val rows = runCatching { DngReader.Rows(file) }.getOrElse {
                        notes += "$kind ${file.name}: ${it.message ?: it::class.simpleName}"
                        continue
                    }
                    if (rows.metadata.width != width || rows.metadata.height != height) {
                        notes += "$kind ${file.name}: ${rows.metadata.width}x${rows.metadata.height}," +
                            " expected ${width}x$height"
                        rows.close()
                        continue
                    }
                    readers += rows
                }
                if (readers.isEmpty()) return null

                val perRow = width.toLong() * 2 * readers.size
                // Clamped as a Long before narrowing: a generous budget over a small frame
                // overflows Int, and an overflowed row count wraps negative and silently becomes
                // the one-row-at-a-time path.
                val bandRows = (budgetBytes / perRow).coerceIn(1L, height.toLong()).toInt()
                val bands = readers.map { ShortArray(width * bandRows) }

                val master = FloatArray(width * height)
                var top = 0
                while (top < height) {
                    val want = minOf(bandRows, height - top)
                    var got = want
                    readers.forEachIndexed { i, reader ->
                        got = minOf(got, reader.read(top, want, bands[i]))
                    }
                    if (got <= 0) break
                    Calibration.masterDarkInto(bands, width * got, master, top * width)
                    top += got
                }
                notes += "master $kind from ${readers.size} frames"
                return master
            } finally {
                readers.forEach { runCatching { it.close() } }
            }
        }

        /** GRBG — this device's, and the fallback when a file does not say (§1.5). */
        private val DEFAULT_CFA = listOf(1, 0, 2, 1)

        /**
         * 64 MB of dark frames in flight while a master is built. Independent of the stacking
         * loop's own budget because the two never run at the same time: the masters are finished
         * before the first tile is read.
         */
        const val DEFAULT_MASTER_BUDGET = 64L * 1024 * 1024
    }
}
