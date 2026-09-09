package com.starstacker.stacking

import com.starstacker.registration.PhaseCorrelation
import com.starstacker.session.SessionSummary
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * T-6.8 / FR-10.5 — combining several nights of the same target into one result.
 *
 * ### Why this is mostly a set of refusals
 *
 * Adding a second night doubles the integration and can easily halve the quality, because the two
 * nights differ in every way the stacker normally gets to assume constant: a different camera, a
 * different part of the sky, a different sky brightness, a different scale on the sensor. Most of
 * what follows is therefore about deciding **whether** two sessions may be combined, and only then
 * about how.
 *
 * The costliest mistake is silent: a composite that quietly includes a night pointed somewhere else
 * looks like a deeper stack and is a smeared one, and nothing about the output says which night
 * spoiled it. So every refusal below carries a reason, and the reasons go into the composite's own
 * record.
 *
 * ### The one hard rejection
 *
 * **A different camera is not a different night, it is a different instrument.** Pixel pitch,
 * focal length, colour filters and the flat all change together, so the frames do not describe the
 * same sky sampled twice — they describe two different samplings, and averaging them is
 * meaningless before any resampling. FR-11.1's per-camera isolation says the same thing from the
 * other end. This is the only check here that cannot be overridden.
 *
 * ### Why the registration has to start cold
 *
 * Within a session, each frame is a few pixels from the last and `SkyDrift` can seed the match.
 * Between nights there is no seed at all: the tripod was moved, the target rose to a different
 * altitude, and the framing is off by an unknown fraction of the frame. That is a **wide search**,
 * and it is exactly what T-4.7's [PhaseCorrelation] does — the same primitive, used for the
 * opposite reason. Built once, consumed three times now.
 */
object MultiNight {

    /**
     * How far apart two pointings may be and still be the same target, in degrees.
     *
     * Generous on purpose: the pointing recorded in `session.json` is a compass and accelerometer
     * reading taken once at Start, and its own accuracy is recorded because it is not good
     * (§`SessionPointing`). This is a check against having photographed something *else*, not a
     * measurement — the registration that follows is what actually establishes the overlap.
     */
    const val MAX_POINTING_DEGREES = 15.0

    /**
     * How different two nights' sky backgrounds may be before it is worth saying so.
     *
     * Not a rejection. A brighter sky is a worse night, not a wrong one, and the normalisation
     * below is what handles it — but a factor of three is usually moonlight or cloud, and someone
     * combining those should know they are.
     */
    const val BACKGROUND_RATIO_WARNING = 3.0

    /** One night's contribution, as much of it as this needs to know. */
    data class Night(
        val folderName: String,
        val cameraId: String,
        val label: String,
        val integrationSeconds: Double,
        val frames: Int,
        /** Median sky level of that night's master, in whatever units it was stacked in. */
        val backgroundAdu: Double?,
        /** From `session.json`, when there was a fix. Degrees. */
        val altitudeDeg: Double? = null,
        val azimuthDeg: Double? = null,
    ) {
        companion object {
            fun of(summary: SessionSummary, backgroundAdu: Double? = null) = Night(
                folderName = summary.folderName,
                cameraId = summary.cameraId,
                label = summary.label,
                integrationSeconds = summary.integrationSeconds,
                frames = summary.accepted,
                backgroundAdu = backgroundAdu,
            )
        }
    }

    sealed interface Verdict {
        /** May be combined. [warnings] are things worth saying, not things that stop it. */
        data class Include(val warnings: List<String> = emptyList()) : Verdict

        /** Must not be combined, and why. */
        data class Reject(val reason: String) : Verdict

        val included: Boolean get() = this is Include
    }

    data class Plan(
        val reference: Night,
        val included: List<Night>,
        val rejected: List<Pair<Night, String>>,
        val warnings: List<String>,
    ) {
        /** FR-10.5's cumulative figure — the number the whole feature exists to increase. */
        val integrationSeconds: Double get() = included.sumOf { it.integrationSeconds }
        val frames: Int get() = included.sumOf { it.frames }

        fun describe(): String = buildString {
            append("${included.size} night(s), ")
            append(SessionSummary.formatDuration(integrationSeconds))
            append(" of $frames frames")
            if (rejected.isNotEmpty()) append(" · ${rejected.size} rejected")
        }

        /** What the composite records about itself, so it can be argued with later (FR-9.2). */
        fun provenance(): Map<String, String> = buildMap {
            put("nights", included.joinToString(",") { it.folderName })
            put("camera", reference.cameraId)
            put("frames", frames.toString())
            put("integrationSeconds", "%.1f".format(integrationSeconds))
            if (rejected.isNotEmpty()) {
                put("rejected", rejected.joinToString("; ") { "${it.first.folderName}: ${it.second}" })
            }
            if (warnings.isNotEmpty()) put("warnings", warnings.joinToString("; "))
        }
    }

    /** Judges one candidate against the night that will be the reference. */
    fun judge(reference: Night, candidate: Night): Verdict {
        if (candidate.cameraId != reference.cameraId) {
            return Verdict.Reject(
                "shot on camera ${candidate.cameraId}, not ${reference.cameraId} — a different " +
                    "camera is a different instrument, not another night",
            )
        }

        val warnings = mutableListOf<String>()

        val separation = separationDegrees(reference, candidate)
        if (separation != null && separation > MAX_POINTING_DEGREES) {
            return Verdict.Reject(
                "pointed %.0f° away, past the %.0f° this treats as the same target"
                    .format(separation, MAX_POINTING_DEGREES),
            )
        }
        if (separation == null) {
            // Not a rejection: most sessions have no fix, and the registration below is the real
            // test. But it is worth saying that the cheap check could not run.
            warnings += "${candidate.folderName}: no pointing recorded, so only registration will " +
                "catch a different target"
        }

        val ratio = backgroundRatio(reference.backgroundAdu, candidate.backgroundAdu)
        if (ratio != null && ratio > BACKGROUND_RATIO_WARNING) {
            warnings += "${candidate.folderName}: sky is %.1f× the reference's — moonlight or cloud"
                .format(ratio)
        }

        if (candidate.frames <= 0) {
            return Verdict.Reject("no frames survived that session's own gate")
        }

        return Verdict.Include(warnings)
    }

    /**
     * Builds the plan.
     *
     * The **reference is the longest night**, not the first or the newest: everything else is
     * scaled and registered onto it, so the deepest one is the one with the least noise to spread
     * into the others and the best chance of a confident registration.
     */
    fun plan(nights: List<Night>): Plan? {
        val reference = nights.filter { it.frames > 0 }.maxByOrNull { it.integrationSeconds }
            ?: return null

        val included = mutableListOf(reference)
        val rejected = mutableListOf<Pair<Night, String>>()
        val warnings = mutableListOf<String>()

        for (night in nights) {
            if (night.folderName == reference.folderName) continue
            when (val verdict = judge(reference, night)) {
                is Verdict.Include -> {
                    included += night
                    warnings += verdict.warnings
                }
                is Verdict.Reject -> rejected += night to verdict.reason
            }
        }

        return Plan(reference, included, rejected, warnings)
    }

    /**
     * Angular separation between two pointings, or null when either has no fix.
     *
     * Altitude and azimuth rather than RA and Dec, because that is what the app records — and it is
     * the *right* thing to compare only because the two nights are being judged for "is this the
     * same object", not "is this the same sky coordinate". Two nights of the same target at
     * different hours sit at genuinely different alt-az, which is why [MAX_POINTING_DEGREES] is
     * loose and why registration is the real test.
     */
    fun separationDegrees(a: Night, b: Night): Double? {
        val altA = a.altitudeDeg ?: return null
        val aziA = a.azimuthDeg ?: return null
        val altB = b.altitudeDeg ?: return null
        val aziB = b.azimuthDeg ?: return null

        // Azimuth converges toward the pole, so the same angular error spans more azimuth degrees
        // the higher you look. Without the cosine, a target near the zenith looks wildly separated
        // from itself.
        val dAlt = altB - altA
        val meanAlt = Math.toRadians((altA + altB) / 2)
        var dAzi = abs(aziB - aziA)
        if (dAzi > 180) dAzi = 360 - dAzi
        return hypot(dAlt, dAzi * cos(meanAlt))
    }

    /** How much brighter one sky is than the other, or null when either is unmeasured. */
    fun backgroundRatio(reference: Double?, candidate: Double?): Double? {
        if (reference == null || candidate == null) return null
        if (reference <= 0 || candidate <= 0) return null
        return maxOf(reference, candidate) / minOf(reference, candidate)
    }

    /**
     * The scale and offset that bring one night's master onto the reference's levels.
     *
     * `out = (value − background) × scale + referenceBackground`
     *
     * **Both terms, and in that order.** The offset alone would leave a brighter night contributing
     * a flatter, lower-contrast version of the same signal, and the scale alone would multiply its
     * sky as well as its target. Subtracting each night's own sky first is what makes the signal
     * comparable; scaling then makes the *units* comparable.
     *
     * @param referenceSignal a bright reference level in the reference night — the point the two
     *   are made to agree at
     */
    fun normalisation(
        referenceBackground: Double,
        referenceSignal: Double,
        nightBackground: Double,
        nightSignal: Double,
    ): Pair<Double, Double>? {
        val referenceSpan = referenceSignal - referenceBackground
        val nightSpan = nightSignal - nightBackground
        // A night with no measurable range above its own sky has nothing to scale. Returning a
        // huge factor instead would let a cloudy hour dominate the result.
        if (referenceSpan <= 0 || nightSpan <= 0) return null
        return (referenceSpan / nightSpan) to referenceBackground
    }

    /**
     * The cold-start offset between two nights' masters.
     *
     * The wide search T-6.8 asks for, and it is [PhaseCorrelation] unchanged — the same primitive
     * T-4.7 uses to rescue a starless session, here because between nights there is no seed to
     * start from. It searches the whole frame by construction, which is the property that matters:
     * the tripod was moved and the framing is off by an unknown fraction of the frame.
     *
     * @return null when the two masters do not correlate confidently enough to be the same field
     */
    fun offsetBetween(
        reference: FloatArray,
        night: FloatArray,
        width: Int,
        height: Int,
    ): PhaseCorrelation.Result? {
        val result = PhaseCorrelation.between(reference, night, width, height)
        return result.takeIf { it.usable }
    }
}
