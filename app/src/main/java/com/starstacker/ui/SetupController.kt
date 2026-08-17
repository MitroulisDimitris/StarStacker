package com.starstacker.ui

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.starstacker.camera.CameraAccess
import com.starstacker.device.CameraProfile
import com.starstacker.exposure.ExposureSolver
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
        private set

    /** FR-5.4's input: the user says how long they have. */
    var sessionMinutes by mutableStateOf(DEFAULT_SESSION_MINUTES)
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
    }

    fun setPointing(fix: PointingFix?) {
        val changed = fix?.declinationDeg != pointing?.declinationDeg
        pointing = fix
        // Declination sets the trailing limit, so a new pointing fix makes the old solve stale.
        // Re-solving is free once the sky has been measured — no new frames are needed.
        if (changed && measurement != null) resolve()
    }

    fun chooseSessionMinutes(minutes: Int) {
        sessionMinutes = minutes.coerceIn(MIN_SESSION_MINUTES, MAX_SESSION_MINUTES)
        replan()
    }

    fun chooseTolerance(px: Double) {
        tolerancePx = px.coerceIn(0.5, 5.0)
        resolve()
    }

    fun toggleWork() { showWork = !showWork }

    /** FR-5.3 — pin a value and re-solve around it. Tapping the pinned ISO again releases it. */
    fun pinIso(iso: Int?) {
        pinnedIso = if (pinnedIso == iso) null else iso
        resolve()
    }

    /** Measures the sky, then solves. The only step that costs frames. */
    fun measureAndSolve() {
        val profile = camera ?: return
        if (job?.isActive == true) return

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
            maxExposureSeconds = profile.exposureMaxSeconds ?: 30.0,
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
            goal = SessionPlanner.Goal.TotalTime(sessionMinutes * 60.0),
            iso = chosen.iso,
            subSeconds = chosen.exposureSeconds,
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

    private companion object {
        const val TAG = "SetupController"
        const val DEFAULT_SESSION_MINUTES = 30
        const val MIN_SESSION_MINUTES = 5
        const val MAX_SESSION_MINUTES = 240
        const val MEASURED_OVERHEAD_SECONDS = 0.01
    }
}
