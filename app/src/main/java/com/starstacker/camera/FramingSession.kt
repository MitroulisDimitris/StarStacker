package com.starstacker.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.util.Log
import com.starstacker.imaging.Autostretch
import com.starstacker.imaging.GrayImage
import com.starstacker.stars.BinnedPlane
import com.starstacker.stars.CfaBinner
import com.starstacker.stars.FrameStars
import com.starstacker.stars.StarDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor

/** What the loop is being asked to shoot. */
data class FramingRequest(
    val iso: Int,
    val exposureNs: Long,
    /** null leaves focus where it is — dioptres, 0.0 being infinity. */
    val focusDiopters: Float?,
)

/**
 * One analysed frame from the framing loop. Everything the screen shows and everything the focus
 * sweep measures comes from here.
 */
class FramingFrame(
    val generation: Int,
    val request: FramingRequest,
    val plane: BinnedPlane,
    val stars: FrameStars,
    val preview: GrayImage,
    val stretch: Autostretch.Stretch,
    val appliedIso: Int?,
    val appliedExposureNs: Long?,
    val appliedFocus: Float?,
    val lensStationary: Boolean,
    /**
     * D-21: the frame's own metadata agrees with what was requested. An unsettled frame is not
     * an error — the sensor takes a few frames to apply a change — but it must never be measured
     * or shown as if it answered the current question.
     */
    val settled: Boolean,
    val timestampNs: Long,
    val analysisMs: Long,
)

/**
 * T-2.2 — the night framing preview, and the machinery T-2.4/T-2.5 drive the focus sweep with.
 *
 * A normal camera preview of a dark sky is a black rectangle: the preview stream runs at video
 * frame rates, and a thirtieth of a second collects nothing. So this is not a preview stream at
 * all. It is a **repeating one-second exposure**, read as RAW, binned, star-detected and
 * autostretched — roughly one frame per second, with the same pipeline the real subs will go
 * through. What you frame on is what you will capture.
 *
 * The refresh rate has to be stated in the UI: at ~1 fps a preview reads as a frozen app unless
 * the interface says otherwise.
 *
 * Framing frames are never written to `lights/`, and the loop stops itself when left alone —
 * heat spent framing is heat unavailable to the session that follows (FR-6.2), and it is spent
 * before a single sub has been taken.
 */
class FramingSession private constructor(
    private val access: CameraAccess,
    private val device: CameraDevice,
    private val chars: CameraCharacteristics,
    private val captureSession: CameraCaptureSession,
    private val rawReader: ImageReader,
    private val secondaryReader: ImageReader,
    val cameraId: String,
    val plan: StreamPlan,
    val support: StreamConfig.Support,
    /** Clockwise rotation from sensor rows to the phone held upright. */
    val sensorOrientation: Int,
    val cfaCodes: List<Int>,
    val blackLevel: Double,
    val whiteLevel: Double,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val detector = StarDetector(saturationLevel = whiteLevel)

    private val _frames = MutableSharedFlow<FramingFrame>(
        replay = 1,
        extraBufferCapacity = 2,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<FramingFrame> = _frames.asSharedFlow()

    /** Hand-off from the camera thread to analysis. Depth 1: a stale frame is worthless here. */
    private val incoming = Channel<RawFrame>(capacity = 1)

    /**
     * Reused sensor buffers. A 12 MP RAW frame is 25 MB; allocating one per second would spend
     * more time collecting garbage than detecting stars (FR-12.2).
     */
    private val pool = ArrayBlockingQueue<ShortArray>(POOL_DEPTH)

    private val results = HashMap<Long, ResultInfo>()

    @Volatile
    private var generation = 0

    @Volatile
    private var pending: FramingRequest? = null

    @Volatile
    private var closed = false

    /** The request the loop is currently running, or null before the first [apply]. */
    val currentRequest: FramingRequest? get() = pending

    val currentGeneration: Int get() = generation

    private class RawFrame(
        val buffer: ShortArray,
        val width: Int,
        val height: Int,
        val timestampNs: Long,
        val generation: Int,
        val request: FramingRequest,
    )

    private class ResultInfo(
        val iso: Int?,
        val exposureNs: Long?,
        val focus: Float?,
        val lensStationary: Boolean,
        val generation: Int,
    )

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val info = ResultInfo(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                lensStationary = ManualRequest.lensStationary(result.get(CaptureResult.LENS_STATE)),
                generation = generation,
            )
            synchronized(results) {
                results[timestamp] = info
                if (results.size > RESULT_CACHE) {
                    val oldest = results.keys.minOrNull()
                    if (oldest != null) results.remove(oldest)
                }
            }
        }
    }

    init {
        repeat(POOL_DEPTH) {
            pool.offer(ShortArray(plan.raw.width * plan.raw.height))
        }

        rawReader.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val request = pending ?: return@setOnImageAvailableListener
                val buffer = pool.poll()
                if (buffer == null) {
                    // Analysis is still busy with both buffers. Dropping a framing frame is the
                    // right answer — the next one is a second away.
                    return@setOnImageAvailableListener
                }
                RawPlane.copy(image, buffer)
                val frame = RawFrame(
                    buffer = buffer,
                    width = image.width,
                    height = image.height,
                    timestampNs = image.timestamp,
                    generation = generation,
                    request = request,
                )
                if (!incoming.trySend(frame).isSuccess) pool.offer(buffer)
            } catch (t: Throwable) {
                Log.w(TAG, "framing frame dropped: ${t.message}")
            } finally {
                image?.close()
            }
        }, access.handler)

        // Nothing reads the second stream — it exists because this HAL will not stream RAW on
        // its own (D-20). It still has to be drained, or its buffer queue fills and the whole
        // session stalls behind it.
        secondaryReader.setOnImageAvailableListener({ reader ->
            runCatching { reader.acquireLatestImage()?.close() }
        }, access.handler)

        scope.launch { analyse() }
    }

    /**
     * Points the loop at a new exposure, ISO or focus position and returns the generation number
     * of the change. Frames tagged with an older generation belong to the previous request.
     */
    fun apply(request: FramingRequest) {
        check(!closed) { "framing session is closed" }
        val targets = listOf(rawReader.surface, secondaryReader.surface)
        val built = ManualRequest.build(
            device = device,
            chars = chars,
            targets = targets,
            iso = request.iso,
            exposureNs = request.exposureNs,
            focusDiopters = request.focusDiopters ?: 0f,
            frameDurationNs = request.exposureNs,
        )
        pending = request
        generation++
        captureSession.setRepeatingRequest(built, captureCallback, access.handler)
    }

    /**
     * The next frame taken *under the current request*, whose metadata confirms it.
     *
     * The wait is generous by design: the sensor applies a change several frames later, and at a
     * four-second framing exposure "several frames" is most of a minute.
     */
    suspend fun awaitSettledFrame(timeoutMs: Long): FramingFrame {
        val target = generation
        return withTimeout(timeoutMs) {
            frames.first { it.generation >= target && it.settled }
        }
    }

    private suspend fun analyse() {
        for (raw in incoming) {
            val started = System.nanoTime()
            try {
                val info = awaitResult(raw.timestampNs)
                val binned = CfaBinner.binGreen(
                    pixels = raw.buffer,
                    width = raw.width,
                    height = raw.height,
                    cfaCodes = cfaCodes,
                    factor = plan.binFactor,
                )
                val stars = detector.detect(binned.data, binned.width, binned.height)
                val stretch = Autostretch.measure(
                    plane = binned.data,
                    count = binned.width * binned.height,
                    black = blackLevel,
                    white = whiteLevel,
                )
                // A fresh byte array per frame: the previous one is still on screen, so it
                // cannot be overwritten in place. At ~800 KB it is a twentieth of the RAW
                // buffer that *is* pooled.
                val gray = Autostretch.toGray8(
                    plane = binned.data,
                    width = binned.width,
                    height = binned.height,
                    stretch = stretch,
                )
                val preview = GrayImage(gray, binned.width, binned.height)
                    .rotated(sensorOrientation)

                val settled = info != null &&
                    info.generation >= raw.generation &&
                    ManualRequest.exposureMatches(info.exposureNs, raw.request.exposureNs) &&
                    ManualRequest.focusMatches(info.focus, raw.request.focusDiopters) &&
                    info.lensStationary

                _frames.emit(
                    FramingFrame(
                        generation = raw.generation,
                        request = raw.request,
                        plane = binned,
                        stars = stars,
                        preview = preview,
                        stretch = stretch,
                        appliedIso = info?.iso,
                        appliedExposureNs = info?.exposureNs,
                        appliedFocus = info?.focus,
                        lensStationary = info?.lensStationary ?: false,
                        settled = settled,
                        timestampNs = raw.timestampNs,
                        analysisMs = (System.nanoTime() - started) / 1_000_000,
                    ),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "framing analysis failed", t)
            } finally {
                pool.offer(raw.buffer)
            }
        }
    }

    /**
     * The image and its metadata arrive on separate paths, so the result for a frame may not
     * have landed when the pixels do. Waits briefly rather than reporting the previous frame's
     * exposure as this one's.
     */
    private suspend fun awaitResult(timestampNs: Long): ResultInfo? {
        var waited = 0L
        while (waited < RESULT_WAIT_MS) {
            synchronized(results) { results[timestampNs] }?.let { return it }
            delay(RESULT_POLL_MS)
            waited += RESULT_POLL_MS
        }
        return null
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
        scope.cancel()
    }

    companion object {
        private const val TAG = "FramingSession"
        private const val POOL_DEPTH = 2
        private const val RESULT_CACHE = 8
        private const val RESULT_WAIT_MS = 500L
        private const val RESULT_POLL_MS = 5L

        /**
         * Opens the camera and configures the streams, checking the configuration against the
         * device's own guarantees first (T-2.1).
         */
        suspend fun open(access: CameraAccess, cameraId: String): FramingSession {
            val chars = access.characteristics(cameraId)
            val plan = StreamConfig.planFor(chars)

            val rawReader = ImageReader.newInstance(
                plan.raw.width, plan.raw.height, ImageFormat.RAW_SENSOR, 2,
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
                Log.i(TAG, "stream support: ${support.detail}")
                check(!support.blocking) {
                    "camera $cameraId refuses the framing configuration: ${support.detail}"
                }

                val session = access.createSession(device, surfaces)
                return FramingSession(
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
                    cfaCodes = cfaCodesOf(chars),
                    blackLevel = blackLevelOf(chars),
                    whiteLevel = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
                        ?: DEFAULT_WHITE_LEVEL).toDouble(),
                )
            } catch (t: Throwable) {
                runCatching { device?.close() }
                runCatching { rawReader.close() }
                runCatching { secondaryReader.close() }
                throw t
            }
        }

        private const val DEFAULT_WHITE_LEVEL = 1023

        /** Row-major over the 2x2 cell: 0 = red, 1 = green, 2 = blue — what [CfaBinner] wants. */
        fun cfaCodesOf(chars: CameraCharacteristics): List<Int> =
            when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> listOf(0, 1, 1, 2)
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> listOf(1, 0, 2, 1)
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> listOf(1, 2, 0, 1)
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> listOf(2, 1, 1, 0)
                else -> listOf(1, 0, 2, 1)
            }

        /**
         * Black level of the green samples specifically — they are the only ones the analysis
         * plane contains, and on sensors where Gr and Gb differ, averaging in red and blue would
         * shift the whole background.
         */
        private fun blackLevelOf(chars: CameraCharacteristics): Double {
            val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                ?: return 0.0
            val values = IntArray(4).also { pattern.copyTo(it, 0) }
            val codes = cfaCodesOf(chars)
            val greens = values.filterIndexed { index, _ -> codes.getOrNull(index) == 1 }
            return if (greens.isEmpty()) values.average() else greens.average()
        }
    }
}
