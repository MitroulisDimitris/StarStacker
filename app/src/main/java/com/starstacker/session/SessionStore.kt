package com.starstacker.session

import java.io.File
import java.io.OutputStream

/**
 * T-0.5 / T-3.8 — where a session's bytes go, behind an interface that hides whether they are
 * going through SAF or through a plain file path.
 *
 * The interface exists for two reasons that are worth separating. The obvious one is that FR-9.1
 * wants a user-chosen root via `ACTION_OPEN_DOCUMENT_TREE`, and SAF is unpleasant enough that the
 * capture engine should not have to know about it. The load-bearing one is the plan's warning:
 * **do not use `DocumentFile` for per-frame work.** `DocumentFile.findFile()` is a linear scan of
 * the directory per call, and a session writing 150 frames into a folder that already holds 150
 * frames turns writing into a quadratic crawl. So nothing here looks a file up by name to write
 * it — a create returns a stream, and the identity of what was created is remembered by the
 * caller, which is what `session.json` is for anyway.
 */
interface SessionStore {

    /** Something a user can read and find on a PC. Not a URI. */
    val displayPath: String

    /** Free space on the volume, bytes — the input to FR-5.4's storage warning. */
    fun freeBytes(): Long

    /** Creates the folder tree of FR-9.1 and returns a handle to it. */
    fun createSession(folderName: String): SessionFolder

    /** Opens an existing session folder, or null when it is not there. */
    fun openSession(folderName: String): SessionFolder?

    /**
     * FR-10.6.4 — sessions are discovered by scanning the root, so that a folder copied back
     * from a PC is as real as one this app created. This is the only listing operation, and it
     * is not on the per-frame path.
     */
    fun listSessions(): List<String>

    /**
     * T-3.28 / **D-26** — removes a session folder and everything under it.
     *
     * D-10 says the app never deletes, and this does not contradict it: D-10 is about the app's
     * own judgement, so a frame the gate rejected still stays on disk forever. A person clearing
     * their own 3.6 GB session is a different act, and refusing it is not honouring D-10 but
     * hiding behind it.
     *
     * **Whole sessions only.** [folderName] must be a plain child of the root — the guard is in
     * the implementations, and it is there because this is the one method in the app that can
     * destroy a night's work. Returns false when nothing was removed.
     */
    fun deleteSession(folderName: String): Boolean
}

/** One session's folder. */
interface SessionFolder {

    val name: String
    val displayPath: String

    /**
     * A stream for a new frame. Called once per frame with a name the caller already knows, so
     * there is never a lookup — see the note on [SessionStore].
     */
    fun createFrame(directory: String, fileName: String): OutputStream

    /**
     * Writes a small file so that a reader either sees the previous contents or the new ones,
     * never a half-written mixture.
     *
     * `session.json` is rewritten after **every frame** (T-3.7), so this runs 150+ times a
     * session while a process that may be killed at any moment is doing the writing. The cost is
     * irrelevant — tens of KB against 25 MB per frame — but the atomicity is not: a torn
     * `session.json` would orphan every DNG beside it, which is the exact failure the incremental
     * write exists to prevent.
     */
    fun writeAtomically(fileName: String, bytes: ByteArray)

    fun readText(fileName: String): String?

    fun listFrames(directory: String): List<String>

    /**
     * Bytes on disk, summed over the frames and the log — what T-3.28's confirmation states and
     * what the session pane shows per row.
     *
     * Enumerates the folder, so it is not on the per-frame path (see [SessionStore]) and the
     * session pane loads it off the main thread.
     */
    fun sizeBytes(): Long
}

/**
 * The plain-file implementation, over `getExternalFilesDir` or any other real path.
 *
 * Not a stopgap for testing: this path is reachable from a PC over USB as
 * `Android/data/com.starstacker/files/...`, so it satisfies FR-9.1's actual concern — that the
 * user can move 6 GB off the phone without the app's help — while the SAF implementation lands.
 * It is also the only implementation the JVM tests can use, which keeps the layout and the
 * incremental-write behaviour testable without a device.
 */
class FileSessionStore(private val root: File) : SessionStore {

    override val displayPath: String get() = root.absolutePath

    override fun freeBytes(): Long = runCatching { root.usableSpace }.getOrDefault(0L)

    override fun createSession(folderName: String): SessionFolder {
        val dir = File(root, folderName)
        dir.mkdirs()
        SessionLayout.DIRECTORIES.forEach { File(dir, it).mkdirs() }
        return FileSessionFolder(dir)
    }

    override fun openSession(folderName: String): SessionFolder? {
        val dir = File(root, folderName)
        return if (dir.isDirectory) FileSessionFolder(dir) else null
    }

    override fun listSessions(): List<String> =
        root.listFiles()
            ?.filter { it.isDirectory && File(it, SessionLayout.SESSION_JSON).isFile }
            ?.map { it.name }
            ?.sortedDescending()
            .orEmpty()

    override fun deleteSession(folderName: String): Boolean {
        if (!SessionLayout.isPlainChildName(folderName)) return false
        val dir = File(root, folderName)
        // Canonical paths, not the names: a name that passed the guard could still resolve
        // outside the root through a symlink, and this is the one call that cannot be taken back.
        val inside = runCatching {
            dir.canonicalFile.parentFile == root.canonicalFile
        }.getOrDefault(false)
        if (!inside || !dir.isDirectory) return false
        return dir.deleteRecursively()
    }
}

private class FileSessionFolder(private val dir: File) : SessionFolder {

    override val name: String get() = dir.name
    override val displayPath: String get() = dir.absolutePath

    override fun createFrame(directory: String, fileName: String): OutputStream {
        val target = File(dir, directory).apply { mkdirs() }
        return File(target, fileName).outputStream().buffered(BUFFER)
    }

    override fun writeAtomically(fileName: String, bytes: ByteArray) {
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.tmp")
        temp.outputStream().use { it.write(bytes) }
        if (!temp.renameTo(target)) {
            // Rename can fail if the destination exists on some filesystems. Falling back to a
            // delete-then-rename is a strictly worse guarantee, so it is the fallback and not
            // the method: it leaves a window where neither file is there.
            target.delete()
            if (!temp.renameTo(target)) {
                target.outputStream().use { it.write(bytes) }
                temp.delete()
            }
        }
    }

    override fun readText(fileName: String): String? =
        File(dir, fileName).takeIf { it.isFile }?.readText()

    override fun listFrames(directory: String): List<String> =
        File(dir, directory).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()

    override fun sizeBytes(): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)

    private companion object {
        const val BUFFER = 1 shl 16
    }
}
