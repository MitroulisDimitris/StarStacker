package com.starstacker.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * FR-9.1's folder layout, as arithmetic on strings so it can be tested without a filesystem.
 *
 * ```
 * <root>/<yyyy-MM-dd_HHmm>_<target-or-camera>/
 * ├── lights/   *.dng
 * ├── darks/    *.dng
 * ├── flats/
 * ├── master/
 * └── session.json
 * ```
 *
 * The names are part of the contract, not an implementation detail: FR-10.6.4 requires a session
 * to be recognised by *scanning the root*, including a folder that has been to a PC and back, so
 * the layout is what identifies a session and nothing else can be.
 */
object SessionLayout {

    const val LIGHTS = "lights"
    const val DARKS = "darks"
    const val FLATS = "flats"
    const val MASTER = "master"
    const val SESSION_JSON = "session.json"

    val DIRECTORIES = listOf(LIGHTS, DARKS, FLATS, MASTER)

    /**
     * Local time, deliberately. The folder name is read by a person standing in a field who knows
     * what time it was; UTC would make every session after midnight look like the wrong night.
     *
     * **The date is never in the name twice.** T-3.30 names an unnamed session for the day —
     * `2026-08-18`, or `2026-08-18-2` for the second that night — and the stamp already begins
     * with that date, so appending the label would produce `2026-08-18_2115_2026-08-18`. A label
     * that is the day itself therefore contributes no suffix; `session.json` keeps the full label
     * either way ([SessionInfo.label]), so nothing is lost by the folder being terse.
     */
    fun folderName(startedAtEpochMs: Long, label: String, timeZone: TimeZone = TimeZone.getDefault()):
        String {
        val format = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).apply { this.timeZone = timeZone }
        val stamp = format.format(Date(startedAtEpochMs))
        val cleaned = sanitise(label)
        return if (isDayLabel(cleaned, startedAtEpochMs, timeZone)) stamp else "${stamp}_$cleaned"
    }

    /**
     * Is this label the day's own name, exactly as [SessionNaming.forDay] produces it — the date,
     * or the date and a bare number?
     *
     * **The suffix has to be all digits.** Matching `startsWith("$day-")` alone swallowed
     * `2026-08-18-comet`, a name someone chose, and dropped it from the folder entirely: the label
     * survived only in `session.json`, and the folder on disk looked like an unnamed session. Only
     * the generated form is terse; anything a person typed reaches the folder.
     */
    private fun isDayLabel(cleaned: String, startedAtEpochMs: Long, timeZone: TimeZone): Boolean {
        val day = SessionNaming.dayOf(startedAtEpochMs, timeZone)
        if (cleaned == day) return true
        if (!cleaned.startsWith("$day-")) return false
        val suffix = cleaned.removePrefix("$day-")
        return suffix.isNotEmpty() && suffix.all { it.isDigit() }
    }

    /**
     * A name that can only ever mean one direct child of the root — no separators, no traversal,
     * no empty string.
     *
     * The guard on [SessionStore.deleteSession], which is the only call in the app that can
     * destroy a night's work. Checked here rather than in each store so both implementations
     * cannot disagree about it, and so it can be tested without a filesystem.
     */
    fun isPlainChildName(folderName: String): Boolean =
        folderName.isNotBlank() &&
            folderName != "." && folderName != ".." &&
            folderName.none { it == '/' || it == '\\' || it == '\u0000' }

    /**
     * Frame file names sort in capture order as text, because that is how every other tool will
     * list them — `light_0001.dng`, not `light_1.dng`.
     */
    fun frameName(kind: FrameKind, index: Int): String {
        val prefix = if (kind == FrameKind.DARK) "dark" else "light"
        return "%s_%04d.dng".format(prefix, index)
    }

    fun directoryFor(kind: FrameKind): String = if (kind == FrameKind.DARK) DARKS else LIGHTS

    /**
     * Anything that is not a letter, digit, dash or underscore becomes a dash. Session folders
     * travel to desktops, external drives and cloud sync, and a name that is legal on one of
     * those is not legal on all of them.
     */
    fun sanitise(label: String): String {
        val cleaned = label.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .take(48)
        return cleaned.ifEmpty { "session" }
    }
}
