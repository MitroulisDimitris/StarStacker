package com.starstacker.camera

import android.util.Log

/**
 * OI-18 — readable characteristics do not imply an openable camera.
 *
 * On the Nothing Phone (3a) Pro the ultrawide and tele report full LEVEL_3 RAW characteristics
 * but are absent from `getCameraIdList()`. Whether `openCamera` accepts them decides whether v1
 * is a one-camera app on this device, so the question gets answered by trying it rather than by
 * reasoning about it.
 */
data class OpenResult(
    val cameraId: String,
    val opened: Boolean,
    val detail: String,
) {
    fun describe(): String = "$cameraId: " + if (opened) "OPENED" else "refused — $detail"
}

object OpenabilityProbe {

    private const val TAG = "OpenabilityProbe"

    /** Opens each camera in turn and closes it immediately. Never throws. */
    suspend fun run(access: CameraAccess, cameraIds: List<String>): List<OpenResult> =
        cameraIds.map { id ->
            try {
                access.withDevice(id) { /* opened successfully; close immediately */ }
                OpenResult(id, opened = true, detail = "opened and closed cleanly")
            } catch (e: CameraOpenException) {
                Log.i(TAG, "camera $id refused: ${e.reason}")
                OpenResult(id, opened = false, detail = e.reason)
            } catch (e: Throwable) {
                Log.w(TAG, "camera $id failed unexpectedly", e)
                OpenResult(id, opened = false, detail = e::class.java.simpleName + ": " + e.message)
            }
        }
}
