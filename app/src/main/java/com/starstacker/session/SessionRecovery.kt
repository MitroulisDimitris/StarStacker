package com.starstacker.session

/**
 * T-3.13 / FR-6.4 — an interrupted session is resumable rather than lost.
 *
 * The states this reasons about are the ones written to disk by [SessionWriter] as they happen,
 * which is the only place they could usefully live: the process that knew the session was running
 * is precisely the process that is no longer there. A session is resumable when its log says it
 * was still going, and the app has no other opinion on the matter — a folder that says
 * `CAPTURING` was captured by a process that never got to say otherwise.
 *
 * What "resume" means is deliberately narrow: **carry on filling the same folder from the frame
 * after the last one logged.** It does not re-derive an exposure or re-run focus, because the
 * whole value of resuming is that those decisions were already made under the sky that is still
 * up. [CaptureEngine][com.starstacker.capture.CaptureEngine] starts from `lights.size + 1` for
 * exactly this reason, so a resumed session needs no separate code path in the engine.
 */
object SessionRecovery {

    /** States that mean the session stopped without finishing. */
    private val INTERRUPTED = setOf(
        SessionState.CAPTURING,
        SessionState.PAUSED,
        SessionState.FOCUSING,
        SessionState.DARKS,
        SessionState.FINALISING,
    )

    data class Resumable(
        val folderName: String,
        val log: SessionLog,
    ) {
        val lightsRemaining: Int
            get() = (log.info.plannedLightCount - log.lights.size).coerceAtLeast(0)

        val darksRemaining: Int
            get() = (log.info.plannedDarkCount - log.darks.size).coerceAtLeast(0)

        /**
         * A session with nothing left to shoot is not worth offering. It stopped between its last
         * frame and being marked done, which is a bookkeeping gap, not lost sky.
         */
        val worthResuming: Boolean get() = lightsRemaining > 0 || darksRemaining > 0

        /** What the offer says, in the terms the user cares about. */
        fun describe(): String = buildString {
            append("${log.lights.size} of ${log.info.plannedLightCount} frames")
            if (log.darks.isNotEmpty()) append(", ${log.darks.size} darks")
            append(" · ${"%.0f".format(log.acceptedIntegrationSeconds / 60)} min banked")
            if (lightsRemaining > 0) append(" · $lightsRemaining left")
        }
    }

    /**
     * Every interrupted session in the root, newest first.
     *
     * Scans rather than consulting an index, per FR-10.6.4 and D-5 — a folder copied back from a
     * PC is as real as one this app wrote, and an index would be a second source of truth that is
     * wrong the moment the folder changes underneath it.
     */
    fun findInterrupted(store: SessionStore): List<Resumable> =
        store.listSessions().mapNotNull { name ->
            val folder = store.openSession(name) ?: return@mapNotNull null
            val text = folder.readText(SessionLayout.SESSION_JSON) ?: return@mapNotNull null
            val log = runCatching { SessionLog.decode(text) }.getOrNull() ?: return@mapNotNull null
            if (log.info.state !in INTERRUPTED) return@mapNotNull null
            Resumable(name, log)
        }.filter { it.worthResuming }

    /** The one to offer on startup: the most recent interrupted session, if there is one. */
    fun mostRecent(store: SessionStore): Resumable? =
        findInterrupted(store).maxByOrNull { it.log.info.startedAtEpochMs }

    /**
     * Marks a session abandoned so it stops being offered, without deleting anything (D-10).
     * The frames stay exactly where they are and remain stackable.
     */
    fun abandon(store: SessionStore, folderName: String) {
        val folder = store.openSession(folderName) ?: return
        val writer = SessionWriter.resume(folder) ?: return
        writer.setState(SessionState.FAILED, System.currentTimeMillis())
    }
}
