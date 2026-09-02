package com.starstacker.stacking

/**
 * T-6.4 / FR-10.4 — the choices that decide what a master looks like, for one run.
 *
 * ### Why these live together and travel as one value
 *
 * FR-10.4 lets any session be restacked with different settings, and FR-10.4.1 makes that
 * non-destructive: previous results are kept and **labelled with the settings that produced
 * them**. That only works if "the settings that produced it" is a single thing that can be
 * recorded, compared and re-applied — not a handful of parameters gathered from wherever each one
 * happened to be stored.
 *
 * So this is the unit. It goes into `session.json` after a stack (FR-9.2), and a restack starts
 * from what is recorded there rather than from the app's current preferences, because those can
 * have been changed in between and a restack must **reproduce** rather than approximate.
 *
 * ### Defaults come from Settings; a run can override them
 *
 * `StackingSettings` holds what a *new* stack starts with, which is the answer for the person who
 * set it once and never thinks about it again. This is what one run actually used. The expert
 * panel on the stacking screen edits the latter without touching the former — changing your mind
 * about one session is not the same as changing your mind about all of them.
 */
data class StackSettings(
    /** How N samples of one pixel become one — T-5.4's four choices. */
    val method: Combine.Method = Combine.Method.SIGMA_CLIP,

    /** What to do with the ragged border where the frames did not all overlap — T-5.6. */
    val crop: LinearMaster.Crop = LinearMaster.Crop.DEFAULT,

    /**
     * T-5.5 — the percentage of frames to keep, best first. 100 stacks everything.
     *
     * The default drops the worst 5%, which on a long run is the aeroplane, the one the neighbour's
     * security light caught, and the one where cloud arrived. On a short run it drops nothing,
     * because the count is rounded up — see [FrameQuality.keepBest].
     */
    val keepBestPercent: Int = FrameQuality.DEFAULT_KEEP_PERCENT,

    /**
     * T-5.5 — whether the frames that are kept are weighted by quality, or counted equally.
     *
     * Separate from [keepBestPercent] because they are separate questions: the cut is about frames
     * bad enough to exclude, and this is about the rest not being identical. Off means a plain
     * average of what survived the cut, which is what a desktop tool does by default and what
     * T-5.7 will need to compare like with like.
     */
    val weightByQuality: Boolean = true,
) {

    /** A fresh combiner for this run. Fresh because `SigmaClip` carries per-stack counters. */
    fun combiner(): TiledStacker.Combiner = Combine.of(method)

    /** One line for the UI, and the label FR-10.4.1 wants against a stored result. */
    fun describe(): String = buildString {
        append(method.label)
        append(" · ")
        append(crop.label)
        if (keepBestPercent < 100) append(" · best $keepBestPercent%")
        if (weightByQuality && Combine.supportsWeights(method)) append(" · weighted")
    }

    /** For `session.json` — see the class note on why this is recorded per session. */
    fun toMap(): Map<String, String> = mapOf(
        KEY_METHOD to method.name,
        KEY_CROP to crop.name,
        KEY_KEEP to keepBestPercent.toString(),
        KEY_WEIGHTED to weightByQuality.toString(),
    )

    companion object {
        const val KEY_METHOD = "method"
        const val KEY_CROP = "crop"
        const val KEY_KEEP = "keepBestPercent"
        const val KEY_WEIGHTED = "weighted"

        /**
         * Reads back what produced a master, tolerantly.
         *
         * Tolerant because a session folder can arrive from a PC or from an older build
         * (FR-10.6.4), and an unrecognised value should degrade to the default rather than
         * refusing to open the session it describes.
         */
        fun fromMap(map: Map<String, String>): StackSettings = StackSettings(
            method = Combine.Method.entries.firstOrNull { it.name == map[KEY_METHOD] }
                ?: Combine.Method.SIGMA_CLIP,
            crop = LinearMaster.Crop.of(map[KEY_CROP]),
            // Absent means an older stack, from before T-5.5. It kept everything and weighted
            // nothing, so that is what it has to read back as — defaulting to today's behaviour
            // would describe a master that was not made that way.
            keepBestPercent = map[KEY_KEEP]?.toIntOrNull()?.coerceIn(1, 100) ?: 100,
            weightByQuality = map[KEY_WEIGHTED]?.toBooleanStrictOrNull() ?: false,
        )
    }
}
