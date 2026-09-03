package com.starstacker.diag

import com.starstacker.calibration.CalibrationLibrary
import com.starstacker.calibration.FlatField
import com.starstacker.camera.CameraAccess
import com.starstacker.camera.RawCapture
import com.starstacker.dng.DngReader
import java.io.File

/**
 * T-8.3 — shooting a flat field, on the device.
 *
 * ### Why this is a diagnostic first and a screen second
 *
 * The whole of the flat's *value* is in `FlatField` and `CalibrationLibrary`, which are pure and
 * tested. What cannot be tested off-device is the bit that points the camera at something: whether
 * the metering lands in the usable band, whether the frames come back at the size the library
 * expects, and whether the falloff the lens actually has is the falloff §1.41 measured indirectly
 * from the sky.
 *
 * So this runs the capture from `adb` and reports the numbers. The guided screen is worth building
 * once the numbers say what to guide someone towards.
 *
 * ### What to point it at
 *
 * An evenly lit, featureless, bright surface. In practice: **a laptop or monitor showing a blank
 * white page, from far enough back to fill the frame and close enough to be out of focus.** A
 * twilight sky works and is better, and is also not available indoors in the middle of the night,
 * which is when someone finds out they need a flat.
 *
 * ### The metering, which is the only clever part
 *
 * A flat has to land in a band — bright enough not to be noise, dim enough not to clip — and the
 * band is narrow because vignetting means the centre is several times the corners. So it shoots one
 * probe, measures where the bright end fell, and scales the exposure to put it at
 * [TARGET_PEAK] of full scale. One correction rather than a search, because the response is linear
 * and a second probe would only measure the same thing again.
 */
object FlatCheck {

    /**
     * @param root where the library lives — `getExternalFilesDir(null)`.
     * @param frames how many to keep. Sixteen is comfortably above [FlatField.MIN_FRAMES] and
     *   takes a few seconds at these exposures.
     */
    suspend fun run(
        access: CameraAccess,
        cameraId: String,
        root: File,
        frames: Int,
        iso: Int,
        /**
         * Shoot one frame, report what it sees, and stop.
         *
         * A go/no-go before committing sixteen frames and a library write. The thing that decides
         * whether a flat is any good is what is in front of the lens, and that is the one thing
         * neither this nor the validity checks can arrange — so it is worth *looking* first. The
         * probe is left on disk to be pulled and examined.
         */
        probeOnly: Boolean = false,
        log: (String) -> Unit,
    ) {
        log("flats: point the camera at an evenly lit blank surface — a white screen will do")
        val scratch = File(root, "flat-capture").apply {
            if (!probeOnly) listFiles()?.forEach { it.delete() }
            mkdirs()
        }

        try {
            // Metering, iterated rather than guessed once.
            //
            // The first version fired a single 2 ms probe and scaled from it, and on a real screen
            // that came back at **2% of full scale** — the corners reading 1 ADU, which is noise.
            // Every number derived from that frame was meaningless, and the check duly blamed the
            // user's screen for the code's exposure. The response is linear, so one correction
            // usually lands it; what was missing was the willingness to look again.
            var exposureNs = PROBE_EXPOSURE_NS
            var probe: File? = null
            var metadata = DngReader.readMetadata(
                shoot(access, cameraId, iso, exposureNs, scratch, "probe.dng", log) ?: return,
            )
            var black = 0.0
            var white = 65535
            var peak = 0.0

            for (attempt in 1..MAX_PROBES) {
                val file = File(scratch, "probe.dng")
                metadata = DngReader.readMetadata(file)
                black = metadata.blackLevels.filter { it.isFinite() }.average()
                    .takeIf { it.isFinite() } ?: 0.0
                white = metadata.whiteLevel ?: 65535
                peak = brightEnd(file)
                val fraction = (peak - black) / (white - black)
                log(
                    "flats: probe %d at %.1f ms — bright end %.0f of %d (%.0f%% of full scale)".format(
                        attempt, exposureNs / 1e6, peak, white, fraction * 100,
                    ),
                )
                probe = file

                if (fraction in USABLE_LOW..USABLE_HIGH || attempt == MAX_PROBES) break

                val scale = (TARGET_PEAK / fraction).coerceIn(1.0 / MAX_STEP, MAX_STEP)
                val next = (exposureNs * scale).toLong().coerceIn(MIN_EXPOSURE_NS, MAX_EXPOSURE_NS)
                if (next == exposureNs) break
                exposureNs = next
                shoot(access, cameraId, iso, exposureNs, scratch, "probe.dng", log) ?: return
            }

            val settled = probe ?: return
            if (probeOnly) {
                report(settled, black, white, log)
                log("flats: use --ei exposureMs ${(exposureNs / 1_000_000).coerceAtLeast(1)} if you shoot now")
                log("flats: probe kept at ${settled.path}")
                return
            }

            log("flats: shooting $frames at %.1f ms".format(exposureNs / 1e6))

            val planes = mutableListOf<ShortArray>()
            for (i in 1..frames) {
                val file = shoot(access, cameraId, iso, exposureNs, scratch, "flat_%02d.dng".format(i), log)
                    ?: return
                planes += DngReader.read(file).pixels
                file.delete()
                if (i % 4 == 0) log("flats:   $i of $frames")
            }

            val result = FlatField.build(planes, metadata.width, metadata.height, black, white)
            log("flats: ${result.describe()}")
            result.rejections.forEach { log("flats:   - frame ${it.index + 1}: ${it.reason}") }
            result.warnings.forEach { log("flats:   ! $it") }

            val master = result.master
            if (master == null) {
                log("flats: nothing was stored — see the reasons above")
                return
            }

            // §1.41 measured the falloff indirectly, from the sky background of a real session.
            // This is the direct measurement, and the two should agree.
            log(
                "flats: falloff %.2f× — §1.41 inferred about 4.4× from the sky background"
                    .format(result.falloff),
            )

            val info = CalibrationLibrary.FlatInfo(
                cameraId = cameraId,
                capturedAtEpochMs = System.currentTimeMillis(),
                width = metadata.width,
                height = metadata.height,
                frames = result.used,
                falloff = result.falloff,
                iso = iso,
                exposureNs = exposureNs,
                focusDiopters = null,
                notes = result.warnings,
            )
            if (CalibrationLibrary.save(root, info, master)) {
                log("flats: stored — every session on camera $cameraId will now be flat-corrected")
                log("flats: ${File(File(root, CalibrationLibrary.DIRECTORY), "camera-$cameraId").path}")
            } else {
                log("flats: the library refused the write")
            }
        } finally {
            if (!probeOnly) {
                scratch.listFiles()?.forEach { it.delete() }
                scratch.delete()
            }
        }
    }

    /**
     * What one probe frame says about the setup, before sixteen more are spent on it.
     *
     * The three questions a person standing in front of a screen actually needs answered: is it
     * bright enough, is it even, and is there anything in the frame that is not the screen.
     */
    private fun report(file: File, black: Double, white: Int, log: (String) -> Unit) {
        val image = DngReader.read(file)
        val w = image.metadata.width
        val h = image.metadata.height
        val full = (white - black).coerceAtLeast(1.0)
        val box = (minOf(w, h) / 8).coerceAtLeast(8)

        fun patch(left: Int, top: Int): Double {
            var sum = 0.0
            var n = 0
            for (y in top until minOf(top + box, h)) {
                for (x in left until minOf(left + box, w)) {
                    sum += image.sample(x, y) - black
                    n++
                }
            }
            return if (n == 0) 0.0 else sum / n
        }

        val centre = patch(w / 2 - box / 2, h / 2 - box / 2)
        val corners = listOf(
            patch(0, 0) to "top-left",
            patch(w - box, 0) to "top-right",
            patch(0, h - box) to "bottom-left",
            patch(w - box, h - box) to "bottom-right",
        )
        val corner = corners.map { it.first }.average()

        log("flats: centre %.0f ADU (%.0f%% of full scale)".format(centre, 100 * centre / full))
        corners.forEach { (v, name) ->
            log("flats:   %-13s %6.0f ADU  (%.2f× the centre)".format(name, v, v / centre))
        }
        if (corner > 0) log("flats: falloff %.2f×".format(centre / corner))

        val spread = (corners.maxOf { it.first } - corners.minOf { it.first }) / corner
        log("flats: corner-to-corner spread %.0f%%".format(spread * 100))
        val falloff = if (corner > 0) centre / corner else 0.0
        when {
            centre / full > 0.9 -> log("flats: VERDICT — clipping. Move back or dim the screen.")
            centre / full < 0.05 ->
                log("flats: VERDICT — far too dark. Is the screen actually white and facing the lens?")
            centre < corner ->
                log("flats: VERDICT — the corners are brighter than the centre. That is not a flat.")

            // Checked before the corner spread, because it is the failure a screen actually
            // produces and the spread is a symptom of it. A lens does two to four times; this
            // camera does 4.4× measured against the sky (§1.41). Anything far above that is the
            // *source* falling off, not the lens: hold a phone close to a panel and the centre of
            // the frame is the nearest point of it while the corners are further away and seen at
            // a steep angle, so inverse-square and cosine pile on top of the vignette.
            //
            // Using it would over-correct the corners by the excess — amplifying their noise and
            // leaving a bright halo. Worse than no flat at all.
            falloff > MAX_USABLE_FALLOFF -> {
                log("flats: VERDICT — %.1f× falloff is the setup, not the lens.".format(falloff))
                log("flats:   A lens does 2–4×; this one measured 4.4× against the sky.")
                log("flats:   Either back off until the panel is far away and still fills the")
                log("flats:   frame, or put a diffuser right on the lens — two or three layers of")
                log("flats:   white t-shirt, or a sheet of printer paper — and light that instead.")
                log("flats:   A twilight sky is the easy answer when there is one.")
            }

            spread > 0.25 ->
                log("flats: VERDICT — the light is one-sided (%.0f%%). Centre the lens on the screen.".format(spread * 100))
            spread > 0.12 ->
                log("flats: VERDICT — usable, but the light is a little uneven (%.0f%%).".format(spread * 100))
            else -> log("flats: VERDICT — good. Even, well lit, nothing in the way.")
        }
    }

    private suspend fun shoot(
        access: CameraAccess,
        cameraId: String,
        iso: Int,
        exposureNs: Long,
        dir: File,
        name: String,
        log: (String) -> Unit,
    ): File? {
        val outcome = runCatching {
            RawCapture.captureSingle(
                access = access,
                cameraId = cameraId,
                iso = iso,
                exposureNs = exposureNs,
                outputDir = dir,
                fileName = name,
            )
        }.getOrElse {
            log("flats: capture failed — ${it.message ?: it::class.simpleName}")
            return null
        }
        val file = File(dir, name)
        if (!file.isFile) {
            log("flats: no frame came back${outcome.let { "" }}")
            return null
        }
        return file
    }

    /** The bright end of a frame, which is where clipping happens — see [FlatField]. */
    private fun brightEnd(file: File): Double {
        val image = DngReader.read(file)
        val pixels = image.pixels
        val stride = 37
        val n = (pixels.size + stride - 1) / stride
        val samples = DoubleArray(n)
        var k = 0
        var i = 0
        while (i < pixels.size && k < n) {
            samples[k++] = (pixels[i].toInt() and 0xFFFF).toDouble()
            i += stride
        }
        java.util.Arrays.sort(samples, 0, k)
        return if (k == 0) 0.0 else samples[((k - 1) * 0.999).toInt()]
    }

    /**
     * Where the bright end should land: high enough that the corners are well above the noise,
     * with headroom so a slightly brighter frame does not clip. Half of full scale is the
     * conventional answer and the reasoning behind it is exactly this trade.
     */
    private const val TARGET_PEAK = 0.55

    /**
     * Where the metering starts. Short on purpose — clipping tells you nothing about how far over
     * you are, while a dark frame still says how far under — and it climbs from here.
     */
    private const val PROBE_EXPOSURE_NS = 2_000_000L

    /** Probes before giving up. Linear response means two is usually one more than needed. */
    private const val MAX_PROBES = 4

    /** Close enough to [TARGET_PEAK] that another probe would not improve the answer. */
    private const val USABLE_LOW = 0.30
    private const val USABLE_HIGH = 0.80

    /** A cap per step, so one noise-level reading cannot ask for a thousandfold jump. */
    private const val MAX_STEP = 64.0

    /**
     * Above this the illumination is falling off faster than any lens does, so the flat would be
     * measuring the light source rather than the camera. Six times is generous: this lens is 4.4×.
     */
    private const val MAX_USABLE_FALLOFF = 6.0

    private const val MIN_EXPOSURE_NS = 100_000L
    private const val MAX_EXPOSURE_NS = 2_000_000_000L
}
