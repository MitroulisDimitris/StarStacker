package com.starstacker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.starstacker.MainActivity
import com.starstacker.camera.CameraAccess
import com.starstacker.exposure.ExposureSolver
import com.starstacker.core.AppContainer
import com.starstacker.session.SessionPointing
import com.starstacker.session.SessionRoot
import com.starstacker.session.SessionStore
import com.starstacker.session.SessionInfo
import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionLog
import com.starstacker.session.SessionState
import com.starstacker.session.SessionWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * T-3.6 / D-12 — the foreground service the unattended session lives in.
 *
 * **Why a service at all**, stated plainly because it was measured rather than assumed: on
 * 2026-08-17 an Activity-scoped capture loop was frozen within about five seconds of the screen
 * going off, its process still alive, with no display surface anywhere in the capture session
 * (§1.7, OI-20). D-22 removed the *surface* problem and could not remove the *lifecycle* one.
 * Nothing an Activity owns survives the screen going off.
 *
 * The type is `camera`, which is the only foreground service type with **no time limit** — the
 * `dataSync` and `mediaProcessing` types carry a 6 h budget and a mandatory `onTimeout()`, which
 * is fine for stacking and fatal for a session that runs all night. `camera` is while-in-use
 * restricted, so it must be started from a tap while the app is visible; the Start button is
 * exactly that, and once started it keeps running with the app gone.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var environment: DeviceEnvironment? = null
    private var access: CameraAccess? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { engine?.pause(); return START_STICKY }
            ACTION_RESUME -> { engine?.resume(); return START_STICKY }
            ACTION_END_AND_DARKS -> { engine?.endAndTakeDarks(); return START_STICKY }
            ACTION_CONFIRM_DARKS -> { engine?.confirmDarks(); return START_STICKY }
            ACTION_SKIP_DARKS -> { engine?.skipDarks(); return START_STICKY }
            ACTION_STOP -> { stopSequence(); return START_NOT_STICKY }
        }

        val request = intent?.toRequest() ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            notification("Starting session", "Preparing the camera"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            },
        )
        acquireWakeLock()
        begin(
            request,
            intent.getStringExtra(EXTRA_LABEL) ?: "session",
            intent.getStringExtra(EXTRA_RESUME),
        )
        return START_REDELIVER_INTENT
    }

    private fun begin(request: CaptureEngine.Request, label: String, resumeFolder: String?) {
        if (job?.isActive == true) return

        // T-0.7: the container owns construction. T-0.5: it hands back the user's chosen folder
        // when there is one and app-private storage otherwise — the engine never learns which.
        val container = AppContainer.from(this)
        val store = container.sessionStore()

        // T-3.13: resuming fills the *same* folder from the frame after the last one logged, and
        // does not re-derive the exposure or re-run focus — the value of resuming is that those
        // decisions were made under the sky that is still up.
        val writer = resumeFolder
            ?.let { store.openSession(it) }
            ?.let { SessionWriter.resume(it) }
            ?.also { Log.i(TAG, "resuming ${it.log.info.sessionId} at ${it.log.lights.size} frames") }
            ?: newSession(store, request, label)
        writer.begin()

        val env = container.deviceEnvironment().also { environment = it }
        val camera = container.cameraAccess().also { access = it }
        val created = CaptureEngine(camera, writer, env)
        engine = created
        _progress.value = created.progress.value

        job = scope.launch {
            launch {
                created.progress.collect { progress ->
                    _progress.value = progress
                    notifyProgress(progress)
                }
            }
            created.run(request)
            // The sequence has finished on its own. Drop out of the foreground so the
            // notification stops implying that something is still happening.
            stopSequence()
        }
    }

    private fun newSession(
        store: SessionStore,
        request: CaptureEngine.Request,
        label: String,
    ): SessionWriter {
        val startedAt = AppContainer.from(this).clock.nowEpochMs()
        val folderName = SessionLayout.folderName(startedAt, label)
        return SessionWriter(
            store.createSession(folderName),
            SessionLog(
                SessionInfo(
                    sessionId = folderName,
                    startedAtEpochMs = startedAt,
                    deviceModel = Build.MODEL,
                    cameraId = request.cameraId,
                    plannedIso = request.iso,
                    plannedExposureNs = request.exposureNs,
                    plannedLightCount = request.lightCount,
                    plannedDarkCount = request.darkCount,
                    focusDiopters = request.focusDiopters,
                    // FR-9.2: the declination is the one input to the sub length that leaves no
                    // trace in the result, so a log without it cannot say whether the trailing
                    // limit was relaxed or worst-cased at the equator.
                    latitudeDeg = request.pointing?.latitudeDeg,
                    longitudeDeg = request.pointing?.longitudeDeg,
                    altitudeDeg = request.pointing?.altitudeDeg,
                    azimuthDeg = request.pointing?.azimuthTrueDeg,
                    declinationDeg = request.pointing?.declinationDeg,
                    fieldRotationArcsecPerSec = request.pointing?.fieldRotationArcsecPerSec,
                    compassAccuracy = request.pointing?.compassAccuracy,
                ),
            ),
        )
    }

    private fun stopSequence() {
        engine?.stop()
        job?.cancel()
        job = null
        engine = null
        runCatching { environment?.close() }
        runCatching { access?.close() }
        environment = null
        access = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { environment?.close() }
        runCatching { access?.close() }
        releaseWakeLock()
        scope.cancel()
        engine = null
    }

    // --- notification ------------------------------------------------------------------

    private fun notifyProgress(progress: CaptureEngine.Progress) {
        val title = when (progress.state) {
            // The one notification that has to reach someone who has walked away.
            SessionState.AWAITING_DARKS -> "Cover the lens for darks"
            SessionState.DARKS -> "Darks ${progress.darksCaptured}"
            SessionState.PAUSED -> "Paused"
            SessionState.DONE -> "Session complete"
            SessionState.FAILED -> "Session failed"
            else -> "Frame ${progress.framesCaptured} of ${progress.target}"
        }
        if (progress.state == SessionState.AWAITING_DARKS) {
            notificationManager().notify(
                NOTIFICATION_ID,
                notification(
                    "Cover the lens for darks",
                    "Lights are done. Cover the lens, then tap to continue — or skip the darks.",
                ),
            )
            return
        }
        val detail = buildString {
            append("${progress.framesAccepted} accepted")
            progress.lastHfr?.let { append(" · HFR %.2f".format(it)) }
            progress.lastStarCount?.let { append(" · $it stars") }
            if (progress.cooling) append(" · cooling")
        }
        notificationManager().notify(NOTIFICATION_ID, notification(title, detail))
    }

    private fun notification(title: String, text: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun ensureChannel() {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Capture session",
                // Low: this notification is a status line for something the user started
                // deliberately and walked away from. It must not make a sound at 3 a.m.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress of an unattended capture session" },
        )
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * The screen going off is not the only way a long session dies — the CPU suspending is
     * another. A partial wake lock is the documented way to keep the process running while the
     * display sleeps, which is the entire operating mode of this feature.
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_SESSION_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /** NaN as the "absent" sentinel, matching how the nullable focus extra is already carried. */
private fun Intent.optDouble(key: String): Double? =
    getDoubleExtra(key, Double.NaN).takeIf { !it.isNaN() }

private fun Intent.toRequest(): CaptureEngine.Request? {
        val cameraId = getStringExtra(EXTRA_CAMERA_ID) ?: return null
        val iso = getIntExtra(EXTRA_ISO, -1).takeIf { it > 0 } ?: return null
        val exposure = getLongExtra(EXTRA_EXPOSURE_NS, -1L).takeIf { it > 0 } ?: return null
        return CaptureEngine.Request(
            cameraId = cameraId,
            iso = iso,
            exposureNs = exposure,
            focusDiopters = getFloatExtra(EXTRA_FOCUS, Float.NaN).takeIf { !it.isNaN() },
            lightCount = getIntExtra(EXTRA_LIGHTS, 0),
            darkCount = getIntExtra(EXTRA_DARKS, 0),
            pointing = SessionPointing(
                latitudeDeg = optDouble(EXTRA_LAT),
                longitudeDeg = optDouble(EXTRA_LON),
                altitudeDeg = optDouble(EXTRA_ALT),
                azimuthTrueDeg = optDouble(EXTRA_AZ),
                declinationDeg = optDouble(EXTRA_DEC),
                fieldRotationArcsecPerSec = optDouble(EXTRA_FIELD_ROT),
                compassAccuracy = getStringExtra(EXTRA_COMPASS),
            ).takeIf { !it.isEmpty },
        )
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "StarStacker:capture"

        /** Twelve hours. Longer than any night, and a bound rather than a promise. */
        private const val MAX_SESSION_MS = 12L * 60 * 60 * 1000

        const val ACTION_PAUSE = "com.starstacker.PAUSE"
        const val ACTION_RESUME = "com.starstacker.RESUME"
        const val ACTION_END_AND_DARKS = "com.starstacker.END_AND_DARKS"
        const val ACTION_CONFIRM_DARKS = "com.starstacker.CONFIRM_DARKS"
        const val ACTION_SKIP_DARKS = "com.starstacker.SKIP_DARKS"
        const val ACTION_STOP = "com.starstacker.STOP"

        private const val EXTRA_CAMERA_ID = "cameraId"
        private const val EXTRA_ISO = "iso"
        private const val EXTRA_EXPOSURE_NS = "exposureNs"
        private const val EXTRA_FOCUS = "focus"
        private const val EXTRA_LIGHTS = "lights"
        private const val EXTRA_DARKS = "darks"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_ALT = "alt"
        private const val EXTRA_AZ = "az"
        private const val EXTRA_DEC = "dec"
        private const val EXTRA_FIELD_ROT = "fieldRot"
        private const val EXTRA_COMPASS = "compass"

        /** Folder name of a session to continue filling, per T-3.13. */
        private const val EXTRA_RESUME = "resume"

        /**
         * The running engine, held statically because the UI is a *pure function of the session
         * state* (D-6) and may not exist while the session does. A ViewModel cannot own this:
         * the process that started the session is allowed to lose its Activity and keep shooting.
         */
        @Volatile
        private var engine: CaptureEngine? = null

        private val _progress = MutableStateFlow(CaptureEngine.Progress())
        val progress: StateFlow<CaptureEngine.Progress> = _progress.asStateFlow()

        val running: Boolean get() = engine != null

        /** @param resumeFolder T-3.13 — continue an interrupted session instead of starting one. */
        fun start(
            context: Context,
            request: CaptureEngine.Request,
            label: String,
            resumeFolder: String? = null,
        ) {
            val intent = Intent(context, CaptureService::class.java).apply {
                resumeFolder?.let { putExtra(EXTRA_RESUME, it) }
                putExtra(EXTRA_CAMERA_ID, request.cameraId)
                putExtra(EXTRA_ISO, request.iso)
                putExtra(EXTRA_EXPOSURE_NS, request.exposureNs)
                request.focusDiopters?.let { putExtra(EXTRA_FOCUS, it) }
                putExtra(EXTRA_LIGHTS, request.lightCount)
                putExtra(EXTRA_DARKS, request.darkCount)
                putExtra(EXTRA_LABEL, label)
                request.pointing?.let { p ->
                    p.latitudeDeg?.let { putExtra(EXTRA_LAT, it) }
                    p.longitudeDeg?.let { putExtra(EXTRA_LON, it) }
                    p.altitudeDeg?.let { putExtra(EXTRA_ALT, it) }
                    p.azimuthTrueDeg?.let { putExtra(EXTRA_AZ, it) }
                    p.declinationDeg?.let { putExtra(EXTRA_DEC, it) }
                    p.fieldRotationArcsecPerSec?.let { putExtra(EXTRA_FIELD_ROT, it) }
                    p.compassAccuracy?.let { putExtra(EXTRA_COMPASS, it) }
                }
            }
            context.startForegroundService(intent)
        }

        fun send(context: Context, action: String) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(action),
            )
        }
    }
}
