package com.starstacker.device

import com.starstacker.json.Json

/**
 * FR-3.2.1 — the device profile is exportable as JSON so a friend can send their device's
 * profile without installing a calibration workflow.
 *
 * Hand-rolled rather than pulling in a serialization dependency: the shape is fixed, the
 * output is read by humans and desktop scripts, and this stays unit-testable on the JVM
 * (org.json is an Android stub in unit tests). The writer itself now lives in [Json], which
 * also reads — `session.json` has to be parsed back (D-5), not just written.
 */
object ProfileJson {

    fun encode(profile: DeviceProfile): String = Json.write(profile.toMap())

    private fun DeviceProfile.toMap(): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to schemaVersion,
        "capturedAtEpochMs" to capturedAtEpochMs,
        "manufacturer" to manufacturer,
        "model" to model,
        "device" to device,
        "androidRelease" to androidRelease,
        "sdkInt" to sdkInt,
        "supportedAbis" to supportedAbis,
        "sensors" to linkedMapOf(
            "accelerometer" to sensors.accelerometer,
            "magnetometer" to sensors.magnetometer,
            "gyroscope" to sensors.gyroscope,
            "gps" to sensors.gps,
        ),
        "concurrentCameraIdSets" to concurrentCameraIdSets,
        "physicalCameraCount" to cameras.count { !it.isLogical },
        "logicalCameraCount" to cameras.count { it.isLogical },
        "cameras" to cameras.map { it.toMap() },
    )

    private fun CameraProfile.toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "exposed" to exposed,
        "discovery" to discovery.name,
        "isLogical" to isLogical,
        "logicalParentIds" to logicalParentIds,
        "physicalChildIds" to physicalChildIds,
        "facing" to facing,
        "hardwareLevel" to hardwareLevel.name,
        "capabilities" to capabilities,
        "sensor" to linkedMapOf(
            "pixelArray" to pixelArray?.toString(),
            "activeArray" to activeArray?.toString(),
            "physicalSizeMm" to physicalSizeMm?.let { listOf(it.width, it.height) },
            "binningFactor" to binningFactor?.toString(),
            "cfaArrangement" to cfaArrangement,
            "whiteLevel" to whiteLevel,
            "blackLevelPattern" to blackLevelPattern,
            "timestampSource" to timestampSource,
            "maxFrameDurationNs" to maxFrameDurationNs,
        ),
        "raw" to linkedMapOf(
            "sizes" to rawSizes.map { it.toString() },
            "maxSize" to maxRawSize?.toString(),
            "megapixels" to maxRawSize?.megapixels?.round(2),
            // OI-17 — the two pitches, side by side, because confusing them makes the
            // trailing limit wrong by the binning factor.
            "naivePixelPitchUm" to naivePixelPitchUm?.round(4),
            "effectivePixelPitchUm" to effectivePixelPitchUm?.round(4),
            "binningRatio" to rawBinningRatio?.round(3),
            "rawSmallerThanPixelArray" to rawIsBinned,
            "sensorBinsInternally" to sensorBinsInternally,
        ),
        "lens" to linkedMapOf(
            "focalLengthsMm" to focalLengthsMm,
            "aperturesF" to aperturesF,
            "horizontalFovDeg" to focalLengthsMm.map { horizontalFovDegrees(it)?.round(2) },
            "focusDistanceCalibration" to focusDistanceCalibration,
            "minimumFocusDistanceDiopters" to minimumFocusDistanceDiopters,
            "hyperfocalDistanceDiopters" to hyperfocalDistanceDiopters,
            "afAvailableModes" to afAvailableModes,
            "focusType" to focusType.name,
            "oisModes" to oisModes,
            "eisModes" to eisModes,
        ),
        "exposure" to linkedMapOf(
            "isoMin" to isoMin,
            "isoMax" to isoMax,
            "exposureMinNs" to exposureMinNs,
            "exposureMaxNs" to exposureMaxNs,
            "exposureMaxSeconds" to exposureMaxSeconds?.round(4),
        ),
        // Null until a real CaptureResult supplies it (T-1.4): SENSOR_NOISE_PROFILE is a
        // result key, not a characteristics key.
        "noiseProfile" to noiseProfile?.map { listOf(it.s, it.o) },
        "mandatoryStreamCombinations" to mandatoryStreamCombinations,
    )

    private fun Double.round(places: Int): Double {
        var factor = 1.0
        repeat(places) { factor *= 10 }
        return kotlin.math.round(this * factor) / factor
    }
}
