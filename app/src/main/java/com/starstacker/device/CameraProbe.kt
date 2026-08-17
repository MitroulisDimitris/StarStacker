package com.starstacker.device

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.MandatoryStreamCombination
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SizeF

/**
 * FR-3.2 — first-run capability probe.
 *
 * Enumerates every *physical* camera, including those hidden behind a logical camera, and
 * converts CameraCharacteristics into the plain [DeviceProfile] model.
 *
 * Needs no permissions: reading characteristics does not require CAMERA. That makes this the
 * cheapest possible answer to "does this device work at all" (plan OI-6).
 */
object CameraProbe {

    private const val TAG = "CameraProbe"

    /** Highest unpublished camera ID worth probing. Vendors keep these small in practice. */
    private const val MAX_PROBED_CAMERA_ID = 31

    private fun physicalChildrenOf(manager: CameraManager, id: String): Set<String> = runCatching {
        manager.getCameraCharacteristics(id).physicalCameraIds
    }.getOrElse { emptySet() }

    fun probe(context: Context): DeviceProfile {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val exposedIds: List<String> = try {
            manager.cameraIdList.toList()
        } catch (t: Throwable) {
            Log.e(TAG, "cameraIdList failed", t)
            emptyList()
        }

        // Discovery runs in three passes, because getCameraIdList() is not the whole truth.
        //
        // On the Nothing Phone (3a) Pro the camera service holds five HAL devices — main,
        // front, ultrawide, tele and a logical camera fronting [ultrawide, main, tele] — but
        // publishes only main and front. The extra lenses are invisible to pass 1 *and* to
        // pass 2, because their logical parent is itself unpublished. Only a direct ID probe
        // finds them. FR-3.2 asks for every physical camera, so all three passes run.
        val parentOf = mutableMapOf<String, MutableList<String>>()
        val discovery = mutableMapOf<String, Discovery>()
        val allIds = LinkedHashSet<String>()

        // Pass 1 — published cameras.
        for (id in exposedIds) {
            allIds += id
            discovery[id] = Discovery.LISTED
        }

        // Pass 2 — physical cameras behind published logical ones.
        for (id in exposedIds.toList()) {
            for (p in physicalChildrenOf(manager, id)) {
                if (p != id && allIds.add(p)) discovery[p] = Discovery.PHYSICAL_CHILD
                if (p != id) parentOf.getOrPut(p) { mutableListOf() } += id
            }
        }

        // Pass 3 — unpublished IDs. Characteristics are often readable even when the ID is
        // absent from the list; whether such a camera can be *opened* is a separate question
        // that T-1.3 answers. Reported honestly either way rather than silently omitted.
        for (candidate in 0..MAX_PROBED_CAMERA_ID) {
            val id = candidate.toString()
            if (id in allIds) continue
            val readable = runCatching { manager.getCameraCharacteristics(id) }.isSuccess
            if (readable) {
                allIds += id
                discovery[id] = Discovery.HIDDEN
                for (p in physicalChildrenOf(manager, id)) {
                    if (p != id && allIds.add(p)) discovery[p] = Discovery.HIDDEN
                    if (p != id) parentOf.getOrPut(p) { mutableListOf() } += id
                }
            }
        }

        val cameras = allIds.mapNotNull { id ->
            runCatching {
                readCamera(
                    manager = manager,
                    id = id,
                    exposed = id in exposedIds,
                    discovery = discovery[id] ?: Discovery.HIDDEN,
                    parents = parentOf[id].orEmpty(),
                )
            }.onFailure { Log.e(TAG, "probe of camera $id failed", it) }.getOrNull()
        }.sortedWith(compareBy({ it.discovery.ordinal }, { it.id.toIntOrNull() ?: 0 }))

        val concurrent: List<List<String>> = runCatching {
            manager.concurrentCameraIds.map { it.toList() }
        }.getOrElse { emptyList() }

        return DeviceProfile(
            capturedAtEpochMs = System.currentTimeMillis(),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            cameras = cameras,
            concurrentCameraIdSets = concurrent,
            sensors = readSensors(context),
        )
    }

    private fun readCamera(
        manager: CameraManager,
        id: String,
        exposed: Boolean,
        discovery: Discovery,
        parents: List<String>,
    ): CameraProfile {
        val c = manager.getCameraCharacteristics(id)

        val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.map { capabilityName(it) } ?: emptyList()

        val streamMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = runCatching {
            streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { SizePx(it.width, it.height) }
        }.getOrNull().orEmpty()

        val minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()

        return CameraProfile(
            id = id,
            exposed = exposed,
            discovery = discovery,
            logicalParentIds = parents,
            physicalChildIds = physicalChildrenOf(manager, id).toList().sorted(),
            facing = facingName(c.get(CameraCharacteristics.LENS_FACING)),
            hardwareLevel = hardwareLevel(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            capabilities = capabilities,
            hasRawCapability = capabilities.contains("RAW"),
            hasManualSensor = capabilities.contains("MANUAL_SENSOR"),
            hasUltraHighResolutionSensor = capabilities.contains("ULTRA_HIGH_RESOLUTION_SENSOR"),

            pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toSizePx(),
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toSizePx(),
            physicalSizeMm = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.toSizeMm(),
            rawSizes = rawSizes,
            binningFactor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { c.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)?.toSizePx() }
                    .getOrNull()
            } else null,

            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.toList().orEmpty(),
            aperturesF = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.toList().orEmpty(),
            isoMin = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.lower,
            isoMax = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper,
            exposureMinNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.lower,
            exposureMaxNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.upper,
            maxFrameDurationNs = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION),

            cfaArrangement = cfaName(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)),
            whiteLevel = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            blackLevelPattern = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                IntArray(4).also { p.copyTo(it, 0) }.toList()
            },
            // SENSOR_NOISE_PROFILE is a CaptureResult key, not a characteristics key — it only
            // arrives with a real frame. Populated in T-1.4; null here by design.
            noiseProfile = null,
            timestampSource = timestampSourceName(
                c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
            ),

            focusDistanceCalibration = focusCalibrationName(
                c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION),
            ),
            minimumFocusDistanceDiopters = minFocusDistance,
            hyperfocalDistanceDiopters = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE),
            afAvailableModes = afModes,

            oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.toList().orEmpty(),
            eisModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.toList().orEmpty(),
            mandatoryStreamCombinations = readMandatoryCombinations(c),
        )
    }

    /**
     * The device's own list of guaranteed stream configurations (OI-3). Reading this beats
     * reasoning from the published compatibility tables, because it is what this device
     * actually promises.
     */
    private fun readMandatoryCombinations(c: CameraCharacteristics): List<String> = runCatching {
        val combos: Array<MandatoryStreamCombination>? =
            c.get(CameraCharacteristics.SCALER_MANDATORY_STREAM_COMBINATIONS)
        combos?.map { combo ->
            val streams = combo.streamsInformation.joinToString(" + ") { s ->
                val size = s.availableSizes.maxByOrNull { it.width.toLong() * it.height }
                "${formatName(s.format)}@${size?.width}x${size?.height}"
            }
            "${combo.description}: $streams"
        }.orEmpty()
    }.getOrElse { emptyList() }

    private fun readSensors(context: Context): SensorAvailability {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val pm = context.packageManager
        return SensorAvailability(
            accelerometer = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
            magnetometer = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null,
            gyroscope = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
            gps = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
        )
    }

    // ---- name mappings ------------------------------------------------------------

    private fun hardwareLevel(v: Int?): HardwareLevel = when (v) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
        else -> HardwareLevel.UNKNOWN
    }

    private fun facingName(v: Int?): String = when (v) {
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun cfaName(v: Int?): String? = when (v) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "NIR"
        null -> null
        else -> "UNKNOWN($v)"
    }

    private fun timestampSourceName(v: Int?): String = when (v) {
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
        else -> "UNSPECIFIED"
    }

    private fun focusCalibrationName(v: Int?): String? = when (v) {
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "UNCALIBRATED"
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
        else -> null
    }

    private fun capabilityName(v: Int): String = when (v) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_TEN_BIT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
        else -> "CAPABILITY_$v"
    }

    private fun formatName(f: Int): String = when (f) {
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.RAW12 -> "RAW12"
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.YUV_420_888 -> "YUV"
        ImageFormat.PRIVATE -> "PRIV"
        ImageFormat.HEIC -> "HEIC"
        ImageFormat.DEPTH16 -> "DEPTH16"
        else -> "FMT_$f"
    }

    private fun Size.toSizePx() = SizePx(width, height)
    private fun Rect.toSizePx() = SizePx(width(), height())
    private fun SizeF.toSizeMm() = SizeMm(width, height)

    @Suppress("unused")
    private fun Range<*>.describe() = "$lower..$upper"
}
