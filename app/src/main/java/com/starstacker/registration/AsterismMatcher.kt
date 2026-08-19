package com.starstacker.registration

import com.starstacker.stars.Star
import kotlin.math.abs
import kotlin.math.hypot

/**
 * T-4.2 / FR-7.2 — which star in this frame is which star in the reference.
 *
 * Registration is two problems and this is the hard one. Once you know that star 7 here is star 12
 * there, fitting the transform is least squares (T-4.3). Working out the correspondence, from two
 * lists of dots with no labels, is the part that needs an idea.
 *
 * ### The idea: shapes survive what positions do not
 *
 * A star's *position* changes between frames — that is the whole problem. But a **triangle of three
 * stars** has a shape, and shape is unchanged by moving, turning or scaling the field. So describe
 * every triangle by the ratios of its side lengths, and a triangle in one frame can be recognised
 * in the other without knowing anything about how the camera moved. Each recognised pair proposes
 * three star correspondences; the ones proposed over and over are right.
 *
 * `astroalign` (MIT) is the reference implementation of this approach, and the invariant used here
 * is the same pair of sorted side ratios.
 *
 * ### Three details that decide whether it works
 *
 * **Handedness is kept, not discarded.** A triangle and its mirror image have identical side
 * ratios, so ratios alone match a shape to its reflection — and since the sky applies rotation and
 * translation but never a reflection (**FR-7.3**), every such match is a false one. Storing the
 * sign of the triangle's area throws half the false candidates away for the cost of a subtraction.
 *
 * **Thin triangles are refused.** Three nearly collinear stars have a shape that changes wildly
 * under a small centroid error, so their descriptor is noise wearing a number's clothes. They are
 * dropped rather than matched and then rejected downstream.
 *
 * **Only the brightest stars are used.** Triangle count grows as the cube of the star count, and
 * the faint end of a detection list is where the spurious detections live. Taking the brightest
 * [DEFAULT_MAX_STARS] bounds the work and improves the input at the same time.
 *
 * ### The seeded path, which is why T-4.1 exists
 *
 * When [SkyDrift] can predict the transform, correspondence stops needing an idea at all: move the
 * reference stars by the prediction and pair each with whatever is nearest. That path costs
 * nothing, and — the point — **it works with four stars**, where triangle statistics have nothing
 * to say. Thin cloud and moonlight produce exactly those frames, in the middle of sessions that
 * would otherwise be continuous, so the cheap path is also the robust one.
 *
 * Asterism matching remains the fallback for when there is no fix, no compass, or a pointing near
 * the zenith where the seed refuses to answer.
 */
object AsterismMatcher {

    /** The minimum this needs from a detection. [Star] converts with [of]. */
    data class Detection(val x: Double, val y: Double, val flux: Double) {
        companion object {
            fun of(stars: List<Star>): List<Detection> =
                stars.map { Detection(it.x, it.y, it.flux) }
        }
    }

    /** One star in the reference paired with one in the target, and how sure we are. */
    data class Correspondence(
        val referenceIndex: Int,
        val targetIndex: Int,
        /** How many triangles proposed this pair. 1 for the seeded path, which does not vote. */
        val votes: Int,
    )

    enum class Method {
        /** Triangle shapes. Needs stars; needs no sensors. */
        ASTERISM,

        /** [SkyDrift]'s prediction plus nearest-neighbour. Needs sensors; needs almost no stars. */
        SEEDED,

        /** Neither route produced enough to fit a transform on. */
        NONE,
    }

    data class Match(
        val pairs: List<Correspondence>,
        val method: Method,
    ) {
        val count: Int get() = pairs.size
        val usable: Boolean get() = pairs.size >= MIN_PAIRS_FOR_A_FIT

        companion object {
            val NONE = Match(emptyList(), Method.NONE)
        }
    }

    /**
     * Matches [target] against [reference].
     *
     * @param seed [SkyDrift]'s prediction of where the reference stars will have moved to, when one
     *   is available and trustworthy. Tried first; asterism matching is the fallback.
     */
    fun match(
        reference: List<Detection>,
        target: List<Detection>,
        seed: SkyDrift.Seed? = null,
        frameWidth: Double = 0.0,
        frameHeight: Double = 0.0,
        maxStars: Int = DEFAULT_MAX_STARS,
        descriptorTolerance: Double = DEFAULT_DESCRIPTOR_TOLERANCE,
    ): Match {
        val ref = brightest(reference, maxStars)
        val tgt = brightest(target, maxStars)
        if (ref.size < 3 || tgt.size < 3) return Match.NONE

        if (seed != null && seed.trustworthy) {
            val seeded = matchBySeed(ref, tgt, seed, frameWidth, frameHeight)
            // Only taken when it is convincing. A seed with a stale compass reading produces a
            // handful of coincidental pairings, and half a correspondence set is worse than none:
            // T-4.3's RANSAC would fit the coincidences and report a confident wrong answer.
            if (seeded.size >= MIN_PAIRS_FOR_A_FIT) {
                return Match(seeded, Method.SEEDED)
            }
        }

        val pairs = matchByAsterism(ref, tgt, descriptorTolerance)
        return if (pairs.isEmpty()) Match.NONE else Match(pairs, Method.ASTERISM)
    }

    // ------------------------------------------------------------------ the seeded path

    private fun matchBySeed(
        reference: List<Indexed>,
        target: List<Indexed>,
        seed: SkyDrift.Seed,
        frameWidth: Double,
        frameHeight: Double,
    ): List<Correspondence> {
        val cx = (frameWidth - 1) / 2.0
        val cy = (frameHeight - 1) / 2.0
        val t = Math.toRadians(seed.rotationDeg)
        val cos = kotlin.math.cos(t)
        val sin = kotlin.math.sin(t)

        val taken = HashSet<Int>()
        val pairs = ArrayList<Correspondence>()
        reference.forEach { r ->
            // Where the seed says this star will have gone.
            val ox = r.x - cx
            val oy = r.y - cy
            val px = cx + ox * cos - oy * sin + seed.dx
            val py = cy + ox * sin + oy * cos + seed.dy

            var bestIndex = -1
            var best = Double.MAX_VALUE
            var second = Double.MAX_VALUE
            target.forEach { c ->
                val d = hypot(c.x - px, c.y - py)
                if (d < best) {
                    second = best
                    best = d
                    bestIndex = c.index
                } else if (d < second) {
                    second = d
                }
            }
            // Accepted only when the nearest is both close enough *and* clearly nearer than the
            // runner-up. In a dense field the closest star to a prediction is often not the right
            // one, and an ambiguous pair is an outlier with extra steps.
            if (bestIndex >= 0 && best <= SEED_RADIUS_PX && best < second * SEED_AMBIGUITY &&
                taken.add(bestIndex)
            ) {
                pairs += Correspondence(r.index, bestIndex, votes = 1)
            }
        }
        return pairs
    }

    // ------------------------------------------------------------------ the asterism path

    private fun matchByAsterism(
        reference: List<Indexed>,
        target: List<Indexed>,
        tolerance: Double,
    ): List<Correspondence> {
        val refTriangles = triangles(reference)
        val tgtTriangles = triangles(target).sortedBy { it.ratioLong }
        if (refTriangles.isEmpty() || tgtTriangles.isEmpty()) return emptyList()

        val longs = DoubleArray(tgtTriangles.size) { tgtTriangles[it].ratioLong }
        val votes = HashMap<Long, Int>()
        // How many triangles each reference star belongs to. This is what the votes are measured
        // against: see [resolve].
        val participation = HashMap<Int, Int>()
        refTriangles.forEach {
            participation[it.v0] = (participation[it.v0] ?: 0) + 1
            participation[it.v1] = (participation[it.v1] ?: 0) + 1
            participation[it.v2] = (participation[it.v2] ?: 0) + 1
        }

        refTriangles.forEach { a ->
            // Only triangles whose first ratio is already close can match, so a binary search
            // replaces the outer half of an O(n^2) sweep. With a few thousand triangles a side
            // that is the difference between milliseconds and a visible pause every frame.
            var lo = longs.lowerBound(a.ratioLong - tolerance)
            while (lo < tgtTriangles.size && longs[lo] <= a.ratioLong + tolerance) {
                val b = tgtTriangles[lo]
                lo++
                if (b.clockwise != a.clockwise) continue
                if (abs(a.ratioShort - b.ratioShort) > tolerance) continue
                // The vertex orders are canonical, so matching triangles agree vertex by vertex.
                vote(votes, a.v0, b.v0)
                vote(votes, a.v1, b.v1)
                vote(votes, a.v2, b.v2)
            }
        }
        return resolve(votes, participation)
    }

    /**
     * Turns the vote table into a one-to-one correspondence list.
     *
     * **A vote count means nothing on its own; it has to be measured against how many chances the
     * pair had.** Measured on a 24-star field: a true correspondence collects 251-277 votes out of
     * the 253 triangles its star belongs to — very nearly *every* triangle containing that star
     * finds its partner, which is what being right looks like. Coincidences between two unrelated
     * fields collect 14-35 of the same 253. The two populations are an order of magnitude apart,
     * so the ratio separates them cleanly where an absolute count cannot: with eight stars a true
     * pair earns about 21 votes, which a flat threshold tuned for 24 stars would reject and one
     * tuned for 8 would let every coincidence through.
     *
     * **One-to-one matters too.** A bright star often collects votes from several target stars, and
     * keeping all of them would hand T-4.3 a set that cannot be satisfied by any transform — the
     * fit would then be dragged by contradictions rather than by outliers, which RANSAC handles far
     * less gracefully. Best votes win, ties are dropped rather than guessed.
     *
     * This is a filter, not a verdict. **T-4.3's RANSAC is the real guard** against a set that
     * agrees with itself and not with any rigid transform; the job here is to keep obvious rubbish
     * out of it, and to make a failed match look like a failure rather than like fifteen confident
     * pairs.
     */
    private fun resolve(
        votes: Map<Long, Int>,
        participation: Map<Int, Int>,
    ): List<Correspondence> {
        val ordered = votes.entries.sortedByDescending { it.value }
        val usedRef = HashSet<Int>()
        val usedTgt = HashSet<Int>()
        val out = ArrayList<Correspondence>()
        ordered.forEach { (key, count) ->
            if (count < MIN_VOTES) return@forEach
            val chances = participation[(key ushr 32).toInt()] ?: 0
            if (chances <= 0 || count < chances * MIN_MATCH_FRACTION) return@forEach
            val r = (key ushr 32).toInt()
            val t = (key and 0xFFFFFFFFL).toInt()
            if (usedRef.add(r) && usedTgt.add(t)) {
                out += Correspondence(r, t, count)
            }
        }
        return out
    }

    private fun vote(votes: HashMap<Long, Int>, r: Int, t: Int) {
        val key = (r.toLong() shl 32) or (t.toLong() and 0xFFFFFFFFL)
        votes[key] = (votes[key] ?: 0) + 1
    }

    // ------------------------------------------------------------------ triangles

    private class Indexed(val index: Int, val x: Double, val y: Double)

    /**
     * A triangle's shape, and nothing about where it is.
     *
     * [ratioLong] and [ratioShort] are the sorted side ratios — unchanged by translation, rotation
     * and scale, which is the whole point. [clockwise] is the one thing that is *not* invariant
     * under reflection, kept deliberately: see the class note.
     */
    private class Triangle(
        val v0: Int,
        val v1: Int,
        val v2: Int,
        val ratioLong: Double,
        val ratioShort: Double,
        val clockwise: Boolean,
    )

    private fun triangles(stars: List<Indexed>): List<Triangle> {
        val out = ArrayList<Triangle>()
        for (i in stars.indices) {
            for (j in i + 1 until stars.size) {
                for (k in j + 1 until stars.size) {
                    triangleOf(stars[i], stars[j], stars[k])?.let { out += it }
                }
            }
        }
        return out
    }

    private fun triangleOf(a: Indexed, b: Indexed, c: Indexed): Triangle? {
        val ab = hypot(a.x - b.x, a.y - b.y)
        val bc = hypot(b.x - c.x, b.y - c.y)
        val ca = hypot(c.x - a.x, c.y - a.y)
        val shortest = minOf(ab, bc, ca)
        val longest = maxOf(ab, bc, ca)
        if (shortest < MIN_SIDE_PX) return null
        // Nearly collinear: the ratios of a sliver swing wildly for a fraction of a pixel of
        // centroid error, so the descriptor carries noise rather than shape.
        if (longest / shortest > MAX_ELONGATION) return null

        // Canonical vertex order: each vertex named by the side opposite it, shortest side first.
        // Two matching triangles then agree vertex by vertex without any further work, which is
        // what makes the three votes below meaningful rather than arbitrary.
        val sides = listOf(Triple(bc, a, 0), Triple(ca, b, 1), Triple(ab, c, 2))
            .sortedBy { it.first }
        val v0 = sides[0].second
        val v1 = sides[1].second
        val v2 = sides[2].second
        val d0 = sides[0].first
        val d1 = sides[1].first
        val d2 = sides[2].first
        if (d0 <= 0.0 || d1 <= 0.0) return null

        val cross = (v1.x - v0.x) * (v2.y - v0.y) - (v1.y - v0.y) * (v2.x - v0.x)
        if (abs(cross) < MIN_AREA) return null

        return Triangle(
            v0 = v0.index,
            v1 = v1.index,
            v2 = v2.index,
            ratioLong = d2 / d0,
            ratioShort = d1 / d0,
            clockwise = cross < 0,
        )
    }

    private fun brightest(stars: List<Detection>, limit: Int): List<Indexed> =
        stars.withIndex()
            .sortedByDescending { it.value.flux }
            .take(limit)
            .map { Indexed(it.index, it.value.x, it.value.y) }

    /** First index whose value is >= [target]. The array is sorted ascending. */
    private fun DoubleArray.lowerBound(target: Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Below three pairs there is no transform to fit, only an interpolation through the points. */
    const val MIN_PAIRS_FOR_A_FIT = 3

    /**
     * The brightest N stars are used. Triangle count goes as N³, so this is the one knob that
     * decides whether matching is microseconds or seconds — 30 stars is 4060 triangles.
     */
    const val DEFAULT_MAX_STARS = 30

    /** How close two shape descriptors must be. Roughly a percent of the ratio. */
    const val DEFAULT_DESCRIPTOR_TOLERANCE = 0.02

    /** A pair proposed by only one triangle is a coincidence; two agreeing triangles is evidence. */
    private const val MIN_VOTES = 2

    /**
     * What fraction of a star's triangles must agree before the pairing is believed.
     *
     * Measured separation on a 24-star field is 1.09 for true pairs against 0.14 for coincidences
     * (see [resolve]), so anywhere in between works and the exact value is not delicate. A quarter
     * is chosen low rather than central, because the cost of the two errors is not symmetric: a
     * missing pair costs one star out of dozens, while a rejected *frame* costs the whole exposure.
     * Outliers that survive this are what T-4.3 exists to discard.
     */
    private const val MIN_MATCH_FRACTION = 0.25

    private const val MIN_SIDE_PX = 5.0
    private const val MAX_ELONGATION = 10.0
    private const val MIN_AREA = 25.0

    /** How far from the seed's prediction a star may sit and still be believed. */
    private const val SEED_RADIUS_PX = 8.0

    /** The nearest candidate must be this much nearer than the runner-up to count. */
    private const val SEED_AMBIGUITY = 0.6
}
