package com.starstacker.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlin.math.abs

/**
 * FR-6.1 in one place: the request that turns the OEM camera into an instrument.
 *
 * Every stage that would corrupt the linear signal is disabled explicitly. Anything left on here
 * poisons everything downstream and cannot be undone — a noise-reduced sub cannot be
 * un-noise-reduced, and a shading-corrected frame cannot be flat-fielded. Both the single-shot
 * path (T-1.4) and the framing/focus loop (T-2.2, T-2.4) build from this, so there is exactly one
 * definition of "processing off" to keep correct.
 */

/**
 * How much of the "turn the ISP off" request to apply.
 *
 * Ordered from least to most aggressive so a stalling HAL can be bisected: the goal is the
 * strongest profile that still produces frames on this device.
 */
enum class RequestProfile {
    /** Manual ISO and exposure only, everything else left at the template default. */
    MINIMAL,

    /** MINIMAL + noise reduction, edge enhancement and hot-pixel correction off. */
    NO_ISP,

    /** NO_ISP + shading correction off with the shading map still reported. */
    NO_SHADING,

    /** NO_SHADING + CONTROL_MODE off and distortion correction off. FR-6.1 in full. */
    FULL,
}

object ManualRequest {

    private const val TAG = "ManualRequest"

    fun builder(
        device: CameraDevice,
        chars: CameraCharacteristics,
        targets: List<Surface>,
        iso: Int,
        exposureNs: Long,
        focusDiopters: Float = 0f,
        frameDurationNs: Long = exposureNs,
        profile: RequestProfile = RequestProfile.FULL,
    ): CaptureRequest.Builder {
        // TEMPLATE_MANUAL disables 3A in the template itself. TEMPLATE_STILL_CAPTURE starts
        // from auto everything and relies on our overrides winning, which is a weaker
        // guarantee on an OEM HAL — and on this one it silently lost, returning 30 ms frames
        // for a 10 s request (D-21).
        val hasManual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val template =
            if (hasManual) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_STILL_CAPTURE

        val b = device.createCaptureRequest(template)
        targets.forEach { b.addTarget(it) }

        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)

        // Tripod use: stabilisation only introduces motion between subs.
        b.set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
        )
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )

        if (profile >= RequestProfile.NO_ISP) {
            setIfSupported(
                b, CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            )
            setIfSupported(
                b, CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_OFF,
                chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
            )
            setIfSupported(
                b, CaptureRequest.HOT_PIXEL_MODE,
                CaptureRequest.HOT_PIXEL_MODE_OFF,
                chars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
            )
        }

        if (profile >= RequestProfile.NO_SHADING) {
            // Shading correction off, but the map still reported — FR-6.1 and the input to flats.
            b.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
            b.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON,
            )
        }

        if (profile >= RequestProfile.FULL) {
            b.set(CaptureRequest.CONTROL_MODE, CameraCharacteristics.CONTROL_MODE_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Geometric distortion correction would resample the frame before we ever see
                // it, which breaks the measured lens intrinsics of FR-4.1.5.
                b.set(
                    CaptureRequest.DISTORTION_CORRECTION_MODE,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
                )
            }
        }

        return b
    }

    fun build(
        device: CameraDevice,
        chars: CameraCharacteristics,
        targets: List<Surface>,
        iso: Int,
        exposureNs: Long,
        focusDiopters: Float = 0f,
        frameDurationNs: Long = exposureNs,
        profile: RequestProfile = RequestProfile.FULL,
    ): CaptureRequest =
        builder(device, chars, targets, iso, exposureNs, focusDiopters, frameDurationNs, profile)
            .build()

    /**
     * D-21: verify, never assume. A frame that quietly used a different exposure than the one
     * requested is worse than a missing frame, because nothing downstream can detect it.
     *
     * Exposure is honoured to within a rounding step, not exactly.
     */
    fun exposureMatches(applied: Long?, requested: Long): Boolean =
        applied != null && abs(applied - requested) <= requested / 100 + 1_000L

    /** Focus tolerance in dioptres. The VCM is positioned open-loop; it lands *near* the ask. */
    fun focusMatches(applied: Float?, requested: Float?, tolerance: Float = 0.02f): Boolean = when {
        requested == null -> true
        applied == null -> false
        else -> abs(applied - requested) <= tolerance
    }

    /** True when the lens has stopped moving, so the frame is not smeared by the motor. */
    fun lensStationary(lensState: Int?): Boolean =
        lensState == null || lensState == CaptureResult.LENS_STATE_STATIONARY

    private fun <T> setIfSupported(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<T>,
        value: T,
        available: IntArray?,
    ) {
        val intValue = value as? Int
        if (available == null || intValue == null || available.contains(intValue)) {
            builder.set(key, value)
        } else {
            Log.i(TAG, "${key.name}=$value unsupported; leaving at template default")
        }
    }
}
