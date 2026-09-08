package com.starstacker.diag

import com.starstacker.camera.CameraAccess
import com.starstacker.camera.RawCapture
import com.starstacker.device.CameraProfile
import com.starstacker.dng.DngReader
import com.starstacker.moon.Disc
import com.starstacker.moon.LunarExposure
import com.starstacker.moon.LunarGeometry
import com.starstacker.moon.LunarPlan
import com.starstacker.moon.Sharpness
import java.io.File

/**
 * T-11.1 / T-11.2 / T-11.3 — moon mode's acceptance, which needs a moon.
 *
 * ### Why this is a diagnostic before it is a screen
 *
 * Everything moon mode computes is pure and tested against a synthetic disc, which proves the
 * arithmetic and proves nothing about the sky. Three things can only be answered by pointing a
 * phone at the real thing:
 *
 * - whether the metering loop converges on a *real* moon, whose brightness depends on phase and
 *   altitude in ways Looney 11 does not know about (§1.45: the truth was five stops from the rule
 *   on the session that motivated all this);
 * - whether [Disc] finds the disc against a real sky, with real haze and real stray light;
 * - whether the sharpness metric is steady enough frame to frame to rank by, or whether seeing
 *   moves it around so much that the ranking is noise.
 *
 * ### The acceptance, stated so a run either passes or does not
 *
 * T-11.1 asks for **no pixel of the disc at the white level, and the peak within 60–80%**. That is
 * checked and reported as a verdict rather than left for someone to read off a histogram.
 *
 *     adb shell am start -n com.starstacker/.MainActivity --es diag moon \
 *         --es camera 3 --ei iso 100 --ei frames 12
 *
 * Frames are kept on the device so the disc can be looked at afterwards, because a verdict that
 * says "peak at 71%" and a frame that turns out to be a streetlight are indistinguishable from
 * here.
 */
object MoonCheck {

    suspend fun run(
        access: CameraAccess,
        cameraId: String,
        cameras: List<CameraProfile>,
        root: File,
        iso: Int,
        frames: Int,
        freeBytes: Long,
        log: (String) -> Unit,
    ) {
        // ---------------------------------------------------------------- T-11.2, the report
        val report = LunarGeometry.report(cameras)
        if (report.options.isEmpty()) {
            log("moon: ${report.reason}")
            return
        }
        log("moon: what each camera would give —")
        for (option in report.options) {
            val mark = if (option.id == report.recommendedId) " ← recommended" else ""
            log("  camera ${option.id}: ${option.describe()}$mark")
        }
        log("moon: ${report.reason}")
        report.warning?.let { log("moon: $it") }
        report.noteFor(cameraId)?.let { log("moon: using camera $cameraId — $it") }

        val chosen = report.optionFor(cameraId)
        if (chosen == null) {
            log("moon: camera $cameraId is not in the profile; cannot size the disc for it")
            return
        }

        val scratch = File(root, "diag-moon").apply { mkdirs() }

        // ---------------------------------------------------------------- T-11.1, the metering
        // Looney 11 is the starting guess only, computed from this camera's own aperture.
        val aperture = chosen.apertureF
        if (aperture == null) {
            log("moon: camera $cameraId reports no aperture, so there is no starting guess to make")
            return
        }
        var exposureNs = LunarExposure.looneyElevenNs(aperture, iso)
        log(
            "moon: Looney 11 at f/%.2f ISO %d starts at %.3f ms".format(aperture, iso, exposureNs / 1e6),
        )

        val range = chosen.minExposureNs?.let { lo ->
            val hi = cameras.first { it.id == cameraId }.exposureMaxNs ?: (lo * 1_000_000)
            lo..hi
        }

        var settled: LunarExposure.Probe? = null
        var lastFile: File? = null
        for (attempt in 1..LunarExposure.MAX_PROBES) {
            val file = shoot(access, cameraId, iso, exposureNs, scratch, "probe.dng", log) ?: return
            lastFile = file
            val image = DngReader.read(file)
            val metadata = DngReader.readMetadata(file)
            val black = metadata.blackLevels.filter { it.isFinite() }.average()
                .takeIf { it.isFinite() } ?: 0.0
            val white = metadata.whiteLevel ?: 1023

            val pixels = toDoubles(image.pixels)
            val disc = Disc.find(pixels, image.metadata.width, image.metadata.height, white.toDouble())
            if (disc == null) {
                log(
                    "moon: probe $attempt at %.3f ms — no disc found. Is the moon in frame?"
                        .format(exposureNs / 1e6),
                )
                // Not a reason to stop: a wildly wrong exposure can hide the disc at either end,
                // and the loop's whole job is to find an exposure that shows it.
                exposureNs = (exposureNs * 8).coerceIn(
                    range?.first ?: 1L, range?.last ?: Long.MAX_VALUE,
                )
                continue
            }

            val probe = LunarExposure.assess(
                exposureNs = exposureNs,
                iso = iso,
                peakAdu = disc.peakAdu,
                blackLevel = black,
                whiteLevel = white,
                clippedPixels = disc.clippedPixels,
                exposureRange = range,
            )
            log("moon: probe $attempt — ${disc.describe()}")
            log("moon: probe $attempt — ${probe.describe()}")

            if (probe.verdict == LunarExposure.Verdict.SETTLED) {
                settled = probe
                break
            }
            if (probe.verdict == LunarExposure.Verdict.UNREACHABLE) {
                log(
                    "moon: this camera cannot reach the exposure the moon needs. " +
                        (report.noteFor(cameraId) ?: "Try a different camera."),
                )
                return
            }
            exposureNs = probe.nextExposureNs ?: break
        }

        if (settled == null) {
            log("moon: FAILED — did not settle in ${LunarExposure.MAX_PROBES} probes")
            return
        }
        log(
            "moon: metered at %.3f ms, peak %.1f%% of scale, 0 clipped"
                .format(settled.exposureNs / 1e6, settled.peakFraction * 100),
        )

        // ---------------------------------------------------------------- T-11.4, the plan
        val plan = LunarPlan.plan(
            exposureNs = settled.exposureNs,
            iso = iso,
            freeBytes = freeBytes,
            arcsecPerPixel = chosen.arcsecPerPixel,
        )
        log("moon: a full run would be ${plan.describe()}")
        log("moon: ${plan.note}")

        // ---------------------------------------------------------------- T-11.3/T-11.6, sharpness
        // A short burst at the settled exposure, to see whether the metric is steady enough to
        // rank by. If seeing moves it less than the noise does, lucky imaging has nothing to pick.
        log("moon: shooting $frames frames to measure sharpness spread")
        val scores = mutableListOf<Double>()
        for (i in 0 until frames) {
            val file = shoot(access, cameraId, iso, settled.exposureNs, scratch, "frame_%02d.dng".format(i), log)
                ?: break
            val image = DngReader.read(file)
            val white = DngReader.readMetadata(file).whiteLevel ?: 1023
            val pixels = toDoubles(image.pixels)
            val disc = Disc.find(pixels, image.metadata.width, image.metadata.height, white.toDouble())
            val sharpness = Sharpness.overDisc(pixels, image.metadata.width, image.metadata.height, disc)
            scores += sharpness
            log("  frame %02d: sharpness %.4f%s".format(i, sharpness, if (disc == null) " (no disc)" else ""))
        }

        verdict(settled, scores, lastFile, scratch, log)
    }

    private fun verdict(
        settled: LunarExposure.Probe,
        scores: List<Double>,
        lastFile: File?,
        scratch: File,
        log: (String) -> Unit,
    ) {
        log("--- T-11.1 / T-11.3 ---")

        val exposureOk = settled.clippedPixels == 0 &&
            settled.peakFraction in LunarExposure.ACCEPT_LOW..LunarExposure.ACCEPT_HIGH
        log(
            "exposure: %s — peak %.1f%% (want %.0f–%.0f%%), %d clipped (want 0)".format(
                if (exposureOk) "PASS" else "FAIL",
                settled.peakFraction * 100,
                LunarExposure.ACCEPT_LOW * 100, LunarExposure.ACCEPT_HIGH * 100,
                settled.clippedPixels,
            ),
        )

        if (scores.size < 3) {
            log("sharpness: INCONCLUSIVE — only ${scores.size} frames measured")
        } else {
            val best = scores.max()
            val worst = scores.min()
            val mean = scores.average()
            val spread = if (best <= 0) 0.0 else (best - worst) / best
            log(
                "sharpness: best %.4f, worst %.4f, mean %.4f — spread %.1f%%"
                    .format(best, worst, mean, spread * 100),
            )
            // The number that decides whether lucky imaging is worth doing here. If the spread is
            // tiny, the seeing was steady and the cut has nothing to choose; if it is large, the
            // cut is the difference between a sharp result and a soft one.
            log(
                when {
                    best <= 0.0 -> "sharpness: FAIL — no frame produced a usable measurement"
                    spread < 0.05 ->
                        "sharpness: PASS, but the seeing was steady (%.1f%% spread) — the "
                            .format(spread * 100) +
                            "keep-best cut will not change much tonight"
                    else ->
                        "sharpness: PASS — %.1f%% spread across the run, so lucky imaging has "
                            .format(spread * 100) +
                            "something to pick from"
                },
            )
        }

        lastFile?.let { log("frames kept in ${scratch.path} — pull them and look at the disc") }
    }

    /** DNG pixels are unsigned 16-bit; the moon package works in doubles. */
    private fun toDoubles(pixels: ShortArray): DoubleArray =
        DoubleArray(pixels.size) { (pixels[it].toInt() and 0xFFFF).toDouble() }

    private suspend fun shoot(
        access: CameraAccess,
        cameraId: String,
        iso: Int,
        exposureNs: Long,
        dir: File,
        name: String,
        log: (String) -> Unit,
    ): File? {
        runCatching {
            RawCapture.captureSingle(
                access = access,
                cameraId = cameraId,
                iso = iso,
                exposureNs = exposureNs,
                outputDir = dir,
                fileName = name,
            )
        }.getOrElse {
            log("moon: capture failed — ${it.message ?: it::class.simpleName}")
            return null
        }
        return File(dir, name).takeIf { it.isFile }
            ?: run { log("moon: no frame came back"); null }
    }
}
