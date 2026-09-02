package com.starstacker.stacking

import android.content.Context

/**
 * T-5.6 / T-6.4 — the stacking choices the user owns, remembered between sessions.
 *
 * ### Why a setting and not a prompt
 *
 * These are preferences in the strict sense: one answer suits a given person almost every time,
 * and being asked after every session would be noise. **D-27** points the same way from the other
 * side — nothing should start because a screen appeared, and the corollary is that a question with
 * a stable answer belongs somewhere it can be answered once, deliberately, rather than in the path
 * of the thing the user came to do.
 *
 * ### These are defaults, and the stacking screen can override them for one run
 *
 * What is stored here is what a **new** stack starts with; the expert panel edits a copy for that
 * run. Changing your mind about one session is not the same as changing your mind about all of
 * them, and the panel does not write back.
 *
 * What reproduces a master is neither of those — it is the value recorded in that session's
 * `session.json` (FR-9.2), because both of these can be changed afterwards and a restack has to
 * reproduce a master rather than approximate it.
 */
object StackingSettings {

    private const val PREFS = "stacking"
    private const val KEY_CROP = "crop"
    private const val KEY_METHOD = "method"

    /** What a new stack starts with. */
    fun defaults(context: Context): StackSettings {
        val prefs = prefs(context)
        return StackSettings(
            method = Combine.Method.entries
                .firstOrNull { it.name == prefs.getString(KEY_METHOD, null) }
                ?: Combine.Method.SIGMA_CLIP,
            crop = LinearMaster.Crop.of(prefs.getString(KEY_CROP, null)),
        )
    }

    fun setDefaults(context: Context, settings: StackSettings) {
        prefs(context).edit()
            .putString(KEY_CROP, settings.crop.name)
            .putString(KEY_METHOD, settings.method.name)
            .apply()
    }

    fun crop(context: Context): LinearMaster.Crop = defaults(context).crop

    fun setCrop(context: Context, crop: LinearMaster.Crop) {
        setDefaults(context, defaults(context).copy(crop = crop))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
