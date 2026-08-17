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
import com.starstacker.device.NoiseProfileEntry
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
    /**
     * `SENSOR_NOISE_PROFILE` for this frame. A result key, so it is per-ISO and only readable
     * here — the exposure engine's read-noise figures at Functional tier come from it (T-3.1).
     */
    val noiseProfile: List<NoiseProfileEntry>?,
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

    /** Generation → the request that generation asked for, so a late frame is judged against it. */
    private val requests = HashMap<Int, FramingRequest>()

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
    )

    private class ResultInfo(
        val iso: Int?,
        val exposureNs: Long?,
        val focus: Float?,
        val noiseProfile: List<NoiseProfileEntry>?,
        val lensStationary: Boolean,
        /** Read back off the request's tag, so it names the request that *made* this frame. */
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
                noiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)
                    ?.map { NoiseProfileEntry(it.first, it.second) },
                lensStationary = ManualRequest.lensStationary(result.get(CaptureResult.LENS_STATE)),
                generation = request.tag as? Int ?: -1,
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
                if (pending == null) return@setOnImageAvailableListener
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
        val next = generation + 1
        val built = ManualRequest.builder(
            device = device,
            chars = chars,
            targets = targets,
            iso = request.iso,
            exposureNs = request.exposureNs,
            focusDiopters = request.focusDiopters ?: 0f,
            frameDurationNs = request.exposureNs,
        ).apply {
            // Measured on this HAL: a focus change takes nine or ten *frames* to appear in the
            // results, no matter how many requests are issued meanwhile. Stamping the request
            // with its generation and reading it back off `CaptureRequest.tag` in the result is
            // the only way to know which request a frame answers — the alternative is guessing
            // the pipeline depth, and the depth is a property of the OEM's HAL, not of us.
            setTag(next)
        }.build()
        synchronized(requests) {
            requests[next] = request
            if (requests.size > REQUEST_CACHE) {
                requests.keys.minOrNull()?.let { requests.remove(it) }
            }
        }
        pending = request
        generation = next
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

    /**
     * The next settled frame whose **lens position has also stopped changing** — the same
     * position as the settled frame before it.
     *
     * Settling and arriving are two different events on this HAL. The request tag tells you the
     * frame was taken under the current settings; it says nothing about whether the voice-coil
     * motor has finished travelling, and `LENS_STATE` cannot be used to fill the gap because it
     * reports STATIONARY at intermediate positions mid-move (measured 2026-08-17). A sweep that
     * trusts the first settled frame gets a real HFR filed under the position the lens was
     * leaving rather than the one it was going to, which does not look like an error — it looks
     * like a slightly shifted focus curve.
     *
     * Costs one extra frame per position. That is the cheapest honest answer available.
     */
    suspend fun awaitStableFrame(
        timeoutMs: Long,
        tolerance: Float = FOCUS_STABLE_TOLERANCE,
    ): FramingFrame {
        val target = generation
        var previous: FramingFrame? = null
        return withTimeout(timeoutMs) {
            frames.first { frame ->
                if (frame.generation < target || !frame.settled) return@first false
                val last = previous
                previous = frame
                last != null && ManualRequest.focusMatches(
                    applied = frame.appliedFocus,
                    requested = last.appliedFocus,
                    tolerance = tolerance,
                )
            }
        }
    }

    private suspend fun analyse() {
        for (raw in incoming) {
            val started = System.nanoTime()
            try {
                val info = awaitResult(raw.timestampNs)

                // The generation the *frame* belongs to is the one carried on its own request,
                // not whatever was current when its pixels turned up. Those differ by the
                // pipeline depth, which is nine or ten frames here.
                val frameGeneration = info?.generation?.takeIf { it >= 0 } ?: -1
                val frameRequest = synchronized(requests) { requests[frameGeneration] }
                    ?: pending
                    ?: continue

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

                // D-21, per frame. Exposure is checked against the value that was asked for,
                // because a frame that quietly used a different one is undetectable downstream.
                //
                // Focus is *not* checked for equality with the request, and that is deliberate.
                // `LENS_FOCUS_DISTANCE` on this lens is APPROXIMATE-calibration: the position is
                // quantised to a ~0.037-dioptre motor step, and a request of exactly 0.0 is
                // answered with the hyperfocal position (0.1216 here) rather than the hard stop.
                // Demanding equality would reject every frame forever. What matters for focus is
                // knowing where the lens *is* — which the result reports, and which the sweep
                // records as the sample's position — and that it has stopped moving.
                val settled = info != null &&
                    frameGeneration >= generation &&
                    ManualRequest.exposureMatches(info.exposureNs, frameRequest.exposureNs) &&
                    info.lensStationary

                _frames.emit(
                    FramingFrame(
                        generation = frameGeneration,
                        request = frameRequest,
                        plane = binned,
                        stars = stars,
                        preview = preview,
                        stretch = stretch,
                        appliedIso = info?.iso,
                        appliedExposureNs = info?.exposureNs,
                        appliedFocus = info?.focus,
                        noiseProfile = info?.noiseProfile,
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
        /**
         * Results kept for pairing against their pixels, by `SENSOR_TIMESTAMP`.
         *
         * Sized in *entries*, so its reach in time shrinks as the exposure does — at 8 entries a
         * 20 ms framing request kept only 160 ms of history and nothing ever paired, so nothing
         * ever settled. The exposure engine's test frames are deliberately short (T-3.1), which
         * is exactly where the old value fell over. These are a few dozen bytes each.
         */
        private const val RESULT_CACHE = 64

        /**
         * Generations kept addressable. Must comfortably exceed the request pipeline depth: a
         * frame landing now was requested [PIPELINE_DEPTH_FRAMES] generations ago and still has
         * to find its own request to be judged against.
         */
        private const val REQUEST_CACHE = 64

        /**
         * Measured on the reference device 2026-08-17: a change to `LENS_FOCUS_DISTANCE` takes
         * nine to ten frames to appear in the capture results, independent of exposure length
         * and of how many requests are issued in between. Callers that wait for a frame to
         * answer their request must budget in frames, not in seconds.
         */
        const val PIPELINE_DEPTH_FRAMES = 10

        /**
         * Two lens positions this close are the same motor step. The measured step on the
         * reference device is ~0.037 dioptres, so a tenth of that is comfortably inside the
         * quantisation without being at the mercy of float noise.
         */
        const val FOCUS_STABLE_TOLERANCE = 0.004f
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
        /** Shared with [SequenceSession], which needs the same green-only figure. */
        fun blackLevelOfPublic(chars: CameraCharacteristics): Double = blackLevelOf(chars)

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
