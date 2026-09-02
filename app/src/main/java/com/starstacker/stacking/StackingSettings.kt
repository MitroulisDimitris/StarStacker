package com.starstacker.stacking

import android.content.Context

/**
 * T-5.6 — the stacking choices the user owns, remembered between sessions.
 *
 * ### Why a setting and not a prompt
 *
 * The crop mode is a preference in the strict sense: one answer suits a given person almost every
 * time, and being asked it after every session would be noise. **D-27**'s rule points the same way
 * from the other side — nothing that costs anything should start because a screen appeared, and
 * the corollary is that a question with a stable answer belongs somewhere it can be answered once,
 * deliberately, rather than in the path of the thing the user actually came to do.
 *
 * ### It is a default, not a lock
 *
 * What is stored here is what a *new* stack starts with. The value that matters for reproducing a
 * master is the one recorded in that session's `session.json` (FR-9.2), because the setting can be
 * changed afterwards and a restack has to reproduce the master rather than approximate it. When
 * the stacking screen exists it will offer a per-run override seeded from this, in the same place
 * as T-5.4's combiner choice — which is the other half of this and has nowhere to live yet.
 */
object StackingSettings {

    private const val PREFS = "stacking"
    private const val KEY_CROP = "crop"

    /** What a new stack starts with. */
    fun crop(context: Context): LinearMaster.Crop =
        LinearMaster.Crop.of(prefs(context).getString(KEY_CROP, null))

    fun setCrop(context: Context, crop: LinearMaster.Crop) {
        prefs(context).edit().putString(KEY_CROP, crop.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
