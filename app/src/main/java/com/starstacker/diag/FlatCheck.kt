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
        log: (String) -> Unit,
    ) {
        log("flats: point the camera at an evenly lit blank surface — a white screen will do")
        val scratch = File(root, "flat-capture").apply {
            listFiles()?.forEach { it.delete() }
            mkdirs()
        }

        try {
            // The probe. Short, because an indoor screen is far brighter than the sky this camera
            // is usually pointed at, and starting long would clip and tell us nothing.
            val probeNs = PROBE_EXPOSURE_NS
            val probe = shoot(access, cameraId, iso, probeNs, scratch, "probe.dng", log) ?: return
            val metadata = DngReader.readMetadata(probe)
            val black = metadata.blackLevels.filter { it.isFinite() }.average().takeIf { it.isFinite() } ?: 0.0
            val white = metadata.whiteLevel ?: 65535
            val peak = brightEnd(probe)
            log(
                "flats: probe at %.1f ms — bright end %.0f of %d (%.0f%% of full scale)".format(
                    probeNs / 1e6, peak, white, 100 * (peak - black) / (white - black),
                ),
            )

            val scale = ((white - black) * TARGET_PEAK) / (peak - black).coerceAtLeast(1.0)
            val exposureNs = (probeNs * scale).toLong().coerceIn(MIN_EXPOSURE_NS, MAX_EXPOSURE_NS)
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
            scratch.listFiles()?.forEach { it.delete() }
            scratch.delete()
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

    /** Short enough not to clip against a screen at arm's length. */
    private const val PROBE_EXPOSURE_NS = 2_000_000L

    private const val MIN_EXPOSURE_NS = 100_000L
    private const val MAX_EXPOSURE_NS = 2_000_000_000L
}
