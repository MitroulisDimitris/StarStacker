package com.starstacker.moon

import com.starstacker.device.CameraProfile
import kotlin.math.roundToInt

/**
 * T-11.2 — how big the moon lands on each camera, and which of them can expose it.
 *
 * ### Compute, report, recommend, never impose
 *
 * The temptation is to have moon mode pick the longest lens and be done. That is wrong twice over
 * (§1.45): architecturally, because this app is built on a *measured* profile rather than on
 * knowledge of one phone, and so hard-coding "use the tele" encodes a fact about one handset into
 * a design that has to run on a phone with one camera, or four, or a tele worse than its main
 * sensor. And as a product, because the framing is the photographer's — a tight disc is one
 * picture and a moon small in a landscape is another, and the second wants a wide lens.
 *
 * So everything here is derived from [CameraProfile] and returned as a *report*. The mode shows the
 * numbers, recommends the largest disc with that number as the reason, defaults to it, and then
 * does whatever it is told.
 *
 * ### The two formulae, and where their inputs come from
 *
 *     arcsec per pixel = 206265 x pixel pitch (mm) / focal length (mm)
 *     moon across (px) = 1865 arcsec / arcsec per pixel
 *
 * `206265` is arcsec per radian. The pitch is `physicalSizeMm` over `pixelArray`, both already in
 * the profile, and the focal is `focalLengthsMm`. Nothing is looked up and nothing is assumed.
 *
 * ### Refusing to fail quietly
 *
 * A camera whose shortest exposure at its lowest ISO still clips the disc cannot photograph the
 * moon at all, and the failure mode is a white blob that looks like a bug in the stacker. So
 * [CameraOption.canExposeMoon] is computed against [LunarExposure]'s Looney 11 starting point, and
 * [Report.warning] names a camera that *would* work when the chosen one will not.
 */
object LunarGeometry {

    /** Arcsec per radian. */
    const val ARCSEC_PER_RADIAN = 206265.0

    /**
     * The moon's mean apparent diameter, in arcsec — 31.1 arcmin.
     *
     * It is not a constant in nature: perigee to apogee runs 29.4' to 33.5', so the true figure
     * moves ±8% over a month. Mean is the right default for "how big will it be", and the error is
     * far smaller than the difference between two cameras, which is what this is used to decide.
     */
    const val MOON_ARCSEC = 1865.0

    /** What one camera would give, with the arithmetic that says so. */
    data class CameraOption(
        val id: String,
        val focalMm: Float,
        val apertureF: Float?,
        val pixelPitchMm: Double,
        val arcsecPerPixel: Double,
        val moonPixelsAcross: Int,
        /** Shortest exposure the sensor advertises, in ns. */
        val minExposureNs: Long?,
        val isoMin: Int?,
        /** False when even the shortest exposure at the lowest ISO would clip the disc. */
        val canExposeMoon: Boolean,
        /** What Looney 11 asks for at [isoMin], in ns — the number [minExposureNs] is judged against. */
        val wantedExposureNs: Long?,
    ) {
        /** The fraction of the frame's shorter side the disc covers, which is what "small" means. */
        fun fillFraction(shorterSidePx: Int): Double =
            if (shorterSidePx <= 0) 0.0 else moonPixelsAcross.toDouble() / shorterSidePx

        fun describe(): String = buildString {
            append("%.1f mm · %.1f\"/px · moon %d px".format(focalMm, arcsecPerPixel, moonPixelsAcross))
            if (!canExposeMoon) append(" · cannot expose without clipping")
        }
    }

    /** Every camera ranked, plus the recommendation and the reason for it. */
    data class Report(
        val options: List<CameraOption>,
        val recommendedId: String?,
        val reason: String,
        /** Set when the recommendation, or the whole device, cannot do the job. */
        val warning: String? = null,
    ) {
        fun optionFor(id: String): CameraOption? = options.firstOrNull { it.id == id }

        /** The reason to show against a camera the user picked rather than the recommended one. */
        fun noteFor(id: String): String? {
            val chosen = optionFor(id) ?: return null
            if (!chosen.canExposeMoon) {
                val alternative = options.firstOrNull { it.canExposeMoon }
                return if (alternative == null) {
                    "No camera on this device can expose the moon without clipping."
                } else {
                    "This camera cannot expose the moon without clipping — " +
                        "camera ${alternative.id} can."
                }
            }
            val best = options.firstOrNull() ?: return null
            if (best.id == id) return null
            return "Camera ${best.id} would give ${best.moonPixelsAcross} px of moon " +
                "against ${chosen.moonPixelsAcross} px here."
        }
    }

    /** Arcsec covered by one pixel. Returns null when the profile cannot say. */
    fun arcsecPerPixel(pixelPitchMm: Double, focalMm: Float): Double? {
        if (pixelPitchMm <= 0.0 || focalMm <= 0f || !focalMm.isFinite()) return null
        return ARCSEC_PER_RADIAN * pixelPitchMm / focalMm
    }

    /** The sensor's pitch in mm, from the physical size over the pixel array. */
    fun pixelPitchMm(camera: CameraProfile): Double? {
        val mm = camera.physicalSizeMm ?: return null
        val px = camera.pixelArray ?: return null
        if (px.width <= 0 || mm.width <= 0f) return null
        return mm.width.toDouble() / px.width
    }

    /** How many pixels the moon spans at this plate scale. */
    fun moonPixelsAcross(arcsecPerPixel: Double): Int =
        if (arcsecPerPixel <= 0.0) 0 else (MOON_ARCSEC / arcsecPerPixel).roundToInt()

    /**
     * Ranks every camera in the profile by how large the moon lands on it.
     *
     * Cameras the arithmetic cannot be done for — no physical size, no focal length — are left out
     * rather than guessed at, and [Report.warning] says so if that leaves nothing.
     */
    fun report(cameras: List<CameraProfile>): Report {
        val options = cameras.mapNotNull { option(it) }.sortedByDescending { it.moonPixelsAcross }

        if (options.isEmpty()) {
            return Report(
                options = emptyList(),
                recommendedId = null,
                reason = "No camera reported both a physical sensor size and a focal length, so " +
                    "the moon's size cannot be computed for any of them.",
                warning = "Moon mode cannot size the disc on this device.",
            )
        }

        // The recommendation is the largest disc among the cameras that can actually expose it.
        // Ranking by size and *then* filtering would recommend a camera that returns a white blob.
        val usable = options.filter { it.canExposeMoon }
        val best = usable.firstOrNull()

        if (best == null) {
            val biggest = options.first()
            return Report(
                options = options,
                recommendedId = biggest.id,
                reason = "Camera ${biggest.id} gives the largest disc at " +
                    "${biggest.moonPixelsAcross} px.",
                warning = "No camera on this device can expose the moon without clipping: the " +
                    "shortest exposure at the lowest ISO is still too long. Frames will need a " +
                    "neutral-density filter, or the disc will clip.",
            )
        }

        val runnerUp = usable.getOrNull(1)
        val reason = buildString {
            append("Camera ${best.id} gives the largest disc at ${best.moonPixelsAcross} px")
            if (runnerUp != null) append(", against ${runnerUp.moonPixelsAcross} px on camera ${runnerUp.id}")
            append(".")
        }

        // A disc this small is a real limit of the hardware and belongs in the UI rather than in a
        // disappointment afterwards. A telescope gives a thousand pixels; a phone does not.
        val warning = if (best.moonPixelsAcross < SMALL_DISC_PX) {
            "Even the best camera here puts the moon at only ${best.moonPixelsAcross} px across. " +
                "That is the honest limit of this hardware — a stack will still help, but do not " +
                "expect crater detail."
        } else {
            null
        }

        return Report(options, best.id, reason, warning)
    }

    private fun option(camera: CameraProfile): CameraOption? {
        val pitch = pixelPitchMm(camera) ?: return null
        val focal = camera.focalLengthsMm.firstOrNull { it > 0f } ?: return null
        val arcsec = arcsecPerPixel(pitch, focal) ?: return null
        val aperture = camera.aperturesF.firstOrNull { it > 0f }

        // Judged at the lowest ISO, because that is where the wanted exposure is longest and the
        // camera therefore has the best chance of reaching it.
        val iso = camera.isoMin?.takeIf { it > 0 }
        val wanted = if (aperture != null && iso != null) {
            LunarExposure.looneyElevenNs(aperture, iso)
        } else {
            null
        }
        val minExposure = camera.exposureMinNs?.takeIf { it > 0 }
        val canExpose = when {
            wanted == null || minExposure == null -> true // unknown is not the same as broken
            else -> minExposure <= wanted
        }

        return CameraOption(
            id = camera.id,
            focalMm = focal,
            apertureF = aperture,
            pixelPitchMm = pitch,
            arcsecPerPixel = arcsec,
            moonPixelsAcross = moonPixelsAcross(arcsec),
            minExposureNs = minExposure,
            isoMin = iso,
            canExposeMoon = canExpose,
            wantedExposureNs = wanted,
        )
    }

    /** Below this the disc is small enough that the UI should say so before the shoot. */
    const val SMALL_DISC_PX = 120
}
