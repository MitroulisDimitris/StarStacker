package com.starstacker.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.starstacker.session.SessionCatalogue
import com.starstacker.session.SessionLog
import com.starstacker.session.SessionRoot
import com.starstacker.session.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T-3.27 / T-3.28 — the state behind the session pane and one session's detail.
 *
 * ### Everything here is off the main thread, and that is not a formality
 *
 * This is the first screen in the app that reads **every** `session.json` in the root and sums the
 * bytes under every folder; the rest of the app reads five logs and no sizes. On a season's worth of
 * nights that is a real amount of I/O, and **OI-5** exists to find out how much — so [scanResult] carries
 * the elapsed time it cost, and [scanNote] states it on screen at the point where it stops being
 * acceptable. The measurement is what decides whether D-5's cached index is worth building: an
 * index is a second source of truth that can go stale, and a scan that takes 3 ms does not need
 * one.
 *
 * ### Deletion goes through a confirmation it cannot skip
 *
 * [askDelete] never deletes. It sets [pending], the pane draws what is about to be lost — frame
 * counts and size on disk, from [SessionSummary.describeLoss] — and only [confirmDelete] touches
 * the disk. One route for a single row and for a batch, so the two confirmations cannot word the
 * loss differently or, worse, one of them forget to state it (**D-26**).
 */
class SessionsController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** The whole root, newest first, and what the scan cost. Null until the first one finishes. */
    var scanResult: SessionCatalogue.Scan? by mutableStateOf(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var error: String? by mutableStateOf(null)
        private set

    /** The session whose detail screen is open, with the full log behind it. */
    var detail: Detail? by mutableStateOf(null)
        private set

    /**
     * The folder [open] is currently reading, if any.
     *
     * Needed because the read is asynchronous and navigation is not. `onOpen` pushes the detail
     * screen immediately, so without this the screen composes with [detail] still null, reads that
     * as "the log could not be read", and pops itself back off the stack before the load lands —
     * a tap that looks like it did nothing on a fast scan and flickers on a slow one. Three states,
     * not two: reading, read, and could not be read.
     */
    var opening: String? by mutableStateOf(null)
        private set

    /** What [askDelete] has queued up, and what the confirmation is describing. */
    var pending: Pending? by mutableStateOf(null)
        private set

    /** Set after a delete so the pane can say what happened rather than just showing fewer rows. */
    var lastAction: String? by mutableStateOf(null)
        private set

    val sessions: List<SessionSummary> get() = scanResult?.sessions.orEmpty()

    data class Detail(
        val summary: SessionSummary,
        val log: SessionLog,
        val displayPath: String?,
    )

    data class Pending(val targets: List<SessionSummary>) {
        val frames: Int get() = targets.sumOf { it.lights + it.darks }
        val bytes: Long get() = targets.sumOf { it.sizeBytes }

        /**
         * What is about to be lost, named. One session states its own line; a batch states the
         * total, because eleven separate lines is not something anyone reads before tapping.
         */
        fun describe(): String = when (targets.size) {
            0 -> "nothing selected"
            1 -> "${targets.first().label} · ${targets.first().describeLoss()}"
            else -> buildString {
                append("${targets.size} sessions · $frames frames")
                if (bytes > 0L) append(" · ${SessionSummary.formatBytes(bytes)}")
            }
        }
    }

    /**
     * Rescans the root.
     *
     * Always a full rescan rather than a patch of the list in memory: a folder can appear or
     * vanish because a PC put it there or took it away (FR-10.6.4), so the list this screen shows
     * is only ever as true as its last look at the disk.
     */
    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SessionCatalogue.all(SessionRoot.store(context)) }
            }
            loading = false
            result
                .onSuccess { scanResult = it; error = null }
                .onFailure {
                    Log.e(TAG, "session scan failed", it)
                    error = it.message ?: it::class.java.simpleName
                }
        }
    }

    /**
     * Loads one session's full log for the detail screen.
     *
     * [opening] is set **before** the coroutine suspends, so it is already true by the time the
     * screen that depends on it composes.
     */
    fun open(summary: SessionSummary) {
        detail = null
        opening = summary.folderName
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val store = SessionRoot.store(context)
                SessionCatalogue.log(store, summary.folderName)?.let { log ->
                    Detail(summary, log, SessionCatalogue.displayPath(store, summary.folderName))
                }
            }
            opening = null
            if (loaded == null) {
                error = "${summary.folderName} could not be read"
            } else {
                detail = loaded
                error = null
            }
        }
    }

    fun closeDetail() {
        detail = null
        opening = null
    }

    /** Queues a confirmation. **Never deletes** — see the class note. */
    fun askDelete(targets: List<SessionSummary>) {
        if (targets.isEmpty()) return
        lastAction = null
        pending = Pending(targets)
    }

    fun cancelDelete() {
        pending = null
    }

    /**
     * Deletes what [askDelete] queued, then rescans.
     *
     * Reports per-session rather than assuming success: a document provider can refuse a delete,
     * and a batch where two of eleven survived must say so. Anything left behind reappears in the
     * list on the rescan, which is the honest outcome — the pane shows what is on disk.
     */
    fun confirmDelete(onDeleted: (Int) -> Unit = {}) {
        val targets = pending?.targets ?: return
        pending = null
        loading = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val store = SessionRoot.store(context)
                targets.count { runCatching { store.deleteSession(it.folderName) }.getOrDefault(false) }
            }
            loading = false
            lastAction = when {
                outcome == targets.size && outcome == 1 -> "Deleted ${targets.first().label}"
                outcome == targets.size -> "Deleted $outcome sessions"
                outcome == 0 -> "Nothing could be deleted — the storage refused"
                else -> "Deleted $outcome of ${targets.size} — the rest could not be removed"
            }
            // The open detail screen may be describing a folder that is now gone.
            if (targets.any { it.folderName == detail?.summary?.folderName }) detail = null
            onDeleted(outcome)
            refresh()
        }
    }

    /**
     * **OI-5's readout**, shown in the pane rather than buried in a log.
     *
     * Only says anything once the scan is slow enough to matter. The threshold is deliberately low:
     * the point is to catch the cost growing before someone with 200 sessions finds it, and the
     * measured baseline is a 0.001 s scan of a 2-session root (§1.16) — so a scan taking a
     * noticeable fraction of a second is already the signal D-5's index was predicted for.
     */
    fun scanNote(): String? {
        val result = scanResult ?: return null
        if (result.elapsedMs < SLOW_SCAN_MS) return null
        return "read ${result.total} sessions in ${result.elapsedMs} ms"
    }

    private companion object {
        const val TAG = "SessionsController"

        /** Past this, the scan is worth stating — see [scanNote]. */
        const val SLOW_SCAN_MS = 250L
    }
}
