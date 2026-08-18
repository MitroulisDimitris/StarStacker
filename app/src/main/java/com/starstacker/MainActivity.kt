package com.starstacker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import android.util.Log
import com.starstacker.camera.CameraAccess
import com.starstacker.camera.OpenabilityProbe
import com.starstacker.camera.RawCapture
import com.starstacker.capture.CaptureEngine
import com.starstacker.capture.CaptureService
import com.starstacker.device.CameraPicker
import com.starstacker.device.CameraProbe
import com.starstacker.device.DeviceProfile
import com.starstacker.device.ProfileJson
import com.starstacker.device.Qualification
import com.starstacker.diag.FieldDiagnostics
import com.starstacker.diag.FieldLog
import com.starstacker.diag.StorageBenchmark
import com.starstacker.dng.DngReader
import com.starstacker.focus.FocusSweep
import com.starstacker.device.Tier
import com.starstacker.pointing.Astro
import com.starstacker.pointing.PointingFix
import com.starstacker.session.SessionPointing
import com.starstacker.session.SessionRoot
import com.starstacker.session.toSessionPointing
import com.starstacker.session.SessionRecovery
import com.starstacker.session.SessionState
import com.starstacker.pointing.PointingSource
import com.starstacker.stars.CfaBinner
import com.starstacker.stars.StarDetector
import com.starstacker.ui.DiagnosticsState
import com.starstacker.ui.FramingController
import androidx.activity.compose.BackHandler
import com.starstacker.ui.BackStack
import com.starstacker.ui.Screen
import com.starstacker.ui.CaptureScreen
import com.starstacker.ui.FramingScreen
import com.starstacker.ui.SetupController
import com.starstacker.session.SessionCatalogue
import com.starstacker.session.SessionSummary
import com.starstacker.ui.MainScreen
import com.starstacker.ui.MainWarning
import com.starstacker.ui.Permissions
import com.starstacker.ui.SettingsScreen
import com.starstacker.ui.SetupScreen
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
 * Four screens: the capability probe (T-1.1/T-1.2), framing & focus (Phase 1B), session setup
 * with the exposure solve (T-3.4/T-3.5), and the live capture screen with its completion summary
 * (T-3.11/T-3.15). Still a `when` over an enum rather than a navigation library — T-0.3's real
 * graph arrives when there is a back stack worth modelling, and a linear flow of four is not it.
 *
 * The capture screen is a **pure function of [CaptureService.progress]**, per D-6: the session
 * belongs to the service, not to this Activity, and survives it being destroyed.
 */
class MainActivity : ComponentActivity() {

    private var profile by mutableStateOf<DeviceProfile?>(null)
    private var diagnostics by mutableStateOf(DiagnosticsState())
    private var locationGranted by mutableStateOf(false)

    /**
     * T-0.4 — every runtime permission currently held. A set rather than a flag each, so the
     * settings screen is a pure function of it and a new permission needs no new plumbing.
     */
    private var granted by mutableStateOf<Set<String>>(emptySet())

    /** See [askForNotificationsOnce] — a refused prompt must not become a loop. */
    private var notificationsAsked = false

    /** T-3.13: scanned once at launch, so the offer is there before anything else is touched. */
    private var resumable by mutableStateOf<SessionRecovery.Resumable?>(null)

    private val pointingSource by lazy { PointingSource(this) }
    private val framing by lazy { FramingController(this, lifecycleScope) }
    private val setup by lazy { SetupController(this, lifecycleScope) }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { reprobe() }

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed -> locationGranted = allowed; refreshPermissions() }

    /** T-0.4 — used by the settings screen, which asks for them by name. */
    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }

    private fun refreshPermissions() {
        granted = Permissions.all
            .filter { ContextCompat.checkSelfPermission(this, it.id) == PackageManager.PERMISSION_GRANTED }
            .map { it.id }
            .toSet()
        locationGranted = Permissions.FINE_LOCATION in granted
    }

    /**
     * Android stops showing the system prompt after two refusals, and from then on the only route
     * is the app's own settings page. Without this the Allow button would silently do nothing,
     * which reads as a bug in this app rather than a decision already made.
     */
    /**
     * T-0.4 — asked here, on the way into framing, rather than at first launch.
     *
     * A prompt fired at cold start is answered before the user knows what the app does, and
     * "Allow notifications?" out of context is refused by reflex. By this point they have opened
     * the camera to point it at something, which is the moment "this runs with the screen off and
     * will ask you to cover the lens" becomes a true and relevant sentence.
     *
     * Only ever once per launch: Android silently ignores the request after two refusals, and
     * re-firing it would turn a decision into a loop. The settings screen carries the full
     * rationale and a route to the system page for anyone who wants to change their mind.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationsAsked || Permissions.NOTIFICATIONS in granted) return
        notificationsAsked = true
        requestPermission.launch(Permissions.NOTIFICATIONS)
    }

    /**
     * T-3.21 — open the folder sessions are written to.
     *
     * **It cannot work for the default.** `Android/data/...` is unbrowsable on Android 11+, so an
     * app-private root opens in no file manager at all. Rather than a button that silently does
     * nothing, the absence of a chosen root is treated as the question it really is: this is the
     * first moment picking one is actually motivated, so the picker is what opens.
     */
    private fun openSessionFolder() {
        val tree = SessionRoot.current(this)?.takeIf { SessionRoot.isUsable(this) }
        if (tree == null) {
            pickSessionRoot.launch(null)
            return
        }
        val opened = runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(tree, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }.isSuccess
        // Not every device ships a documents app that answers ACTION_VIEW on a tree; the picker
        // rooted at the same folder is the fallback that always exists.
        if (!opened) runCatching { pickSessionRoot.launch(tree) }
    }

    private fun openSystemAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null),
                ),
            )
        }.onFailure { Log.w(TAG, "could not open app settings", it) }
    }

    /**
     * T-0.5 — the session root picker. `OpenDocumentTree` returns null when the user backs out,
     * which leaves the previous choice alone rather than clearing it.
     */
    private val pickSessionRoot = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null && SessionRoot.remember(this, uri)) {
            sessionRootLabel = SessionRoot.describe(this)
        logSize = FieldLog.sizeBytes()
        refreshPermissions()
        refreshMain()
        }
    }

    /** Surfaced on the landing screen so the storage in use is stated, not assumed. */
    private var sessionRootLabel by mutableStateOf("")

    /** T-3.18 — the main screen's session list, loaded off the launch path. */
    private var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
    private var sessionCount by mutableStateOf(0)
    private var freeBytes by mutableStateOf(0L)
    private var deviceTempC by mutableStateOf<Double?>(null)
    private var warningDismissed by mutableStateOf(false)

    /** T-0.6 — pulled on demand rather than polled; the file is the source of truth. */
    private var logTail by mutableStateOf<List<String>>(emptyList())
    private var logSize by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Session screens are read on a tripod in the dark; keeping the screen alive is the
        // behaviour the whole app wants, so it starts here rather than being bolted on later.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sessionRootLabel = SessionRoot.describe(this)

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

        // T-3.6's acceptance driven from adb. The `camera` foreground service type is
        // while-in-use restricted, so it has to be started from a visible Activity — which this
        // is, exactly as the real Start button will be:
        //   adb shell am start -n com.starstacker/.MainActivity --es diag capture \
        //       --ei frames 8 --ei exposureMs 1000 --ei iso 800 --ei darks 2
        // T-0.5's acceptance / OI-5. Needs no camera, so it sits ahead of the capture diagnostics:
        //   adb shell am start -n com.starstacker/.MainActivity --es diag storage \
        //       --ei files 200 --ei sizeMb 25
        // T-3.23: the openability probe lost its button, so it gets its own trigger rather than
        // relying on `autodiag`, whose `profile?.let { }` silently skips it — measured 2026-08-18,
        // where autodiag went straight to RawCapture and logged no openability line at all.
        //   adb shell am start -n com.starstacker/.MainActivity --es diag openability
        if (intent?.getStringExtra("diag") == "openability" && hasCameraPermission()) {
            lifecycleScope.launch { runOpenabilityTest(CameraProbe.probe(this@MainActivity)) }
        }

        // T-0.6's acceptance: force a crash and recover the log from the device.
        //   adb shell am start -n com.starstacker/.MainActivity --es diag crash
        // Deliberately on a background thread — the uncaught-exception handler has to work for
        // the capture thread, which is where a session actually dies, not just the main one.
        if (intent?.getStringExtra("diag") == "crash") crashForDiagnostics()

        if (intent?.getStringExtra("diag") == "storage") {
            val files = intent.getIntExtra("files", 200)
            val sizeMb = intent.getIntExtra("sizeMb", 24)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    StorageBenchmark.run(
                        store = SessionRoot.store(this@MainActivity),
                        files = files,
                        bytesEach = sizeMb * 1024 * 1024,
                    )
                }.onFailure { Log.e("StorageBenchmark", "benchmark failed", it) }
            }
        }

        if (intent?.getStringExtra("diag") == "capture" && hasCameraPermission()) {
            // `--ez resume true` continues the most recent interrupted session instead of
            // starting a new one, which is T-3.13's acceptance driven from adb.
            val resuming = intent.getBooleanExtra("resume", false)
            val store = SessionRoot.store(this)
            val interrupted = if (resuming) SessionRecovery.mostRecent(store) else null
            if (resuming && interrupted == null) {
                Log.i(TAG, "resume asked for, but no interrupted session was found")
                return
            }

            val request = interrupted?.let {
                CaptureEngine.Request(
                    cameraId = it.log.info.cameraId,
                    iso = it.log.info.plannedIso,
                    exposureNs = it.log.info.plannedExposureNs,
                    focusDiopters = it.log.info.focusDiopters,
                    lightCount = it.log.info.plannedLightCount,
                    darkCount = it.log.info.plannedDarkCount,
                )
            } ?: CaptureEngine.Request(
                cameraId = MAIN_CAMERA_ID,
                iso = intent.getIntExtra("iso", 800),
                exposureNs = intent.getIntExtra("exposureMs", 1000) * 1_000_000L,
                focusDiopters = null,
                lightCount = intent.getIntExtra("frames", 8),
                darkCount = intent.getIntExtra("darks", 0),
            )

            CaptureService.start(
                context = this,
                // Pointing is normally frozen at Start from the live compass, which the diag
                // path has no access to at onCreate. Passing it as strings lets the whole
                // transport — intent extras, SessionInfo, session.json, the DNG's GPS tags — be
                // exercised from adb without waiting for a sky:
                //   --es lat 51.5 --es lon -0.12 --es dec 22.3 --es compass HIGH
                request = request.copy(pointing = intent.diagPointing()),
                label = intent.getStringExtra("label") ?: "diag",
                resumeFolder = interrupted?.folderName,
            )
            Log.i(TAG, "capture service started${interrupted?.let { " — resuming ${it.describe()}" } ?: ""}")
            // Deliberately falls through to setContent: the live capture screen is a pure
            // function of the service's state, so starting a session from adb should land on
            // exactly the screen starting one from the button lands on.
        }

        // Modes handled above are not FieldDiagnostics' business; without this it logs
        // "unknown diag mode" for every one of them.
        val fieldDiag = intent?.getStringExtra("diag")
            ?.takeUnless { it in setOf("capture", "storage", "crash", "openability") }
        if (fieldDiag != null && hasCameraPermission()) {
            val frames = intent?.getIntExtra("frames", 12) ?: 12
            val exposureMs = intent?.getIntExtra("exposureMs", 1000) ?: 1000
            val iso = intent?.getIntExtra("iso", 3200) ?: 3200
            lifecycleScope.launch { runFieldDiagnostics(fieldDiag, frames, iso, exposureMs) }
        }

        locationGranted = pointingSource.hasLocationPermission()
        resumable = runCatching { SessionRecovery.mostRecent(sessionStore()) }.getOrNull()

        setContent {
            StarStackerTheme {
                val current = profile ?: return@StarStackerTheme
                val qualification = remember(current) { Qualification.qualifyDevice(current) }
                var exportedPath by remember { mutableStateOf<String?>(null) }
                var nav by rememberSaveable(stateSaver = BackStack.Saver) {
                    mutableStateOf(BackStack())
                }
                val screen = nav.current

                // T-0.3: without this the system back gesture leaves the app from any screen,
                // including mid-session. At the root it stays unhandled, which is where "back"
                // legitimately means "leave".
                BackHandler(enabled = nav.canGoBack) { nav = nav.pop() }
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
                    // Sensors run only on the screens that show their numbers. A magnetometer
                    // polled through a 45-minute capture is battery spent on a reading nobody is
                    // looking at — and the pointing that matters was fixed when Start was pressed.
                    if (screen !in setOf(Screen.FRAMING, Screen.SETUP) ||
                        !pointingSource.hasRequiredSensors()
                    ) {
                        return@LaunchedEffect
                    }
                    pointingSource.fixes().collect { pointing = it }
                }
                LaunchedEffect(pointing) { setup.setPointing(pointing) }

                // The session belongs to the service (D-6), so the screen reads its flow rather
                // than owning any of it, and shows the same thing after the Activity is recreated.
                val capture by CaptureService.progress.collectAsStateWithLifecycle()
                LaunchedEffect(capture.state) {
                    if (capture.state == SessionState.CAPTURING ||
                        capture.state == SessionState.DARKS ||
                        capture.state == SessionState.AWAITING_DARKS
                    ) {
                        nav = nav.enterCapture()
                    }
                }

                // Android draws edge-to-edge, so without this every screen's first row sits under
                // the clock and battery. Applied once here rather than per screen — measured on
                // device: the main screen's title and the settings gear were both occluded.
                Box(Modifier.systemBarsPadding()) {
                when (screen) {
                    Screen.MAIN -> MainScreen(
                        readiness = buildString {
                            append(current.cameras.firstOrNull { it.id == selectedCameraId }
                                ?.let { cam ->
                                    cam.focalLengthsMm.firstOrNull()
                                        ?.let { "Camera ${cam.id} · %.1fmm".format(it) }
                                        ?: "Camera ${cam.id}"
                                }
                                ?: "No camera selected")
                            append(" · ")
                            append(
                                when {
                                    framing.storedFocus == null -> "focus not stored"
                                    framing.storedFocus?.fixedFocus == true -> "fixed focus"
                                    else -> "focus stored"
                                },
                            )
                        },
                        // Below Start, never above it, and only when there is something true to
                        // say. Calibration warnings arrive with Phase 6.
                        warning = when {
                            warningDismissed -> null
                            qualification.bestTier == Tier.UNSUPPORTED -> MainWarning(
                                "This device cannot capture",
                                qualification.headline,
                                null,
                            )
                            !hasCameraPermission() -> MainWarning(
                                "Camera access not granted",
                                "Nothing can be captured until it is.",
                                "Settings",
                            )
                            else -> null
                        },
                        sessions = sessions,
                        totalSessions = sessionCount,
                        freeBytes = freeBytes,
                        deviceTempC = deviceTempC,
                        moonPercent = (Astro.moonIlluminatedFraction(System.currentTimeMillis()) * 100).toInt(),
                        canStart = hasCameraPermission() && qualification.bestTier != Tier.UNSUPPORTED,
                        onStart = {
                            askForNotificationsOnce()
                            nav = nav.push(Screen.FRAMING)
                        },
                        onOpenSettings = { nav = nav.push(Screen.SETTINGS) },
                        onOpenSessionFolder = { openSessionFolder() },
                        onAllSessions = { openSessionFolder() },
                        onDismissWarning = { warningDismissed = true },
                        resumable = resumable.takeIf { !CaptureService.running }?.describe(),
                        onResume = {
                            val session = resumable ?: return@MainScreen
                            CaptureService.start(
                                context = this@MainActivity,
                                request = CaptureEngine.Request(
                                    cameraId = session.log.info.cameraId,
                                    iso = session.log.info.plannedIso,
                                    exposureNs = session.log.info.plannedExposureNs,
                                    focusDiopters = session.log.info.focusDiopters,
                                    lightCount = session.log.info.plannedLightCount,
                                    darkCount = session.log.info.plannedDarkCount,
                                ),
                                label = "resumed",
                                resumeFolder = session.folderName,
                            )
                            resumable = null
                            nav = nav.enterCapture()
                        },
                        onDiscardResumable = {
                            val session = resumable ?: return@MainScreen
                            SessionRecovery.abandon(sessionStore(), session.folderName)
                            resumable = null
                        },
                    )

                    Screen.PROBE -> ProbeScreen(
                        profile = current,
                        qualification = qualification,
                        diagnostics = diagnostics.copy(
                            cameraPermissionGranted = hasCameraPermission(),
                        ),
                        onCaptureRaw = { exposureNs -> scope.launch { runCapture(exposureNs) } },
                        onOpenFraming = {
                            askForNotificationsOnce()
                            nav = nav.push(Screen.FRAMING)
                        },
                        onOpenSettings = { nav = nav.pop() },
                        resumable = resumable.takeIf { !CaptureService.running },
                        onResumeSession = {
                            val session = resumable ?: return@ProbeScreen
                            CaptureService.start(
                                context = this@MainActivity,
                                request = CaptureEngine.Request(
                                    cameraId = session.log.info.cameraId,
                                    iso = session.log.info.plannedIso,
                                    exposureNs = session.log.info.plannedExposureNs,
                                    focusDiopters = session.log.info.focusDiopters,
                                    lightCount = session.log.info.plannedLightCount,
                                    darkCount = session.log.info.plannedDarkCount,
                                ),
                                label = "resumed",
                                resumeFolder = session.folderName,
                            )
                            resumable = null
                            nav = nav.enterCapture()
                        },
                        onDiscardResumable = {
                            val session = resumable ?: return@ProbeScreen
                            SessionRecovery.abandon(sessionStore(), session.folderName)
                            resumable = null
                        },
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
                            nav = nav.toRoot()
                        },
                        onContinue = {
                            framing.stop()
                            current.cameras.firstOrNull { it.id == selectedCameraId }
                                ?.let { setup.select(it) }
                            nav = nav.push(Screen.SETUP)
                        },
                    )

                    Screen.SETUP -> SetupScreen(
                        controller = setup,
                        pointing = pointing,
                        onBack = { nav = nav.pop() },
                        onStart = {
                            val plan = setup.plan ?: return@SetupScreen
                            val camera = setup.camera ?: return@SetupScreen
                            CaptureService.start(
                                context = this@MainActivity,
                                request = CaptureEngine.Request(
                                    cameraId = camera.id,
                                    iso = plan.iso,
                                    exposureNs = (plan.subSeconds * 1e9).toLong(),
                                    focusDiopters = framing.storedFocus
                                        ?.takeIf { !it.fixedFocus }?.diopters,
                                    lightCount = plan.lightCount,
                                    darkCount = plan.darkCount,
                                    // Frozen here, at Start. The compass is not polled during
                                    // capture, and the pointing that matters is the one the
                                    // exposure was solved against — re-reading it an hour later
                                    // would describe a sky that has moved.
                                    pointing = pointing?.toSessionPointing(),
                                ),
                                label = "session",
                            )
                            nav = nav.enterCapture()
                        },
                    )

                    Screen.CAPTURE -> CaptureScreen(
                        progress = capture,
                        log = capture.log,
                        onPause = {
                            CaptureService.send(this@MainActivity, CaptureService.ACTION_PAUSE)
                        },
                        onResume = {
                            CaptureService.send(this@MainActivity, CaptureService.ACTION_RESUME)
                        },
                        onEndAndTakeDarks = {
                            CaptureService.send(
                                this@MainActivity, CaptureService.ACTION_END_AND_DARKS,
                            )
                        },
                        onConfirmDarks = {
                            CaptureService.send(
                                this@MainActivity, CaptureService.ACTION_CONFIRM_DARKS,
                            )
                        },
                        onSkipDarks = {
                            CaptureService.send(this@MainActivity, CaptureService.ACTION_SKIP_DARKS)
                        },
                        onDone = { nav = nav.toRoot() },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        sessionRoot = sessionRootLabel,
                        onPickSessionRoot = {
                            pickSessionRoot.launch(SessionRoot.current(this@MainActivity))
                        },
                        onOpenSessionFolder = { openSessionFolder() },
                        onOpenProbe = { nav = nav.push(Screen.PROBE) },
                        grantedPermissions = granted,
                        onRequestPermission = { requestPermission.launch(it) },
                        onOpenSystemSettings = { openSystemAppSettings() },
                        logTail = logTail,
                        logSizeBytes = logSize,
                        onRefreshLog = {
                            logTail = FieldLog.tail()
                            logSize = FieldLog.sizeBytes()
                        },
                        onShareLog = { shareFieldLog() },
                        onExportProfile = { exportedPath = exportAndShare(current) },
                        exportedPath = exportedPath,
                        onBack = { nav = nav.pop() },
                    )
                }
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
        diagnostics = diagnostics.copy(busy = "Opening each camera")
        val ids = profile.cameras.map { it.id }
        val results = CameraAccess(this).use { access ->
            withContext(Dispatchers.IO) { OpenabilityProbe.run(access, ids) }
        }
        diagnostics = diagnostics.copy(busy = null)
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

                        "solve" -> {
                            val camera = profile?.cameras?.firstOrNull { it.id == MAIN_CAMERA_ID }
                            if (camera == null) {
                                log("no profile for camera $MAIN_CAMERA_ID")
                            } else {
                                FieldDiagnostics.solveExposure(
                                    access = access,
                                    camera = camera,
                                    isos = isoLadder(camera.isoMin, camera.isoMax),
                                    exposureNs = exposureMs * 1_000_000L,
                                    log = log,
                                )
                            }
                        }

                        else -> log("unknown diag mode '$mode' — expected framing, focus, lens or solve")
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

    /** Full stops across the sensor's range — the ISOs a solve actually has to consider. */
    private fun isoLadder(min: Int?, max: Int?): List<Int> {
        val lo = (min ?: 50).coerceAtLeast(25)
        val hi = (max ?: 3200).coerceAtLeast(lo)
        val stops = mutableListOf<Int>()
        var iso = lo
        while (iso <= hi) {
            stops += iso
            iso *= 2
        }
        return stops
    }

    private fun sessionStore() = SessionRoot.store(this)

    /** Diagnostic-only pointing, supplied as strings so `am` needs no typed-extra flags. */
    private fun Intent.diagPointing(): SessionPointing? = SessionPointing(
        latitudeDeg = getStringExtra("lat")?.toDoubleOrNull(),
        longitudeDeg = getStringExtra("lon")?.toDoubleOrNull(),
        altitudeDeg = getStringExtra("alt")?.toDoubleOrNull(),
        azimuthTrueDeg = getStringExtra("az")?.toDoubleOrNull(),
        declinationDeg = getStringExtra("dec")?.toDoubleOrNull(),
        fieldRotationArcsecPerSec = getStringExtra("fieldRot")?.toDoubleOrNull(),
        compassAccuracy = getStringExtra("compass"),
    ).takeIf { !it.isEmpty }

    /**
     * The crash diagnostic has to be reachable while a session is *already running*, which is the
     * only state T-0.6's acceptance cares about — and by then the Activity exists, so a second
     * `am start` never re-enters `onCreate`.
     */
    /**
     * Reads the session list, free space and device temperature.
     *
     * Off the main thread because it parses one `session.json` per row — D-5's cached index is the
     * eventual answer and OI-5 wants the scan measured, but five logs is tolerable meanwhile.
     */
    private fun refreshMain() {
        lifecycleScope.launch(Dispatchers.IO) {
            val store = SessionRoot.store(this@MainActivity)
            val recent = runCatching { SessionCatalogue.recent(store) }.getOrDefault(emptyList())
            val total = runCatching { SessionCatalogue.count(store) }.getOrDefault(0)
            val free = runCatching { store.freeBytes() }.getOrDefault(0L)
            val temp = batteryTemperatureC()
            withContext(Dispatchers.Main) {
                sessions = recent
                sessionCount = total
                freeBytes = free
                deviceTempC = temp
            }
        }
    }

    /**
     * Battery temperature as the device's, per D-16's chain. Read directly rather than through
     * `DeviceEnvironment`, which would register a gyro listener to answer a question the main
     * screen asks once.
     */
    private fun batteryTemperatureC(): Double? = runCatching {
        registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10.0 }
    }.getOrNull()

    override fun onResume() {
        super.onResume()
        refreshMain()
        // Permissions can change outside the app — the system settings page is one tap away from
        // the settings screen, so the state has to be re-read rather than remembered.
        refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("diag") == "crash") crashForDiagnostics()
    }

    private fun crashForDiagnostics() {
        FieldLog.write("W", TAG, "diag crash requested - about to throw on a worker thread")
        lifecycleScope.launch(Dispatchers.IO) {
            error("deliberate T-0.6 crash from the capture-side thread")
        }
    }

    /** T-0.6's other half: a log nobody can send is a log nobody reads. */
    private fun shareFieldLog() {
        val file = FieldLog.currentFile() ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "StarStacker field log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share field log"))
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
