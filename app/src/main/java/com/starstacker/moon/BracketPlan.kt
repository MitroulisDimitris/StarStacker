package com.starstacker.moon

import com.starstacker.exposure.SessionPlanner
import com.starstacker.session.ExposureSet
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * T-11.9 — planning a *moon in scene* session, which shoots two exposures instead of one.
 *
 * ### Why two, in one number
 *
 * The 2026-09-06 session wanted **2 474.6 ms** for the harbour and **0.13 ms** for the moon: a
 * separation of **19 035x, or 14.2 stops**, against the 10 stops between this sensor's black level
 * of 64 and its white level of 1023. The scene is wider than the sensor, so one exposure must
 * sacrifice an end — and stacking closes none of it, because averaging buys `sqrt(N)` in the
 * shadows and nothing at all in the highlights. At the moon's exposure a 600 ADU shoreline reads
 * 0.03 ADU and quantises to black, where the mean of a thousand zeros is still zero.
 *
 * ### How the two sets are interleaved, which OI-26 settled and the plan first got wrong
 *
 * The obvious approach — re-apply a repeating request per switch — costs **7.6 s and 5.7 discarded
 * frames per switch** on the reference device, because the pipeline is ten frames deep and drains
 * at the old exposure. No block size fixes that: blocking cuts the number of switches, not the
 * price of one, and blocks of 20 still add 30% to a session.
 *
 * What works is **injection**: the short exposure repeats continuously — it is what keeps RAW
 * streaming on this HAL, and it *is* the moon set — while each ground frame is submitted as a
 * one-shot `captureBurst` against it (`SequenceSession.injectBurst`). Measured at
 * **2 499.998 ms for a 2 500 ms ask, zero frames at the wrong exposure, and no measurable
 * per-frame cost**. The only charge is **5.19 s to a submission's first frame**, paid once per
 * burst — which is why [Plan.groundBursts] submits the ground set in as few bursts as it can.
 *
 * ### The session length nobody would guess
 *
 * Registered on the static ground, the moon sweeps through pixel space, so at any pixel it is an
 * outlier present in a minority of frames — and `SigmaClip` rejects it, leaving clean sky behind
 * the disc for the sharp version to drop into. That only works if the ground set runs long enough
 * for the disc to clear its own diameter several times: **~6 min at sidereal on the reference tele,
 * ~11 min at the rate 2026-09-06 actually saw.** Under it, the composite has to mask a streak.
 *
 * This is computable before the shoot and is therefore [Plan.groundMinutesRequired], stated up
 * front rather than discovered in the master.
 */
object BracketPlan {

    /**
     * Ground frames per injected burst.
     *
     * Bounded by the 5.19 s cost of a submission's first frame, which is amortised over the burst,
     * against the fact that a burst is also the granularity at which the moon set gets a look in:
     * while a burst of ground frames is draining, the short frames between them are the ones the
     * repeating request slips in, and a very long burst starves the moon set of the fresh moments
     * lucky imaging depends on.
     */
    const val GROUND_FRAMES_PER_BURST = 10

    /** Measured on the reference device (OI-26): the cost of a burst's first frame. */
    const val BURST_LATENCY_MS = 5_190.0

    data class Plan(
        /** The short set: metered on the disc (T-11.1). */
        val moonExposureNs: Long,
        val moonIso: Int,
        val moonFrames: Int,
        /** The long set: metered on the ground, exactly as a deep-sky session is (T-8.3). */
        val groundExposureNs: Long,
        val groundIso: Int,
        val groundFrames: Int,
        val groundBursts: Int,
        val seconds: Double,
        val bytesRequired: Long,
        /** How long the ground set must run for the drifting disc to be rejected out of the sky. */
        val groundMinutesRequired: Int,
        val meetsGroundMinimum: Boolean,
        val notes: List<String>,
    ) {
        val totalFrames: Int get() = moonFrames + groundFrames

        fun describe(): String =
            "%d moon @ %.3f ms + %d ground @ %.2f s in %d burst(s) — %.1f min, %.1f GB".format(
                moonFrames, moonExposureNs / 1e6,
                groundFrames, groundExposureNs / 1e9, groundBursts,
                seconds / 60, bytesRequired / 1e9,
            )

        /** Which set a frame at [index] within a burst cycle belongs to — for the capture loop. */
        fun setFor(isGround: Boolean): ExposureSet =
            if (isGround) ExposureSet.GROUND else ExposureSet.MOON
    }

    /**
     * Plans a bracketed run.
     *
     * @param groundSeconds how long the user wants the ground set to run. The moon set rides along
     *   for the same wall-clock time, since it is the repeating request underneath.
     * @param moonPixelsAcross from [LunarGeometry], for the rejection-window arithmetic
     * @param driftArcsecPerSec measured where possible; the sidereal rate is the worst case, and
     *   §1.45 forbids treating any single session's figure as a constant
     */
    fun plan(
        moonExposureNs: Long,
        moonIso: Int,
        groundExposureNs: Long,
        groundIso: Int,
        groundSeconds: Double,
        moonPixelsAcross: Int,
        arcsecPerPixel: Double,
        freeBytes: Long,
        driftArcsecPerSec: Double = LunarPlan.SIDEREAL_ARCSEC_PER_SEC,
        bytesPerFrame: Long = SessionPlanner.DEFAULT_BYTES_PER_FRAME,
    ): Plan {
        val notes = mutableListOf<String>()

        // The ground set paces the session: its frames are seconds each, the moon's are 33 ms.
        val groundPerFrameMs = groundExposureNs / 1e6
        val wantedGround = if (groundPerFrameMs <= 0) 0
        else (groundSeconds * 1000.0 / groundPerFrameMs).toInt()

        // The moon set fills whatever the ground set leaves, at the readout floor OI-26 measured —
        // not at its exposure, which is three hundred times shorter than a frame can be read out.
        val moonPerFrameMs = maxOf(LunarPlan.READOUT_FLOOR_MS, moonExposureNs / 1e6)

        // Storage is shared between the two sets, and the ground set is the one that cannot be
        // shortened without failing the rejection window — so it is served first.
        val budget = (freeBytes * LunarPlan.STORAGE_FRACTION).toLong().coerceAtLeast(0L)
        val affordable = if (bytesPerFrame <= 0) Int.MAX_VALUE else (budget / bytesPerFrame).toInt()

        val groundFrames = wantedGround.coerceAtMost(affordable).coerceAtLeast(0)
        if (groundFrames < wantedGround) {
            notes += "Storage cut the ground set from $wantedGround frames to $groundFrames."
        }

        val remaining = (affordable - groundFrames).coerceAtLeast(0)
        // The moon set is capped both by what is left and by the same diminishing-returns ceiling
        // a disc-only run uses: past a few hundred frames sqrt(N) has flattened.
        val actualSeconds = groundFrames * groundPerFrameMs / 1000.0
        val moonCapacity = if (moonPerFrameMs <= 0) 0
        else (actualSeconds * 1000.0 / moonPerFrameMs).toInt()
        val moonFrames = minOf(remaining, moonCapacity, LunarPlan.MAX_FRAMES)
        if (moonFrames < moonCapacity) {
            notes += "The moon set is capped at $moonFrames of the $moonCapacity frames that " +
                "would fit in the session's own length."
        }

        val bursts = if (groundFrames == 0) 0
        else ceil(groundFrames.toDouble() / GROUND_FRAMES_PER_BURST).toInt()

        // Each burst pays its first frame's latency once (OI-26), which is real session time.
        val seconds = actualSeconds + bursts * BURST_LATENCY_MS / 1000.0

        // Two figures, and they must not be confused: the *requirement* is in seconds, and the
        // minutes are for saying it out loud. Testing against the rounded-up minutes would make
        // `suggestedGroundFrames` fail its own check — 149 frames is 372.5 s, which clears the
        // 371 s needed and falls short of the 7 minutes that rounds to.
        val secondsRequired = LunarPlan.groundSetSeconds(
            moonPixelsAcross, arcsecPerPixel, driftArcsecPerSec,
        )
        val minutesRequired = LunarPlan.groundSetMinutes(
            moonPixelsAcross, arcsecPerPixel, driftArcsecPerSec,
        )
        val meets = actualSeconds >= secondsRequired
        notes += if (meets) {
            "The ground set runs %.1f min, past the %.1f min the drifting disc needs to be rejected "
                .format(actualSeconds / 60, secondsRequired / 60) +
                "out of the sky — the composite will have clean sky behind the moon."
        } else {
            "The ground set runs only %.1f min against the %d min needed for the disc to clear "
                .format(actualSeconds / 60, minutesRequired) + 
                "itself. The moon will leave a streak the composite has to mask around."
        }

        notes += "The ground set needs full calibration — darks, flat and gradient removal — " +
            "because it is an ordinary long exposure pointed at a landscape. The moon set needs " +
            "almost none: sub-millisecond darks are bias frames, and a small centred object sees " +
            "little of the corner vignetting."

        return Plan(
            moonExposureNs = moonExposureNs,
            moonIso = moonIso,
            moonFrames = moonFrames,
            groundExposureNs = groundExposureNs,
            groundIso = groundIso,
            groundFrames = groundFrames,
            groundBursts = bursts,
            seconds = seconds,
            bytesRequired = (moonFrames + groundFrames).toLong() * bytesPerFrame,
            groundMinutesRequired = minutesRequired,
            meetsGroundMinimum = meets,
            notes = notes,
        )
    }

    /**
     * The shortest ground set that satisfies the rejection window, in frames.
     *
     * What the UI should offer as its default, so the common case does not have to be discovered:
     * a user who accepts the suggestion gets clean sky behind the moon without knowing why.
     */
    fun suggestedGroundFrames(
        groundExposureNs: Long,
        moonPixelsAcross: Int,
        arcsecPerPixel: Double,
        driftArcsecPerSec: Double = LunarPlan.SIDEREAL_ARCSEC_PER_SEC,
    ): Int {
        if (groundExposureNs <= 0) return 0
        val seconds = LunarPlan.groundSetSeconds(
            moonPixelsAcross, arcsecPerPixel, driftArcsecPerSec,
        )
        return ceil(seconds * 1e9 / groundExposureNs).roundToInt().coerceAtLeast(1)
    }
}
