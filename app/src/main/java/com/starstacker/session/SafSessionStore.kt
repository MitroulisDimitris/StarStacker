package com.starstacker.session

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import java.io.OutputStream

/**
 * T-0.5 — [SessionStore] over the Storage Access Framework, so a session lands in a folder the
 * user picked and keeps after the app is uninstalled.
 *
 * ### Why not `DocumentFile`
 *
 * The plan's warning, and it is the reason this class is hand-written: `DocumentFile.findFile()`
 * is a **linear scan of the directory per call**. A session writing 150 frames into a folder that
 * already holds 150 frames turns writing into a quadratic crawl, and the crawl gets worse exactly
 * as the session gets more valuable. Nothing here looks a file up by name on the per-frame path.
 *
 * The structure that avoids it: a [SafSessionFolder] resolves each subdirectory's document URI
 * **once**, at open or create, and caches it. Writing a frame is then
 * `createDocument(parent, name)` + `openOutputStream` — two calls, neither of which enumerates
 * anything. Lookups by name happen only in [openSession] and [listFrames], which are not on the
 * per-frame path.
 *
 * ### Two things SAF cannot do, stated rather than papered over
 *
 * **There is no atomic replace.** [FileSessionFolder] gets one from `rename`; a document provider
 * offers no equivalent, so [SafSessionFolder.writeAtomically] cannot be atomic in the same sense.
 * Rather than pretend, the write is arranged so the *reader* can always recover: the new bytes go
 * to a temporary document first and the previous one is only removed once they are down, and
 * [readText] falls back to the temporary when the main document is missing. A process killed
 * mid-write leaves one of the two readable, which is the guarantee that actually matters — a torn
 * `session.json` would orphan every DNG beside it.
 *
 * **Free space is an estimate.** A tree URI is not a path, so `StatFs` cannot be pointed at it.
 * `fstatvfs` on the volume backing the root is tried first and is right for the ordinary case of
 * a folder on internal storage; the fallback is the primary volume, which will be wrong if the
 * user picked an SD card. It informs a warning, not a decision, so an estimate is acceptable —
 * but it is an estimate.
 */
class SafSessionStore(
    private val context: Context,
    private val treeUri: Uri,
) : SessionStore {

    private val resolver = context.contentResolver

    private val rootDocumentUri: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    override val displayPath: String get() = describe(treeUri)

    override fun freeBytes(): Long = runCatching {
        resolver.openFileDescriptor(rootDocumentUri, "r")?.use { pfd ->
            val stat = Os.fstatvfs(pfd.fileDescriptor)
            stat.f_bavail * stat.f_frsize
        }
    }.getOrNull() ?: runCatching {
        val fallback = StatFs(context.filesDir.absolutePath)
        fallback.availableBlocksLong * fallback.blockSizeLong
    }.getOrDefault(0L)

    override fun createSession(folderName: String): SessionFolder {
        val existing = childDocumentId(rootDocumentUri, folderName)
        val sessionUri = if (existing != null) {
            documentUri(existing)
        } else {
            DocumentsContract.createDocument(
                resolver,
                rootDocumentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                folderName,
            ) ?: error("could not create session folder '$folderName' under $displayPath")
        }

        val children = SessionLayout.DIRECTORIES.associateWith { name ->
            val id = childDocumentId(sessionUri, name)
            if (id != null) {
                documentUri(id)
            } else {
                DocumentsContract.createDocument(
                    resolver,
                    sessionUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    name,
                ) ?: error("could not create '$name' inside session '$folderName'")
            }
        }
        return SafSessionFolder(context, treeUri, folderName, displayPath, sessionUri, children)
    }

    override fun openSession(folderName: String): SessionFolder? {
        val sessionId = childDocumentId(rootDocumentUri, folderName) ?: return null
        val sessionUri = documentUri(sessionId)
        val children = SessionLayout.DIRECTORIES.mapNotNull { name ->
            childDocumentId(sessionUri, name)?.let { name to documentUri(it) }
        }.toMap()
        return SafSessionFolder(context, treeUri, folderName, displayPath, sessionUri, children)
    }

    /**
     * FR-10.6.4 — a folder copied back from a PC is as real as one this app created, so sessions
     * are discovered by scanning rather than from an index.
     *
     * One query of the root's children, then one query per candidate to confirm it holds a
     * `session.json`. That second query is why **OI-5** wants this measured: it is linear in the
     * number of sessions, and the point of D-5's cached index is to keep it off the launch path.
     */
    override fun listSessions(): List<String> =
        queryChildren(rootDocumentUri)
            .filter { it.isDirectory }
            .filter { childDocumentId(documentUri(it.documentId), SessionLayout.SESSION_JSON) != null }
            .map { it.name }
            .sortedDescending()

    private fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun childDocumentId(parent: Uri, name: String): String? =
        queryChildren(parent).firstOrNull { it.name == name }?.documentId

    private fun queryChildren(parent: Uri): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getDocumentId(parent),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            Child(
                                documentId = cursor.getString(0),
                                name = cursor.getString(1) ?: "",
                                mimeType = cursor.getString(2) ?: "",
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.onFailure { Log.w(TAG, "listing $parent failed", it) }.getOrDefault(emptyList())
    }

    private data class Child(val documentId: String, val name: String, val mimeType: String) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private companion object {
        const val TAG = "SafSessionStore"

        /** The tree URI's last path segment is the closest thing SAF has to a readable path. */
        fun describe(treeUri: Uri): String =
            runCatching { Uri.decode(DocumentsContract.getTreeDocumentId(treeUri)) }
                .getOrNull()
                ?.substringAfter(':')
                ?.ifBlank { null }
                ?.let { "/$it" }
                ?: treeUri.toString()
    }
}

private class SafSessionFolder(
    context: Context,
    private val treeUri: Uri,
    override val name: String,
    private val rootDisplayPath: String,
    private val sessionUri: Uri,
    /** Subdirectory document URIs, resolved once. The whole point — see [SafSessionStore]. */
    private val directories: Map<String, Uri>,
) : SessionFolder {

    private val resolver = context.contentResolver

    override val displayPath: String get() = "$rootDisplayPath/$name"

    /**
     * Writes through a [ParcelFileDescriptor] rather than `openOutputStream`, which is the plan's
     * instruction and has a reason: a provider is free to satisfy `openOutputStream` with a
     * **pipe**, and a 24 MiB frame every few seconds through a pipe is a copy per frame that the
     * capture cadence pays for. Asking for the descriptor gets the real file where the provider
     * has one to give.
     */
    override fun createFrame(directory: String, fileName: String): OutputStream {
        val parent = directories[directory]
            ?: error("session '$name' has no '$directory' directory")
        val uri = DocumentsContract.createDocument(resolver, parent, mimeFor(fileName), fileName)
            ?: error("could not create '$fileName' in '$directory'")
        val descriptor = resolver.openFileDescriptor(uri, "w")
            ?: error("could not open '$fileName' in '$directory' for writing")
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor).buffered(BUFFER)
    }

    /**
     * See the class note on [SafSessionStore]: this is *recoverable*, not atomic. The order is
     * chosen so that at every instant at least one of the two documents holds a complete copy.
     */
    override fun writeAtomically(fileName: String, bytes: ByteArray) {
        val tempName = "$fileName$TEMP_SUFFIX"
        childId(sessionUri, tempName)?.let { deleteDocument(it) }

        val tempUri = DocumentsContract.createDocument(
            resolver,
            sessionUri,
            mimeFor(fileName),
            tempName,
        ) ?: error("could not create '$tempName' in session '$name'")
        resolver.openOutputStream(tempUri, "w")?.use { it.write(bytes) }
            ?: error("could not open '$tempName' for writing")

        // Only now is the previous copy expendable.
        childId(sessionUri, fileName)?.let { deleteDocument(it) }
        runCatching { DocumentsContract.renameDocument(resolver, tempUri, fileName) }
            .onFailure { Log.w(TAG, "rename of $tempName failed; the temp copy remains", it) }
    }

    override fun readText(fileName: String): String? =
        readDocument(fileName) ?: readDocument("$fileName$TEMP_SUFFIX")

    override fun listFrames(directory: String): List<String> {
        val parent = directories[directory] ?: return emptyList()
        return queryChildren(parent)
            .filterNot { it.second == DocumentsContract.Document.MIME_TYPE_DIR }
            .map { it.first }
            .sorted()
    }

    private fun readDocument(fileName: String): String? {
        val id = childId(sessionUri, fileName) ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        return runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    private fun deleteDocument(documentId: String) {
        runCatching {
            DocumentsContract.deleteDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            )
        }.onFailure { Log.w(TAG, "could not delete $documentId", it) }
    }

    private fun childId(parent: Uri, childName: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getDocumentId(parent),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        return runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == childName) return@use cursor.getString(0)
                }
                null
            }
        }.getOrNull()
    }

    private fun queryChildren(parent: Uri): List<Pair<String, String>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getDocumentId(parent),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add((cursor.getString(0) ?: "") to (cursor.getString(1) ?: ""))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val TAG = "SafSessionFolder"

        const val BUFFER = 1 shl 16

        /**
         * Deliberately not `.tmp`: [readText] treats this document as a valid fallback copy, and
         * a suffix that reads as scratch invites a cleanup script to delete the only good one.
         */
        const val TEMP_SUFFIX = ".writing"

        /**
         * Providers may rewrite a display name whose extension disagrees with the MIME type, and
         * a frame whose name on disk differs from the name in `session.json` is a frame the log
         * has lost. Naming the types keeps the extension intact.
         */
        fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "")) {
            "dng" -> "image/x-adobe-dng"
            "json" -> "application/json"
            "tif", "tiff" -> "image/tiff"
            else -> "application/octet-stream"
        }
    }
}
