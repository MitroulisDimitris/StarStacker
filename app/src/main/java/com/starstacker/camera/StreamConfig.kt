package com.starstacker.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.MandatoryStreamCombination
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.util.Log
import android.view.Surface
import com.starstacker.device.SizePx
import java.util.concurrent.Executor

/**
 * T-2.1 — turning a [StreamPlan] into a session the device has agreed to, before opening it.
 *
 * OI-3 settled the approach: do not reason from the published compatibility tables, ask the
 * device. It publishes its own guaranteed list as `SCALER_MANDATORY_STREAM_COMBINATIONS`, and
 * any specific configuration can be confirmed outright with `isSessionConfigurationSupported()`.
 * Both are checked here, because they answer different questions — the list says "this shape of
 * configuration is promised", the query says "these exact surfaces will configure".
 */
object StreamConfig {

    private const val TAG = "StreamConfig"

    /**
     * @param supported null when the device declined to answer — an older HAL, or a query it
     *   does not implement. Not an error: a null means "find out by configuring it".
     */
    data class Support(
        val supported: Boolean?,
        val guaranteedBy: String?,
        val detail: String,
    ) {
        val blocking: Boolean get() = supported == false
    }

    fun planFor(chars: CameraCharacteristics): StreamPlan {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("camera reports no stream configuration map")
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.map { SizePx(it.width, it.height) }.orEmpty()
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.map { SizePx(it.width, it.height) }.orEmpty()
        return StreamPlanner.choose(rawSizes, yuvSizes)
    }

    /**
     * Looks for a guaranteed combination of the same shape as the plan: one RAW output at the
     * planned size plus one YUV output. Returns the device's own description of it.
     */
    fun guaranteedCombinationFor(chars: CameraCharacteristics, plan: StreamPlan): String? =
        runCatching {
            val combos: Array<MandatoryStreamCombination>? =
                chars.get(CameraCharacteristics.SCALER_MANDATORY_STREAM_COMBINATIONS)
            combos?.firstOrNull { combo ->
                val outputs = combo.streamsInformation.filter { !it.isInput }
                if (outputs.size != 2) return@firstOrNull false
                val raw = outputs.firstOrNull { it.format == ImageFormat.RAW_SENSOR }
                val yuv = outputs.firstOrNull { it.format == ImageFormat.YUV_420_888 }
                if (raw == null || yuv == null) return@firstOrNull false
                raw.availableSizes.any {
                    it.width == plan.raw.width && it.height == plan.raw.height
                }
            }?.description?.toString()
        }.getOrNull()

    /**
     * Asks the device whether these exact surfaces will configure.
     *
     * Deprecated on API 35 in favour of `CameraDevice.CameraDeviceSetup`, which can answer
     * without opening the camera. Not worth adopting yet: this check runs with the device
     * already open, so the only thing the newer API would buy is answering a question we are
     * not asking.
     */
    @Suppress("DEPRECATION")
    fun check(
        device: CameraDevice,
        chars: CameraCharacteristics,
        plan: StreamPlan,
        surfaces: List<Surface>,
        executor: Executor,
    ): Support {
        val guaranteed = guaranteedCombinationFor(chars, plan)
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            surfaces.map { OutputConfiguration(it) },
            executor,
            NoopStateCallback,
        )

        return try {
            val supported = device.isSessionConfigurationSupported(configuration)
            Support(
                supported = supported,
                guaranteedBy = guaranteed,
                detail = buildString {
                    append(if (supported) "device confirms " else "device REFUSES ")
                    append(plan.reason)
                    if (guaranteed != null) append(" · guaranteed as \"$guaranteed\"")
                },
            )
        } catch (t: Throwable) {
            // UnsupportedOperationException for a HAL that will not answer,
            // IllegalArgumentException for a configuration it cannot even parse.
            Log.i(TAG, "isSessionConfigurationSupported declined: ${t.message}")
            Support(
                supported = null,
                guaranteedBy = guaranteed,
                detail = "device would not answer (${t::class.java.simpleName}); " +
                    (guaranteed?.let { "but guarantees \"$it\"" } ?: "no matching guarantee either"),
            )
        }
    }

    /** `SessionConfiguration` demands a callback it never invokes for a support query. */
    private object NoopStateCallback :
        android.hardware.camera2.CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) = Unit
        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) = Unit
    }
}
