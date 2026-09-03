package com.starstacker.calibration

import com.starstacker.json.Json
import com.starstacker.json.double
import com.starstacker.json.int
import com.starstacker.json.long
import com.starstacker.json.string
import com.starstacker.stacking.LinearMaster
import java.io.File

/**
 * T-8.3 / FR-4.1 — calibration that belongs to the **camera** rather than to the night.
 *
 * ### Why a library rather than a folder per session
 *
 * Darks belong to a session: dark current depends on temperature and exposure, and **D-16** matches
 * them by shooting them at the end of the same run. A flat does not. Vignetting is a property of
 * the lens, fixed until the lens is cleaned or the phone is dropped, so a flat is good for every
 * session that camera ever shoots.
 *
 * That distinction is the whole argument for this class. FR-9.1 anticipates it — a session's
 * `flats/` may be *"a symlink/copy from calibration library, or reference"* — and the practical
 * point is blunter: **shooting flats every night is the thing that makes people stop shooting
 * flats.** Once, indoors, against a bright screen, and every session afterwards is corrected.
 *
 * ### What is stored, and why it is a TIFF
 *
 * The master flat goes out through [LinearMaster] as a single-channel 32-bit float TIFF — the same
 * writer, and therefore the same tested `SampleFormat` handling, as the linear master. It is stored
 * **normalised to a mean of 1** and at full sensor resolution in CFA layout, because that is what
 * `Calibration.apply` divides by (T-5.2, before debayer).
 *
 * A float TIFF rather than a private binary blob because it costs nothing and buys something real:
 * the user can open their own flat in Siril and see whether it looks like a lens.
 *
 * ### Keyed by camera, and honest about what it does not key on
 *
 * The key is the camera id. **Not focus**, although dust shadows do move with it — the falloff that
 * dominates does not, and demanding a flat per focus position would mean never having one. **Not
 * ISO or exposure**, because a normalised flat is a shape rather than a level. Both of those are
 * simplifications and both are recorded in the metadata, so a future version can tell whether a
 * stored flat was shot anywhere near the session using it.
 */
object CalibrationLibrary {

    /** What a stored flat knows about itself — FR-9.2's audit trail, for calibration. */
    data class FlatInfo(
        val cameraId: String,
        val capturedAtEpochMs: Long,
        val width: Int,
        val height: Int,
        val frames: Int,
        val falloff: Double,
        val iso: Int,
        val exposureNs: Long,
        val focusDiopters: Float?,
        val notes: List<String> = emptyList(),
    ) {
        fun describe(): String = buildString {
            append("camera $cameraId · $frames frames · %.2f× falloff".format(falloff))
            append(" · ${width}x$height")
            if (notes.isNotEmpty()) append(" · ${notes.size} notes")
        }

        fun toJson(): Map<String, Any?> = linkedMapOf(
            "cameraId" to cameraId,
            "capturedAt" to capturedAtEpochMs,
            "width" to width,
            "height" to height,
            "frames" to frames,
            "falloff" to falloff,
            "iso" to iso,
            "exposureNs" to exposureNs,
            "focusDiopters" to focusDiopters?.toDouble(),
            "notes" to notes,
        )

        companion object {
            fun fromJson(map: Map<String, Any?>): FlatInfo? {
                val camera = map.string("cameraId") ?: return null
                return FlatInfo(
                    cameraId = camera,
                    capturedAtEpochMs = map.long("capturedAt") ?: 0L,
                    width = map.int("width") ?: return null,
                    height = map.int("height") ?: return null,
                    frames = map.int("frames") ?: 0,
                    falloff = map.double("falloff") ?: 0.0,
                    iso = map.int("iso") ?: 0,
                    exposureNs = map.long("exposureNs") ?: 0L,
                    focusDiopters = map.double("focusDiopters")?.toFloat(),
                    notes = (map["notes"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                )
            }
        }
    }

    /** A flat and the metadata beside it. */
    class Flat(val info: FlatInfo, val pixels: FloatArray)

    /**
     * Saves a master flat for [info]`.cameraId`, replacing whatever was there.
     *
     * Replacing rather than versioning, deliberately: a newer flat of the same lens is a better
     * measurement of the same thing, and keeping the old one would only invite the question of
     * which to use. FR-10.4.2's `Stale` marking is the mechanism that tells sessions stacked with
     * the old one that they could be restacked — a different problem, and not this one.
     */
    fun save(root: File, info: FlatInfo, master: FloatArray): Boolean {
        val dir = directoryFor(root, info.cameraId)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return runCatching {
            LinearMaster.write(
                file = File(dir, FLAT_FILE),
                master = master,
                width = info.width,
                height = info.height,
                channels = 1,
                description = "StarStacker flat field | ${info.describe()}",
            )
            File(dir, INFO_FILE).writeText(Json.write(info.toJson()))
            true
        }.getOrDefault(false)
    }

    /** The metadata alone, which is all the UI needs to say whether a camera is calibrated. */
    fun info(root: File, cameraId: String): FlatInfo? = runCatching {
        val file = File(directoryFor(root, cameraId), INFO_FILE)
        if (!file.isFile) return null
        FlatInfo.fromJson(Json.parseObject(file.readText()))
    }.getOrNull()

    /**
     * The flat itself, for a frame of exactly [width] × [height].
     *
     * **Refuses a flat of the wrong size** rather than scaling one. A flat is a per-photosite
     * measurement, and resampling it would blend neighbouring sites of different colours — the
     * same error T-5.2 exists to avoid, arriving from the calibration side.
     */
    fun flat(root: File, cameraId: String, width: Int, height: Int): Flat? {
        val info = info(root, cameraId) ?: return null
        if (info.width != width || info.height != height) return null
        val image = LinearMaster.read(File(directoryFor(root, cameraId), FLAT_FILE)) ?: return null
        if (image.width != width || image.height != height) return null
        return Flat(info, image.pixels)
    }

    /** Every camera with a stored flat, for the settings screen. */
    fun cameras(root: File): List<FlatInfo> =
        File(root, DIRECTORY).listFiles { f -> f.isDirectory }
            ?.mapNotNull { info(root, it.name.removePrefix(CAMERA_PREFIX)) }
            ?.sortedBy { it.cameraId }
            .orEmpty()

    fun delete(root: File, cameraId: String): Boolean {
        val dir = directoryFor(root, cameraId)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return dir.delete()
    }

    private fun directoryFor(root: File, cameraId: String): File =
        File(File(root, DIRECTORY), CAMERA_PREFIX + sanitise(cameraId))

    /** Camera ids come from the HAL and are usually digits, but nothing promises that. */
    private fun sanitise(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifEmpty { "unknown" }

    const val DIRECTORY = "calibration"
    private const val CAMERA_PREFIX = "camera-"
    const val FLAT_FILE = "flat.tif"
    private const val INFO_FILE = "flat.json"
}
