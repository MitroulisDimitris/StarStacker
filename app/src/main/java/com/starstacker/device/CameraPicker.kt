package com.starstacker.device

/**
 * T-2.7 — the camera picker, derived from the probe rather than written down.
 *
 * The prototype's picker shows "24mm · Main · Best light-gathering. Fully calibrated." Those are
 * three different kinds of claim and only one of them can be hard-coded: the focal length is
 * measured, the role is *relative* to the other lenses on this particular phone, and the note has
 * to be true of the device in hand. FR-11.2 makes that concrete — it guesses the ultrawide will
 * be the best astro camera, and on the reference device it is comfortably the worst (1.12 µm at
 * f/2.2 against 2.00 µm at f/1.88). A hard-coded string would have shipped that guess as advice.
 *
 * Logical multi-cameras are excluded: a logical camera fronts lenses that are already listed, so
 * offering it would be offering the same instrument twice (FR-11.1).
 */
data class CameraOption(
    val id: String,
    /** Role relative to the other lenses on this device: Main, Ultrawide, Tele. */
    val name: String,
    val focalMm: Float?,
    /** Focal length in the units people actually compare — 35 mm equivalent. */
    val equivalent35mm: Int?,
    val apertureF: Float?,
    val pixelPitchUm: Double?,
    val horizontalFovDeg: Double?,
    val maxExposureSeconds: Double?,
    val fixedFocus: Boolean,
    val note: String,
    /** Non-null when the camera is usable but something about it should be said out loud. */
    val warning: String?,
    val selectable: Boolean,
    val recommended: Boolean,
) {
    val headline: String
        get() = equivalent35mm?.let { "${it}mm" }
            ?: focalMm?.let { "%.1fmm".format(it) }
            ?: "—"
}

object CameraPicker {

    /** Diagonal of a 35 mm frame, for the equivalent focal length. */
    private const val FULL_FRAME_DIAGONAL_MM = 43.2667

    fun options(
        profile: DeviceProfile,
        qualification: DeviceQualification,
    ): List<CameraOption> {
        val candidates = profile.cameras
            .filter { it.facing == "BACK" && !it.isLogical }
            .sortedBy { it.focalLengthsMm.firstOrNull() ?: Float.MAX_VALUE }
        if (candidates.isEmpty()) return emptyList()

        val mainId = candidates.maxByOrNull { lightGatheringScore(it) }?.id
        val widestId = candidates.firstOrNull()?.id
        val longestId = candidates.lastOrNull()?.id
        val names = nameCameras(candidates, mainId)

        val recommendedId = candidates
            .filter { qualification.cameras.firstOrNull { q -> q.cameraId == it.id }?.usable == true }
            .maxByOrNull { lightGatheringScore(it) }
            ?.id

        return candidates.map { cam ->
            val q = qualification.cameras.firstOrNull { it.cameraId == cam.id }
            val focal = cam.focalLengthsMm.firstOrNull()
            CameraOption(
                id = cam.id,
                name = names.getValue(cam.id),
                focalMm = focal,
                equivalent35mm = equivalent35mm(cam),
                apertureF = cam.aperturesF.firstOrNull(),
                pixelPitchUm = cam.effectivePixelPitchUm,
                horizontalFovDeg = focal?.let { cam.horizontalFovDegrees(it) },
                maxExposureSeconds = cam.exposureMaxSeconds,
                fixedFocus = cam.focusType == FocusType.FIXED,
                note = noteFor(cam, isMain = cam.id == mainId, isWidest = cam.id == widestId,
                    isLongest = cam.id == longestId, onlyOne = candidates.size == 1),
                warning = warningFor(cam, q),
                selectable = q?.usable ?: false,
                recommended = cam.id == recommendedId,
            )
        }
    }

    /**
     * How much light one pixel collects: pitch² over f-number². Not resolution, not sensor size
     * — the quantity that decides whether a faint star clears the read noise in a 12 s sub.
     */
    private fun lightGatheringScore(cam: CameraProfile): Double {
        val pitch = cam.effectivePixelPitchUm ?: return 0.0
        val fNumber = cam.aperturesF.firstOrNull()?.toDouble() ?: return 0.0
        if (fNumber <= 0.0) return 0.0
        return pitch * pitch / (fNumber * fNumber)
    }

    private fun equivalent35mm(cam: CameraProfile): Int? {
        val focal = cam.focalLengthsMm.firstOrNull() ?: return null
        val size = cam.physicalSizeMm ?: return null
        val diagonal = Math.sqrt(
            (size.width.toDouble() * size.width + size.height.toDouble() * size.height),
        )
        if (diagonal <= 0.0) return null
        return Math.round(focal * FULL_FRAME_DIAGONAL_MM / diagonal).toInt()
    }

    /**
     * Roles are relative. "Ultrawide" only means anything next to a main camera, so the names
     * come from where each lens sits against the one with the best light gathering.
     */
    private fun nameCameras(cameras: List<CameraProfile>, mainId: String?): Map<String, String> {
        val names = LinkedHashMap<String, String>()
        var wideIndex = 0
        var teleIndex = 0
        val mainFocal = cameras.firstOrNull { it.id == mainId }?.focalLengthsMm?.firstOrNull()

        for (cam in cameras) {
            val focal = cam.focalLengthsMm.firstOrNull()
            names[cam.id] = when {
                cam.id == mainId -> "Main"
                focal == null || mainFocal == null -> "Camera ${cam.id}"
                focal < mainFocal -> {
                    wideIndex++
                    if (wideIndex == 1) "Ultrawide" else "Ultrawide $wideIndex"
                }

                else -> {
                    teleIndex++
                    if (teleIndex == 1) "Tele" else "Tele $teleIndex"
                }
            }
        }
        return names
    }

    private fun noteFor(
        cam: CameraProfile,
        isMain: Boolean,
        isWidest: Boolean,
        isLongest: Boolean,
        onlyOne: Boolean,
    ): String {
        val pitch = cam.effectivePixelPitchUm
        val aperture = cam.aperturesF.firstOrNull()
        val fov = cam.focalLengthsMm.firstOrNull()?.let { cam.horizontalFovDegrees(it) }

        val role = when {
            onlyOne -> "The only rear camera on this device."
            isMain -> "Best light-gathering on this device" +
                (if (pitch != null && aperture != null) {
                    " — %.2f µm pixels at f/%.1f.".format(pitch, aperture)
                } else ".")

            isWidest -> "Widest field" +
                (fov?.let { " at %.0f° across".format(it) } ?: "") +
                " — the longest subs before trailing."

            isLongest -> "Tightest field" +
                (fov?.let { " at %.0f° across".format(it) } ?: "") +
                " — small bright targets, but fewer stars to register on."

            else -> "Intermediate field" + (fov?.let { " at %.0f° across".format(it) } ?: "") + "."
        }

        val focus = when (cam.focusType) {
            FocusType.FIXED -> " Fixed focus: nothing to calibrate and nothing to drift."
            FocusType.MOTOR -> ""
            FocusType.UNKNOWN -> " Focus type not reported — the sweep runs anyway."
        }

        val light = if (!isMain && !onlyOne && pitch != null && aperture != null) {
            " %.2f µm at f/%.1f.".format(pitch, aperture)
        } else {
            ""
        }

        return role + light + focus
    }

    private fun warningFor(cam: CameraProfile, q: CameraQualification?): String? {
        val reasons = mutableListOf<String>()
        if (q?.usable == false) {
            reasons += q.blockingReason ?: "does not meet the hard requirements"
        }
        if (cam.discovery == Discovery.HIDDEN) {
            // OI-19: every ID on the reference device opens, but only camera 0 has completed a
            // real capture. Opening is not capturing.
            reasons += "not published by the system — it opens, but capture is unproven"
        }
        val maxExposure = cam.exposureMaxSeconds
        if (maxExposure != null && maxExposure < Qualification.SHORT_MAX_EXPOSURE_SECONDS &&
            maxExposure >= Qualification.MIN_MAX_EXPOSURE_SECONDS
        ) {
            reasons += "caps out at %.1f s per frame".format(maxExposure)
        }
        return reasons.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
