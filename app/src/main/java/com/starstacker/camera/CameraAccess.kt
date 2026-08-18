package com.starstacker.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * T-1.3 — Camera2 lifecycle.
 *
 * One dedicated handler thread, suspend functions over the callback API, and a hard guarantee
 * that the device is closed. A leaked CameraDevice locks the camera for every other app on the
 * phone until the process dies, which during a 40-minute unattended session is the difference
 * between a recoverable error and a wasted night.
 *
 * Deliberately not tied to a Compose or Activity lifecycle: capture will move into a foreground
 * service in T-3.6 and this must survive that move unchanged.
 */
class CameraAccess(context: Context) : AutoCloseable {

    private val manager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val thread = HandlerThread("StarStacker-Camera").apply { start() }

    /**
     * The camera callback thread. Exposed because ImageReader listeners must be posted to a
     * looper, and callers run on Dispatchers.IO which is not one.
     */
    val handler = Handler(thread.looper)

    private val executor = Executor { handler.post(it) }

    fun characteristics(cameraId: String): CameraCharacteristics =
        manager.getCameraCharacteristics(cameraId)

    /**
     * Opens a camera. Callers must close the returned device — use [withDevice] unless there
     * is a reason not to.
     */
    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalCoroutinesApi::class)   // resume(value) { release } — see onOpened
    suspend fun openDevice(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            try {
                manager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(device: CameraDevice) {
                            // `resume(value) { release }` rather than a bare `isActive` check:
                            // cancellation can land in the window between the check and the
                            // resume, and then nobody owns the device — which locks the camera
                            // for every app on the phone until this process dies. The lambda
                            // runs precisely when the value arrives with no coroutine left to
                            // receive it (T-1.3's cancel phase provokes it deliberately).
                            cont.resume(device) { device.close() }
                        }

                        override fun onDisconnected(device: CameraDevice) {
                            device.close()
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    CameraOpenException(cameraId, "disconnected", null),
                                )
                            }
                        }

                        override fun onError(device: CameraDevice, error: Int) {
                            device.close()
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    CameraOpenException(cameraId, describeError(error), error),
                                )
                            }
                        }
                    },
                    handler,
                )
            } catch (e: CameraAccessException) {
                cont.resumeWithException(CameraOpenException(cameraId, "access: ${e.reason}", null, e))
            } catch (e: IllegalArgumentException) {
                // The documented outcome for an ID absent from getCameraIdList() (OI-18).
                cont.resumeWithException(CameraOpenException(cameraId, "unknown camera id", null, e))
            } catch (e: SecurityException) {
                cont.resumeWithException(CameraOpenException(cameraId, "permission denied", null, e))
            }
        }

    /** Opens, runs [block], and closes the device even if [block] throws. */
    suspend fun <T> withDevice(cameraId: String, block: suspend (CameraDevice) -> T): T {
        val device = openDevice(cameraId)
        return try {
            block(device)
        } finally {
            runCatching { device.close() }
                .onFailure { Log.w(TAG, "close of camera $cameraId failed", it) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createSession(
        device: CameraDevice,
        surfaces: List<Surface>,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            surfaces.map { OutputConfiguration(it) },
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cont.resume(session) { session.close() }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("capture session configuration failed"),
                        )
                    }
                }
            },
        )
        try {
            device.createCaptureSession(config)
        } catch (e: CameraAccessException) {
            cont.resumeWithException(e)
        }
    }

    /**
     * Runs a repeating request until one result completes, then stops.
     *
     * Qualcomm's CamX pipeline will not reliably deliver a one-shot still on a session that has
     * never streamed — the request is accepted and no frame ever arrives. Warming up costs one
     * short frame and turns a hang into a capture.
     */
    suspend fun warmUp(
        session: CameraCaptureSession,
        request: CaptureRequest,
    ): TotalCaptureResult = suspendCancellableCoroutine { cont ->
        // Cancelled between setRepeatingRequest and the first result, the request keeps running:
        // the sensor stays hot for a caller that has gone away, and heat spent here is heat
        // unavailable to the session that follows (FR-6.2).
        cont.invokeOnCancellation { runCatching { session.stopRepeating() } }
        try {
            session.setRepeatingRequest(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        if (cont.isActive) {
                            runCatching { s.stopRepeating() }
                            cont.resume(result)
                        }
                    }
                },
                handler,
            )
        } catch (e: CameraAccessException) {
            cont.resumeWithException(e)
        }
    }

    /** Fires one request and waits for its completed result. */
    suspend fun captureOnce(
        session: CameraCaptureSession,
        request: CaptureRequest,
    ): TotalCaptureResult = suspendCancellableCoroutine { cont ->
        try {
            session.capture(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        if (cont.isActive) cont.resume(result)
                    }

                    override fun onCaptureFailed(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure,
                    ) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("capture failed, reason ${failure.reason}"),
                            )
                        }
                    }
                },
                handler,
            )
        } catch (e: CameraAccessException) {
            cont.resumeWithException(e)
        }
    }

    /**
     * Watches what the *camera service* thinks is available, on the camera thread.
     *
     * This is the only in-process way to ask the question T-1.3 actually cares about — not "did
     * our code call close" but "would another app get the camera now". `onCameraUnavailable`
     * fires when any client on the phone holds a camera, so it witnesses both a device this
     * process leaked and one another app has taken.
     */
    fun watchAvailability(callback: CameraManager.AvailabilityCallback): AutoCloseable {
        manager.registerAvailabilityCallback(callback, handler)
        return AutoCloseable { manager.unregisterAvailabilityCallback(callback) }
    }

    override fun close() {
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "CameraAccess"

        fun describeError(error: Int): String = when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "in use by another client"
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "disabled by policy"
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "fatal device error"
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "fatal service error"
            else -> "error $error"
        }
    }
}

class CameraOpenException(
    val cameraId: String,
    val reason: String,
    val errorCode: Int?,
    cause: Throwable? = null,
) : Exception("camera $cameraId: $reason", cause)
