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
     */
    fun folderName(startedAtEpochMs: Long, label: String, timeZone: TimeZone = TimeZone.getDefault()):
        String {
        val format = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).apply { this.timeZone = timeZone }
        return "${format.format(Date(startedAtEpochMs))}_${sanitise(label)}"
    }

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
