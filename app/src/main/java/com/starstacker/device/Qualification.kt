package com.starstacker.device

/**
 * FR-3.1 — runtime gating, not a whitelist.
 *
 * Four hard disqualifiers, none of which calibration can fix: no RAW, no manual exposure,
 * LEGACY hardware level, or a max exposure under ~2s. Everything else is a tier, not a gate.
 *
 * Pure logic over [CameraProfile], so every branch is unit-testable without a device
 * (plan T-1.2).
 */

enum class Verdict { PASS, WARN, FAIL }

enum class Tier {
    /** Hard requirements met + calibration complete. */
    FULL,

    /** Hard requirements met, no calibration yet. Everything works; results improve later. */
    FUNCTIONAL,

    /** LIMITED hardware level, or RAW present but stream configs constrained. */
    DEGRADED,

    /** A hard requirement failed. Name which one. */
    UNSUPPORTED,
}

data class Check(
    val label: String,
    val value: String,
    val verdict: Verdict,
    val note: String = "",
)

data class CameraQualification(
    val cameraId: String,
    val tier: Tier,
    val checks: List<Check>,
    /** Non-null only when tier == UNSUPPORTED. Names the specific missing capability. */
    val blockingReason: String?,
) {
    val usable: Boolean get() = tier != Tier.UNSUPPORTED
}

object Qualification {

    /** FR-3.1: below this the app cannot do useful deep-sky work. */
    const val MIN_MAX_EXPOSURE_SECONDS = 2.0

    /** FR-3.2.2: above 2s but short enough to change session design materially. */
    const val SHORT_MAX_EXPOSURE_SECONDS = 10.0

    fun qualify(cam: CameraProfile): CameraQualification {
        val checks = mutableListOf<Check>()
        val blockers = mutableListOf<String>()

        // ---- Hard requirement 1: hardware level -------------------------------------
        val levelVerdict = when (cam.hardwareLevel) {
            HardwareLevel.FULL, HardwareLevel.LEVEL_3 -> Verdict.PASS
            HardwareLevel.LIMITED -> Verdict.WARN
            HardwareLevel.LEGACY -> Verdict.FAIL
            HardwareLevel.EXTERNAL, HardwareLevel.UNKNOWN -> Verdict.FAIL
        }
        checks += Check(
            label = "Hardware level",
            value = cam.hardwareLevel.name,
            verdict = levelVerdict,
            note = when (levelVerdict) {
                Verdict.PASS -> "Full manual control available"
                Verdict.WARN -> "LIMITED — Degraded tier: reduced frame rate or resolution"
                Verdict.FAIL -> "LEGACY and EXTERNAL cannot do manual capture"
            },
        )
        if (levelVerdict == Verdict.FAIL) blockers += "hardware level is ${cam.hardwareLevel.name}"

        // ---- Hard requirement 2: RAW ------------------------------------------------
        checks += Check(
            label = "RAW capability",
            value = if (cam.hasRawCapability) "present" else "absent",
            verdict = if (cam.hasRawCapability) Verdict.PASS else Verdict.FAIL,
            note = "REQUEST_AVAILABLE_CAPABILITIES_RAW",
        )
        if (!cam.hasRawCapability) blockers += "no RAW capability"

        // ---- Hard requirement 3: manual exposure + ISO ------------------------------
        checks += Check(
            label = "Manual exposure + ISO",
            value = if (cam.hasManualSensor) "available" else "unavailable",
            verdict = if (cam.hasManualSensor) Verdict.PASS else Verdict.FAIL,
            note = "REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR",
        )
        if (!cam.hasManualSensor) blockers += "no manual exposure/ISO control"

        // ---- Hard requirement 4: max exposure time ----------------------------------
        val maxExp = cam.exposureMaxSeconds
        val expVerdict = when {
            maxExp == null -> Verdict.FAIL
            maxExp < MIN_MAX_EXPOSURE_SECONDS -> Verdict.FAIL
            maxExp < SHORT_MAX_EXPOSURE_SECONDS -> Verdict.WARN
            else -> Verdict.PASS
        }
        checks += Check(
            label = "Max exposure",
            value = maxExp?.let { formatSeconds(it) } ?: "unknown",
            verdict = expVerdict,
            note = when (expVerdict) {
                Verdict.PASS -> "Long enough for sky-limited subs"
                Verdict.WARN -> "Under ${SHORT_MAX_EXPOSURE_SECONDS.toInt()}s — usable, but session " +
                    "design changes materially (FR-3.2.2)"
                Verdict.FAIL -> "Under ${MIN_MAX_EXPOSURE_SECONDS.toInt()}s — cannot do useful deep-sky work"
            },
        )
        if (expVerdict == Verdict.FAIL) {
            blockers += if (maxExp == null) "max exposure time not reported"
            else "max exposure ${formatSeconds(maxExp)} is under ${MIN_MAX_EXPOSURE_SECONDS.toInt()}s"
        }

        // ---- Informational: the quad-Bayer trap (OI-17) -----------------------------
        val ratio = cam.rawBinningRatio
        if (ratio != null && cam.effectivePixelPitchUm != null) {
            checks += Check(
                label = "RAW pixel pitch",
                value = "%.2f um".format(cam.effectivePixelPitchUm),
                verdict = Verdict.PASS,
                note = if (cam.rawIsBinned) {
                    "RAW is binned %.1fx vs the pixel array (naive pitch would be %.2f um). ".format(
                        ratio, cam.naivePixelPitchUm ?: 0.0,
                    ) + "Larger effective pixels — good for astro. The trailing limit must use " +
                        "the effective pitch, not the naive one."
                } else {
                    "RAW delivered at full pixel-array resolution — naive and effective pitch agree"
                },
            )
        }

        // ---- Informational: focus ---------------------------------------------------
        checks += Check(
            label = "Focus",
            value = when (cam.focusType) {
                FocusType.MOTOR -> "AF motor present"
                FocusType.FIXED -> "fixed focus"
                FocusType.UNKNOWN -> "not reported"
            },
            verdict = if (cam.focusType == FocusType.UNKNOWN) Verdict.WARN else Verdict.PASS,
            note = when (cam.focusType) {
                FocusType.MOTOR ->
                    "Infinity focus must be calibrated and re-verified per session (FR-6.3)"
                FocusType.FIXED ->
                    "Immune to focus drift — skip the sweep and record 'fixed focus' (FR-4.1.4.1)"
                FocusType.UNKNOWN ->
                    "Neither AF modes nor minimum focus distance reported. Assume a motor and " +
                        "run the sweep: a soft session is unrecoverable, a redundant sweep costs " +
                        "two minutes"
            },
        )

        // ---- Informational: how this camera was found -------------------------------
        if (cam.discovery != Discovery.LISTED) {
            checks += Check(
                label = "Availability",
                value = when (cam.discovery) {
                    Discovery.PHYSICAL_CHILD -> "physical sub-camera"
                    Discovery.HIDDEN -> "not published"
                    Discovery.LISTED -> "listed"
                },
                verdict = Verdict.WARN,
                note = "Absent from getCameraIdList(). Characteristics are readable, but " +
                    "opening it may still be refused — untested until T-1.3",
            )
        }

        // ---- Informational: OIS -----------------------------------------------------
        val oisOffAvailable = cam.oisModes.isEmpty() || cam.oisModes.contains(0)
        checks += Check(
            label = "OIS disableable",
            value = if (cam.oisModes.isEmpty()) "no OIS" else if (oisOffAvailable) "yes" else "no",
            verdict = if (oisOffAvailable) Verdict.PASS else Verdict.WARN,
            note = if (oisOffAvailable) {
                "OIS can be turned off for tripod use (FR-6.1)"
            } else {
                "OIS cannot be disabled — expect sub-pixel drift between frames on a tripod"
            },
        )

        val tier = when {
            blockers.isNotEmpty() -> Tier.UNSUPPORTED
            cam.hardwareLevel == HardwareLevel.LIMITED -> Tier.DEGRADED
            // FULL requires calibration, which does not exist until Phase 6. Until then the
            // best any camera can reach is FUNCTIONAL — and FR-3.1.1 says that must be enough.
            else -> Tier.FUNCTIONAL
        }

        return CameraQualification(
            cameraId = cam.id,
            tier = tier,
            checks = checks,
            blockingReason = blockers.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    /**
     * Device-level verdict: the app is usable if any back-facing camera qualifies.
     * A phone whose front camera alone passes is not an astro camera.
     */
    fun qualifyDevice(profile: DeviceProfile): DeviceQualification {
        val perCamera = profile.cameras.map { qualify(it) }
        // Physical rear cameras only. A logical multi-camera fronts lenses already counted, so
        // including it would inflate the count (FR-11.1: one instrument per physical camera).
        val backIds = profile.cameras
            .filter { it.facing == "BACK" && !it.isLogical }.map { it.id }.toSet()
        val usableBack = perCamera.filter { it.cameraId in backIds && it.usable }

        val bestTier = usableBack.minByOrNull { it.tier.ordinal }?.tier ?: Tier.UNSUPPORTED

        // Published and hidden cameras are counted separately. A hidden camera's
        // characteristics are readable, but nothing has yet proved it can be opened, and
        // promising the user four cameras that turn out to be one would be a lie.
        val publishedIds = profile.cameras
            .filter { it.discovery == Discovery.LISTED }.map { it.id }.toSet()
        val publishedUsable = usableBack.count { it.cameraId in publishedIds }
        val hiddenUsable = usableBack.size - publishedUsable

        val hiddenSuffix = if (hiddenUsable > 0) {
            " (+$hiddenUsable hidden, openability unproven)"
        } else {
            ""
        }

        val headline = when {
            usableBack.isEmpty() -> "Unsupported — no rear camera meets the requirements"
            publishedUsable == 0 -> "Blocked — the only qualifying rear cameras are unpublished"
            bestTier == Tier.DEGRADED ->
                "Usable, degraded — $publishedUsable published rear camera(s)$hiddenSuffix"
            else -> "Supported — $publishedUsable published rear camera(s)$hiddenSuffix"
        }

        val sensorChecks = listOf(
            Check(
                "Accelerometer", if (profile.sensors.accelerometer) "present" else "absent",
                if (profile.sensors.accelerometer) Verdict.PASS else Verdict.FAIL,
                "Required for pointing altitude",
            ),
            Check(
                "Magnetometer", if (profile.sensors.magnetometer) "present" else "absent",
                if (profile.sensors.magnetometer) Verdict.PASS else Verdict.FAIL,
                "Required for pointing azimuth",
            ),
            Check(
                "Gyroscope", if (profile.sensors.gyroscope) "present" else "absent",
                if (profile.sensors.gyroscope) Verdict.PASS else Verdict.WARN,
                "Strongly recommended — improves the analytic registration seed",
            ),
            Check(
                "GPS", if (profile.sensors.gps) "present" else "absent",
                if (profile.sensors.gps) Verdict.PASS else Verdict.WARN,
                "Strongly recommended — latitude drives the field-rotation rate",
            ),
        )

        return DeviceQualification(
            cameras = perCamera,
            sensors = sensorChecks,
            bestTier = bestTier,
            headline = headline,
        )
    }

    private fun formatSeconds(s: Double): String = when {
        s >= 1.0 -> "%.1f s".format(s)
        else -> "1/%.0f s".format(1.0 / s)
    }
}

data class DeviceQualification(
    val cameras: List<CameraQualification>,
    val sensors: List<Check>,
    val bestTier: Tier,
    val headline: String,
)
