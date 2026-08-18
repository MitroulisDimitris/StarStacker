package com.starstacker.stars

import kotlin.math.roundToInt

/**
 * T-3.14 — how far the field has slid between two frames, in pixels, translation only.
 *
 * **Not registration, and deliberately not.** FR-7.3's real transform is Phase 2's job: asterism
 * matching, RANSAC, rotation and scale. This exists to keep a *preview* roughly aligned so the
 * user can see the stack building, and D-18 caps what it may cost — anything heavier competes with
 * capture for thermal budget, which raises dark current in the frames still being taken. A preview
 * that degrades the data it is previewing is a bad trade at any resolution.
 *
 * Translation-only also means it goes wrong in a known direction. On a fixed alt-az tripod the
 * field *rotates*, so stars far from the centre of rotation smear even when the offset is perfect.
 * That is expected, it is the visible reminder that this is a preview and not the stack, and it is
 * exactly what Phase 2 removes.
 *
 * ### Offset voting rather than matching
 *
 * Pairing star A in one frame with star A in another is the hard problem asterism matching solves.
 * It can be skipped here: **every correct pair yields the same offset, and wrong pairs scatter.**
 * So the offsets of all pairs are binned and the fullest bin wins — a vote in which the signal
 * agrees and the noise does not. With the brightest [MAX_STARS] from each frame that is a few
 * hundred candidates, which costs nothing.
 *
 * The vote also supplies its own confidence: a peak bin with too few votes means the frames do not
 * agree, and [estimate] returns null rather than a plausible number. Cloud, a bump or a genuinely
 * new field all land there, and a preview that skips a frame is better than one that smears it.
 */
object StarOffset {

    /** Brightest stars used from each frame. The vote does not improve much past this. */
    const val MAX_STARS = 25

    /** Bin size in pixels. Wide enough to absorb centroid noise, tight enough to stay a vote. */
    const val BIN_PX = 2.0

    /** Below this many agreeing pairs the answer is noise, not an offset. */
    const val MIN_VOTES = 4

    data class Offset(val dx: Double, val dy: Double, val votes: Int)

    /**
     * @return how far [current] has moved relative to [reference], or null when too few pairs
     *   agree for the answer to mean anything.
     */
    fun estimate(
        reference: List<Star>,
        current: List<Star>,
        maxStars: Int = MAX_STARS,
        binPx: Double = BIN_PX,
        minVotes: Int = MIN_VOTES,
    ): Offset? {
        val from = reference.brightest(maxStars)
        val to = current.brightest(maxStars)
        if (from.isEmpty() || to.isEmpty()) return null

        // Bin key packs the two bin indices into one Long, which keeps this a single hash lookup
        // per candidate rather than an allocation per candidate (FR-12.2).
        val votes = HashMap<Long, Int>(from.size * to.size)
        var bestKey = 0L
        var bestCount = 0
        for (r in from) {
            for (c in to) {
                val bx = ((c.x - r.x) / binPx).roundToInt()
                val by = ((c.y - r.y) / binPx).roundToInt()
                val key = (bx.toLong() shl 32) or (by.toLong() and 0xFFFFFFFFL)
                val count = (votes[key] ?: 0) + 1
                votes[key] = count
                if (count > bestCount) {
                    bestCount = count
                    bestKey = key
                }
            }
        }
        if (bestCount < minVotes) return null

        // Refine: average the pairs that actually landed in the winning bin, so the answer is not
        // quantised to the bin grid it was found on.
        val bx = (bestKey shr 32).toInt()
        val by = bestKey.toInt()
        var sumX = 0.0
        var sumY = 0.0
        var n = 0
        for (r in from) {
            for (c in to) {
                val dx = c.x - r.x
                val dy = c.y - r.y
                if ((dx / binPx).roundToInt() == bx && (dy / binPx).roundToInt() == by) {
                    sumX += dx
                    sumY += dy
                    n++
                }
            }
        }
        if (n == 0) return null
        return Offset(sumX / n, sumY / n, bestCount)
    }

    private fun List<Star>.brightest(limit: Int): List<Star> =
        if (size <= limit) this else sortedByDescending { it.flux }.take(limit)
}
