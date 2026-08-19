package com.starstacker.camera

/**
 * Decides when a run of unusable frames has stopped being *settling* and started being *refusal*.
 *
 * `SequenceSession.nextVerifiedFrame` skips any frame whose own metadata does not confirm what was
 * asked (D-21). That is right while the sensor applies a change — the first frame or two come back
 * under the old settings — and catastrophic if it never will: at a 120 s exposure, a device that
 * silently clamps would discard a two-minute frame, then another, until the session budget was
 * gone, and then report a *timeout*, which names the wrong problem entirely.
 *
 * Pure Kotlin with no Android imports, deliberately: this is the rule that decides whether a night
 * is abandoned, and it is worth testing on a laptop rather than inferring from a field log.
 *
 * ### The two kinds of skip are not the same, and conflating them breaks darks
 *
 * A frame can be skipped because **the exposure is wrong** or because **the generation is old** —
 * and only the first is evidence of refusal. The generation guard exists for darks: after the
 * sensor is stopped and restarted while the user covers the lens, frames from before the cover
 * went on are still in flight, and they must not be filed as darks. Those frames have the *right*
 * exposure and are legitimately waited out. Counting them toward a refusal budget would abandon
 * every session that takes darks, which is the opposite of the failure this guards against.
 */
class ExposureAttempts(private val refuseAfter: Int?) {

    private var consecutiveWrongExposures = 0

    /** The exposure the sensor last came back with, for the message when giving up. */
    var lastAppliedNs: Long? = null
        private set

    /**
     * Records one unusable frame.
     *
     * @param exposureMatched whether the frame's own metadata confirmed the requested exposure —
     *   false means the sensor gave a different exposure, true means it was skipped for its
     *   generation alone.
     * @return true when the caller should stop waiting and report a refusal.
     */
    fun skipped(exposureMatched: Boolean, appliedNs: Long?): Boolean {
        if (exposureMatched) {
            // A legitimate wait. It also *clears* the count rather than merely not adding to it:
            // a correct frame in between means the sensor is answering, so any earlier mismatches
            // were settling after all.
            consecutiveWrongExposures = 0
            return false
        }
        consecutiveWrongExposures++
        lastAppliedNs = appliedNs
        return refuseAfter != null && consecutiveWrongExposures >= refuseAfter
    }

    /** Frames rejected for their exposure since the last good one — for logs and tests. */
    val wrongExposures: Int get() = consecutiveWrongExposures
}
