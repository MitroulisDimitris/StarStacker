package com.starstacker.registration

import kotlin.math.abs

/**
 * T-4.5 / FR-7.5 — how much sky every frame of the session actually shares.
 *
 * ### What the number means, and why it is not the one Setup predicted
 *
 * A stack can only use sky that **every** frame saw. As the session drifts and the field rotates,
 * the frames stop overlapping perfectly and the usable region shrinks from the edges inwards. This
 * is the live measurement of what is left.
 *
 * `SessionPlanner.commonAreaFraction` already predicts a number that sounds like this one and is
 * not. It answers *"how large a centred rectangle survives this much rotation"* — a crop, computed
 * from the predicted field rotation alone, before a single frame exists. This answers *"what
 * fraction of the reference frame did all the frames actually cover"*, from the measured
 * transforms, and it includes **drift**, which the prediction ignores entirely. On an alt-az tripod
 * drift is usually the larger effect, so the two numbers can differ a great deal and neither is
 * wrong: one is a plan and one is a result.
 *
 * ### Exact, not sampled
 *
 * The intersection of rectangles under rotation is a convex polygon, and convex polygons intersect
 * exactly by clipping — Sutherland–Hodgman, one half-plane at a time. The alternative would be to
 * count pixels on a grid, which is slower *and* approximate, and would make a 2 % change in the
 * readout indistinguishable from sampling noise.
 *
 * It is also naturally incremental, which is what makes it a live figure: the running intersection
 * is a polygon, each new frame clips it a little further, and the cost per frame is a handful of
 * vertices rather than anything that grows with the session.
 */
object CommonArea {

    data class Point(val x: Double, val y: Double)

    /** The frame's own footprint, in its own coordinates. */
    fun rectangle(width: Double, height: Double): List<Point> = listOf(
        Point(0.0, 0.0),
        Point(width, 0.0),
        Point(width, height),
        Point(0.0, height),
    )

    /** Twice the signed area — positive for counter-clockwise winding. */
    private fun signedArea2(polygon: List<Point>): Double {
        if (polygon.size < 3) return 0.0
        var sum = 0.0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum
    }

    fun area(polygon: List<Point>): Double = abs(signedArea2(polygon)) / 2.0

    /**
     * Clips [subject] to the inside of the convex polygon [clip].
     *
     * Winding is normalised rather than assumed. The frames arrive as rectangles built here, but
     * they are rotated by transforms whose sign convention lives in another file, and a polygon
     * that comes back wound the other way would silently clip to *nothing* — an empty common area,
     * reported as 0 %, which looks exactly like a session that drifted off target.
     */
    fun clip(subject: List<Point>, clip: List<Point>): List<Point> {
        if (subject.isEmpty() || clip.size < 3) return emptyList()
        val window = if (signedArea2(clip) < 0) clip.reversed() else clip

        var output = subject
        for (i in window.indices) {
            if (output.isEmpty()) return emptyList()
            val a = window[i]
            val b = window[(i + 1) % window.size]
            output = clipToEdge(output, a, b)
        }
        return output
    }

    /** Keeps whatever lies to the left of the directed edge a→b, adding crossings as it goes. */
    private fun clipToEdge(polygon: List<Point>, a: Point, b: Point): List<Point> {
        val out = ArrayList<Point>(polygon.size + 2)
        for (i in polygon.indices) {
            val current = polygon[i]
            val previous = polygon[(i + polygon.size - 1) % polygon.size]
            val currentIn = side(a, b, current) >= 0
            val previousIn = side(a, b, previous) >= 0

            if (currentIn) {
                if (!previousIn) intersection(previous, current, a, b)?.let { out += it }
                out += current
            } else if (previousIn) {
                intersection(previous, current, a, b)?.let { out += it }
            }
        }
        return out
    }

    private fun side(a: Point, b: Point, p: Point): Double =
        (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    /**
     * Where the segment p→q crosses the infinite line a→b.
     *
     * Solving `cross(b-a, p + t(q-p) - a) = 0` gives `t = -side(a,b,p) / cross(b-a, q-p)`, and
     * **the minus sign is the whole of it**. Dropped, every crossing lands the same distance on the
     * wrong side of the edge, the clipped polygon self-intersects, and its signed area becomes
     * meaningless — which showed up as a common area of 150 units inside two 100-unit squares, and
     * as 21 % for a drift that should have cost 2.5 %.
     */
    private fun intersection(p: Point, q: Point, a: Point, b: Point): Point? {
        val dx = q.x - p.x
        val dy = q.y - p.y
        val denominator = (b.x - a.x) * dy - (b.y - a.y) * dx
        if (abs(denominator) < 1e-12) return null
        val t = -side(a, b, p) / denominator
        return Point(p.x + t * dx, p.y + t * dy)
    }
}

/**
 * T-4.5 — the live `NN %`, accumulated one frame at a time.
 *
 * Only **accepted** frames narrow it. A frame the gate threw out is not going into the stack, so it
 * places no constraint on what the stack can use — and letting a rejected frame shrink the readout
 * would mean a single cloud passing permanently lowered a number that describes the finished image.
 */
class CommonAreaTracker(
    private val width: Double,
    private val height: Double,
) {
    private val full = CommonArea.rectangle(width, height)
    private val fullArea = width * height

    /** The running intersection, in reference-frame coordinates. */
    var polygon: List<CommonArea.Point> = full
        private set

    private var frames = 0

    /** Fraction of the reference frame that every accepted frame has covered, 0–1. */
    val fraction: Double
        get() = if (fullArea <= 0.0) 0.0 else (CommonArea.area(polygon) / fullArea).coerceIn(0.0, 1.0)

    val percent: Int get() = Math.round(fraction * 100).toInt()

    /** True once anything has actually been measured, so the UI can tell 100 % from "no data". */
    val measured: Boolean get() = frames > 0

    /**
     * Folds in one accepted frame.
     *
     * @param transform maps **reference → this frame**, as `RigidFit` produces it. The footprint
     *   this frame contributes is therefore its rectangle carried *back* into reference
     *   coordinates by the inverse: a frame whose stars sit 10 px right of the reference's saw
     *   sky 10 px to the left of what the reference saw. Getting that inversion backwards would
     *   make the common area shrink at exactly the right rate in exactly the wrong direction,
     *   which no readout could reveal.
     */
    fun include(transform: RigidTransform?) {
        frames++
        if (transform == null) return
        val inverse = transform.inverse()
        val footprint = full.map { corner ->
            val (x, y) = inverse.apply(corner.x, corner.y)
            CommonArea.Point(x, y)
        }
        polygon = CommonArea.clip(polygon, footprint)
    }

    /** `87 % of the frame is common to all 42 kept` — FR-7.5's readout. */
    fun describe(): String =
        if (!measured) "not measured yet" else "$percent% common to all $frames"
}
