package com.starstacker.device

/**
 * Device profile model — FR-3.2.
 *
 * Deliberately free of `android.*` types. The probe (CameraProbe) converts
 * CameraCharacteristics into these plain values; everything downstream — qualification,
 * the exposure engine, JSON export — works on this and is therefore unit-testable on the
 * JVM without a device.
 */

enum class HardwareLevel { LEGACY, LIMITED, FULL, LEVEL_3, EXTERNAL, UNKNOWN }

/** How the probe found a camera. Hidden cameras exist in the HAL but are not published. */
enum class Discovery {
    /** In getCameraIdList() — openable by any app. */
    LISTED,

    /** A physical camera behind a listed logical camera. */
    PHYSICAL_CHILD,

    /** Not published at all; found by probing IDs directly. May not be openable. */
    HIDDEN,
}

/**
 * A null LENS_INFO_MINIMUM_FOCUS_DISTANCE means "not reported", not "fixed focus" — treating
 * the two as the same skips the focus sweep and softens an entire session (FR-6.3).
 */
enum class FocusType { FIXED, MOTOR, UNKNOWN }

data class SizePx(val width: Int, val height: Int) {
    override fun toString() = "${width}x$height"
    val megapixels: Double get() = width.toDouble() * height / 1_000_000.0
}

data class SizeMm(val width: Float, val height: Float) {
    override fun toString() = "%.2f x %.2f mm".format(width, height)
}

/** One (S, O) pair per CFA channel, from SENSOR_NOISE_PROFILE. Variance = S*signal + O. */
data class NoiseProfileEntry(val s: Double, val o: Double)

data class CameraProfile(
    val id: String,
    /** False for physical cameras hidden behind a logical camera — still probed (FR-3.2). */
    val exposed: Boolean,
    val discovery: Discovery,
    val logicalParentIds: List<String>,
    /** For a logical multi-camera: the physical cameras it fronts. */
    val physicalChildIds: List<String>,
    val facing: String,
    val hardwareLevel: HardwareLevel,
    val capabilities: List<String>,
    val hasRawCapability: Boolean,
    val hasManualSensor: Boolean,
    val hasUltraHighResolutionSensor: Boolean,

    val pixelArray: SizePx?,
    val activeArray: SizePx?,
    val physicalSizeMm: SizeMm?,
    val rawSizes: List<SizePx>,
    val binningFactor: SizePx?,

    val focalLengthsMm: List<Float>,
    val aperturesF: List<Float>,
    val isoMin: Int?,
    val isoMax: Int?,
    val exposureMinNs: Long?,
    val exposureMaxNs: Long?,
    val maxFrameDurationNs: Long?,

    val cfaArrangement: String?,
    val whiteLevel: Int?,
    val blackLevelPattern: List<Int>?,
    val noiseProfile: List<NoiseProfileEntry>?,
    val timestampSource: String,

    val focusDistanceCalibration: String?,
    val minimumFocusDistanceDiopters: Float?,
    val hyperfocalDistanceDiopters: Float?,
    val afAvailableModes: List<Int>,

    val oisModes: List<Int>,
    val eisModes: List<Int>,
    val mandatoryStreamCombinations: List<String>,
) {
    /** Largest RAW_SENSOR output the device will give us. */
    val maxRawSize: SizePx? get() = rawSizes.maxByOrNull { it.width.toLong() * it.height }

    /**
     * Whether this camera can focus at all.
     *
     * AF modes are the primary evidence: a camera offering any mode beyond OFF has a motor.
     * Minimum focus distance is the fallback, and only counts when it is actually reported —
     * a null must not be read as "fixed focus" (FR-4.1.4.1 vs FR-6.3).
     */
    val focusType: FocusType
        get() = when {
            afAvailableModes.any { it != 0 } -> FocusType.MOTOR
            (minimumFocusDistanceDiopters ?: 0f) > 0f -> FocusType.MOTOR
            afAvailableModes.isNotEmpty() || minimumFocusDistanceDiopters != null -> FocusType.FIXED
            else -> FocusType.UNKNOWN
        }

    val hasAfMotor: Boolean get() = focusType == FocusType.MOTOR

    /**
     * A logical multi-camera is a virtual device fronting other physical ones — it is not a
     * lens and must not be counted as one. FR-11.1 treats each *physical* camera as a separate
     * instrument, so this is the line between "how many cameras" and "how many instruments".
     */
    val isLogical: Boolean
        get() = capabilities.contains("LOGICAL_MULTI_CAMERA") || physicalChildIds.size > 1

    /**
     * True when the sensor bins internally (SENSOR_INFO_BINNING_FACTOR) — a quad-Bayer sensor
     * delivering a quarter-resolution Bayer frame. Distinct from [rawIsBinned], which compares
     * the RAW stream against the *reported* pixel array; when the platform already reports the
     * binned array (as most do), that ratio is 1.0 while the sensor is still binning.
     */
    val sensorBinsInternally: Boolean
        get() = (binningFactor?.width ?: 1) > 1 || (binningFactor?.height ?: 1) > 1

    /**
     * Pitch computed the naive way: physical sensor width / full pixel array width.
     *
     * On a quad-Bayer sensor this is NOT the pitch that governs star trailing, because the
     * RAW stream is delivered binned. Reported only so the two can be compared. See OI-17.
     */
    val naivePixelPitchUm: Double?
        get() {
            val phys = physicalSizeMm ?: return null
            val arr = pixelArray ?: return null
            if (arr.width == 0) return null
            return phys.width * 1000.0 / arr.width
        }

    /**
     * The pitch that actually matters: physical sensor width divided by the width of the RAW
     * frame we are handed. This is the number the NPF trailing limit must use (T-3.2).
     */
    val effectivePixelPitchUm: Double?
        get() {
            val phys = physicalSizeMm ?: return null
            val raw = maxRawSize ?: return null
            if (raw.width == 0) return null
            return phys.width * 1000.0 / raw.width
        }

    /** > ~1.5 means the RAW stream is binned relative to the full pixel array. */
    val rawBinningRatio: Double?
        get() {
            val arr = pixelArray ?: return null
            val raw = maxRawSize ?: return null
            if (raw.width == 0) return null
            return arr.width.toDouble() / raw.width
        }

    val rawIsBinned: Boolean get() = (rawBinningRatio ?: 1.0) > 1.5

    val exposureMaxSeconds: Double? get() = exposureMaxNs?.div(1_000_000_000.0)

    /** Horizontal field of view for a given focal length, degrees. */
    fun horizontalFovDegrees(focalMm: Float): Double? {
        val phys = physicalSizeMm ?: return null
        if (focalMm <= 0f) return null
        return Math.toDegrees(2.0 * kotlin.math.atan(phys.width / (2.0 * focalMm)))
    }
}

data class SensorAvailability(
    val accelerometer: Boolean,
    val magnetometer: Boolean,
    val gyroscope: Boolean,
    val gps: Boolean,
)

data class DeviceProfile(
    val schemaVersion: Int = 1,
    val capturedAtEpochMs: Long,
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val cameras: List<CameraProfile>,
    val concurrentCameraIdSets: List<List<String>>,
    val sensors: SensorAvailability,
)
