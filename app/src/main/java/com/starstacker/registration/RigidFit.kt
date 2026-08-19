package com.starstacker.registration

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A rotation about a point, followed by a shift. Three degrees of freedom, which is all the sky
 * has (**FR-7.3**).
 *
 * **No scale and no shear**, deliberately. The lens does not zoom between frames and the sky does
 * not stretch, so a fit with more freedom than the physics has will spend that freedom absorbing
 * noise — a four-parameter fit will happily shrink the field a fraction of a percent to reduce a
 * residual that came from centroid error, and every star then lands slightly wrong in a way no
 * later stage can undo.
 *
 * The rotation centre is carried explicitly rather than assumed. Rotation about the origin and
 * rotation about the frame centre describe the same family of transforms with different numbers,
 * and silently mixing the two conventions is the sort of error that produces a plausible-looking
 * stack with soft corners. [SkyDrift.Seed] and the synthetic sky both rotate about the frame
 * centre, so that is what this expects to be given.
 */
data class RigidTransform(
    val rotationDeg: Double,
    val dx: Double,
    val dy: Double,
    val centreX: Double,
    val centreY: Double,
) {
    fun apply(x: Double, y: Double): Pair<Double, Double> {
        val t = Math.toRadians(rotationDeg)
        val ox = x - centreX
        val oy = y - centreY
        return Pair(
            centreX + ox * cos(t) - oy * sin(t) + dx,
            centreY + ox * sin(t) + oy * cos(t) + dy,
        )
    }

    /** The transform that undoes this one — what stacking applies to bring a frame home. */
    fun inverse(): RigidTransform {
        val t = Math.toRadians(-rotationDeg)
        // Undo the shift first, then the rotation, and re-express the result about the same centre.
        val ix = -(dx * cos(t) - dy * sin(t))
        val iy = -(dx * sin(t) + dy * cos(t))
        return RigidTransform(-rotationDeg, ix, iy, centreX, centreY)
    }

    /** The six numbers FR-9.2 stores per frame: `[a, b, c, d, tx, ty]`. */
    fun toMatrix(): List<Double> {
        val t = Math.toRadians(rotationDeg)
        val c = cos(t)
        val s = sin(t)
        return listOf(
            c, -s, s, c,
            centreX - centreX * c + centreY * s + dx,
            centreY - centreX * s - centreY * c + dy,
        )
    }
}

/**
 * T-4.3 / FR-7.3 — the transform, fitted to correspondences that are not all correct.
 *
 * ### Why a plain least-squares fit is not enough
 *
 * Least squares is the right answer to "what transform best explains these pairs" and the wrong
 * answer to the question actually being asked, because it assumes every pair is a measurement of
 * the same thing. One mismatched star — and T-4.2 hands over a few, by design, since it filters
 * rather than adjudicates — drags the fit towards a compromise that fits nothing. Squared error
 * makes that worse: an outlier ten pixels out pulls a hundred times harder than a good pair a
 * pixel out.
 *
 * **RANSAC inverts the problem.** Rather than fitting everything and hoping, it repeatedly fits the
 * *minimum* number of pairs that determine a transform, and asks how many of the rest agree. A
 * hypothesis built from two correct pairs is confirmed by all the other correct ones; a hypothesis
 * built from an outlier is confirmed by nothing. The largest agreement wins, and the answer is then
 * refitted on all of its supporters — the sample chooses the inliers, the inliers give the
 * precision.
 *
 * ### Two pairs, because that is what three degrees of freedom cost
 *
 * A rotation and a shift are three numbers; each matched star supplies two equations. Two pairs is
 * therefore the smallest sample that determines the answer, and small samples are what make RANSAC
 * work — the chance of drawing an all-inlier sample falls off with sample size, so a method needing
 * four pairs needs far more attempts at the same outlier rate.
 *
 * ### The seed is a free hypothesis
 *
 * T-4.1 predicts the transform outright, so it is tried as a candidate alongside the random
 * samples. When the compass is good it wins immediately and RANSAC merely polishes it; when the
 * compass is stale it collects no support and costs one evaluation. That is what "refining the
 * seed" means here: not trusting it, scoring it.
 */
object RigidFit {

    /**
     * @param inliers the correspondences that agreed with [transform] — the ones a caller should
     *   keep, and the count that says whether to believe any of it.
     * @param residualRmsPx how far the inliers miss, in pixels. **T-4.4 watches this**: a spike in
     *   an otherwise steady session is the signature of the tripod being knocked, which no single
     *   frame's star shapes would reveal.
     */
    data class Result(
        val transform: RigidTransform?,
        val inliers: List<AsterismMatcher.Correspondence>,
        val residualRmsPx: Double,
        val attempted: Int,
    ) {
        val inlierCount: Int get() = inliers.size
        val succeeded: Boolean get() = transform != null

        companion object {
            fun failed(attempted: Int = 0) =
                Result(null, emptyList(), Double.NaN, attempted)
        }
    }

    /**
     * Fits a rigid transform to [pairs], discarding whatever disagrees.
     *
     * @param seed tried as a hypothesis before any random sample. Scored like every other, never
     *   trusted.
     * @param random seeded by default, because **a registration result that changes between runs
     *   cannot be tested or debugged** — a frame that stacks today and is rejected tomorrow on the
     *   same data is a bug nobody can reproduce.
     */
    fun fit(
        reference: List<AsterismMatcher.Detection>,
        target: List<AsterismMatcher.Detection>,
        pairs: List<AsterismMatcher.Correspondence>,
        centreX: Double,
        centreY: Double,
        seed: SkyDrift.Seed? = null,
        tolerancePx: Double = DEFAULT_TOLERANCE_PX,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        random: Random = Random(DETERMINISTIC_SEED),
    ): Result {
        if (pairs.size < MIN_PAIRS) return Result.failed()

        var best: List<AsterismMatcher.Correspondence> = emptyList()
        var attempted = 0

        // The seed first: when the compass is good this is already the answer, and the loop below
        // then only has to fail to beat it.
        if (seed != null && seed.trustworthy) {
            val hypothesis = RigidTransform(seed.rotationDeg, seed.dx, seed.dy, centreX, centreY)
            best = consensus(reference, target, pairs, hypothesis, tolerancePx)
            attempted++
        }

        // Adaptive: once a large consensus is found, the chance that a better one is still hiding
        // collapses, so the loop stops early rather than grinding out a fixed count. On the clean
        // sets T-4.2 usually produces this exits after a handful of samples.
        var limit = maxIterations
        var i = 0
        while (i < limit) {
            i++
            attempted++
            val a = pairs[random.nextInt(pairs.size)]
            val b = pairs[random.nextInt(pairs.size)]
            if (a.referenceIndex == b.referenceIndex) continue

            val hypothesis = fromTwo(reference, target, a, b, centreX, centreY) ?: continue
            val support = consensus(reference, target, pairs, hypothesis, tolerancePx)
            if (support.size > best.size) {
                best = support
                limit = minOf(limit, iterationsNeeded(support.size.toDouble() / pairs.size))
            }
        }

        if (best.size < MIN_PAIRS) return Result.failed(attempted)

        // Refit on the whole consensus. The minimal sample was only ever a way of *finding* the
        // inliers; two pairs carry two pairs' worth of centroid noise, and twenty carry far less.
        val refined = leastSquares(reference, target, best, centreX, centreY)
            ?: return Result.failed(attempted)
        // The refit can shift the transform enough to change who agrees with it, so consensus is
        // taken once more. Without this the reported inliers describe the sample's transform and
        // the returned transform is a different one.
        val finalInliers = consensus(reference, target, pairs, refined, tolerancePx)
        if (finalInliers.size < MIN_PAIRS) return Result.failed(attempted)

        return Result(
            transform = refined,
            inliers = finalInliers,
            residualRmsPx = rms(reference, target, finalInliers, refined),
            attempted = attempted,
        )
    }

    /**
     * The exact least-squares rigid transform for a set of correspondences — Kabsch in two
     * dimensions, in closed form.
     *
     * Closed form rather than iterative because there is no reason to iterate: the optimum of a
     * sum of squared distances under rotation is an arctangent of two sums, exactly, and an
     * iterative solver would add a convergence criterion and a failure mode in exchange for
     * nothing.
     */
    fun leastSquares(
        reference: List<AsterismMatcher.Detection>,
        target: List<AsterismMatcher.Detection>,
        pairs: List<AsterismMatcher.Correspondence>,
        centreX: Double,
        centreY: Double,
    ): RigidTransform? {
        if (pairs.size < 2) return null
        var ax = 0.0
        var ay = 0.0
        var bx = 0.0
        var by = 0.0
        pairs.forEach {
            val r = reference.getOrNull(it.referenceIndex) ?: return null
            val t = target.getOrNull(it.targetIndex) ?: return null
            ax += r.x; ay += r.y; bx += t.x; by += t.y
        }
        val n = pairs.size
        ax /= n; ay /= n; bx /= n; by /= n

        var sinSum = 0.0
        var cosSum = 0.0
        pairs.forEach {
            val r = reference[it.referenceIndex]
            val t = target[it.targetIndex]
            val ux = r.x - ax
            val uy = r.y - ay
            val vx = t.x - bx
            val vy = t.y - by
            sinSum += ux * vy - uy * vx
            cosSum += ux * vx + uy * vy
        }
        // Every point on top of its own centroid: no rotation is determined, and returning zero
        // would be a guess dressed as a measurement.
        if (hypot(sinSum, cosSum) < DEGENERATE) return null

        val theta = atan2(sinSum, cosSum)
        val c = cos(theta)
        val s = sin(theta)
        // Re-express the shift about the requested centre: p' = R(p - centre) + centre + d.
        val rx = (ax - centreX) * c - (ay - centreY) * s + centreX
        val ry = (ax - centreX) * s + (ay - centreY) * c + centreY
        return RigidTransform(Math.toDegrees(theta), bx - rx, by - ry, centreX, centreY)
    }

    /** The transform through two pairs exactly — RANSAC's minimal sample. */
    private fun fromTwo(
        reference: List<AsterismMatcher.Detection>,
        target: List<AsterismMatcher.Detection>,
        a: AsterismMatcher.Correspondence,
        b: AsterismMatcher.Correspondence,
        centreX: Double,
        centreY: Double,
    ): RigidTransform? {
        val r0 = reference.getOrNull(a.referenceIndex) ?: return null
        val r1 = reference.getOrNull(b.referenceIndex) ?: return null
        val t0 = target.getOrNull(a.targetIndex) ?: return null
        val t1 = target.getOrNull(b.targetIndex) ?: return null
        // Two stars close together define their separation angle poorly, so the rotation they
        // imply is mostly centroid noise — a bad hypothesis wastes an iteration and, worse,
        // occasionally collects enough accidental support to win.
        if (hypot(r1.x - r0.x, r1.y - r0.y) < MIN_BASELINE_PX) return null
        if (hypot(t1.x - t0.x, t1.y - t0.y) < MIN_BASELINE_PX) return null
        return leastSquares(reference, target, listOf(a, b), centreX, centreY)
    }

    private fun consensus(
        reference: List<AsterismMatcher.Detection>,
        target: List<AsterismMatcher.Detection>,
        pairs: List<AsterismMatcher.Correspondence>,
        transform: RigidTransform,
        tolerancePx: Double,
    ): List<AsterismMatcher.Correspondence> = pairs.filter {
        val r = reference.getOrNull(it.referenceIndex)
        val t = target.getOrNull(it.targetIndex)
        if (r == null || t == null) {
            false
        } else {
            val (px, py) = transform.apply(r.x, r.y)
            hypot(t.x - px, t.y - py) <= tolerancePx
        }
    }

    private fun rms(
        reference: List<AsterismMatcher.Detection>,
        target: List<AsterismMatcher.Detection>,
        pairs: List<AsterismMatcher.Correspondence>,
        transform: RigidTransform,
    ): Double {
        if (pairs.isEmpty()) return Double.NaN
        var sum = 0.0
        pairs.forEach {
            val r = reference[it.referenceIndex]
            val t = target[it.targetIndex]
            val (px, py) = transform.apply(r.x, r.y)
            val d = hypot(t.x - px, t.y - py)
            sum += d * d
        }
        return sqrt(sum / pairs.size)
    }

    /**
     * How many samples are still worth drawing, given how good the best consensus already is.
     *
     * The standard RANSAC bound: to be [CONFIDENCE] sure of drawing at least one sample free of
     * outliers, when a fraction *w* of the pairs are inliers and a sample is [MIN_PAIRS] of them,
     * needs `log(1 − confidence) / log(1 − w²)` attempts. With a clean correspondence set that is
     * two or three, which is why this loop usually stops almost immediately.
     */
    private fun iterationsNeeded(inlierFraction: Double): Int {
        if (inlierFraction <= 0.0) return DEFAULT_MAX_ITERATIONS
        val p = inlierFraction * inlierFraction
        if (p >= 1.0 - 1e-9) return 1
        val needed = ln(1.0 - CONFIDENCE) / ln(1.0 - p)
        return needed.coerceIn(1.0, DEFAULT_MAX_ITERATIONS.toDouble()).toInt()
    }

    /** Below three there is nothing to disagree with, so nothing has been verified. */
    const val MIN_PAIRS = 3

    /**
     * How far a star may miss the prediction and still be counted an inlier.
     *
     * Detection is good to about a tenth of a pixel on a clean star (T-2.3), so two pixels is
     * generous — deliberately. The threshold's job is to separate *the right star* from *a
     * different star*, and stars are rarely two pixels apart; tightening it towards the centroid
     * precision would start rejecting good pairs on nothing but seeing and focus drift.
     */
    const val DEFAULT_TOLERANCE_PX = 2.0

    const val DEFAULT_MAX_ITERATIONS = 400

    /** Two stars nearer than this define their separation angle too poorly to hypothesise from. */
    private const val MIN_BASELINE_PX = 20.0

    private const val CONFIDENCE = 0.999
    private const val DEGENERATE = 1e-9

    /** Fixed so the same frames always register the same way. See [fit]. */
    private const val DETERMINISTIC_SEED = 7
}
