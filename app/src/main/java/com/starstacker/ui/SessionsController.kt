package com.starstacker.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.starstacker.session.SessionCatalogue
import com.starstacker.stacking.MultiNight
import com.starstacker.stacking.MasterVersions
import com.starstacker.session.StoragePlan
import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionFilter
import com.starstacker.calibration.Staleness
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

    /**
     * T-6.2 — the sort and filter, held here rather than in the composable.
     *
     * Survives navigation into a session and back, which is the trip that makes a filter kept in
     * the screen feel broken: someone narrows to one camera, opens a session, comes back, and the
     * list has forgotten.
     */
    var filter: SessionFilter by mutableStateOf(SessionFilter())
        private set

    /** What the list should actually draw. */
    val visibleSessions: List<SessionSummary> get() = filter.apply(sessions)

    /** T-6.1 — the list's thumbnails, decoded on demand. */
    val thumbnails = SessionThumbnails(SessionRoot.fileRoot(context), scope)

    /** T-6.7 — the whole root's usage, for the header. */
    val totals: StoragePlan.Totals get() = StoragePlan.totals(sessions)

    fun applyFilter(next: SessionFilter) {
        filter = next
    }

    fun clearFilter() {
        filter = SessionFilter()
    }

    /**
     * T-6.8 — whether a multi-session selection could be combined, and what it would cost.
     *
     * A *preview*, not an action: the composite stack itself is not built. Showing the verdict
     * anyway is worth it because the question "can these two nights go together" is answerable
     * from the logs alone, and the answer is usually no for a reason worth knowing.
     */
    fun combinePlan(targets: List<SessionSummary>): MultiNight.Plan? {
        if (targets.size < 2) return null
        return MultiNight.plan(targets.map { MultiNight.Night.of(it) })
    }

    data class Detail(
        val summary: SessionSummary,
        val log: SessionLog,
        val displayPath: String?,
        /** T-6.5 — every master this session has produced, and which one a reader sees. */
        val versions: MasterVersions.Index = MasterVersions.Index(),
        /** T-6.6 — whether the calibration behind the current master has changed since. */
        val staleness: Staleness.Report =
            Staleness.Report(Staleness.Verdict.UNKNOWN, emptyList()),
        /** T-6.7 — what this session costs and what could be freed. */
        val storage: StoragePlan.Plan? = null,
    ) {
        /** T-6.3 — frames the user has overridden, either way. */
        val overridden: Int get() = log.overridden.size

        /** T-6.5 — the two newest versions, for the comparison FR-10.4 asks for. */
        fun comparison(): Pair<MasterVersions.Version, MasterVersions.Version>? {
            val sorted = versions.versions.sortedByDescending { it.id }
            if (sorted.size < 2) return null
            return sorted[1] to sorted[0]
        }
    }

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
                .onSuccess { scanResult = it; error = null; thumbnails.clear() }
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
                    Detail(
                        summary = summary,
                        log = log,
                        displayPath = SessionCatalogue.displayPath(store, summary.folderName),
                        versions = readVersions(summary.folderName),
                        staleness = Staleness.compare(
                            recorded = log.info.calibrationVersions,
                            current = Staleness.versionsFor(calibrationRoot(), log.info.cameraId),
                        ),
                        storage = readStorage(store, summary, log),
                    )
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

    /**
     * T-6.3 — include or exclude one light frame by hand, and write it down.
     *
     * Written to `session.json` immediately rather than held until something is stacked: the log
     * is the source of truth (D-5), and an override that lived only in memory would be lost to the
     * next process death — after the person had already decided.
     */
    fun setFrameOverride(index: Int, include: Boolean?) {
        val current = detail ?: return
        val updated = current.log.withOverride(index, include)
        detail = current.copy(log = updated)
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    SessionRoot.store(context)
                        .openSession(current.summary.folderName)
                        ?.writeAtomically(SessionLayout.SESSION_JSON, updated.encode().toByteArray())
                    true
                }.getOrDefault(false)
            }
            if (!written) {
                // Put the screen back where the disk actually is. A toggle that looks like it
                // stuck and did not is worse than one that visibly failed.
                detail = current
                error = "the override could not be saved"
            }
        }
    }

    /** T-6.5 — show a different version, without restacking anything. */
    fun selectVersion(id: Int) {
        val current = detail ?: return
        scope.launch {
            val index = withContext(Dispatchers.IO) {
                sessionDir(current.summary.folderName)
                    ?.let { runCatching { MasterVersions.makeCurrent(it, id) }.getOrNull() }
            }
            if (index != null) detail = current.copy(versions = index)
        }
    }

    fun deleteVersion(id: Int) {
        val current = detail ?: return
        scope.launch {
            val index = withContext(Dispatchers.IO) {
                sessionDir(current.summary.folderName)
                    ?.let { runCatching { MasterVersions.delete(it, id) }.getOrNull() }
            }
            if (index != null) {
                detail = current.copy(versions = index)
                lastAction = if (index.versions.any { it.id == id }) {
                    "the last version cannot be deleted"
                } else {
                    "deleted v$id"
                }
            }
        }
    }

    /** What [askStorageAction] has queued. Nothing is deleted until it is confirmed. */
    var pendingStorage: StoragePlan.Action? by mutableStateOf(null)
        private set

    /**
     * T-6.7 — queues a partial deletion. **Never deletes**, exactly as [askDelete] does not.
     *
     * One confirmation route for whole sessions and for parts of them, so the two cannot word the
     * loss differently or one of them forget to state it (D-26).
     */
    fun askStorageAction(action: StoragePlan.Action) {
        if (!action.available) return
        lastAction = null
        pendingStorage = action
    }

    fun cancelStorageAction() {
        pendingStorage = null
    }

    fun confirmStorageAction() {
        val action = pendingStorage ?: return
        val current = detail ?: return
        pendingStorage = null

        // Named directories, never a pattern (section 1.29).
        val directories = when {
            action.label.startsWith("Delete subs") ->
                listOf(SessionLayout.LIGHTS, SessionLayout.DARKS)
            action.label.startsWith("Delete darks") -> listOf(SessionLayout.DARKS)
            action.label.startsWith("Delete the masters") -> listOf(SessionLayout.MASTER)
            else -> return
        }

        loading = true
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                val store = SessionRoot.store(context)
                val folder = store.openSession(current.summary.folderName)
                folder != null && directories.all {
                    runCatching { folder.deleteDirectory(it) }.getOrDefault(false)
                }
            }
            loading = false
            lastAction = if (removed) {
                "${action.label} — freed ${SessionSummary.formatBytes(action.bytes)}"
            } else {
                "nothing could be deleted — the storage refused"
            }
            refresh()
            open(current.summary)
        }
    }

    private fun sessionDir(folderName: String): java.io.File? =
        SessionRoot.fileRoot(context).let { root -> java.io.File(root, folderName) }
            .takeIf { it.isDirectory }

    private fun calibrationRoot(): java.io.File? =
        context.getExternalFilesDir(null) ?: context.filesDir

    private fun readVersions(folderName: String): MasterVersions.Index =
        sessionDir(folderName)
            ?.let { runCatching { MasterVersions.read(it) }.getOrNull() }
            ?: MasterVersions.Index()

    private fun readStorage(
        store: com.starstacker.session.SessionStore,
        summary: SessionSummary,
        log: SessionLog,
    ): StoragePlan.Plan? {
        val folder = store.openSession(summary.folderName) ?: return null
        return runCatching {
            val lights = folder.sizeBytes(SessionLayout.LIGHTS)
            val darks = folder.sizeBytes(SessionLayout.DARKS)
            val masters = folder.sizeBytes(SessionLayout.MASTER)
            StoragePlan.of(
                folderName = summary.folderName,
                lightBytes = lights,
                darkBytes = darks,
                masterBytes = masters,
                otherBytes = (summary.sizeBytes - lights - darks - masters).coerceAtLeast(0L),
                hasMaster = masters > 0 && log.info.stacking.isNotEmpty(),
                state = log.info.state,
            )
        }.getOrNull()
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
