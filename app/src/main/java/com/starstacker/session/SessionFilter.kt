package com.starstacker.session

/**
 * T-6.2 — sorting and filtering the session list (FR-10.2).
 *
 * ### Why this is a value rather than a pile of lambdas
 *
 * A sort and a filter are the two things a list screen forgets across a rotation, a process death
 * and a trip into a detail screen. Keeping them in one serialisable value means the screen can
 * restore what someone had set up rather than resetting to newest-first every time they look at a
 * session and come back — which on a list of a night's work is the difference between a tool and a
 * toy.
 *
 * ### The ordering rules that are not obvious
 *
 * **Ties break by folder name, always.** Two sessions started in the same minute are common — the
 * app writes a session per target — and a sort whose result depends on filesystem enumeration
 * order would reshuffle the list every scan.
 *
 * **Sorting by label puts unlabelled sessions last, not first.** An unnamed session's label is its
 * start time ([SessionSummary.labelIsStartTime]), so sorting alphabetically would file them all
 * together under whatever hour they began — a block of "02:14, 02:31, 03:05" sitting in the middle
 * of the alphabet, which is neither useful nor what anyone means by "sort by target".
 */
data class SessionFilter(
    val sort: Sort = Sort.NEWEST,
    /** Free text matched against the label and the folder name, case-insensitively. */
    val query: String = "",
    /** Empty means every camera. */
    val cameras: Set<String> = emptySet(),
    /** Empty means every state. */
    val states: Set<SessionState> = emptySet(),
    val onlyStacked: Boolean = false,
    val onlyNeedingAttention: Boolean = false,
) {

    enum class Sort(val label: String) {
        NEWEST("Newest first"),
        OLDEST("Oldest first"),
        TARGET("Target"),
        CAMERA("Camera"),
        LONGEST("Most integration"),
        LARGEST("Largest on disk"),
    }

    val isDefault: Boolean
        get() = this == SessionFilter()

    /** One line for the UI, so an active filter is visible rather than mysterious. */
    fun describe(): String {
        if (isDefault) return sort.label
        val parts = mutableListOf(sort.label)
        if (query.isNotBlank()) parts += "“$query”"
        if (cameras.isNotEmpty()) parts += "camera ${cameras.sorted().joinToString("/")}"
        if (states.isNotEmpty()) parts += states.joinToString("/") { it.name.lowercase() }
        if (onlyStacked) parts += "stacked"
        if (onlyNeedingAttention) parts += "needs attention"
        return parts.joinToString(" · ")
    }

    fun apply(sessions: List<SessionSummary>): List<SessionSummary> =
        sessions.filter { matches(it) }.sortedWith(comparator())

    fun matches(session: SessionSummary): Boolean {
        if (query.isNotBlank()) {
            val needle = query.trim().lowercase()
            val haystack = session.label.lowercase() + " " + session.folderName.lowercase()
            if (!haystack.contains(needle)) return false
        }
        if (cameras.isNotEmpty() && session.cameraId !in cameras) return false
        if (states.isNotEmpty() && session.state !in states) return false
        if (onlyStacked && !session.stacked) return false
        if (onlyNeedingAttention && !session.needsAttention) return false
        return true
    }

    private fun comparator(): Comparator<SessionSummary> {
        // Every comparator ends with the folder name, so the order is total. Without it two
        // sessions that tie on the key would land in whatever order the filesystem enumerated
        // them, and the list would reshuffle on every scan.
        val tie = compareBy<SessionSummary> { it.folderName }
        return when (sort) {
            Sort.NEWEST -> compareByDescending<SessionSummary> { it.startedAtEpochMs }.then(tie)
            Sort.OLDEST -> compareBy<SessionSummary> { it.startedAtEpochMs }.then(tie)
            Sort.TARGET -> compareBy<SessionSummary>(
                // Unlabelled sessions sort last rather than under their start time — see the
                // class note on why alphabetical is wrong for them.
                { if (it.labelIsStartTime) 1 else 0 },
                { it.label.lowercase() },
            ).then(tie)
            Sort.CAMERA -> compareBy<SessionSummary> { it.cameraId }
                .thenByDescending { it.startedAtEpochMs }
                .then(tie)
            Sort.LONGEST -> compareByDescending<SessionSummary> { it.integrationSeconds }.then(tie)
            Sort.LARGEST -> compareByDescending<SessionSummary> { it.sizeBytes }.then(tie)
        }
    }

    companion object {
        /** The cameras present in a list, for offering only the ones that exist. */
        fun camerasIn(sessions: List<SessionSummary>): List<String> =
            sessions.map { it.cameraId }.filter { it.isNotBlank() }.distinct().sorted()

        /** The states present in a list, in the enum's own order rather than alphabetically. */
        fun statesIn(sessions: List<SessionSummary>): List<SessionState> =
            SessionState.entries.filter { state -> sessions.any { it.state == state } }
    }
}
