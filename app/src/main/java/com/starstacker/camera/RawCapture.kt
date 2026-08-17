package com.starstacker.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.view.Surface
import com.starstacker.dng.DngReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T-1.4 — a single manual RAW_SENSOR frame written as a DNG.
 *
 * The FR-6.1 request profile itself lives in [ManualRequest], shared with the framing and focus
 * loops so there is one definition of "OEM processing off" rather than two that can drift apart.
 */

data class CaptureOutcome(
    val file: File?,
    val cameraId: String,
    val profile: RequestProfile,
    val withPreviewSurface: Boolean,
    val requestedIso: Int,
    val requestedExposureNs: Long,
    val actualIso: Int?,
    val actualExposureNs: Long?,
    val frameSize: String,
    val fileBytes: Long,
    val elapsedMs: Long,
    /** SENSOR_NOISE_PROFILE — a CaptureResult key, so this is the first place it can be read. */
    val noiseProfile: List<Pair<Double, Double>>?,
    val rollingShutterSkewNs: Long?,
    val sensorTimestampNs: Long?,
    /**
     * T-1.6 acceptance: null when not checked, otherwise whether the DNG read back off disk
     * matches the buffer that came out of the sensor, sample for sample.
     */
    val roundTrip: String? = null,
) {
    /**
     * FR-4.1.6 wants measured versus requested exposure. A mismatch here is not cosmetic: the
     * exposure engine's sky-limited solve assumes it got the integration time it asked for.
     */
    val exposureErrorPercent: Double?
        get() = actualExposureNs?.let {
            (it - requestedExposureNs) * 100.0 / requestedExposureNs
        }
}

object RawCapture {

    private const val TAG = "RawCapture"
    private const val SLACK_MS = 20_000L
    private const val PROBE_EXPOSURE_NS = 30_000_000L
    private const val PROBE_TIMEOUT_MS = 8_000L
    private const val DRAIN_DELAY_MS = 200L
    private const val MAX_SETTLE_ATTEMPTS = 5

    /**
     * Finds a working capture configuration by trying them in order of increasing strictness,
     * with and without a preview surface alongside the RAW stream.
     *
     * "No-preview DNG capture" is a guaranteed combination on paper, but guaranteed
     * configurations are not guaranteed to be well-exercised by an OEM, so both are tried.
     */
    suspend fun diagnose(
        access: CameraAccess,
        cameraId: String,
        outputDir: File,
    ): List<String> {
        val lines = mutableListOf<String>()
        for (withPreview in listOf(false, true)) {
            for (profile in RequestProfile.entries) {
                val label = "${if (withPreview) "preview+raw" else "raw-only"}/$profile"
                val result = runCatching {
                    captureSingle(
                        access = access,
                        cameraId = cameraId,
                        iso = 800,
                        exposureNs = PROBE_EXPOSURE_NS,
                        outputDir = outputDir,
                        fileName = null,
                        profile = profile,
                        withPreviewSurface = withPreview,
                        timeoutMs = PROBE_TIMEOUT_MS,
                    )
                }
                if (result.isSuccess) {
                    lines += "$label: OK"
                } else {
                    val e = result.exceptionOrNull()
                    lines += "$label: ${e?.let { it::class.java.simpleName }} ${e?.message.orEmpty()}"
                }
                Log.i(TAG, lines.last())
            }
        }
        return lines
    }

    suspend fun captureSingle(
        access: CameraAccess,
        cameraId: String,
        iso: Int,
        exposureNs: Long,
        outputDir: File,
        fileName: String?,
        profile: RequestProfile = RequestProfile.FULL,
        withPreviewSurface: Boolean = true,
        timeoutMs: Long = exposureNs / 1_000_000 + SLACK_MS,
        verifyRoundTrip: Boolean = false,
    ): CaptureOutcome = access.withDevice(cameraId) { device ->
        val chars = access.characteristics(cameraId)
        val rawSize = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height }
            ?: error("camera $cameraId offers no RAW_SENSOR sizes")

        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3)
        var previewTexture: SurfaceTexture? = null
        var previewSurface: Surface? = null
        try {
            if (withPreviewSurface) {
                previewTexture = SurfaceTexture(0).apply { setDefaultBufferSize(1920, 1080) }
                previewSurface = Surface(previewTexture)
            }

            val armed = AtomicBoolean(false)
            val imageReady = CompletableDeferred<Image>()
            reader.setOnImageAvailableListener({ r ->
                runCatching { r.acquireNextImage() }
                    .onSuccess { img ->
                        when {
                            img == null -> Unit
                            !armed.get() -> img.close()
                            !imageReady.complete(img) -> img.close()
                            else -> Unit
                        }
                    }
                    .onFailure { imageReady.completeExceptionally(it) }
            }, access.handler)

            val surfaces = listOfNotNull(previewSurface, reader.surface)
            val session = access.createSession(device, surfaces)
            try {
                // Warm the pipeline on the preview surface where there is one; a HAL that
                // will not stream RAW-only still needs a settled 3A/stream state.
                val warmTargets = listOfNotNull(previewSurface) .ifEmpty { listOf(reader.surface) }
                val warmRequest = ManualRequest.build(
                    device, chars, warmTargets, iso, PROBE_EXPOSURE_NS, profile = profile,
                )
                withTimeout(timeoutMs) { access.warmUp(session, warmRequest) }
                delay(DRAIN_DELAY_MS)

                val request = ManualRequest.build(
                    device, chars, listOf(reader.surface), iso, exposureNs, profile = profile,
                )

                // Sensor settings take effect several frames after submission, so the first
                // still capture after a warm-up comes back with the *warm-up's* exposure.
                // Discard results until the metadata confirms the request was actually
                // applied — a frame that silently used 30 ms instead of 10 s is worse than
                // no frame at all, because nothing downstream can detect it.
                var settled = false
                val started = System.nanoTime()
                for (attempt in 1..MAX_SETTLE_ATTEMPTS) {
                    val r = withTimeout(timeoutMs) { access.captureOnce(session, request) }
                    val applied = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    Log.i(
                        TAG,
                        "settle attempt $attempt: exp=$applied " +
                            "iso=${r.get(CaptureResult.SENSOR_SENSITIVITY)}",
                    )
                    if (ManualRequest.exposureMatches(applied, exposureNs)) {
                        settled = true
                        break
                    }
                }
                check(settled) {
                    "sensor settings never applied after $MAX_SETTLE_ATTEMPTS attempts " +
                        "(asked ${exposureNs}ns)"
                }

                // Settling frames were taken with the reader disarmed, so take one more now
                // that the pipeline is known to be honouring the request.
                armed.set(true)
                val result = withTimeout(timeoutMs) { access.captureOnce(session, request) }
                val image = withTimeout(timeoutMs) { imageReady.await() }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000

                val file = fileName?.let { File(outputDir, it) }
                // Snapshot the sensor buffer before DngCreator consumes it, so the file can be
                // checked against what actually came off the sensor (T-1.6 acceptance).
                val sensorSamples = if (file != null && verifyRoundTrip) RawPlane.copy(image) else null
                try {
                    if (file != null) {
                        DngCreator(chars, result).use { dng ->
                            FileOutputStream(file).use { out -> dng.writeImage(out, image) }
                        }
                    }
                } finally {
                    image.close()
                }

                val roundTrip = if (file != null && sensorSamples != null) {
                    verify(file, sensorSamples)
                } else {
                    null
                }

                CaptureOutcome(
                    file = file,
                    cameraId = cameraId,
                    profile = profile,
                    withPreviewSurface = withPreviewSurface,
                    requestedIso = iso,
                    requestedExposureNs = exposureNs,
                    actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                    actualExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    frameSize = "${rawSize.width}x${rawSize.height}",
                    fileBytes = file?.length() ?: 0L,
                    elapsedMs = elapsedMs,
                    noiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)
                        ?.map { it.first to it.second },
                    rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                    sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP),
                    roundTrip = roundTrip,
                )
            } finally {
                runCatching { session.close() }
            }
        } finally {
            runCatching { reader.close() }
            runCatching { previewSurface?.release() }
            runCatching { previewTexture?.release() }
        }
    }


    /** Reads the written DNG back and compares it sample-for-sample with the sensor buffer. */
    private fun verify(file: File, sensorSamples: ShortArray): String = try {
        val decoded = DngReader.read(file)
        when {
            decoded.pixels.size != sensorSamples.size ->
                "MISMATCH: file has ${decoded.pixels.size} samples, sensor gave " +
                    "${sensorSamples.size}"

            else -> {
                var firstBad = -1
                for (i in sensorSamples.indices) {
                    if (decoded.pixels[i] != sensorSamples[i]) {
                        firstBad = i
                        break
                    }
                }
                if (firstBad < 0) {
                    "OK — ${sensorSamples.size} samples identical, CFA " +
                        "${decoded.metadata.cfaPattern?.name ?: "?"}"
                } else {
                    "MISMATCH at sample $firstBad: sensor=" +
                        "${sensorSamples[firstBad].toInt() and 0xFFFF} file=" +
                        "${decoded.pixels[firstBad].toInt() and 0xFFFF}"
                }
            }
        }
    } catch (t: Throwable) {
        "READ FAILED: ${t::class.java.simpleName}: ${t.message}"
    }

}
