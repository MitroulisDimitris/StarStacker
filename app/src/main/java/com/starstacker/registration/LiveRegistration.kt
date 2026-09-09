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
        /**
         * True when this frame had too few stars to *become* the session's reference.
         *
         * Distinct from [failed], and the distinction is the whole of §1.29. A frame that cannot
         * be a reference has not failed to register — there was nothing to register against. Said
         * the other way, the first frame of a session can never be a registration failure, and
         * reporting one is telling the user their tripod moved when the sky is overcast.
         */
        val tooFewStars: Boolean = false,
        /** T-4.7 — why the star path gave up, when the fallback was the thing that placed it. */
        val fallbackReason: String? = null,
        /** T-4.7 — [PhaseCorrelation.Result.peakRatio], so the confidence is auditable. */
        val fallbackConfidence: Double? = null,
    ) {
        val failed: Boolean get() = transform == null && !isReference && !tooFewStars
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

            /** Not enough stars to be the session's reference — see [Outcome.tooFewStars]. */
            val STARVED = FAILED.copy(tooFewStars = true)
        }
    }

    private var reference: List<AsterismMatcher.Detection>? = null
    private var centreX = 0.0
    private var centreY = 0.0

    /**
     * The reference frame's pixels, kept for T-4.7's fallback.
     *
     * Only the analysis plane, and only a copy of it — the caller's buffer is reused frame to
     * frame, so holding the reference means holding a copy or holding nothing. At bin 2 that is
     * about 12 MB, which is the price of being able to register a session that has no stars in it.
     */
    private var referencePlane: FloatArray? = null
    private var referencePlaneWidth = 0
    private var referencePlaneHeight = 0

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
            if (detections.size < minReferenceStars) return Outcome.STARVED
            reference = detections
            centreX = (sensorWidth - 1) / 2.0
            centreY = (sensorHeight - 1) / 2.0
            referencePlane = plane.data.copyOf(plane.width * plane.height)
            referencePlaneWidth = plane.width
            referencePlaneHeight = plane.height
            return Outcome.REFERENCE
        }

        val match = AsterismMatcher.match(
            reference = existing,
            target = detections,
            seed = seed,
            frameWidth = sensorWidth.toDouble(),
            frameHeight = sensorHeight.toDouble(),
        )
        if (!match.usable) return correlate(plane, "no usable star match")

        val fit = RigidFit.fit(
            reference = existing,
            target = detections,
            pairs = match.pairs,
            centreX = centreX,
            centreY = centreY,
            seed = seed,
            tolerancePx = tolerancePx,
        )
        if (!fit.succeeded) return correlate(plane, "the star fit did not converge")

        return Outcome(
            transform = fit.transform,
            residualRmsPx = fit.residualRmsPx,
            inlierCount = fit.inlierCount,
            method = match.method,
            verdict = monitor.observe(fit.residualRmsPx),
            isReference = false,
        )
    }

    /**
     * T-4.7 — the whole-image fallback, taken only once star matching has already given up.
     *
     * ### Why it is worth having
     *
     * Session `2026-09-06_0118` lost **34 of 67 frames** to "could not be registered", on a scene
     * that whole-image correlation places at a shift of exactly **(0, 0)** — it was aligned to the
     * pixel and there was nothing to fail at. The scene is a crescent moon over a harbour: two
     * rigid bodies, no point sources, and a detection set that reshuffled as glare thickened.
     * DeepSkyStacker fails on the same frames and fails worse, at 0 of 67.
     *
     * ### Why a normal session pays nothing
     *
     * This runs **only on the failure path**. A session whose stars match never reaches it, so the
     * cost is a transform on frames that would otherwise have been thrown away.
     *
     * ### The limit, which is recorded rather than hidden
     *
     * The answer is a **translation**, and a translation cannot correct field rotation. So the
     * outcome carries [AsterismMatcher.Method.PHASE_CORRELATION] into the frame log, and the guard
     * against misuse is [PhaseCorrelation.MIN_PEAK_RATIO]: a rotating field smears the correlation
     * peak and the confidence collapses, so a session the star matcher *should* have handled is
     * refused here too rather than quietly rescued with the wrong model.
     */
    private fun correlate(plane: BinnedPlane, why: String): Outcome {
        val referencePixels = referencePlane ?: return Outcome.FAILED
        if (plane.width != referencePlaneWidth || plane.height != referencePlaneHeight) {
            // A different analysis geometry mid-session. Nothing sane to correlate against.
            return Outcome.FAILED
        }

        val result = PhaseCorrelation.between(
            reference = referencePixels,
            target = plane.data,
            width = plane.width,
            height = plane.height,
        )
        if (!result.usable) return Outcome.FAILED

        // The correlation works in analysis-plane pixels; transforms are in sensor pixels.
        val dx = plane.toSensorPixels(result.dx)
        val dy = plane.toSensorPixels(result.dy)

        return Outcome(
            transform = RigidTransform(
                rotationDeg = 0.0,
                dx = dx,
                dy = dy,
                centreX = centreX,
                centreY = centreY,
            ),
            // No stars were fitted, so there is no residual to report. NaN rather than 0, which
            // would read as a perfect fit in every summary that averages these.
            residualRmsPx = Double.NaN,
            inlierCount = 0,
            method = AsterismMatcher.Method.PHASE_CORRELATION,
            // Deliberately not fed to the monitor: it tracks a *star* residual, and handing it a
            // frame with none would poison the baseline every later frame is judged against.
            verdict = ResidualMonitor.Verdict.UNKNOWN,
            isReference = false,
            fallbackReason = why,
            fallbackConfidence = result.peakRatio,
        )
    }

    /** One line for the frame log, so a rejection can be argued with later (**D-10**). */
    fun describe(outcome: Outcome): String = when {
        outcome.isReference -> "reference frame"
        outcome.tooFewStars -> "too few stars to start a session on"
        outcome.failed -> "could not be registered against the reference frame"
        outcome.method == AsterismMatcher.Method.PHASE_CORRELATION ->
            // Says *translation only* out loud. A frame placed this way is not equivalent to one
            // the star matcher placed, and a log that did not distinguish them would hide it.
            "whole-image correlation, translation only (%s; peak %.0fx)".format(
                outcome.fallbackReason ?: "star matching failed",
                outcome.fallbackConfidence ?: Double.NaN,
            )
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
