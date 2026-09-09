package com.starstacker.session

/**
 * T-6.7 / FR-10.6.2 — what a session is costing, and what can safely be dropped.
 *
 * ### The one that matters: subs are irreplaceable and masters are not
 *
 * A master is **derived**. It can be rebuilt from the subs at any time, with better calibration or
 * different settings, and FR-10.4 exists precisely so that it can be. The subs cannot be rebuilt
 * from anything — they are a night that has happened.
 *
 * So "delete subs, keep masters" is a **one-way door**, and this treats it as one. It is offered
 * only where a master already exists, it says exactly what would be lost, and it never runs
 * without being asked. That is also why the numbers below are computed and returned rather than
 * acted on: this file decides nothing.
 *
 * *The memory this is written against:* 5 GB of irreplaceable field data was deleted on 2026-08-19
 * by a glob that matched more than it meant to (§1.29). Nothing here takes a pattern.
 */
object StoragePlan {

    /** What a single action would free, and what it would cost. */
    data class Action(
        val label: String,
        val bytes: Long,
        /** True when what it removes can be rebuilt from what stays. */
        val reversible: Boolean,
        /** Why it cannot be offered, or null when it can. */
        val blockedBecause: String? = null,
    ) {
        val available: Boolean get() = blockedBecause == null && bytes > 0

        fun describe(): String = buildString {
            append(label)
            append(" · ")
            append(SessionSummary.formatBytes(bytes))
            if (!reversible) append(" · cannot be undone")
            blockedBecause?.let { append(" · $it") }
        }
    }

    data class Plan(
        val folderName: String,
        val totalBytes: Long,
        val lightBytes: Long,
        val darkBytes: Long,
        val masterBytes: Long,
        val actions: List<Action>,
    ) {
        fun describe(): String =
            "$folderName · ${SessionSummary.formatBytes(totalBytes)} " +
                "(${SessionSummary.formatBytes(lightBytes)} lights, " +
                "${SessionSummary.formatBytes(darkBytes)} darks, " +
                "${SessionSummary.formatBytes(masterBytes)} masters)"

        /** The action a UI should offer first, or null when nothing is worth offering. */
        val best: Action? get() = actions.filter { it.available }.maxByOrNull { it.bytes }
    }

    /**
     * Builds the plan.
     *
     * Sizes are supplied rather than measured here so this stays pure and testable; the caller
     * already walks the folder for [SessionSummary.sizeBytes] and knows the layout.
     */
    fun of(
        folderName: String,
        lightBytes: Long,
        darkBytes: Long,
        masterBytes: Long,
        otherBytes: Long = 0L,
        hasMaster: Boolean,
        state: SessionState,
    ): Plan {
        val total = lightBytes + darkBytes + masterBytes + otherBytes

        val subsAction = when {
            !hasMaster -> Action(
                "Delete subs, keep the master",
                lightBytes + darkBytes,
                reversible = false,
                // The whole safety property in one clause: without a master, deleting the subs
                // deletes the session. There is nothing left to have been kept.
                blockedBecause = "there is no master yet — this would delete the whole session",
            )
            state == SessionState.CAPTURING -> Action(
                "Delete subs, keep the master",
                lightBytes + darkBytes,
                reversible = false,
                blockedBecause = "this session is still capturing",
            )
            else -> Action(
                "Delete subs, keep the master",
                lightBytes + darkBytes,
                reversible = false,
            )
        }

        val darksAction = when {
            !hasMaster -> Action(
                "Delete darks, keep the lights",
                darkBytes,
                reversible = false,
                blockedBecause = "the darks are still needed to stack this session",
            )
            else -> Action(
                // Narrower than the above and worth offering separately: the darks are already
                // baked into the master, and a restack of the *lights* with a library dark is a
                // real workflow. Dropping the darks keeps that possible; dropping the lights does
                // not.
                "Delete darks, keep the lights",
                darkBytes,
                reversible = false,
            )
        }

        val mastersAction = Action(
            "Delete the masters, keep the subs",
            masterBytes,
            // The only reversible one here, and the reason the distinction is worth drawing: a
            // master is derived, so deleting it costs time rather than data.
            reversible = true,
            blockedBecause = if (masterBytes <= 0) "there are no masters to delete" else null,
        )

        return Plan(
            folderName = folderName,
            totalBytes = total,
            lightBytes = lightBytes,
            darkBytes = darkBytes,
            masterBytes = masterBytes,
            actions = listOf(subsAction, darksAction, mastersAction),
        )
    }

    /** Across a whole root, for the "total usage" half of FR-10.6.2. */
    data class Totals(
        val sessions: Int,
        val totalBytes: Long,
        val stackedBytes: Long,
        val unstackedBytes: Long,
    ) {
        /**
         * What could be freed by dropping every stacked session's subs.
         *
         * Reported as a single number because that is the question someone with a full phone
         * actually asks — and never acted on in bulk, for the reason in the class note.
         */
        val reclaimableBytes: Long get() = stackedBytes

        fun describe(): String =
            "$sessions session(s) · ${SessionSummary.formatBytes(totalBytes)}" +
                if (reclaimableBytes > 0) {
                    " · up to ${SessionSummary.formatBytes(reclaimableBytes)} in stacked sessions"
                } else {
                    ""
                }
    }

    fun totals(sessions: List<SessionSummary>): Totals = Totals(
        sessions = sessions.size,
        totalBytes = sessions.sumOf { it.sizeBytes },
        stackedBytes = sessions.filter { it.stacked }.sumOf { it.sizeBytes },
        unstackedBytes = sessions.filterNot { it.stacked }.sumOf { it.sizeBytes },
    )
}
