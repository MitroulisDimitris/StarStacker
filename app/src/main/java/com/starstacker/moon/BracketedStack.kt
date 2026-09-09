package com.starstacker.moon

import com.starstacker.dng.DngReader
import com.starstacker.session.ExposureSet
import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionLog
import com.starstacker.stacking.LinearMaster
import com.starstacker.stacking.StackJob
import com.starstacker.stacking.StackSettings
import java.io.File

/**
 * T-11.9–T-11.11 — stacking a *moon in scene* session, which is two sessions in one folder.
 *
 * ### Why two runs and not one
 *
 * The two sets are 14 stops apart. Averaged together they produce nothing usable: the moon frames
 * contribute black sky where the ground is, and the ground frames contribute a clipped blob where
 * the moon is. So each set is stacked on its own terms — different calibration, different quality
 * metric, different registration reference — and the results are composited.
 *
 * They are also *calibrated differently*, which is worth stating because it is the opposite of what
 * §1.45 first claimed. The ground set is an ordinary long exposure pointed at a landscape and wants
 * the full treatment: real darks, the flat, gradient removal. The moon set wants almost none of it —
 * sub-millisecond darks are bias frames, and a small centred object sees little of the corner
 * vignetting. "Calibration nearly vanishes" is true for the disc alone.
 *
 * ### Where the memory goes, and why this checks first
 *
 * The composite holds **both masters at once**: 151 MB each at 12.6 MP, so 302 MB. That is a real
 * constraint rather than a theoretical one — holding two masters is what failed the first stacking
 * run (§1.41), and the fix then was to stop making the second copy. Here the second one is the
 * point, so instead of hoping, [composite] asks the runtime what is free and **declines with a
 * reason** rather than dying of an `OutOfMemoryError` halfway through. A queue behind this should
 * carry on.
 */
object BracketedStack {

    /** The composite of both layers — what most people will actually look at. */
    const val COMPOSITE_FILE_NAME = "stack_linear_composite.tif"

    /** The moon master resampled into the ground master's frame, as an editable layer. */
    const val LAYER_FILE_NAME = "stack_linear_moon_layer.tif"

    /** Headroom over the two masters, for the JVM's own working set. */
    private const val MEMORY_SLACK = 1.35

    data class Result(
        val groundMaster: File?,
        val moonMaster: File?,
        val compositeFile: File?,
        val layerFile: File?,
        val alignment: MasterAlign.Result?,
        val notes: List<String>,
        val error: String? = null,
    ) {
        val succeeded: Boolean get() = error == null && compositeFile != null
    }

    /**
     * Stacks both halves and composites them.
     *
     * @param newJob makes a [StackJob] for one half. Injected rather than constructed here because
     *   the job needs an Android resampler and a bitmap encoder, neither of which belongs in a
     *   file that has to be readable in a JVM test.
     */
    fun run(
        sessionDir: File,
        log: SessionLog,
        settings: StackSettings,
        newJob: (ExposureSet) -> StackJob,
        cancelled: () -> Boolean = { false },
        onNote: (String) -> Unit = {},
    ): Result {
        val notes = mutableListOf<String>()
        fun note(line: String) {
            notes += line
            onNote(line)
        }

        if (!log.info.targetType.isBracketed) {
            return Result(null, null, null, null, null, notes, "not a bracketed session")
        }

        // The ground set first, deliberately: it is the one that defines the frame everything else
        // is placed into, and the one whose failure makes the session worthless. A moon with
        // nothing to sit in is a disc shot, which the user did not ask for.
        note("Stacking the ground set — full calibration, deep-sky metrics.")
        val ground = newJob(ExposureSet.GROUND).run(cancelled)
        if (!ground.succeeded) {
            return Result(null, null, null, null, null, notes, "ground set: ${ground.error}")
        }
        notes += ground.notes

        if (cancelled()) return Result(ground.masterFile, null, null, null, null, notes, "cancelled")

        note("Stacking the moon set — sharpness-ranked, minimal calibration.")
        val moon = newJob(ExposureSet.MOON).run(cancelled)
        if (!moon.succeeded) {
            // The ground master is a real result and is kept: a landscape without the moon pasted
            // in is still a picture, and throwing it away because the second half failed would be
            // the worse of the two outcomes.
            note("The moon set did not stack (${moon.error}); the ground master is kept.")
            return Result(ground.masterFile, null, null, null, null, notes)
        }
        notes += moon.notes

        return composite(sessionDir, log, ground.masterFile, moon.masterFile, notes, ::note)
    }

    /**
     * Reads both masters back, aligns them, and writes the composite and the layer.
     *
     * Separate from [run] so it can be re-run on an existing pair without re-stacking — the blend
     * is a taste decision and re-doing it should not cost twenty minutes.
     */
    fun composite(
        sessionDir: File,
        log: SessionLog,
        groundMaster: File?,
        moonMaster: File?,
        notes: MutableList<String> = mutableListOf(),
        note: (String) -> Unit = {},
    ): Result {
        if (groundMaster == null || moonMaster == null) {
            return Result(groundMaster, moonMaster, null, null, null, notes, "a master is missing")
        }

        val groundImage = LinearMaster.read(groundMaster)
            ?: return Result(groundMaster, moonMaster, null, null, null, notes, "the ground master will not read")

        val needed = groundImage.pixels.size.toLong() * Float.SIZE_BYTES * MEMORY_SLACK
        val free = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }
        if (needed > free) {
            return Result(
                groundMaster, moonMaster, null, null, null, notes,
                "not enough memory to hold both masters: %.0f MB needed, %.0f MB free"
                    .format(needed / 1e6, free / 1e6),
            )
        }

        val moonImage = LinearMaster.read(moonMaster)
            ?: return Result(groundMaster, moonMaster, null, null, null, notes, "the moon master will not read")

        if (moonImage.width != groundImage.width || moonImage.height != groundImage.height) {
            // Two different crops of the same session. Compositing them would place the disc by
            // coordinates that mean different things in each, which is worse than declining.
            return Result(
                groundMaster, moonMaster, null, null, null, notes,
                "the two masters are different sizes (${groundImage.width}x${groundImage.height} " +
                    "against ${moonImage.width}x${moonImage.height}) — restack both with the same crop",
            )
        }

        val width = groundImage.width
        val height = groundImage.height

        // Where the moon is in its own master, measured on the luminance of the stacked result.
        val moonDisc = Disc.find(luminance(moonImage.pixels, width, height, moonImage.channels), width, height, Double.MAX_VALUE)
            ?: return Result(groundMaster, moonMaster, null, null, null, notes, "no disc in the moon master")

        // Where it belongs, from the clipped blob in each long frame (T-11.10).
        val samples = groundSamples(sessionDir, log, note)
        val referenceEpoch = referenceEpochOf(log)
        val alignment = MasterAlign.align(samples, referenceEpoch, moonDisc, width, height)

        if (alignment is MasterAlign.Result.Rejected) {
            return Result(
                groundMaster, moonMaster, null, null, alignment, notes,
                "could not place the moon: ${alignment.reason}",
            )
        }
        val matched = alignment as MasterAlign.Result.Matched
        note("Placed the moon: ${matched.note}.")
        matched.driftPxPerSec?.let {
            note("Measured drift this session: %.3f px/s — not assumed from the sidereal rate.".format(it))
        }

        // The layer first, because it is the deliverable that survives a taste change.
        val layerFile = File(File(sessionDir, SessionLayout.MASTER), LAYER_FILE_NAME)
        val layer = Composite.alignedLayer(
            moonImage.pixels, width, height, matched.dx, matched.dy, moonImage.channels,
        )
        runCatching {
            LinearMaster.write(
                file = layerFile, master = layer, width = width, height = height,
                region = LinearMaster.Region(0, 0, width, height),
                channels = moonImage.channels,
                description = "moon layer, registered onto the ground master (T-11.11)",
            )
        }.onFailure { note("Could not write the moon layer: ${it.message}") }

        // Then the merge, into the ground master's own array — which is why the layer is written
        // first: `merge` is destructive, and the ground master on disk is untouched either way.
        val report = Composite.merge(
            ground = groundImage.pixels,
            moon = moonImage.pixels,
            width = width,
            height = height,
            moonDisc = moonDisc,
            shiftX = matched.dx,
            shiftY = matched.dy,
            channels = moonImage.channels,
        )
        note(report.describe())

        val compositeFile = File(File(sessionDir, SessionLayout.MASTER), COMPOSITE_FILE_NAME)
        val written = runCatching {
            LinearMaster.write(
                file = compositeFile, master = groundImage.pixels, width = width, height = height,
                region = LinearMaster.Region(0, 0, width, height),
                channels = groundImage.channels,
                description = "moon-in-scene composite: ${report.describe()}",
            )
        }.getOrElse {
            return Result(groundMaster, moonMaster, null, layerFile, alignment, notes, "could not write the composite: ${it.message}")
        }

        note(
            "Wrote the composite (%.1f MB) and both registered layers. The blend is a starting "
                .format(written / 1e6) + "point — the layers are there to be blended differently.",
        )
        return Result(groundMaster, moonMaster, compositeFile, layerFile, alignment, notes)
    }

    /**
     * The clipped blob in every long frame, with its capture time.
     *
     * Read from the DNGs rather than from the stacked ground master, because in the master the
     * moving disc is a *streak* and its centroid is only the mid-time position. Per-frame gives
     * T-11.10 a drift to fit, which is both more accurate and asymmetry-proof.
     */
    private fun groundSamples(
        sessionDir: File,
        log: SessionLog,
        note: (String) -> Unit,
    ): List<MasterAlign.Sample> {
        val lightsDir = File(sessionDir, SessionLayout.LIGHTS)
        val samples = mutableListOf<MasterAlign.Sample>()
        var missed = 0

        for (record in log.accepted.filter { it.exposureSet == ExposureSet.GROUND }) {
            val file = File(lightsDir, record.fileName)
            if (!file.isFile) continue
            val image = runCatching { DngReader.read(file) }.getOrNull() ?: continue
            val white = image.metadata.whiteLevel?.toDouble() ?: Double.MAX_VALUE
            val pixels = DoubleArray(image.pixels.size) {
                (image.pixels[it].toInt() and 0xFFFF).toDouble()
            }
            val disc = Disc.find(pixels, image.metadata.width, image.metadata.height, white)
            if (disc == null) {
                missed++
                continue
            }
            samples += MasterAlign.Sample(record.capturedAtEpochMs, disc)
        }
        if (missed > 0) {
            note("The moon was not found in $missed ground frame(s) — cloud, or it left the frame.")
        }
        return samples
    }

    /**
     * The instant the composite is *of*.
     *
     * The mid-point of the ground set, because that is what a stack of it represents: every frame
     * contributed equally, so the landscape's own reference time is the middle of the run. The
     * moon is then placed where it was at that instant, which is what makes the picture internally
     * consistent rather than merely aligned.
     */
    private fun referenceEpochOf(log: SessionLog): Long {
        val times = log.accepted
            .filter { it.exposureSet == ExposureSet.GROUND }
            .map { it.capturedAtEpochMs }
            .filter { it > 0 }
        if (times.isEmpty()) return 0L
        return (times.min() + times.max()) / 2
    }

    /** Luminance of an interleaved master, for finding the disc in a colour result. */
    internal fun luminance(data: FloatArray, width: Int, height: Int, channels: Int): DoubleArray {
        val out = DoubleArray(width * height)
        for (i in 0 until width * height) {
            if (channels == 1) {
                out[i] = data[i].toDouble()
            } else {
                val base = i * channels
                // A plain mean rather than Rec. 709 weights: this is used to *locate* a grey
                // object, and weighting the channels would only tilt the threshold by the colour
                // balance of the frame.
                var sum = 0.0
                for (c in 0 until channels) sum += data[base + c]
                out[i] = sum / channels
            }
            if (out[i].isNaN()) out[i] = 0.0
        }
        return out
    }
}
