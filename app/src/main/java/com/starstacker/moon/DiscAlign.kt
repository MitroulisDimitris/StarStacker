package com.starstacker.moon

import com.starstacker.registration.RigidTransform
import kotlin.math.abs
import kotlin.math.hypot

/**
 * T-11.5 — aligning lunar frames on the disc itself.
 *
 * ### Why star matching cannot do this
 *
 * `AsterismMatcher` works by matching *triangles of point sources* between frames. A lunar frame
 * has no point sources: the detector's brightest returns all sit inside one ~30 px blob, which is
 * exactly the failure that lost 34 of 67 frames on 2026-09-06 (§T-4.7). There is nothing to build
 * an asterism from, and there never will be.
 *
 * What a lunar frame does have is one large, bright, unambiguous object, and its centroid is
 * measurable to a fraction of a pixel. So alignment is a difference of centroids.
 *
 * ### Translation only, and why that is not a compromise
 *
 * Field rotation over a lucky-imaging run is negligible — the run is seconds to a couple of minutes
 * and the object is small, so rotation about the frame centre displaces the disc by far less than
 * the seeing does. A rigid fit with a rotation term would be fitting noise. [asTransform] therefore
 * returns a [RigidTransform] with `rotationDeg = 0`, which is the existing type the warp already
 * consumes, so nothing downstream needs to know this came from a different matcher.
 *
 * ### The centroid is not the centre of the moon, and that is fine
 *
 * On a crescent the intensity-weighted centroid sits inside the lit horn, nowhere near the centre
 * of the lunar globe. Alignment does not care: it needs *the same point measured the same way* in
 * every frame, and the centroid is that. It only breaks if the phase changes appreciably during the
 * run, which over minutes it does not.
 *
 * The one thing that does break it is **libration and rotation of the terminator over hours**, so
 * this is documented as valid within a session and not across sessions.
 *
 * ### Its relationship to T-4.7
 *
 * T-4.7 needs star-free registration too, and wants phase correlation over the whole frame because
 * its scenes have no single dominant object. That primitive is strictly more general than this one
 * and lives with T-4.7. This is the cheap case: when there *is* one bright object, its centroid is
 * more accurate than a whole-frame correlation and costs one pass over the pixels.
 */
object DiscAlign {

    /**
     * A shift beyond this fraction of the frame is not the moon drifting, it is a mis-detection —
     * cloud, an aircraft, or the disc leaving frame.
     */
    const val MAX_SHIFT_FRACTION = 0.25

    /** How much the disc's area may change between frames before the match is distrusted. */
    const val MAX_AREA_RATIO = 2.0

    sealed interface Result {
        data class Matched(
            val dx: Double,
            val dy: Double,
            /** How far the disc moved, in pixels. */
            val shiftPx: Double,
        ) : Result

        data class Rejected(val reason: String) : Result
    }

    /**
     * The shift that takes [frame] onto [reference].
     *
     * Sign convention matches `RigidFit`: the transform maps *reference* coordinates to *frame*
     * coordinates, which is the direction the warp samples in.
     */
    fun match(
        reference: Disc.Found?,
        frame: Disc.Found?,
        frameWidth: Int,
        frameHeight: Int,
    ): Result {
        if (reference == null) return Result.Rejected("no disc in the reference frame")
        if (frame == null) return Result.Rejected("no disc found")

        // A disc that has changed size between frames is not the same detection: cloud eats the
        // limb, and a threshold that caught a passing aircraft catches far more area. Either way
        // the centroid is measuring something other than the moon.
        val areaRatio = if (reference.pixelCount == 0) Double.MAX_VALUE else {
            frame.pixelCount.toDouble() / reference.pixelCount
        }
        if (areaRatio > MAX_AREA_RATIO || areaRatio < 1.0 / MAX_AREA_RATIO) {
            return Result.Rejected(
                "the disc changed area by %.1fx against the reference (%d px against %d) — cloud, "
                    .format(
                        if (areaRatio > 1) areaRatio else 1 / areaRatio,
                        frame.pixelCount, reference.pixelCount,
                    ) + "or the detection caught something else",
            )
        }

        val dx = frame.centroidX - reference.centroidX
        val dy = frame.centroidY - reference.centroidY
        val shift = hypot(dx, dy)
        val limit = MAX_SHIFT_FRACTION * minOf(frameWidth, frameHeight)
        if (shift > limit) {
            return Result.Rejected(
                "the disc moved %.0f px, past the %.0f px sanity limit — the detection is not on "
                    .format(shift, limit) + "the same object",
            )
        }

        return Result.Matched(dx, dy, shift)
    }

    /**
     * The match as the transform type the rest of the pipeline already speaks.
     *
     * `rotationDeg` is zero by construction (see the class note), and the centre is the frame
     * centre only because a zero rotation makes it irrelevant — it is filled in so the value is
     * well-formed rather than because anything reads it.
     */
    fun asTransform(
        matched: Result.Matched,
        frameWidth: Int,
        frameHeight: Int,
    ): RigidTransform = RigidTransform(
        rotationDeg = 0.0,
        dx = matched.dx,
        dy = matched.dy,
        centreX = frameWidth / 2.0,
        centreY = frameHeight / 2.0,
    )

    /**
     * Fits a straight line to how the disc moved over time, and reads it at [atEpochMs].
     *
     * T-11.10 needs this to place the moon in a ground stack whose long frames were shot on a
     * different cadence, and it doubles as a measurement of the night's true drift rate — which
     * §1.45 insists must be measured rather than assumed, since the 2026-09-06 figure was 54% of
     * sidereal purely because the moon was low.
     *
     * Least squares rather than first-to-last: every frame's centroid carries seeing noise, and
     * a two-point slope inherits all of it.
     *
     * @return null when fewer than two distinct times were supplied
     */
    fun fitDrift(
        samples: List<Triple<Long, Double, Double>>,
        atEpochMs: Long,
    ): Pair<Double, Double>? {
        if (samples.size < 2) return null
        val meanT = samples.map { it.first.toDouble() }.average()
        if (samples.all { abs(it.first - meanT) < 1e-9 }) return null

        fun slope(value: (Triple<Long, Double, Double>) -> Double): Pair<Double, Double> {
            val meanV = samples.map(value).average()
            var num = 0.0
            var den = 0.0
            for (s in samples) {
                val dt = s.first - meanT
                num += dt * (value(s) - meanV)
                den += dt * dt
            }
            val m = if (den == 0.0) 0.0 else num / den
            return m to (meanV - m * meanT)
        }

        val (mx, cx) = slope { it.second }
        val (my, cy) = slope { it.third }
        return (mx * atEpochMs + cx) to (my * atEpochMs + cy)
    }
}
