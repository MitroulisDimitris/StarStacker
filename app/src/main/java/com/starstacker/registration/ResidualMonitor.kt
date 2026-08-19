package com.starstacker.registration

/**
 * T-4.4 — watches how well frames register, and notices when one suddenly does not.
 *
 * ### The bump the accelerometer cannot see
 *
 * `FrameGate` already rejects a frame when the phone tilted measurably during the exposure, and its
 * own note admits the limit: that catches *gross* bumps, while "the fine motion that matters at the
 * pixel level is far below the accelerometer's noise floor". A footstep on soft ground, a gust
 * against a light tripod, a cable tugging — none of those register as tilt, and all of them smear a
 * seven-second sub.
 *
 * They do show up in the **registration residual**, and that is the point of watching it. A bump
 * moves the star field *during* the exposure, so every star trails a little and its centroid lands
 * somewhere between where it started and where it finished. The transform still fits — the frame is
 * not rotated or shifted in any consistent way — but the inliers all miss by more than they should.
 * The residual is the only place in the pipeline where that shows up, because it is the only number
 * derived from *many stars at once* being wrong together.
 *
 * ### Why a baseline rather than a threshold
 *
 * A fixed residual limit cannot work. What counts as a large residual depends on the pixel scale,
 * the focus, the seeing and the star brightness distribution — a session at 0.2 px and one at 0.8 px
 * can both be perfectly steady. What is *not* ambiguous is a session that was running at 0.25 px
 * and produces a frame at 1.4 px. So the rule is relative, against a rolling median of what this
 * session has actually been doing.
 *
 * ### Two rules learned elsewhere in this project, applied again
 *
 * **A phase too short to judge is inconclusive, not a pass.** §1.16's leak check convicted a clean
 * run twice before it required enough settled cycles to have an opinion. Here the first few frames
 * of a session have no baseline, so they are [Verdict.UNKNOWN] — not "steady", which would be an
 * assertion nobody measured.
 *
 * **A rejected frame does not join the baseline.** `FrameGate` learned this for star counts: a
 * baseline that absorbs the frames it just rejected drifts towards whatever went wrong, and after a
 * few bumps a bumped frame looks normal. Spikes are excluded from the history that judges them.
 */
class ResidualMonitor(
    private val window: Int = DEFAULT_WINDOW,
    private val minFrames: Int = DEFAULT_MIN_FRAMES,
    private val spikeFactor: Double = DEFAULT_SPIKE_FACTOR,
    private val floorPx: Double = DEFAULT_FLOOR_PX,
) {
    enum class Verdict {
        /** Registering as well as this session usually does. */
        STEADY,

        /** Suddenly far worse than this session's own baseline — something moved. */
        SPIKE,

        /** Not enough frames yet to have a baseline. An absence of evidence, stated as one. */
        UNKNOWN,
    }

    private val recent = ArrayDeque<Double>()

    /** The rolling median residual, or null before there are enough frames to have one. */
    val baselinePx: Double?
        get() = if (recent.size < minFrames) null else recent.sorted()[recent.size / 2]

    /**
     * Folds one frame's residual in and says what it looks like.
     *
     * The threshold is a multiple of the baseline **and** a floor above it. The multiple alone
     * misbehaves when a session registers extremely well: at a baseline of 0.05 px, three times is
     * 0.15 px, and ordinary centroid jitter would then read as a bump every few frames. The floor
     * is what stops a very good session becoming a very twitchy one.
     */
    fun observe(residualPx: Double): Verdict {
        if (!residualPx.isFinite() || residualPx < 0.0) return Verdict.UNKNOWN

        val baseline = baselinePx
        if (baseline == null) {
            remember(residualPx)
            return Verdict.UNKNOWN
        }

        val limit = maxOf(baseline * spikeFactor, baseline + floorPx)
        if (residualPx > limit) return Verdict.SPIKE

        remember(residualPx)
        return Verdict.STEADY
    }

    /** How far out of the ordinary the last residual was, for the log line. */
    fun describe(residualPx: Double): String {
        val baseline = baselinePx ?: return "%.2f px, no baseline yet".format(residualPx)
        return "%.2f px against a baseline of %.2f px".format(residualPx, baseline)
    }

    private fun remember(value: Double) {
        recent.addLast(value)
        while (recent.size > window) recent.removeFirst()
    }

    companion object {
        /** Long enough to survive a couple of bad frames, short enough to follow a drifting focus. */
        const val DEFAULT_WINDOW = 12

        /** Below this there is no baseline worth having, so the monitor declines to judge. */
        const val DEFAULT_MIN_FRAMES = 5

        /**
         * A bump has to be this many times the usual residual.
         *
         * Three rather than two: registration residual is already noisy frame to frame, and the
         * cost of the two errors is not symmetric. A missed bump contributes one slightly soft
         * frame to a stack of a hundred and fifty, where a false bump throws away a good one — and
         * a session that cries bump every tenth frame is one nobody will trust.
         */
        const val DEFAULT_SPIKE_FACTOR = 3.0

        /** And at least this much worse in absolute terms. See [observe]. */
        const val DEFAULT_FLOOR_PX = 0.4
    }
}
