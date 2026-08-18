package com.starstacker.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * T-0.5 — which [SessionStore] a session is written through, and the memory of the user's choice.
 *
 * There are two stores and the difference is not performance, it is **survival**:
 *
 * - [FileSessionStore] over `getExternalFilesDir` needs no permission and no picker, and Android
 *   deletes the whole directory when the app is uninstalled. It is also hidden from MTP and from
 *   the Files app on Android 11+, so a user looking for their frames over USB will not find them.
 * - [SafSessionStore] writes into a folder the user chose. It survives uninstall, it is visible
 *   over USB, and it costs one `ACTION_OPEN_DOCUMENT_TREE` at some point before the first session.
 *
 * The second is what FR-9.1 asks for. The first stays as the fallback because **a session that
 * cannot start is worse than a session in an awkward place** — someone standing under a clear sky
 * who has not picked a folder yet should still be able to press start.
 *
 * The grant is taken *persistably* and re-checked on every resolve. A persisted permission can be
 * revoked from system settings, and the SD card it pointed at can be removed; discovering either
 * at frame 1 of an unattended session is discovering it too late, so [store] verifies the grant is
 * still held and silently falls back if it is not.
 */
object SessionRoot {

    private const val PREFS = "session_root"
    private const val KEY_TREE_URI = "tree_uri"
    private const val TAG = "SessionRoot"

    /** The sub-directory used under the app-private fallback root. */
    private const val FALLBACK_DIRECTORY = "sessions"

    /** The intent that asks the user for a folder. */
    fun pickerIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    /**
     * Records the folder the user picked, taking the persistable grant first.
     *
     * Returns false when the grant could not be taken, in which case nothing is remembered — a
     * remembered root that cannot be written to would fail at the worst possible moment.
     */
    fun remember(context: Context, treeUri: Uri): Boolean {
        val taken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "could not persist permission for $treeUri", it) }.isSuccess

        if (!taken) return false
        prefs(context).edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
        return true
    }

    /** Forgets the chosen folder and releases the grant. Sessions already written are untouched. */
    fun forget(context: Context) {
        current(context)?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs(context).edit().remove(KEY_TREE_URI).apply()
    }

    /** The remembered tree URI, or null. Does not check that the grant is still held. */
    fun current(context: Context): Uri? =
        prefs(context).getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /** True when a root is remembered *and* the persisted grant is still in force. */
    fun isUsable(context: Context): Boolean {
        val uri = current(context) ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
    }

    /**
     * The store to write through. SAF when the user has chosen a folder and the grant survives,
     * the app-private fallback otherwise.
     */
    fun store(context: Context): SessionStore {
        val uri = current(context)
        if (uri != null) {
            if (isUsable(context)) {
                return SafSessionStore(context.applicationContext, uri)
            }
            Log.w(TAG, "the grant on $uri is gone — falling back to app-private storage")
        }
        return fallbackStore(context)
    }

    /**
     * The app-private store, used directly by diagnostics that must not depend on a picker having
     * been run.
     */
    fun fallbackStore(context: Context): SessionStore = FileSessionStore(
        File(context.getExternalFilesDir(null) ?: context.filesDir, FALLBACK_DIRECTORY)
            .apply { mkdirs() },
    )

    /**
     * One line for the UI. Says where frames will go *and* whether that place survives uninstall,
     * because the second half is the whole reason the picker exists.
     */
    fun describe(context: Context): String = when {
        isUsable(context) -> store(context).displayPath
        current(context) != null ->
            "folder access was lost — using app-private storage, deleted if the app is uninstalled"
        else -> "app-private storage — deleted if the app is uninstalled"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
