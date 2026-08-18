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
) {
    /** `9 Aug · 142/150 · 28m 24s` — the prototype's second line. */
    fun describe(): String = buildString {
        append(DATE.format(Date(startedAtEpochMs)))
        append(" · $accepted/$lights")
        if (darks > 0) append(" · $darks darks")
        append(" · ${formatDuration(integrationSeconds)}")
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

        fun of(folderName: String, log: SessionLog): SessionSummary = SessionSummary(
            folderName = folderName,
            // The folder is `<date>_<time>_<label>`; the label is the only name a session has
            // until targets become a feature of their own.
            label = folderName.substringAfterLast('_').replaceFirstChar { it.uppercase() },
            startedAtEpochMs = log.info.startedAtEpochMs,
            lights = log.lights.size,
            accepted = log.accepted.size,
            darks = log.darks.size,
            integrationSeconds = log.acceptedIntegrationSeconds,
            state = log.info.state,
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
}
