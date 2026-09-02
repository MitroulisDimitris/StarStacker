package com.starstacker.stacking

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
import android.util.Log
import com.starstacker.MainActivity
import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T-6.4 / **D-12** / FR-10.3 — the foreground service stacking runs in.
 *
 * ### A different service from capture, and a different type
 *
 * D-12 splits these deliberately. Capture uses the `camera` type, which is the only one with **no
 * time limit** — an all-night session needs that. Stacking uses `mediaProcessing` on API 35+ and
 * `dataSync` on 34, both of which carry a **6 h budget and a mandatory `onTimeout()`**: a service
 * that does not stop itself when called there is killed with a `RemoteServiceException`, which is
 * a crash in the user's face rather than a graceful stop.
 *
 * Two services rather than one also means a stack can run while nothing is being captured, and
 * that neither inherits the other's constraints — `camera` is while-in-use restricted, and there
 * is no reason a stack should be.
 *
 * ### Never unprompted, which is also what buys the full budget
 *
 * FR-10.3.3: the app *suggests* a good moment and never starts on its own. That is a product rule
 * — the phone is warm, the battery is finite, and a stack the user did not ask for is the app
 * misbehaving. There is a happy accident underneath it: the platform gives a service started from
 * direct user interaction the full 6 h budget, so the rule that makes the app polite is the same
 * rule that makes it capable.
 *
 * ### The queue, and what "resumable" means here
 *
 * FR-10.3.4 wants several sessions queued and run in order. The queue is held here and survives
 * the Activity, so leaving the app does not lose it.
 *
 * **A timeout stops between sessions, not inside one.** T-6.4's acceptance is that a stop mid-queue
 * *resumes rather than restarts*, and that is what is built: the sessions not yet started are kept
 * and the whole queue is re-offered. Checkpointing inside a single stack — writing partial tiles
 * and picking them up — is not done, so an interrupted session restacks from the beginning. That
 * is an honest limit rather than an oversight: a 150-frame stack is minutes, the 6 h budget is
 * not going to end inside one, and partial masters are the exact thing §1.32 refuses to write.
 */
class StackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelAll()
            return START_NOT_STICKY
        }

        val folders = intent?.getStringArrayExtra(EXTRA_SESSIONS)?.toList().orEmpty()
        if (folders.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        queue.addAll(folders)
        settings = intent?.toSettings() ?: StackSettings()

        startForeground(
            NOTIFICATION_ID,
            notification("Stacking", "Preparing"),
            foregroundType(),
        )

        if (job?.isActive != true) {
            cancelRequested.set(false)
            job = scope.launch { drain() }
        }
        return START_REDELIVER_INTENT
    }

    private fun foregroundType(): Int = when {
        Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    /**
     * The platform's six-hour warning. **Not optional**: a service that does not stop itself here
     * is killed with a `RemoteServiceException`.
     *
     * What is in flight is abandoned rather than finished — there is no time to finish it — but the
     * queue behind it survives in [pending], so the UI can offer to carry on.
     */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "foreground service budget reached with ${queue.size} sessions left")
        cancelRequested.set(true)
        _progress.value = _progress.value.copy(
            state = StackJob.State.CANCELLED,
            message = "Stopped at the 6 hour limit — ${queue.size + 1} left to stack",
        )
        stopSelf()
    }

    private fun drain() {
        val root = SessionRoot.fileRoot(this)
        while (true) {
            if (cancelRequested.get()) break
            val folder = queue.poll() ?: break
            val dir = File(root, folder)
            if (!dir.isDirectory || !File(dir, SessionLayout.SESSION_JSON).isFile) {
                // Almost always T-0.5 rather than a missing folder: a session written through SAF
                // lives somewhere this cannot reach as a File, and saying "not found" would send
                // the user looking for the wrong thing.
                val why = if (SessionRoot.isUsable(this)) {
                    "$folder is in the folder you picked, which stacking cannot open yet (T-0.5)"
                } else {
                    "$folder is not a session folder"
                }
                Log.w(TAG, "skipping: $why")
                _results.value = _results.value +
                    StackJob.Result(StackJob.State.FAILED, error = why)
                continue
            }

            val job = StackJob(dir, settings, Resample, com.starstacker.edit.BitmapJpeg)
            val result = job.run(
                cancelled = { cancelRequested.get() },
                onProgress = { p ->
                    _progress.value = Progress(
                        state = p.state,
                        sessionName = p.sessionName,
                        tile = p.tile,
                        tiles = p.tiles,
                        message = p.message,
                        queued = queue.size,
                    )
                    notify(p)
                },
            )
            // T-7.6 / FR-9.3: the picture goes where the phone keeps pictures. Only from the
            // service, not from StackJob — the diagnostic has no business writing to the gallery,
            // and a JVM test has no MediaStore to write to.
            result.previewFile?.let { jpeg ->
                com.starstacker.edit.Gallery.publish(this, jpeg, folder)
                    ?: Log.w(TAG, "could not publish $folder to the gallery")
            }
            _results.value = _results.value + result
            Log.i(TAG, "$folder: ${result.state}${result.error?.let { " — $it" } ?: ""}")
        }
        finish()
    }

    private fun finish() {
        val outcome = _results.value
        val done = outcome.count { it.succeeded }
        _progress.value = _progress.value.copy(
            state = if (cancelRequested.get()) StackJob.State.CANCELLED else StackJob.State.DONE,
            message = when {
                cancelRequested.get() -> "Cancelled after $done"
                done == outcome.size -> "Stacked $done"
                else -> "Stacked $done of ${outcome.size}"
            },
        )
        // The completion notification is left behind on purpose: the user was told to walk away,
        // so the result has to be there when they come back rather than vanishing with the
        // service. FR-9.4 — never make them hunt for where the output went.
        stopForeground(STOP_FOREGROUND_DETACH)
        notifyFinished()
        stopSelf()
    }

    private fun cancelAll() {
        cancelRequested.set(true)
        queue.clear()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        running = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------ notification

    private fun channel(): NotificationManager {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Stacking", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return manager
    }

    private fun notification(title: String, text: String, percent: Int = -1): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(percent >= 0)
        if (percent >= 0) builder.setProgress(100, percent, false)
        builder.addAction(
            Notification.Action.Builder(
                null,
                "Cancel",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, StackingService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build(),
        )
        return builder.build()
    }

    private fun notify(progress: StackJob.Progress) {
        val remaining = queue.size
        val title = if (remaining > 0) {
            "Stacking ${progress.sessionName} · $remaining more"
        } else {
            "Stacking ${progress.sessionName}"
        }
        channel().notify(NOTIFICATION_ID, notification(title, progress.message, progress.percent))
    }

    private fun notifyFinished() {
        channel().notify(
            NOTIFICATION_ID,
            notification("Stacking finished", _progress.value.message),
        )
    }

    private fun Intent.toSettings() = StackSettings(
        method = getStringExtra(EXTRA_METHOD)
            ?.let { name -> Combine.Method.entries.firstOrNull { it.name == name } }
            ?: Combine.Method.SIGMA_CLIP,
        crop = LinearMaster.Crop.of(getStringExtra(EXTRA_CROP)),
    )

    companion object {
        private const val TAG = "StackingService"
        private const val CHANNEL_ID = "stacking"
        private const val NOTIFICATION_ID = 2

        const val ACTION_CANCEL = "com.starstacker.STACK_CANCEL"
        private const val EXTRA_SESSIONS = "sessions"
        private const val EXTRA_METHOD = "method"
        private const val EXTRA_CROP = "crop"

        /**
         * Held statically for the same reason `CaptureService`'s engine is (**D-6**): the UI is a
         * pure function of this state and may not exist while the work does. A stack is minutes
         * long and the user was explicitly invited to leave the app during it.
         */
        private val queue = ConcurrentLinkedQueue<String>()
        private val cancelRequested = AtomicBoolean(false)

        @Volatile
        private var settings: StackSettings = StackSettings()

        @Volatile
        var running: Boolean = false
            private set

        private val _progress = MutableStateFlow(Progress())
        val progress: StateFlow<Progress> = _progress.asStateFlow()

        private val _results = MutableStateFlow<List<StackJob.Result>>(emptyList())
        val results: StateFlow<List<StackJob.Result>> = _results.asStateFlow()

        /** Sessions accepted and not yet started — what a timeout leaves behind to re-offer. */
        val pending: List<String> get() = queue.toList()

        /**
         * Starts a stack. **Only ever from a tap** (FR-10.3.3), which is also what buys the full
         * six-hour budget.
         */
        fun start(context: Context, sessions: List<String>, settings: StackSettings) {
            if (sessions.isEmpty()) return
            _results.value = emptyList()
            running = true
            _progress.value = Progress(
                state = StackJob.State.PREPARING,
                sessionName = sessions.first(),
                message = "Queued ${sessions.size}",
                queued = sessions.size - 1,
            )
            context.startForegroundService(
                Intent(context, StackingService::class.java).apply {
                    putExtra(EXTRA_SESSIONS, sessions.toTypedArray())
                    putExtra(EXTRA_METHOD, settings.method.name)
                    putExtra(EXTRA_CROP, settings.crop.name)
                },
            )
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, StackingService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }

    /** [StackJob.Progress] plus how many sessions are still behind this one. */
    data class Progress(
        val state: StackJob.State = StackJob.State.DONE,
        val sessionName: String = "",
        val tile: Int = 0,
        val tiles: Int = 0,
        val message: String = "",
        val queued: Int = 0,
    ) {
        val percent: Int
            get() = StackJob.Progress(state, sessionName, tile, tiles, message).percent

        val active: Boolean
            get() = state == StackJob.State.PREPARING ||
                state == StackJob.State.STACKING ||
                state == StackJob.State.WRITING
    }
}
