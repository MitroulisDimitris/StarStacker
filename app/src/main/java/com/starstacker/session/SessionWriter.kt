package com.starstacker.session

import java.io.OutputStream

/**
 * T-3.7 — the frame writer, and the one place that guarantees the log matches the disk.
 *
 * The ordering here is the whole point and it is deliberate: **the frame's bytes are written and
 * closed first, and only then is the log updated.** A log that mentions a frame which is not
 * there is worse than a frame which is not in the log — the first is a lie that a restack will
 * trip over, the second is a file the user can see and recover by hand. Crashing between the two
 * therefore leaves the recoverable failure rather than the corrupting one.
 *
 * `session.json` is rewritten in full after every frame ([SessionFolder.writeAtomically]). That is
 * O(n) serialisation per frame and O(n²) across a session, which sounds careless and is not: a
 * 200-frame log is some tens of KB, so the whole session spends a few MB of writes on it against
 * 5 GB of DNG. Appending to a log that must also be a valid JSON document would buy that back and
 * cost the property T-3.7 exists for.
 */
class SessionWriter(
    private val folder: SessionFolder,
    initial: SessionLog,
) {
    @Volatile
    var log: SessionLog = initial
        private set

    private val lock = Any()

    val displayPath: String get() = folder.displayPath

    /** Writes the opening log before any frame exists, so an empty session is still a session. */
    fun begin() {
        synchronized(lock) { flush() }
    }

    /**
     * Streams one frame to disk and records it.
     *
     * @param write receives the stream; the caller does the encoding (DngCreator writes straight
     *   into it, so a 25 MB frame is never held in memory twice).
     * @return the record as stored, with the file name the writer assigned.
     */
    fun writeFrame(
        kind: FrameKind,
        index: Int,
        record: (fileName: String) -> FrameRecord,
        write: (OutputStream) -> Unit,
    ): FrameRecord {
        val fileName = SessionLayout.frameName(kind, index)
        val directory = SessionLayout.directoryFor(kind)

        folder.createFrame(directory, fileName).use { write(it) }

        val entry = record(fileName)
        synchronized(lock) {
            log = log.withFrame(entry)
            flush()
        }
        return entry
    }

    /** Records a frame that was not written — a capture that failed after being requested. */
    fun recordWithoutFile(entry: FrameRecord) {
        synchronized(lock) {
            log = log.withFrame(entry)
            flush()
        }
    }

    fun setState(state: SessionState, finishedAtEpochMs: Long? = null) {
        synchronized(lock) {
            log = log.withState(state, finishedAtEpochMs)
            flush()
        }
    }

    fun update(transform: (SessionLog) -> SessionLog) {
        synchronized(lock) {
            log = transform(log)
            flush()
        }
    }

    private fun flush() {
        folder.writeAtomically(SessionLayout.SESSION_JSON, log.encode().toByteArray())
    }

    companion object {

        /**
         * T-3.13 — reopens a session left behind by a process that died, so it can be resumed
         * rather than lost (FR-6.4). Returns null when the folder holds no readable log.
         */
        fun resume(folder: SessionFolder): SessionWriter? {
            val text = folder.readText(SessionLayout.SESSION_JSON) ?: return null
            val log = runCatching { SessionLog.decode(text) }.getOrNull() ?: return null
            return SessionWriter(folder, log)
        }
    }
}
