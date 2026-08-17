package com.starstacker.focus

/**
 * T-2.5 — is focus still good?
 *
 * FR-6.3 calls focus the single biggest beginner failure mode, because a soft session is
 * unrecoverable: no amount of stacking sharpens a frame that was never sharp. Two checks come
 * out of that, and they are the same arithmetic at different time scales.
 *
 * - **At session start** ([verify]): drive to the stored position, take one frame, compare.
 * - **During the session** ([FocusMonitor]): watch the running HFR against the reference and
 *   raise a flag when it walks away — thermal drift moves a VCM over half an hour, and a knock
 *   on a tripod leg moves it instantly.
 *
 * A single bad frame is never enough to act on: high cloud raises HFR for one sub and then
 * clears. The monitor works on a rolling median so one frame cannot trip it.
 */

enum class FocusStatus {
    /** Not enough measured frames yet to say anything. */
    UNKNOWN,

    /** HFR is where it was when focus was fixed. */
    LOCKED,

    /** Measurably worse, but still usable — worth a note, not an interruption. */
    DRIFTING,

    /** Far enough out that frames are being wasted. Re-verify. */
    LOST,
}

/**
 * @param referenceHfr the HFR focus was fixed at, in analysis-plane pixels
 * @param window how many recent frames the rolling median covers
 * @param driftFactor multiple of the reference that counts as drift
 * @param lostFactor multiple that counts as lost focus
 * @param margin absolute allowance in pixels, so a very sharp reference (small HFR) does not
 *   make the relative thresholds hair-trigger on seeing alone
 */
class FocusMonitor(
    val referenceHfr: Double,
    private val window: Int = 5,
    private val driftFactor: Double = 1.25,
    private val lostFactor: Double = 1.6,
    private val margin: Double = 0.2,
    private val minStars: Int = FocusSweep.MIN_STARS,
) {
    private val recent = ArrayDeque<Double>()

    var status: FocusStatus = FocusStatus.UNKNOWN
        private set

    /** Rolling median HFR, or null before enough frames have been measured. */
    var medianHfr: Double? = null
        private set

    /**
     * Feeds one frame's measurement in and returns the current verdict.
     *
     * Frames with too few stars are *not* evidence about focus — they are evidence about the
     * sky — so they are ignored here and diagnosed as cloud elsewhere (FR-7.5).
     */
    fun accept(hfr: Double?, starCount: Int): FocusStatus {
        if (hfr == null || hfr <= 0.0 || starCount < minStars) return status

        recent.addLast(hfr)
        while (recent.size > window) recent.removeFirst()

        if (recent.size < MIN_SAMPLES) {
            status = FocusStatus.UNKNOWN
            return status
        }

        val sorted = recent.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        medianHfr = median

        status = when {
            median > referenceHfr * lostFactor + margin -> FocusStatus.LOST
            median > referenceHfr * driftFactor + margin -> FocusStatus.DRIFTING
            else -> FocusStatus.LOCKED
        }
        return status
    }

    fun reset() {
        recent.clear()
        medianHfr = null
        status = FocusStatus.UNKNOWN
    }

    companion object {
        const val MIN_SAMPLES = 3

        /**
         * Session-start verification (FR-6.3): does the stored position still hold?
         *
         * Deliberately one-sided. Measuring *better* than the stored HFR is not a failure — the
         * seeing was worse the night the sweep ran — so only degradation triggers a re-sweep.
         */
        fun verify(
            measuredHfr: Double?,
            starCount: Int,
            stored: FocusRecord,
            driftFactor: Double = 1.25,
            margin: Double = 0.2,
            minStars: Int = FocusSweep.MIN_STARS,
        ): FocusStatus = when {
            measuredHfr == null || measuredHfr <= 0.0 || starCount < minStars -> FocusStatus.UNKNOWN
            measuredHfr > stored.hfr * 1.6 + margin -> FocusStatus.LOST
            measuredHfr > stored.hfr * driftFactor + margin -> FocusStatus.DRIFTING
            else -> FocusStatus.LOCKED
        }
    }
}
