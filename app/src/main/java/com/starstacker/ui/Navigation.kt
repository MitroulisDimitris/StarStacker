package com.starstacker.ui

/**
 * T-0.3 — the screens, and the stack that orders them.
 *
 * [MAIN] became the root in T-3.18. [PROBE] was the root until then, which was the bug §1.15
 * describes: the capability probe is a diagnostic and it had been the front door since Phase 1A.
 * It is still reachable, from Settings, where diagnostics live.
 *
 * [SESSIONS] and [SESSION_DETAIL] are T-3.27, which pulls T-6.1 and T-6.3 forward out of Phase 4
 * because the button that needs them already existed and lied about what it did: `All sessions`
 * called `openSessionFolder()`, the same file-manager route as the folder icon beside it, so the
 * app had two controls doing one thing and no screen for the thing they were named after. The
 * folder icon stays — a file manager is a different job, for when the answer really is "give me
 * the files".
 */
enum class Screen { MAIN, FRAMING, SETUP, CAPTURE, SETTINGS, PROBE, SESSIONS, SESSION_DETAIL, RESULT }

/**
 * T-0.3's back stack, as plain data so the rules can be tested rather than clicked.
 *
 * **What it replaces was not a simplification, it was a missing feature.** Navigation was a single
 * `var screen`, which meant the system back gesture was never handled and therefore *left the app*
 * from any screen — including mid-session. That is the kind of defect nobody files, because it
 * looks like the phone working normally right up until it loses your place.
 *
 * A list rather than a navigation library. The flow is a stack of five, the codebase adds
 * dependencies reluctantly (D-7, D-11), and the rules worth having are the two below — neither of
 * which a library would have got right for us anyway.
 */
data class BackStack(val entries: List<Screen> = listOf(Screen.MAIN)) {

    init {
        require(entries.isNotEmpty()) { "a back stack always has a root" }
    }

    val current: Screen get() = entries.last()

    /** False at the root, where back belongs to the system and means "leave the app". */
    val canGoBack: Boolean get() = entries.size > 1

    /**
     * Pushes unless it is already on top. Re-entering the screen you are on is a no-op rather
     * than a second copy to back out of — automatic navigation (a session starting) can otherwise
     * fire twice and bury the user under duplicates.
     */
    fun push(screen: Screen): BackStack =
        if (current == screen) this else BackStack(entries + screen)

    fun pop(): BackStack = if (canGoBack) BackStack(entries.dropLast(1)) else this

    /**
     * Where a session lands: the capture screen, with **the landing screen behind it and nothing
     * else**.
     *
     * Backing out of a running session onto the setup screen would offer a Start button for a
     * session already running, which is an invitation to start a second one on top of the first.
     * The session belongs to the service and survives the screen (D-6), so leaving the capture
     * screen is safe — it just must not lead back into the flow that began it.
     */
    fun enterCapture(): BackStack = BackStack(listOf(Screen.MAIN, Screen.CAPTURE))

    /** Back to the root, for the completion screen's Done. */
    fun toRoot(): BackStack = BackStack(listOf(Screen.MAIN))

    companion object {
        /**
         * Survives process death, not just rotation. Rotation is locked to portrait, but the
         * system can kill and restore a backgrounded app at any time — and this one is designed
         * to be backgrounded for 45 minutes at a stretch. Coming back to the wrong screen with an
         * empty history is the same bug the stack was added to fix.
         */
        val Saver: androidx.compose.runtime.saveable.Saver<BackStack, List<String>> =
            androidx.compose.runtime.saveable.Saver(
                save = { stack -> stack.entries.map { it.name } },
                restore = { names ->
                    names.mapNotNull { name ->
                        Screen.entries.firstOrNull { it.name == name }
                    }.takeIf { it.isNotEmpty() }?.let { BackStack(it) }
                },
            )
    }
}
