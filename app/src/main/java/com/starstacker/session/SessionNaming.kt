package com.starstacker.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T-3.30 — what a session is called when nobody names it.
 *
 * Every session from the UI was labelled `"session"` — the literal string — so twelve nights of
 * shooting produced twelve folders distinguished only by their timestamps, and twelve rows in the
 * list that all read the same. Start now asks for a name; this is what happens when the answer is
 * "cancel".
 *
 * ### The number comes from the root, never from a counter
 *
 * **D-5**: `session.json` and the folder layout are the source of truth, and there is no database.
 * A "sessions tonight" counter in preferences is wrong the moment a folder is copied in from a PC
 * or deleted from the pane — and FR-10.6.4 promises both of those work. So the number is derived
 * by counting the folders already there, every time, which cannot drift because it is not stored.
 *
 * ### Why the first has no number
 *
 * A night with one session reads `2026-08-18`, and only a second one makes it `2026-08-18-2`. The
 * alternative — `-1` on everything — puts a number on the common case to serve the rare one, and
 * a number that is always `1` is decoration. The number appears exactly when it starts carrying
 * information, which is when there are two things to tell apart.
 *
 * The count is of **every** session that day, not only the unnamed ones: `Orion` followed by a
 * cancelled prompt gives `2026-08-18-2`, which is true — it was the second session of the night —
 * and is the sentence T-3.30 asks for, "told apart without reading the clock".
 *
 * All of it is arithmetic on strings and a clock reading, so it is tested without a filesystem.
 */
object SessionNaming {

    /** The longest label a session can carry, matching [SessionLayout.sanitise]'s own cap. */
    const val MAX_LENGTH = 48

    /**
     * The day's own name, `yyyy-MM-dd`, in **local** time for the same reason
     * [SessionLayout.folderName] is: it is read by someone who knows what night it was, and UTC
     * would file everything after midnight under tomorrow.
     */
    fun dayOf(epochMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { this.timeZone = timeZone }
            .format(Date(epochMs))

    /**
     * The default label for a session starting now: the day, plus a number when it is not the
     * first that day.
     *
     * @param existingFolders every folder name in the root, as [SessionStore.listSessions] gives
     *   them. Names that do not begin with a `yyyy-MM-dd_HHmm` stamp are ignored rather than
     *   guessed at — a folder someone made by hand should not shift tonight's numbering.
     */
    fun forDay(
        epochMs: Long,
        existingFolders: List<String>,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val day = dayOf(epochMs, timeZone)
        val soFar = existingFolders.count { startsWithDay(it, day) }
        return if (soFar == 0) day else "$day-${soFar + 1}"
    }

    /**
     * Folders are `yyyy-MM-dd_HHmm[_label]`, so a session belongs to a day when the stamp does.
     * Matching the prefix and the separator together stops `2026-08-1` counting sessions from
     * the 18th.
     */
    private fun startsWithDay(folderName: String, day: String): Boolean =
        folderName.length > day.length &&
            folderName.startsWith(day) &&
            folderName[day.length] == '_'

    /**
     * What the prompt's OK button does with what was typed.
     *
     * Blank is not an error and not a label — it is the same answer as cancelling, so both routes
     * end at [forDay] and there is one behaviour to explain rather than two. The typed name is
     * otherwise passed through [SessionLayout.sanitise], which is what will name the folder, so
     * what the confirmation shows is what lands on disk.
     */
    fun labelFor(
        typed: String,
        epochMs: Long,
        existingFolders: List<String>,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val cleaned = SessionLayout.sanitise(typed)
        // `sanitise` returns the literal "session" for anything that cleans up to nothing, which
        // is precisely the name T-3.30 exists to abolish — so it is treated as blank.
        val blank = typed.isBlank() || cleaned == FALLBACK
        return if (blank) forDay(epochMs, existingFolders, timeZone) else cleaned
    }

    /** [SessionLayout.sanitise]'s own last resort, and the name this task replaces. */
    private const val FALLBACK = "session"
}
