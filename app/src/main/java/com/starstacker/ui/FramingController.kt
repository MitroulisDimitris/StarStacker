package com.starstacker.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.starstacker.camera.CameraAccess
import com.starstacker.camera.FramingFrame
import com.starstacker.camera.FramingRequest
import com.starstacker.camera.FramingSession
import com.starstacker.device.CameraProfile
import com.starstacker.focus.FocusMonitor
import com.starstacker.focus.FocusRecord
import com.starstacker.focus.FocusRunner
import com.starstacker.focus.FocusSample
import com.starstacker.focus.FocusStatus
import com.starstacker.focus.FocusStore
import com.starstacker.focus.FocusSweep
import com.starstacker.imaging.GrayImage
import com.starstacker.stars.FrameStars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the screen needs from a frame. The frame itself holds megabytes; this does not. */
data class FrameSummary(
    val starCount: Int,
    val hfr: Double?,
    val eccentricity: Double?,
    val background: Double,
    val noise: Double,
    val analysisMs: Long,
    val settled: Boolean,
    /** The frame is clipped — a distinct condition from a starless one, see [FrameStars]. */
    val saturated: Boolean,
    val appliedIso: Int?,
    val appliedExposureNs: Long?,
    val appliedFocus: Float?,
)

/**
 * Owns the framing loop for the UI (T-2.2), and the focus actions that run on top of it
 * (T-2.4, T-2.5).
 *
 * Two things here are not conveniences. The loop **stops itself when left alone**: framing at
 * high ISO with the sensor reading out once a second is real heat, and heat spent before the
 * session starts comes straight out of the session's dark current budget (FR-6.2). And the
 * **refresh rate is stated on screen**, because at roughly one frame per second an unlabelled
 * preview reads as a crashed app.
 */
class FramingController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val access = CameraAccess(context)
    private val focusStore = FocusStore(File(context.filesDir, "focus.json"))

    private var job: Job? = null
    private var session: FramingSession? = null
    private var lastTouchMs = System.currentTimeMillis()

    var running by mutableStateOf(false)
        private set
    var busy: String? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var streamDetail: String? by mutableStateOf(null)
        private set
    var preview: ImageBitmap? by mutableStateOf(null)
        private set
    var frame: FrameSummary? by mutableStateOf(null)
        private set
    var frameCount by mutableStateOf(0)
        private set
    var stoppedForIdle by mutableStateOf(false)
        private set

    var iso by mutableStateOf(DEFAULT_ISO)
        private set
    var exposureNs by mutableStateOf(DEFAULT_EXPOSURE_NS)
        private set
    var boosted by mutableStateOf(false)
        private set

    var storedFocus: FocusRecord? by mutableStateOf(null)
        private set
    var focusStatus by mutableStateOf(FocusStatus.UNKNOWN)
        private set
    var focusMessage: String? by mutableStateOf(null)
        private set
    var sweepSamples by mutableStateOf<List<FocusSample>>(emptyList())
        private set
    var sweepProgress: String? by mutableStateOf(null)
        private set

    private var monitor: FocusMonitor? = null
    private var camera: CameraProfile? = null

    val idleTimeoutSeconds: Int get() = (IDLE_TIMEOUT_MS / 1000).toInt()

    /** Roughly how often a new frame lands, for the "this is not frozen" label. */
    val refreshSeconds: Double get() = exposureNs / 1_000_000_000.0 + ANALYSIS_ALLOWANCE_SECONDS

    suspend fun selectCamera(profile: CameraProfile) {
        if (camera?.id == profile.id) return
        stop()
        camera = profile
        storedFocus = withContext(Dispatchers.IO) { focusStore.get(profile.id) }
        focusStatus = FocusStatus.UNKNOWN
        focusMessage = null
        sweepSamples = emptyList()
        frame = null
        preview = null
        frameCount = 0
        iso = defaultIsoFor(profile)
    }

    fun touch() {
        lastTouchMs = System.currentTimeMillis()
        stoppedForIdle = false
    }

    fun boost(value: Boolean) {
        boosted = value
        exposureNs = if (value) BOOST_EXPOSURE_NS else DEFAULT_EXPOSURE_NS
        touch()
        session?.let { runCatching { it.apply(request()) } }
    }

    fun start() {
        val profile = camera ?: return
        if (running) return
        touch()
        error = null
        stoppedForIdle = false

        // A previous loop may still be tearing its camera down. Opening on top of that gets
        // ERROR_CAMERA_IN_USE, so the new loop waits for the old one to finish letting go.
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            var opened: FramingSession? = null
            try {
                busy = "Opening camera ${profile.id}"
                opened = withContext(Dispatchers.IO) { FramingSession.open(access, profile.id) }
                session = opened
                streamDetail = opened.support.detail
                busy = null
                running = true
                opened.apply(request())
                launch { watchIdle() }
                opened.frames.collect { onFrame(it) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "framing loop failed", t)
                error = "${t::class.java.simpleName}: ${t.message}"
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { runCatching { opened?.close() } }
                }
                session = null
                running = false
                busy = null
            }
        }
    }

    /**
     * Cancellation, not a flag: the loop's own `finally` is what closes the camera, so the
     * teardown happens on exactly one path whether it was stopped, replaced, or failed.
     */
    fun stop() {
        job?.cancel()
    }

    /** T-2.4 — a cold sweep, stored per camera with the elevation it was found at. */
    fun sweepFocus(altitudeDeg: Double?) {
        val profile = camera ?: return
        val live = session ?: run {
            focusMessage = "start the framing loop first — the sweep measures its frames"
            return
        }
        if (profile.focusType == com.starstacker.device.FocusType.FIXED) {
            focusMessage = "fixed focus — nothing to sweep (FR-4.1.4.1)"
            storedFocus = FocusRecord(
                cameraId = profile.id, fixedFocus = true, diopters = 0f, hfr = Double.NaN,
                starCount = 0, altitudeDeg = altitudeDeg, exposureNs = exposureNs, iso = iso,
                verdict = "FIXED", capturedAtEpochMs = System.currentTimeMillis(),
            ).also { focusStore.save(it) }
            return
        }

        touch()
        scope.launch {
            sweepSamples = emptyList()
            busy = "Sweeping focus"
            try {
                val runner = FocusRunner(live, maxDiopters(profile))
                val outcome = runner.sweep(
                    cameraId = profile.id,
                    iso = iso,
                    exposureNs = exposureNs,
                    altitudeDeg = altitudeDeg,
                ) { progress ->
                    sweepProgress = "${progress.index}/${progress.total} · " +
                        "%.3f dioptres".format(progress.sample.diopters)
                    sweepSamples = sweepSamples + progress.sample
                    touch()
                }
                focusStatus = outcome.status
                focusMessage = outcome.message
                outcome.record?.let {
                    focusStore.save(it)
                    storedFocus = it
                    monitor = FocusMonitor(it.hfr)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "focus sweep failed", t)
                focusMessage = "sweep failed: ${t.message}"
            } finally {
                busy = null
                sweepProgress = null
            }
        }
    }

    /** T-2.5 — session-start verification: confirm the stored value, re-sweep only if it moved. */
    fun verifyFocus(altitudeDeg: Double?) {
        val profile = camera ?: return
        val live = session ?: run {
            focusMessage = "start the framing loop first"
            return
        }
        val stored = storedFocus ?: run {
            focusMessage = "no stored focus for this camera yet — run a sweep"
            return
        }

        touch()
        scope.launch {
            busy = "Verifying focus"
            try {
                val runner = FocusRunner(live, maxDiopters(profile))
                val outcome = runner.verify(stored, iso, exposureNs, altitudeDeg)
                focusStatus = outcome.status
                focusMessage = outcome.message
                outcome.record?.let {
                    if (it != stored) focusStore.save(it)
                    storedFocus = it
                    monitor = FocusMonitor(it.hfr)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "focus verification failed", t)
                focusMessage = "verification failed: ${t.message}"
            } finally {
                busy = null
            }
        }
    }

    fun close() {
        stop()
        access.close()
    }

    private fun request() = FramingRequest(
        iso = iso,
        exposureNs = exposureNs,
        focusDiopters = storedFocus?.takeIf { !it.fixedFocus }?.diopters,
    )

    private suspend fun onFrame(frame: FramingFrame) {
        val bitmap = withContext(Dispatchers.Default) { toBitmap(frame.preview) }
        preview = bitmap
        frameCount++
        this.frame = FrameSummary(
            starCount = frame.stars.count,
            hfr = frame.stars.medianHfr,
            eccentricity = frame.stars.medianEccentricity,
            background = frame.stars.background,
            noise = frame.stars.noise,
            analysisMs = frame.analysisMs,
            settled = frame.settled,
            saturated = frame.stars.saturatedFrame,
            appliedIso = frame.appliedIso,
            appliedExposureNs = frame.appliedExposureNs,
            appliedFocus = frame.appliedFocus,
        )

        // FR-6.3's live half: the readout is always on, and the drift flag comes from a rolling
        // median so one hazy frame cannot raise it.
        monitor?.let {
            focusStatus = it.accept(frame.stars.medianHfr, frame.stars.count)
            if (focusStatus == FocusStatus.DRIFTING || focusStatus == FocusStatus.LOST) {
                focusMessage = "HFR has moved to %.2f px from %.2f px at focus — re-verify".format(
                    it.medianHfr ?: Double.NaN, it.referenceHfr,
                )
            }
        }
    }

    private suspend fun watchIdle() {
        while (true) {
            delay(IDLE_CHECK_MS)
            if (busy != null) continue
            if (System.currentTimeMillis() - lastTouchMs > IDLE_TIMEOUT_MS) {
                stoppedForIdle = true
                stop()
                return
            }
        }
    }

    private fun toBitmap(gray: GrayImage): ImageBitmap =
        Bitmap.createBitmap(gray.toArgb(), gray.width, gray.height, Bitmap.Config.ARGB_8888)
            .asImageBitmap()

    private fun maxDiopters(profile: CameraProfile): Float =
        profile.minimumFocusDistanceDiopters?.takeIf { it > 0f } ?: FocusSweep.DEFAULT_SPAN

    /**
     * Framing wants the sky visible, not clean: a high ISO costs read noise that the session's
     * solved ISO would never accept, and buys a picture you can actually frame on.
     */
    private fun defaultIsoFor(profile: CameraProfile): Int {
        val max = profile.isoMax ?: return DEFAULT_ISO
        val min = profile.isoMin ?: 50
        return DEFAULT_ISO.coerceIn(min, max)
    }

    private companion object {
        const val TAG = "FramingController"
        const val DEFAULT_ISO = 3200
        const val DEFAULT_EXPOSURE_NS = 1_000_000_000L
        const val BOOST_EXPOSURE_NS = 4_000_000_000L
        const val IDLE_TIMEOUT_MS = 120_000L
        const val IDLE_CHECK_MS = 5_000L
        const val ANALYSIS_ALLOWANCE_SECONDS = 0.3
    }
}
