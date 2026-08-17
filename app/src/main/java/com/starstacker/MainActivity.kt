package com.starstacker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import android.util.Log
import com.starstacker.camera.CameraAccess
import com.starstacker.camera.OpenabilityProbe
import com.starstacker.camera.RawCapture
import com.starstacker.device.CameraPicker
import com.starstacker.device.CameraProbe
import com.starstacker.device.DeviceProfile
import com.starstacker.device.ProfileJson
import com.starstacker.device.Qualification
import com.starstacker.diag.FieldDiagnostics
import com.starstacker.dng.DngReader
import com.starstacker.focus.FocusSweep
import com.starstacker.pointing.PointingFix
import com.starstacker.pointing.PointingSource
import com.starstacker.stars.CfaBinner
import com.starstacker.stars.StarDetector
import com.starstacker.ui.DiagnosticsState
import com.starstacker.ui.FramingController
import com.starstacker.ui.FramingScreen
import com.starstacker.ui.ProbeScreen
import com.starstacker.ui.theme.StarStackerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1A/1B entry point.
 *
 * Two screens so far: the capability probe (T-1.1/T-1.2), which answers whether there is a
 * device to run on at all, and framing & focus (Phase 1B), which is the first screen that does
 * something on a tripod. The real navigation graph is T-0.3; this is deliberately a switch
 * rather than a half-built version of it.
 */
class MainActivity : ComponentActivity() {

    private enum class Screen { PROBE, FRAMING }

    private var profile by mutableStateOf<DeviceProfile?>(null)
    private var diagnostics by mutableStateOf(DiagnosticsState())
    private var locationGranted by mutableStateOf(false)

    private val pointingSource by lazy { PointingSource(this) }
    private val framing by lazy { FramingController(this, lifecycleScope) }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { reprobe() }

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> locationGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Session screens are read on a tripod in the dark; keeping the screen alive is the
        // behaviour the whole app wants, so it starts here rather than being bolted on later.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        reprobe()

        // Measured on the Nothing (3a) Pro running Android 16: LENS_INFO_MINIMUM_FOCUS_DISTANCE
        // and LENS_INFO_HYPERFOCAL_DISTANCE read null until CAMERA is granted, while every
        // other characteristic reads correctly. Enumeration therefore works unpermissioned,
        // but the lens data does not — so ask, then probe again.
        if (!hasCameraPermission()) {
            requestCamera.launch(Manifest.permission.CAMERA)
        }

        // Debug affordance for on-device iteration:
        //   adb shell am start -n com.starstacker/.MainActivity --ez autodiag true
        //   adb shell am start -n com.starstacker/.MainActivity --es diag framing --ei frames 12
        //   adb shell am start -n com.starstacker/.MainActivity --es diag focus
        // Runs the camera checks head-free and logs the results, so a rebuild-measure cycle
        // does not require tapping through the UI on the device — and, at ~1 fps, so a framing
        // measurement does not require watching a preview for a minute.
        if (intent?.getBooleanExtra("autodiag", false) == true && hasCameraPermission()) {
            lifecycleScope.launch {
                profile?.let { runOpenabilityTest(it) }
                runCaptureDiagnosis()
                runCapture(100_000_000L)
                runCapture(10_000_000_000L)
                Log.i(TAG, "autodiag complete")
            }
        }

        val fieldDiag = intent?.getStringExtra("diag")
        if (fieldDiag != null && hasCameraPermission()) {
            val frames = intent?.getIntExtra("frames", 12) ?: 12
            val exposureMs = intent?.getIntExtra("exposureMs", 1000) ?: 1000
            val iso = intent?.getIntExtra("iso", 3200) ?: 3200
            lifecycleScope.launch { runFieldDiagnostics(fieldDiag, frames, iso, exposureMs) }
        }

        locationGranted = pointingSource.hasLocationPermission()

        setContent {
            StarStackerTheme {
                val current = profile ?: return@StarStackerTheme
                val qualification = remember(current) { Qualification.qualifyDevice(current) }
                var exportedPath by remember { mutableStateOf<String?>(null) }
                var screen by remember { mutableStateOf(Screen.PROBE) }
                val scope = rememberCoroutineScope()

                val options = remember(current, qualification) {
                    CameraPicker.options(current, qualification)
                }
                var selectedCameraId by remember(options) {
                    mutableStateOf(
                        options.firstOrNull { it.recommended }?.id
                            ?: options.firstOrNull { it.selectable }?.id,
                    )
                }
                LaunchedEffect(selectedCameraId) {
                    current.cameras.firstOrNull { it.id == selectedCameraId }
                        ?.let { framing.selectCamera(it) }
                }

                var pointing by remember { mutableStateOf<PointingFix?>(null) }
                LaunchedEffect(screen, locationGranted) {
                    if (screen != Screen.FRAMING || !pointingSource.hasRequiredSensors()) {
                        return@LaunchedEffect
                    }
                    // Sensors run only while the framing screen is up: a magnetometer polled
                    // through a 45-minute session is battery spent on a number nobody is reading.
                    pointingSource.fixes().collect { pointing = it }
                }

                when (screen) {
                    Screen.PROBE -> ProbeScreen(
                        profile = current,
                        qualification = qualification,
                        exportedPath = exportedPath,
                        onExport = { exportedPath = exportAndShare(current) },
                        diagnostics = diagnostics.copy(
                            cameraPermissionGranted = hasCameraPermission(),
                        ),
                        onOpenabilityTest = { scope.launch { runOpenabilityTest(current) } },
                        onCaptureRaw = { exposureNs -> scope.launch { runCapture(exposureNs) } },
                        onOpenFraming = { screen = Screen.FRAMING },
                    )

                    Screen.FRAMING -> FramingScreen(
                        options = options,
                        selectedCameraId = selectedCameraId,
                        controller = framing,
                        pointing = pointing,
                        pointingAvailable = pointingSource.hasRequiredSensors(),
                        locationGranted = locationGranted,
                        onSelectCamera = { selectedCameraId = it },
                        onRequestLocation = {
                            requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        onBack = {
                            framing.stop()
                            screen = Screen.PROBE
                        },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The camera is a single-holder resource. Leaving the framing loop running when the app
        // goes away would lock it for every other app on the phone (T-1.3).
        framing.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        framing.close()
    }

    /** T-1.3 / OI-18 — which of the five camera IDs will actually open? */
    private suspend fun runOpenabilityTest(profile: DeviceProfile) {
        diagnostics = diagnostics.copy(busy = "Opening each camera", openResults = emptyList())
        val ids = profile.cameras.map { it.id }
        val results = CameraAccess(this).use { access ->
            withContext(Dispatchers.IO) { OpenabilityProbe.run(access, ids) }
        }
        diagnostics = diagnostics.copy(
            busy = null,
            openResults = results.map { it.describe() },
        )
        Log.i(TAG, "openability: " + results.joinToString("; ") { it.describe() })
    }

    /** Bisects capture configurations to find one this HAL will actually stream (T-1.4). */
    private suspend fun runCaptureDiagnosis() {
        diagnostics = diagnostics.copy(busy = "Finding a working capture config")
        val dir = File(getExternalFilesDir(null) ?: filesDir, "first-light").apply { mkdirs() }
        val lines = CameraAccess(this).use { access ->
            withContext(Dispatchers.IO) { RawCapture.diagnose(access, MAIN_CAMERA_ID, dir) }
        }
        // CamX floods the log buffer and evicts our lines within seconds, so the record goes
        // to a file rather than to logcat.
        runCatching { File(dir, "capture-diagnosis.txt").writeText(lines.joinToString("\n")) }
        diagnostics = diagnostics.copy(busy = null, captureLines = lines)
    }

    /** T-1.4 — first light. Writes a DNG next to the profile so `adb pull` can fetch it. */
    private suspend fun runCapture(exposureNs: Long) {
        val label = if (exposureNs >= 1_000_000_000L) "${exposureNs / 1_000_000_000}s" else
            "${exposureNs / 1_000_000}ms"
        diagnostics = diagnostics.copy(busy = "Capturing RAW at $label")

        val dir = File(getExternalFilesDir(null) ?: filesDir, "first-light").apply { mkdirs() }
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())

        val lines = try {
            val outcome = CameraAccess(this).use { access ->
                withContext(Dispatchers.IO) {
                    RawCapture.captureSingle(
                        access = access,
                        cameraId = MAIN_CAMERA_ID,
                        iso = CAPTURE_ISO,
                        exposureNs = exposureNs,
                        outputDir = dir,
                        fileName = "raw_${label}_$stamp.dng",
                        verifyRoundTrip = true,
                    )
                }
            }
            buildList {
                add(
                    "${outcome.file?.name ?: "(not written)"} · ${outcome.frameSize} · " +
                        "${outcome.fileBytes / 1024} KB",
                )
                add(
                    "ISO ${outcome.actualIso ?: "?"} (asked ${outcome.requestedIso}) · " +
                        "exp ${outcome.actualExposureNs ?: "?"}ns" +
                        (outcome.exposureErrorPercent?.let { " (%+.2f%%)".format(it) } ?: ""),
                )
                add("elapsed ${outcome.elapsedMs} ms · skew ${outcome.rollingShutterSkewNs ?: "?"} ns")
                add("noise profile: " + (outcome.noiseProfile?.size?.let { "$it channels" } ?: "absent"))
                outcome.roundTrip?.let { add("round trip: $it") }
                outcome.file?.let { addAll(benchmarkAnalysis(it)) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed", t)
            listOf("FAILED: ${t::class.java.simpleName}: ${t.message}")
        }

        diagnostics = diagnostics.copy(busy = null, captureLines = lines)
        Log.i(TAG, "capture: " + lines.joinToString(" | "))
        runCatching {
            File(dir, "capture-log.txt").appendText(lines.joinToString("\n") + "\n\n")
        }
    }

    /**
     * Phase 1B's hardware acceptances, run from `adb`. Results go to a file for the same reason
     * the capture diagnosis does: CamX floods the log buffer and evicts our lines within seconds.
     */
    private suspend fun runFieldDiagnostics(mode: String, frames: Int, iso: Int, exposureMs: Int) {
        val dir = File(getExternalFilesDir(null) ?: filesDir, "first-light").apply { mkdirs() }
        val out = File(dir, "field-diagnosis.txt")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val lines = mutableListOf<String>()
        val log: (String) -> Unit = { line ->
            lines += line
            Log.i(TAG, line)
            runCatching { out.appendText(line + "\n") }
        }

        runCatching { out.appendText("\n===== $mode @ $stamp =====\n") }
        diagnostics = diagnostics.copy(busy = "Field diagnostics: $mode")
        try {
            CameraAccess(this).use { access ->
                withContext(Dispatchers.IO) {
                    when (mode) {
                        "framing" -> FieldDiagnostics.framing(
                            access, MAIN_CAMERA_ID, frames, iso,
                            exposureMs * 1_000_000L, log,
                        )

                        "lens" -> FieldDiagnostics.lensRange(
                            access, MAIN_CAMERA_ID, iso, exposureMs * 1_000_000L,
                            requests = listOf(0f, 0.20f, 0.40f, 0.10f, 0f),
                            framesPerPosition = frames,
                            log = log,
                        )

                        "focus" -> FieldDiagnostics.focusSweep(
                            access, MAIN_CAMERA_ID, iso, exposureMs * 1_000_000L,
                            maxDiopters = profile?.cameras
                                ?.firstOrNull { it.id == MAIN_CAMERA_ID }
                                ?.minimumFocusDistanceDiopters
                                ?.takeIf { it > 0f } ?: FocusSweep.DEFAULT_SPAN,
                            log = log,
                        )

                        else -> log("unknown diag mode '$mode' — expected framing or focus")
                    }
                }
            }
        } catch (t: Throwable) {
            log("FAILED: ${t::class.java.simpleName}: ${t.message}")
            Log.e(TAG, "field diagnostics failed", t)
        } finally {
            diagnostics = diagnostics.copy(busy = null, captureLines = lines.takeLast(24))
            log("=== $mode complete ===")
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun reprobe() {
        val probed = CameraProbe.probe(this)
        profile = probed

        // Always drop a copy at a stable path. The probe is iterated on constantly during
        // Phase 1A, and `adb pull` beats asking someone to tap a button each rebuild.
        runCatching {
            File(getExternalFilesDir(null) ?: filesDir, "device-profile_latest.json")
                .writeText(ProfileJson.encode(probed))
        }
    }

    /** FR-3.2.1 — profile export, so a device can be assessed without a calibration workflow. */
    private fun exportAndShare(profile: DeviceProfile): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val safeModel = profile.model.replace(Regex("[^A-Za-z0-9]+"), "-")
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "device-profile_${safeModel}_$stamp.json")
        file.writeText(ProfileJson.encode(profile))

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "StarStacker device profile — ${profile.model}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share device profile"))
        return file.absolutePath
    }

    /**
     * T-2.3's on-device budget: FR-7.4 puts star detection in the live path, once per sub, so
     * it must cost far less than the exposure it runs alongside.
     */
    private fun benchmarkAnalysis(file: File): List<String> = try {
        val readStart = System.nanoTime()
        val image = DngReader.read(file)
        val readMs = (System.nanoTime() - readStart) / 1_000_000

        val cfa = image.metadata.cfaPattern?.codes ?: listOf(1, 0, 2, 1)
        val binStart = System.nanoTime()
        val plane = CfaBinner.binGreen(
            image.pixels, image.metadata.width, image.metadata.height, cfa, factor = 4,
        )
        val binMs = (System.nanoTime() - binStart) / 1_000_000

        val detectStart = System.nanoTime()
        val stars = StarDetector().detect(plane.data, plane.width, plane.height)
        val detectMs = (System.nanoTime() - detectStart) / 1_000_000

        listOf(
            "analysis: read ${readMs}ms · bin ${binMs}ms · detect ${detectMs}ms " +
                "(${plane.width}x${plane.height})",
            "stars: ${stars.count} · bg ${"%.1f".format(stars.background)} · " +
                "noise ${"%.2f".format(stars.noise)}" +
                (stars.medianHfr?.let { " · HFR %.2f".format(it) } ?: ""),
        )
    } catch (t: Throwable) {
        Log.e(TAG, "analysis failed", t)
        listOf("analysis FAILED: ${t::class.java.simpleName}: ${t.message}")
    }

    private companion object {
        const val TAG = "StarStacker"

        /** The only published rear camera on the reference device (§1.5). */
        const val MAIN_CAMERA_ID = "0"

        /** Arbitrary for a structural first-light test; the exposure engine picks this later. */
        const val CAPTURE_ISO = 800
    }
}
