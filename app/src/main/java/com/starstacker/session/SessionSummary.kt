package com.starstacker.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T-3.18 — one line of a session, for the main screen's list.
 *
 * A summary rather than the log itself: a `session.json` for 105 frames is ~60 KB and the list
 * shows five of them. Carrying the frame arrays into the UI would mean holding megabytes to
 * display three numbers.
 *
 * **Not yet an index.** D-5 assumes a cached one will be necessary and OI-5 wants the scan cost
 * measured; until then this reads and discards, which is honest for the five most recent and will
 * not be for two hundred.
 */
data class SessionSummary(
    val folderName: String,
    val label: String,
    val startedAtEpochMs: Long,
    val lights: Int,
    val accepted: Int,
    val darks: Int,
    val integrationSeconds: Double,
    val state: SessionState,
    /**
     * Bytes on disk, or 0 when nobody has measured it.
     *
     * Zero rather than null because the main screen's five rows deliberately do not pay for it —
     * a directory walk per row on the launch path, to show a number the front door does not
     * display. The session pane (T-3.27) asks for it, states it per row, and puts it in the
     * deletion confirmation, which is the one place the figure changes what someone does.
     */
    val sizeBytes: Long = 0L,
) {
    /** `9 Aug · 142/150 · 28m 24s` — the prototype's second line. */
    fun describe(): String = buildString {
        append(DATE.format(Date(startedAtEpochMs)))
        append(" · $accepted/$lights")
        if (darks > 0) append(" · $darks darks")
        append(" · ${formatDuration(integrationSeconds)}")
    }

    /** The same line with the size on disk, for the pane where storage is half the point. */
    fun describeWithSize(): String =
        if (sizeBytes <= 0L) describe() else "${describe()} · ${formatBytes(sizeBytes)}"

    /** The clock time it started — what tells two sessions of one night apart. */
    fun startedAtClock(): String = TIME.format(Date(startedAtEpochMs))

    /**
     * What T-3.28's confirmation has to state before anything is deleted: how many frames, and
     * how much disk. Named rather than assembled at the call site so the single and the batch
     * confirmation cannot word it differently.
     */
    fun describeLoss(): String = buildString {
        append("$lights light")
        if (lights != 1) append("s")
        if (darks > 0) {
            append(", $darks dark")
            if (darks != 1) append("s")
        }
        if (sizeBytes > 0L) append(" · ${formatBytes(sizeBytes)}")
    }

    /**
     * The badge.
     *
     * The prototype's is an **action** — `Stack now` — which it cannot be yet: stacking is Phase 3,
     * and a button that does nothing is worse than a word that is true. Until then it states where
     * the session got to, and T-5.x turns it back into the action it was designed as.
     */
    val badge: String
        get() = when (state) {
            SessionState.DONE -> "Captured"
            SessionState.FAILED -> "Failed"
            SessionState.CAPTURING, SessionState.DARKS, SessionState.FINALISING -> "Running"
            SessionState.PAUSED, SessionState.AWAITING_DARKS -> "Unfinished"
            SessionState.IDLE, SessionState.FOCUSING -> "Empty"
        }

    val needsAttention: Boolean
        get() = state == SessionState.FAILED || state == SessionState.PAUSED ||
            state == SessionState.AWAITING_DARKS

    companion object {
        private val DATE = SimpleDateFormat("d MMM", Locale.getDefault())
        private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun formatDuration(seconds: Double): String {
            val total = seconds.toLong()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return when {
                h > 0 -> "%dh %02dm".format(h, m)
                m > 0 -> "%dm %02ds".format(m, s)
                else -> "%ds".format(s)
            }
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }

        fun of(folderName: String, log: SessionLog, sizeBytes: Long = 0L): SessionSummary =
            SessionSummary(
            folderName = folderName,
            // T-3.30 put the name in the log, so that is where it is read from. The two fallbacks
            // are for logs written before it existed and for folders that arrived from a PC: the
            // folder's own suffix, and failing that the start time — which was all the rows had
            // when every session was labelled "session" and two of them read "Session" and named
            // nothing (§1.15).
            label = log.info.label.takeIf { it.isNotBlank() }
                ?: folderName.substringAfterLast('_')
                    .takeIf { it.isNotBlank() && !it.equals("session", ignoreCase = true) }
                    ?.replaceFirstChar { it.uppercase() }
                ?: TIME.format(Date(log.info.startedAtEpochMs)),
            startedAtEpochMs = log.info.startedAtEpochMs,
            lights = log.lights.size,
            accepted = log.accepted.size,
            darks = log.darks.size,
            integrationSeconds = log.acceptedIntegrationSeconds,
            state = log.info.state,
            sizeBytes = sizeBytes,
        )
    }
}

/**
 * Reads the newest sessions off the store, newest first.
 *
 * [limit] exists because this is the launch path: the main screen wants five, and parsing every
 * log on a root with a season's worth of nights would be the slowest thing the app does.
 */
object SessionCatalogue {

    fun recent(store: SessionStore, limit: Int = 5): List<SessionSummary> =
        store.listSessions()
            .take(limit)
            .mapNotNull { name ->
                val folder = store.openSession(name) ?: return@mapNotNull null
                val text = folder.readText(SessionLayout.SESSION_JSON) ?: return@mapNotNull null
                runCatching { SessionSummary.of(name, SessionLog.decode(text)) }.getOrNull()
            }

    /** How many there are in total, for the `All sessions · 12` control. */
    fun count(store: SessionStore): Int = store.listSessions().size

    /**
     * T-3.27 — **every** session in the root, with the size on disk, newest first.
     *
     * This is the first thing in the app that reads every `session.json` rather than five, which
     * is exactly the cost **OI-5** exists to measure — so the scan times itself rather than
     * waiting for someone to instrument it later. [Scan.elapsedMs] is what decides whether D-5's
     * cached index is worth its second source of truth; a fast scan makes the index a liability,
     * because an index can be stale and a scan cannot.
     *
     * A folder whose log will not parse is **listed, not skipped** — [SessionSummary.of] cannot
     * describe it, so it comes back in [Scan.unreadable] and the pane says so. Dropping it
     * silently would make a damaged session invisible in the one screen built to find sessions,
     * which is the worst place to hide one: the DNGs are still there and still worth having.
     */
    fun all(store: SessionStore): Scan {
        val startNs = System.nanoTime()
        val summaries = mutableListOf<SessionSummary>()
        val unreadable = mutableListOf<String>()
        store.listSessions().forEach { name ->
            val folder = store.openSession(name)
            val log = folder?.readText(SessionLayout.SESSION_JSON)
                ?.let { runCatching { SessionLog.decode(it) }.getOrNull() }
            if (folder == null || log == null) {
                unreadable += name
                return@forEach
            }
            summaries += SessionSummary.of(
                folderName = name,
                log = log,
                sizeBytes = runCatching { folder.sizeBytes() }.getOrDefault(0L),
            )
        }
        return Scan(
            sessions = summaries,
            unreadable = unreadable,
            elapsedMs = (System.nanoTime() - startNs) / 1_000_000,
        )
    }

    /** One session's full log, for the detail screen's frame log and derivation. */
    fun log(store: SessionStore, folderName: String): SessionLog? {
        val folder = store.openSession(folderName) ?: return null
        val text = folder.readText(SessionLayout.SESSION_JSON) ?: return null
        return runCatching { SessionLog.decode(text) }.getOrNull()
    }

    /** Where the bytes are, as a person can read it — the detail screen's path row. */
    fun displayPath(store: SessionStore, folderName: String): String? =
        store.openSession(folderName)?.displayPath

    /** The scan, and what it cost. The cost is half the point — see [all] and **OI-5**. */
    data class Scan(
        val sessions: List<SessionSummary>,
        val unreadable: List<String> = emptyList(),
        val elapsedMs: Long = 0L,
    ) {
        val total: Int get() = sessions.size + unreadable.size
    }
}
