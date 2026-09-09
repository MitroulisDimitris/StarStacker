package com.starstacker.calibration

import java.io.File

/**
 * T-6.6 / FR-10.4.2 — noticing that a master was built with calibration that has since changed.
 *
 * ### Why this exists at all
 *
 * `CalibrationLibrary` **replaces rather than versions**: a newer flat of the same lens is a better
 * measurement of the same thing, so it overwrites. That is the right call for the library and it
 * has a consequence for everything already stacked — a master built against last month's flat is
 * now the output of a pipeline that no longer exists, and nothing about the file says so.
 *
 * ### And why it never restacks by itself
 *
 * FR-10.4.2 is explicit, and the reason is worth keeping in view: restacking is minutes of full
 * CPU on a phone, it is destructive of nothing but it is not free, and a "better" flat is only
 * better on average — a flat shot through a smeared lens is worse than the one it replaced. So the
 * app *notices* and says so; the person decides.
 *
 * ### What counts as a version
 *
 * Only things the library owns. A session's darks are its own and cannot go stale — they were shot
 * that night, on that sensor, at that temperature, and nothing later replaces them. The flat is
 * the library's, and its version is **when it was taken and how many frames went into it**: two
 * flats from the same night with different frame counts are different masters, and a timestamp
 * alone would call them the same.
 */
object Staleness {

    /** Key under which a camera's flat version is recorded in `session.json`. */
    fun flatKey(cameraId: String): String = "flat.$cameraId"

    /**
     * The version string for a flat, or null when there is none for this camera.
     *
     * Deliberately human-readable: it goes into `session.json`, which is the file someone opens
     * when they want to know what happened, and an opaque hash would answer no question they have.
     */
    fun flatVersion(info: CalibrationLibrary.FlatInfo?): String? =
        info?.let { "${it.capturedAtEpochMs}/${it.frames}f/${it.width}x${it.height}" }

    /** What the library currently holds for [cameraId], as the map recorded against a stack. */
    fun versionsFor(root: File?, cameraId: String): Map<String, String> {
        if (root == null) return emptyMap()
        val flat = flatVersion(CalibrationLibrary.info(root, cameraId)) ?: return emptyMap()
        return mapOf(flatKey(cameraId) to flat)
    }

    enum class Verdict {
        /** Recorded versions match what the library holds now. */
        FRESH,

        /** Something the master was built with has been replaced since. */
        STALE,

        /**
         * Nothing was recorded, so nothing can be said.
         *
         * Every session stacked before T-6.6 is in this state, and so is every session stacked
         * with no library at all. **Not the same as fresh**, and reporting it as fresh would be a
         * lie of exactly the kind this exists to prevent.
         */
        UNKNOWN,
    }

    data class Report(
        val verdict: Verdict,
        /** One line per item that changed, naming what it was and what it is. */
        val changes: List<String>,
    ) {
        val isStale: Boolean get() = verdict == Verdict.STALE

        fun describe(): String = when (verdict) {
            Verdict.FRESH -> "calibration unchanged since this was stacked"
            Verdict.UNKNOWN -> "this master does not record what it was calibrated with"
            Verdict.STALE -> "calibration has changed since: " + changes.joinToString("; ")
        }
    }

    /**
     * Compares what a stack recorded against what the library holds now.
     *
     * @param recorded `SessionInfo.calibrationVersions` from the session that produced the master
     * @param current [versionsFor] the same camera, read now
     */
    fun compare(recorded: Map<String, String>, current: Map<String, String>): Report {
        if (recorded.isEmpty()) {
            // A stack that recorded nothing cannot be judged. If the library now has something,
            // that is worth saying — it is the case where restacking would actually change the
            // picture — but it is still not a *comparison*.
            return Report(
                Verdict.UNKNOWN,
                if (current.isEmpty()) emptyList()
                else listOf("the library now has ${current.keys.joinToString(", ")}"),
            )
        }

        val changes = mutableListOf<String>()
        for ((key, was) in recorded) {
            val now = current[key]
            when {
                now == null -> changes += "$key was $was and is now gone"
                now != was -> changes += "$key was $was and is now $now"
            }
        }
        // Something the library has gained since. A master built without a flat that could now
        // have one is exactly the case FR-10.4.2 wants surfaced.
        for (key in current.keys) {
            if (key !in recorded) changes += "$key is new since this was stacked"
        }

        return Report(
            if (changes.isEmpty()) Verdict.FRESH else Verdict.STALE,
            changes,
        )
    }
}
