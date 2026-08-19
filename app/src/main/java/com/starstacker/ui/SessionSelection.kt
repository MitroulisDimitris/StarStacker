package com.starstacker.ui

import androidx.compose.runtime.saveable.Saver

/**
 * T-3.29 — which sessions are selected, as plain data.
 *
 * Same reasoning as [BackStack]: the rules are worth testing rather than clicking, and a set held
 * inside a composable is a set that cannot be reasoned about from a test. Selection is by folder
 * name because that is a session's identity everywhere else in the app (**D-5**) — a label can
 * repeat, a position in a list changes as soon as one is deleted.
 *
 * **What it deliberately does not have** is a `Stack selected` action. Multi-select serves delete
 * now and stacking later: stacking one session is Phase 3's T-5.x and stacking several is T-6.8,
 * and a visible button that silently does nothing is worse than no button — the same rule that
 * keeps the prototype's `Stack now` badge off the main screen until it can act. The selection is
 * built to carry that action the moment there is an engine behind it.
 */
data class SessionSelection(val names: Set<String> = emptySet()) {

    val count: Int get() = names.size
    val isActive: Boolean get() = names.isNotEmpty()

    operator fun contains(folderName: String): Boolean = folderName in names

    fun toggle(folderName: String): SessionSelection =
        if (folderName in names) {
            SessionSelection(names - folderName)
        } else {
            SessionSelection(names + folderName)
        }

    fun clear(): SessionSelection = SessionSelection()

    /**
     * Drops names that are no longer there — what a delete leaves behind.
     *
     * Without this a batch delete leaves the vanished names selected, so the count keeps claiming
     * sessions that do not exist and a second Delete acts on nothing while saying it acts on
     * three. Called after every reload rather than only after a delete, since a folder can also
     * disappear because a PC removed it.
     */
    fun retaining(existing: Collection<String>): SessionSelection {
        val kept = names.intersect(existing.toSet())
        return if (kept.size == names.size) this else SessionSelection(kept)
    }

    /** `3 selected` — stated, because a count the user cannot see is a count they cannot trust. */
    fun describe(): String = "$count selected"

    companion object {
        /**
         * Survives rotation *and* process death, for the same reason [BackStack.Saver] does: this
         * app is designed to be backgrounded for long stretches, and a selection of eleven
         * sessions that evaporates because the system reclaimed the Activity is a small betrayal
         * of eleven deliberate taps.
         */
        val Saver: Saver<SessionSelection, List<String>> = Saver(
            save = { it.names.toList() },
            restore = { SessionSelection(it.toSet()) },
        )
    }
}
