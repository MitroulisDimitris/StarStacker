package com.starstacker.ui

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.starstacker.camera.CameraAccess
import com.starstacker.device.CameraProfile
import com.starstacker.exposure.ExposureCompensation
import com.starstacker.exposure.ExposureSolver
import com.starstacker.exposure.PredictedHistogram
import com.starstacker.exposure.SessionPlanner
import com.starstacker.exposure.SkyProbe
import com.starstacker.exposure.TrailingLimit
import com.starstacker.pointing.PointingFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * T-3.4 and T-3.5 — the state behind the session setup screen.
 *
 * Two things it is careful about.
 *
 * **The derivation is kept, not regenerated.** FR-5.3 wants `Show work` to explain why *this* ISO
 * won, and an explanation rebuilt for display is a second implementation that can disagree with
 * the first. So the screen renders [ExposureSolver.Solution] itself — the object the answer came
 * out of, with every losing candidate and the reason it lost still attached.
 *
 * **A pin re-solves rather than overrides.** Pinning an ISO runs the whole solve again with the
 * choice forced, so the plan, the storage budget and the derivation all follow the pin. Nothing
 * downstream is disabled by it, which is FR-5.3's actual requirement.
 */
class SetupController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val access = CameraAccess(context)
    private var job: Job? = null

    var camera: CameraProfile? by mutableStateOf(null)
        private set

    var busy: String? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set

    var measurement: SkyProbe.Measurement? by mutableStateOf(null)
        private set
    var solution: ExposureSolver.Solution? by mutableStateOf(null)
        private set
    var plan: SessionPlanner.Plan? by mutableStateOf(null)
        private set

    var pinnedIso: Int? by mutableStateOf(null)
        private set
    var showWork by mutableStateOf(false)

    /**
     * T-3.25 — the user's override of the solved exposure, in stops.
     *
     * The solver keeps deciding and the user keeps the veto. Zero is "take the answer"; the
     * histogram moves under the control so the cost of disagreeing is visible rather than
     * described. The range, the step and the clamp are [ExposureCompensation]'s (T-3.35).
     */
    var exposureStops by mutableStateOf(0.0)
        private set

    /**
     * T-3.33 — whether the sky has been asked for.
     *
     * The screen used to fire [measureAndSolve] from a `LaunchedEffect`, so arriving opened the
     * camera and spent frames because a screen appeared: a mistaken tap cost a sky measurement,
     * and the phone got warm for a reason the user could not see. **D-27** generalises the
     * correction — a measurement that costs frames waits to be asked — and this flag is what
     * distinguishes "not measured yet" from "measured and failed", which need different screens.
     */
    var measurementAsked by mutableStateOf(false)
        private set

    /**
     * T-3.26 — how many light frames to shoot. The slider's value, and the planner's goal.
     *
     * Frames rather than minutes because the frame is the quantum: a request for "30 minutes"
     * is rounded to a frame count regardless, and carrying the minutes as well means rounding
     * twice.
     */
    var frameCount by mutableStateOf(DEFAULT_FRAMES)
        private set

    var tolerancePx by mutableStateOf(TrailingLimit.DEFAULT_TOLERANCE_PX)
        private set

    private var pointing: PointingFix? = null

    fun select(profile: CameraProfile) {
        if (camera?.id == profile.id) return
        camera = profile
        measurement = null
        solution = null
        plan = null
        pinnedIso = null
        error = null
        // A different camera has a different sky rate and a different noise model, so the
        // measurement does not carry over — and per D-27 the new one is asked for, not assumed.
        measurementAsked = false
    }

    fun setPointing(fix: PointingFix?) {
        val changed = fix?.declinationDeg != pointing?.declinationDeg
        pointing = fix
        // Declination sets the trailing limit, so a new pointing fix makes the old solve stale.
        // Re-solving is free once the sky has been measured — no new frames are needed.
        if (changed && measurement != null) resolve()
    }

    /**
     * The most frames the slider offers: whatever fills [MAX_SESSION_HOURS] at this sub length,
     * so the right-hand end is always the same amount of *night* rather than the same number.
     *
     * **The compensated sub, not the solved one** (T-3.35). This read
     * `solution.chosen.exposureSeconds` — the sub before the user's override — so at +2 stops the
     * 2.5-hour bound was already wrong by 4×: the slider counted 4 s frames and offered a session
     * built out of 16 s ones. At the new ±4 it would have been 16×.
     */
    val maxFrames: Int
        get() = ExposureCompensation.maxFrames(
            subSeconds = effectiveSubSeconds ?: 10.0,
            overheadSeconds = MEASURED_OVERHEAD_SECONDS,
            hours = MAX_SESSION_HOURS,
        )

    fun chooseFrameCount(frames: Int) {
        frameCount = frames.coerceIn(1, maxFrames)
        replan()
    }

    fun chooseTolerance(px: Double) {
        tolerancePx = px.coerceIn(0.5, 5.0)
        resolve()
    }

    fun toggleWork() { showWork = !showWork }

    fun compensate(stops: Double) {
        exposureStops = ExposureCompensation.snap(stops)
        // The frame count is bounded by the *compensated* sub (see [maxFrames]), so a longer sub
        // can put the current count past the new right-hand end. Re-clamping here rather than
        // leaving the slider showing a value outside its own range.
        frameCount = frameCount.coerceIn(1, maxFrames)
        replan()
    }

    /**
     * The sub actually planned: the solved answer shifted by [exposureStops], **clamped to the
     * sensor's longest exposure**.
     *
     * The clamp is T-3.35's. Without it, +4 stops on a 4 s solve asks for 64 s from a sensor whose
     * ceiling is 49.64 s (§1.5), and this HAL answers an impossible request by quietly truncating
     * rather than by failing — so the plan, the storage estimate and the end time would all be
     * computed from an exposure that was never going to happen.
     */
    val effectiveSubSeconds: Double?
        get() = solution?.chosen?.exposureSeconds?.let {
            ExposureCompensation.apply(it, exposureStops, sensorMaxSeconds)
        }

    /** True when the sensor's ceiling, not the dial, is deciding the sub — stated on screen. */
    val exposureClamped: Boolean
        get() = solution?.chosen?.exposureSeconds?.let {
            ExposureCompensation.isClampedAt(it, exposureStops, sensorMaxSeconds)
        } == true

    /** `SENSOR_INFO_EXPOSURE_TIME_RANGE`'s upper bound, and the solver's own ceiling. */
    private val sensorMaxSeconds: Double
        get() = camera?.exposureMaxSeconds ?: DEFAULT_MAX_EXPOSURE_SECONDS

    /** How far past the trailing budget the compensated sub goes, in pixels of elongation. */
    val compensatedTrailPx: Double?
        get() {
            val sub = effectiveSubSeconds ?: return null
            val trailing = solution?.trailing ?: return null
            if (trailing.maxExposureSeconds <= 0.0 || !trailing.maxExposureSeconds.isFinite()) {
                return null
            }
            return trailing.tolerancePx * (sub / trailing.maxExposureSeconds)
        }

    /** T-3.25's histogram, re-derived whenever the compensation moves. */
    val histogram: PredictedHistogram.Prediction?
        get() {
            val measured = measurement ?: return null
            val chosen = solution?.chosen ?: return null
            val sub = effectiveSubSeconds ?: return null
            val noise = measured.model.at(chosen.iso) ?: return null
            return runCatching { PredictedHistogram.of(measured.sky, noise, sub) }.getOrNull()
        }

    /** FR-5.3 — pin a value and re-solve around it. Tapping the pinned ISO again releases it. */
    fun pinIso(iso: Int?) {
        pinnedIso = if (pinnedIso == iso) null else iso
        resolve()
    }

    /**
     * Measures the sky, then solves. **The only step that costs frames**, which is why T-3.33
     * moved it behind a button: nothing calls this except a tap.
     */
    fun measureAndSolve() {
        val profile = camera ?: return
        if (job?.isActive == true) return

        measurementAsked = true
        error = null
        job = scope.launch {
            try {
                busy = "Measuring the sky"
                val probe = SkyProbe(access, profile)
                val result = withContext(Dispatchers.IO) {
                    probe.measure(
                        isos = SkyProbe.isoLadder(profile.isoMin, profile.isoMax),
                        exposureNs = SkyProbe.DEFAULT_TEST_EXPOSURE_NS,
                    ) { progress -> busy = "Measuring ISO ${progress.iso}" }
                }
                measurement = result
                resolve()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "sky measurement failed", t)
                error = t.message ?: t::class.java.simpleName
            } finally {
                busy = null
            }
        }
    }

    /** Re-runs the solve on the sky already measured. Costs nothing — no frames are taken. */
    fun resolve() {
        val profile = camera ?: return
        val measured = measurement ?: return
        val pitch = profile.effectivePixelPitchUm
        val focal = profile.focalLengthsMm.firstOrNull()?.toDouble()
        val sensor = profile.physicalSizeMm
        if (pitch == null || focal == null || sensor == null) {
            error = "this camera does not report the pitch, focal length and sensor size the " +
                "trailing limit needs"
            return
        }

        val trailing = TrailingLimit.solve(
            pixelPitchUm = pitch,
            focalLengthMm = focal,
            sensorWidthMm = sensor.width.toDouble(),
            sensorHeightMm = sensor.height.toDouble(),
            fieldCentreDecDeg = pointing?.declinationDeg,
            tolerancePx = tolerancePx,
        )

        solution = ExposureSolver.solve(
            sky = measured.sky,
            noiseModel = measured.model,
            trailing = trailing,
            isoCandidates = SkyProbe.isoLadder(profile.isoMin, profile.isoMax),
            maxExposureSeconds = sensorMaxSeconds,
            dualGainIso = measured.dualGainIso,
            pinnedIso = pinnedIso,
        )
        replan()
    }

    private fun replan() {
        val chosen = solution?.chosen ?: run { plan = null; return }
        val profile = camera ?: return
        val raw = profile.maxRawSize

        plan = SessionPlanner.plan(
            goal = SessionPlanner.Goal.Frames(frameCount),
            iso = chosen.iso,
            subSeconds = effectiveSubSeconds ?: chosen.exposureSeconds,
            frameAspectWidth = raw?.width ?: 4,
            frameAspectHeight = raw?.height ?: 3,
            freeBytes = freeBytes(),
            batteryPercent = batteryPercent(),
            startEpochMs = System.currentTimeMillis(),
            rotationRateArcsecPerSec = pointing?.fieldRotationArcsecPerSec,
            // Measured 2026-08-17: 2 ms. The DNG write hides behind the next exposure, so the
            // planner does not need to reserve time it will not spend (§1.9).
            overheadSeconds = MEASURED_OVERHEAD_SECONDS,
        )
    }

    /** The volume capture will actually write to, not the internal one. */
    private fun freeBytes(): Long = runCatching {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")
            .apply { mkdirs() }
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    private fun batteryPercent(): Double = runCatching {
        val intent = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) 100.0 else level * 100.0 / scale
    }.getOrDefault(100.0)

    /**
     * T-3.33 — what pressing `Measure the sky` will cost, in the units the user pays it in.
     *
     * Stated *before* the button, not after: the whole objection to solving on arrival was that
     * frames were spent by a screen appearing, and a button that spends them without saying so
     * first only moves the surprise one tap later.
     */
    fun measurementCost(): String {
        val profile = camera ?: return "select a camera first"
        val ladder = SkyProbe.isoLadder(profile.isoMin, profile.isoMax)
        val each = SkyProbe.DEFAULT_TEST_EXPOSURE_NS / 1e9
        if (ladder.isEmpty()) return "this camera reports no ISO range to measure across"
        return "%d test frames of %.2f s — one per ISO from %d to %d, a few seconds with the camera open"
            .format(ladder.size, each, ladder.first(), ladder.last())
    }

    /** FR-9.2 keeps the derivation in the session log, so a restack knows why it looks like this. */
    fun derivationLines(): List<String> {
        val current = solution ?: return emptyList()
        return buildList {
            add(current.headline)
            add(current.advisory)
            add(
                "trailing limit %s at %.1f px (%.2f arcsec/px, %s)".format(
                    ExposureSolver.formatSeconds(current.trailing.maxExposureSeconds),
                    current.trailing.tolerancePx,
                    current.trailing.arcsecPerPixel,
                    current.trailing.note,
                ),
            )
            add(
                "sky %.1f e-/s measured at ISO %d · noise model: %s".format(
                    current.sky.electronsPerSecond, current.sky.iso, current.noiseSource,
                ),
            )
            current.dualGainIso?.let { add("dual conversion gain point: ISO $it") }
            current.candidates.forEach { add("ISO ${it.iso}: ${it.reason}") }
        }
    }

    fun close() {
        job?.cancel()
        access.close()
    }

    companion object {
        const val TAG = "SetupController"
        const val DEFAULT_FRAMES = 120

        /**
         * The ceiling assumed when a camera does not report its own exposure range. Conservative
         * on purpose: it is better to refuse a sub the sensor might have taken than to plan a
         * session around one it will truncate.
         */
        const val DEFAULT_MAX_EXPOSURE_SECONDS = 30.0

        /**
         * The slider's right-hand end, in wall-clock hours. The frame count it corresponds to is
         * derived from the sub length, so a 30 s sub and a 2 s sub both reach the same place.
         */
        const val MAX_SESSION_HOURS = 2.5
        const val MEASURED_OVERHEAD_SECONDS = 0.01
    }
}
