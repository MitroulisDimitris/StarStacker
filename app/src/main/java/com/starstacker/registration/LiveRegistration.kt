package com.starstacker.registration

import com.starstacker.stars.BinnedPlane
import com.starstacker.stars.Star

/**
 * T-4.4 / FR-7.2 — registers every frame of a session against the one it started from.
 *
 * The three pieces built before this are each a pure function; this is the thing that holds the
 * state between frames and decides what to do with the answer.
 *
 * ### Against the reference, not against the previous frame
 *
 * Matching each frame to its predecessor is easier — the field has barely moved — and wrong. Every
 * transform carries a fraction of a pixel of error, and chaining a hundred and fifty of them
 * accumulates a drift that no individual measurement would reveal: each step looks excellent and
 * the last frame lands nowhere near the first. Registering against a **fixed reference** keeps the
 * error of every frame independent, at the cost of matching across the full session drift — which
 * is exactly the case T-4.2 was built and tested for, and exactly what T-4.1's seed makes cheap.
 *
 * ### Sensor coordinates, not plane coordinates
 *
 * Detection runs on the binned plane, because that is where it is affordable. The transform,
 * though, is stored in `session.json` (FR-9.2) for a restack that works on **full-resolution**
 * frames, so it is converted out of analysis coordinates before anyone keeps it. Converting at the
 * boundary costs one multiply per star; converting later, or forgetting to, costs a stack aligned
 * to the wrong scale by a factor of the bin.
 *
 * ### The reference can fail to be established
 *
 * The first frame of a session might be cloud. If it were adopted as the reference regardless,
 * every subsequent frame would fail to register against a field of noise, and a whole session would
 * be rejected for the sin of starting badly. So the reference is the first frame that **has enough
 * stars to be one**, and until then frames are registered against nothing and reported as such.
 */
class LiveRegistration(
    private val monitor: ResidualMonitor = ResidualMonitor(),
    private val minReferenceStars: Int = DEFAULT_MIN_REFERENCE_STARS,
    private val tolerancePx: Double = RigidFit.DEFAULT_TOLERANCE_PX,
) {
    /**
     * What registering one frame produced.
     *
     * [bumped] and [failed] are separate because they call for different things and mean different
     * things: a bumped frame is a good frame spoiled by movement, and a failed one is a frame the
     * pipeline cannot place at all — usually cloud. Collapsing them would tell the user to steady
     * their tripod when the sky is the problem.
     */
    data class Outcome(
        val transform: RigidTransform?,
        val residualRmsPx: Double,
        val inlierCount: Int,
        val method: AsterismMatcher.Method,
        val verdict: ResidualMonitor.Verdict,
        val isReference: Boolean,
    ) {
        val failed: Boolean get() = transform == null && !isReference
        val bumped: Boolean get() = verdict == ResidualMonitor.Verdict.SPIKE

        companion object {
            val REFERENCE = Outcome(
                transform = null,
                residualRmsPx = 0.0,
                inlierCount = 0,
                method = AsterismMatcher.Method.NONE,
                verdict = ResidualMonitor.Verdict.UNKNOWN,
                isReference = true,
            )

            val FAILED = Outcome(
                transform = null,
                residualRmsPx = Double.NaN,
                inlierCount = 0,
                method = AsterismMatcher.Method.NONE,
                verdict = ResidualMonitor.Verdict.UNKNOWN,
                isReference = false,
            )
        }
    }

    private var reference: List<AsterismMatcher.Detection>? = null
    private var centreX = 0.0
    private var centreY = 0.0

    /** True once a frame good enough to register against has been seen. */
    val hasReference: Boolean get() = reference != null

    val baselineResidualPx: Double? get() = monitor.baselinePx

    /**
     * Registers one frame.
     *
     * @param seed T-4.1's prediction for the interval since the reference frame — not since the
     *   previous one, because that is what this registers against.
     */
    fun register(
        stars: List<Star>,
        plane: BinnedPlane,
        sensorWidth: Int,
        sensorHeight: Int,
        seed: SkyDrift.Seed? = null,
    ): Outcome {
        val detections = stars.map {
            AsterismMatcher.Detection(
                x = plane.toSensorCoordinate(it.x),
                y = plane.toSensorCoordinate(it.y),
                flux = it.flux,
            )
        }

        val existing = reference
        if (existing == null) {
            if (detections.size < minReferenceStars) return Outcome.FAILED
            reference = detections
            centreX = (sensorWidth - 1) / 2.0
            centreY = (sensorHeight - 1) / 2.0
            return Outcome.REFERENCE
        }

        val match = AsterismMatcher.match(
            reference = existing,
            target = detections,
            seed = seed,
            frameWidth = sensorWidth.toDouble(),
            frameHeight = sensorHeight.toDouble(),
        )
        if (!match.usable) return Outcome.FAILED

        val fit = RigidFit.fit(
            reference = existing,
            target = detections,
            pairs = match.pairs,
            centreX = centreX,
            centreY = centreY,
            seed = seed,
            tolerancePx = tolerancePx,
        )
        if (!fit.succeeded) return Outcome.FAILED

        return Outcome(
            transform = fit.transform,
            residualRmsPx = fit.residualRmsPx,
            inlierCount = fit.inlierCount,
            method = match.method,
            verdict = monitor.observe(fit.residualRmsPx),
            isReference = false,
        )
    }

    /** One line for the frame log, so a rejection can be argued with later (**D-10**). */
    fun describe(outcome: Outcome): String = when {
        outcome.isReference -> "reference frame"
        outcome.failed -> "could not be registered against the reference frame"
        else -> "%s · %d stars matched · %s".format(
            outcome.method.name.lowercase(),
            outcome.inlierCount,
            monitor.describe(outcome.residualRmsPx),
        )
    }

    companion object {
        /**
         * Below this a frame is not fit to be a session's reference.
         *
         * The first frame might be cloud, and adopting it anyway would make every later frame fail
         * against a field of noise — rejecting a whole session for the sin of starting badly. The
         * number is generous because the cost is asymmetric: waiting one more frame for a good
         * reference is nothing, and a bad reference is the session.
         */
        const val DEFAULT_MIN_REFERENCE_STARS = 8
    }
}
