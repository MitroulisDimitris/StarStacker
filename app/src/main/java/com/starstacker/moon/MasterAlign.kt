package com.starstacker.moon

import kotlin.math.hypot

/**
 * T-11.10 — putting the moon stack and the ground stack in the same frame.
 *
 * ### No ephemeris, and no new maths
 *
 * The problem looks like it needs to know where the moon was. It does not. **The moon is a clipped
 * white blob in the long exposures** — that is precisely what makes them unusable for the disc —
 * and a clipped blob has a perfectly good centroid. Match it against the disc centroid of the short
 * stack and the two layers line up.
 *
 * ### Why the blob is tracked per frame rather than measured once
 *
 * The disc moves during the ground set: at the reference tele's worst case, 0.606 px/s, an
 * eleven-minute set is 400 px. In the *stacked* ground master that is a streak, and the centroid of
 * a streak is the mid-time position — usable, but blunt, and wrong if the set is not symmetric
 * about its own middle (a frame dropped for cloud is enough).
 *
 * So the blob is measured in **every** long frame, a straight line fitted through those positions
 * ([DiscAlign.fitDrift]), and the line evaluated at the ground stack's reference epoch. That is
 * both more accurate and free: the frames are being read anyway. It also **measures the night's
 * true drift rate as a by-product**, which is what §1.45 insists on after the 2026-09-06 figure
 * turned out to be 54% of sidereal purely because the moon was low.
 *
 * ### Align on centre, never on edge
 *
 * A hard-clipped disc **blooms past its true limb**: charge spills, and the lit area in a long
 * frame is larger than the moon. The centroid survives that, because blooming is roughly
 * symmetric; the *radius* does not. So nothing here fits a circle or matches a limb, and the size
 * check is deliberately loose — it is there to catch a detection that is not the moon at all, not
 * to measure the moon.
 */
object MasterAlign {

    /**
     * How much bigger the clipped blob may be than the metered disc before the match is refused.
     *
     * Generous on purpose. Blooming in a 14-stop overexposure can double the apparent diameter,
     * which is four times the area — so this is not a measurement, it is a check that the two
     * detections are looking at the same object rather than one of them having found a streetlight.
     */
    const val MAX_AREA_RATIO = 25.0

    /** Under this many samples, a drift fit is guesswork and the streak centroid is more honest. */
    const val MIN_DRIFT_SAMPLES = 3

    data class Sample(
        val epochMs: Long,
        val disc: Disc.Found,
    )

    sealed interface Result {
        /**
         * @param dx how far the moon master must move to sit where the ground master says it goes
         * @param driftPxPerSec measured, not assumed — the by-product worth keeping
         */
        data class Matched(
            val dx: Double,
            val dy: Double,
            val moonAtEpochX: Double,
            val moonAtEpochY: Double,
            val driftPxPerSec: Double?,
            val fromDriftFit: Boolean,
            val note: String,
        ) : Result

        data class Rejected(val reason: String) : Result
    }

    /**
     * Aligns the moon master onto the ground master.
     *
     * @param groundSamples the clipped blob in each *long* frame, with its capture time
     * @param referenceEpochMs the ground stack's reference epoch — the instant the composite is of
     * @param moonDisc the disc in the stacked moon master
     */
    fun align(
        groundSamples: List<Sample>,
        referenceEpochMs: Long,
        moonDisc: Disc.Found?,
        frameWidth: Int,
        frameHeight: Int,
    ): Result {
        if (moonDisc == null) return Result.Rejected("no disc in the moon master")
        if (groundSamples.isEmpty()) {
            return Result.Rejected("the moon was not found in any ground frame")
        }

        // Blooming makes the blob larger, never smaller, so an *under*-sized blob means the
        // detection is not the moon. Both directions are checked because either is a mismatch.
        val meanArea = groundSamples.map { it.disc.pixelCount }.average()
        val ratio = if (moonDisc.pixelCount == 0) Double.MAX_VALUE else meanArea / moonDisc.pixelCount
        if (ratio > MAX_AREA_RATIO || ratio < 1.0 / MAX_AREA_RATIO) {
            return Result.Rejected(
                "the blob in the ground frames is %.1fx the metered disc's area (%.0f px against "
                    .format(ratio, meanArea) +
                    "%d) — too far apart to be the same object".format(moonDisc.pixelCount),
            )
        }

        val (x, y, drift, fitted) = positionAt(groundSamples, referenceEpochMs)

        val dx = x - moonDisc.centroidX
        val dy = y - moonDisc.centroidY
        val shift = hypot(dx, dy)
        // The two masters are the same camera on the same tripod, so the disc cannot be on the
        // other side of the frame. A shift that large means the fit extrapolated somewhere silly
        // or one detection is wrong.
        val limit = DiscAlign.MAX_SHIFT_FRACTION * minOf(frameWidth, frameHeight) * 2
        if (shift > limit) {
            return Result.Rejected(
                "placing the moon would move it %.0f px, past the %.0f px sanity limit"
                    .format(shift, limit),
            )
        }

        val note = buildString {
            if (fitted) {
                append("drift fitted through ${groundSamples.size} ground frames")
                drift?.let { append(" at %.3f px/s".format(it)) }
                append(", evaluated at the stack's reference epoch")
            } else {
                append(
                    "only ${groundSamples.size} ground frame(s) carried the blob, so its mean " +
                        "position is used rather than a fitted drift",
                )
            }
        }

        return Result.Matched(
            dx = dx,
            dy = dy,
            moonAtEpochX = x,
            moonAtEpochY = y,
            driftPxPerSec = drift,
            fromDriftFit = fitted,
            note = note,
        )
    }

    private data class Position(
        val x: Double,
        val y: Double,
        val driftPxPerSec: Double?,
        val fitted: Boolean,
    )

    private fun positionAt(samples: List<Sample>, epochMs: Long): Position {
        if (samples.size < MIN_DRIFT_SAMPLES) {
            return Position(
                samples.map { it.disc.centroidX }.average(),
                samples.map { it.disc.centroidY }.average(),
                null,
                fitted = false,
            )
        }

        val triples = samples.map { Triple(it.epochMs, it.disc.centroidX, it.disc.centroidY) }
        val at = DiscAlign.fitDrift(triples, epochMs)
            ?: return Position(
                samples.map { it.disc.centroidX }.average(),
                samples.map { it.disc.centroidY }.average(),
                null,
                fitted = false,
            )

        // The rate falls out of the same fit: evaluate a second later and difference.
        val ahead = DiscAlign.fitDrift(triples, epochMs + 1000L)
        val rate = ahead?.let { hypot(it.first - at.first, it.second - at.second) }

        return Position(at.first, at.second, rate, fitted = true)
    }
}
