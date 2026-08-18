package com.starstacker.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import java.util.concurrent.Executor

/**
 * The capture-side sibling of [FramingSession] (T-3.6/T-3.7).
 *
 * They look similar and differ in the three places that matter, all of which follow from one
 * distinction: **a framing frame is a glance, a captured frame is the product.**
 *
 * - `acquireNextImage()`, never `acquireLatestImage()`. Framing shows the newest frame and
 *   discards what it skipped; a sequence that skipped a frame would silently shorten the
 *   integration the planner promised.
 * - Back pressure instead of dropping. If the writer falls behind, the sequence waits — a 25 MB
 *   DNG takes as long as it takes, and the honest response to slow storage is a slower cadence,
 *   not a hole in the stack.
 * - The whole [TotalCaptureResult] is kept, not a summary of it, because `DngCreator` needs the
 *   result object itself to write the metadata a desktop stacker will read back.
 *
 * The image is handed over **still open**, so the DNG is written straight out of the sensor buffer
 * without a 25 MB copy in between. The consumer must close it, and [CapturedFrame.use] is the way.
 */
class SequenceSession private constructor(
    private val access: CameraAccess,
    private val device: CameraDevice,
    private val chars: CameraCharacteristics,
    private val captureSession: CameraCaptureSession,
    private val rawReader: ImageReader,
    private val secondaryReader: ImageReader,
    val cameraId: String,
    val plan: StreamPlan,
    val support: StreamConfig.Support,
    val sensorOrientation: Int,
    val cfaCodes: List<Int>,
    val blackLevel: Double,
    val whiteLevel: Double,
) : AutoCloseable {

    /**
     * One captured frame, with its pixels still resident. [close] releases the sensor buffer back
     * to the reader, and until it does the camera is one buffer short — so it is held for as long
     * as the DNG write takes and not a moment longer.
     */
    class CapturedFrame(
        private val image: Image,
        val result: TotalCaptureResult,
        private val chars: CameraCharacteristics,
        val timestampNs: Long,
        val generation: Int,
    ) : AutoCloseable {

        val width: Int get() = image.width
        val height: Int get() = image.height

        val appliedIso: Int? get() = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val appliedExposureNs: Long? get() = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val appliedFocus: Float? get() = result.get(CaptureResult.LENS_FOCUS_DISTANCE)

        /**
         * Writes the DNG straight from the sensor buffer — no intermediate copy.
         *
         * [description] fills `ImageDescription`, which `DngCreator` otherwise writes empty
         * (T-3.16). It is the only place a frame can record which session it belongs to.
         */
        fun writeDng(
            out: OutputStream,
            orientation: Int? = null,
            description: String? = null,
        ) {
            DngCreator(chars, result).use { dng ->
                orientation?.let { dng.setOrientation(it) }
                description?.let { dng.setDescription(it) }
                dng.writeImage(out, image)
            }
        }

        /** Copies the pixels out for analysis. Call before [close]. */
        fun copyPixels(into: ShortArray) = RawPlane.copy(image, into)

        override fun close() {
            runCatching { image.close() }
        }
    }

    /**
     * Depth 1 and *rendezvous-like on purpose*: the camera thread cannot get ahead of the writer,
     * so slow storage shows up as a longer gap between subs rather than as missing frames.
     */
    private val incoming = Channel<CapturedFrame>(capacity = 1)

    private class Metadata(val result: TotalCaptureResult, val generation: Int)

    private val results = HashMap<Long, Metadata>()

    @Volatile
    private var generation = 0

    @Volatile
    private var closed = false

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            synchronized(results) {
                results[timestamp] = Metadata(result, request.tag as? Int ?: -1)
                if (results.size > RESULT_CACHE) {
                    results.keys.minOrNull()?.let { results.remove(it) }
                }
            }
        }
    }

    init {
        rawReader.setOnImageAvailableListener({ reader ->
            try {
                // Next, not latest: every frame is the product (see the class note).
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                val metadata = synchronized(results) { results[image.timestamp] }
                if (metadata == null) {
                    // The metadata has not landed yet. Re-queue the work rather than dropping the
                    // frame: the result arrives within milliseconds and the image is still valid.
                    access.handler.postDelayed({ pair(image) }, RESULT_RETRY_MS)
                    return@setOnImageAvailableListener
                }
                deliver(image, metadata)
            } catch (t: Throwable) {
                Log.w(TAG, "capture frame lost: ${t.message}")
            }
        }, access.handler)

        // D-20/D-23: this HAL will not stream RAW on its own, and an undrained reader stalls it.
        secondaryReader.setOnImageAvailableListener({ reader ->
            runCatching { reader.acquireLatestImage()?.close() }
        }, access.handler)
    }

    private fun pair(image: Image) {
        val metadata = synchronized(results) { results[image.timestamp] }
        if (metadata == null) {
            Log.w(TAG, "no metadata for frame at ${image.timestamp} — dropping")
            runCatching { image.close() }
            return
        }
        deliver(image, metadata)
    }

    private fun deliver(image: Image, metadata: Metadata) {
        // The generation comes off the request's own tag, not from whatever is current now:
        // the pipeline is ten frames deep on this HAL (plan section 1.7), so those differ.
        val frame = CapturedFrame(
            image, metadata.result, chars, image.timestamp, metadata.generation,
        )
        if (!incoming.trySend(frame).isSuccess) {
            // The writer is still busy with the previous frame. Holding a second sensor buffer
            // while it finishes is what the reader's depth is for; if even that is full, the
            // frame is released so the pipeline keeps moving.
            Log.w(TAG, "writer behind — releasing a frame")
            frame.close()
        }
    }

    /** Points the sequence at an exposure. Fixed for the whole run, per FR-6.1. */
    fun apply(iso: Int, exposureNs: Long, focusDiopters: Float?) {
        check(!closed) { "sequence session is closed" }
        val next = generation + 1
        val built = ManualRequest.builder(
            device = device,
            chars = chars,
            targets = listOf(rawReader.surface, secondaryReader.surface),
            iso = iso,
            exposureNs = exposureNs,
            focusDiopters = focusDiopters ?: 0f,
            frameDurationNs = exposureNs,
        ).apply { setTag(next) }.build()
        generation = next
        captureSession.setRepeatingRequest(built, captureCallback, access.handler)
    }

    /** The next captured frame. The caller owns it and must close it. */
    suspend fun nextFrame(timeoutMs: Long): CapturedFrame =
        withTimeout(timeoutMs) { incoming.receive() }

    /**
     * The next frame whose own metadata confirms the exposure it was asked for (D-21).
     *
     * Frames that do not match are closed and skipped — the sensor takes a few frames to apply a
     * change, and a frame taken under the old settings is not a sub, it is a warm-up.
     */
    suspend fun nextVerifiedFrame(
        timeoutMs: Long,
        exposureNs: Long,
        minGeneration: Int = 0,
    ): CapturedFrame = withTimeout(timeoutMs) {
        while (true) {
            val frame = incoming.receive()
            val settled = ManualRequest.exposureMatches(frame.appliedExposureNs, exposureNs)
            // The generation guard matters most after the sensor has been stopped and
            // restarted — for darks, where a frame taken before the lens was covered would
            // otherwise be filed as a dark and quietly poison the master.
            if (settled && frame.generation >= minGeneration) return@withTimeout frame
            Log.i(
                TAG,
                "skipping frame: exposure ${frame.appliedExposureNs} ns for $exposureNs ns, " +
                    "generation ${frame.generation} < $minGeneration",
            )
            frame.close()
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** The generation the next [apply] will produce — for callers that must not accept older. */
    val currentGeneration: Int get() = generation

    /** Stops the sensor between frames — used for the gap while the user covers the lens. */
    fun stopRepeating() {
        runCatching { captureSession.stopRepeating() }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { captureSession.stopRepeating() }
        runCatching { captureSession.close() }
        runCatching { rawReader.close() }
        runCatching { secondaryReader.close() }
        runCatching { device.close() }
        incoming.close()
    }

    companion object {
        private const val TAG = "SequenceSession"
        private const val RESULT_CACHE = 64
        private const val RESULT_RETRY_MS = 8L

        /**
         * Three RAW buffers rather than two: one being written as a DNG, one just captured, one
         * for the sensor to fill. At 25 MB each that is 75 MB, which is the cost of never making
         * the sensor wait for the disk.
         */
        private const val RAW_BUFFERS = 3

        suspend fun open(access: CameraAccess, cameraId: String): SequenceSession {
            val chars = access.characteristics(cameraId)
            val plan = StreamConfig.planFor(chars)

            val rawReader = ImageReader.newInstance(
                plan.raw.width, plan.raw.height, ImageFormat.RAW_SENSOR, RAW_BUFFERS,
            )
            val secondaryReader = ImageReader.newInstance(
                plan.secondary.width, plan.secondary.height, ImageFormat.YUV_420_888, 2,
            )

            var device: CameraDevice? = null
            try {
                device = access.openDevice(cameraId)
                val surfaces = listOf(rawReader.surface, secondaryReader.surface)
                val executor = Executor { access.handler.post(it) }
                val support = StreamConfig.check(device, chars, plan, surfaces, executor)
                check(!support.blocking) {
                    "camera $cameraId refuses the capture configuration: ${support.detail}"
                }

                val session = access.createSession(device, surfaces)
                return SequenceSession(
                    access = access,
                    device = device,
                    chars = chars,
                    captureSession = session,
                    rawReader = rawReader,
                    secondaryReader = secondaryReader,
                    cameraId = cameraId,
                    plan = plan,
                    support = support,
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                    cfaCodes = FramingSession.cfaCodesOf(chars),
                    blackLevel = FramingSession.blackLevelOfPublic(chars),
                    whiteLevel = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023)
                        .toDouble(),
                )
            } catch (t: Throwable) {
                runCatching { device?.close() }
                runCatching { rawReader.close() }
                runCatching { secondaryReader.close() }
                throw t
            }
        }
    }
}
