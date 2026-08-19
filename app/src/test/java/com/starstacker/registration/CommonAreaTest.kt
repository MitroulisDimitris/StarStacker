package com.starstacker.registration

import com.starstacker.exposure.SessionPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-4.5 — the common area, against cases whose answers are arithmetic.
 *
 * A pure translation makes the overlap a rectangle, so the answer is a multiplication anyone can do
 * on paper. Those cases come first, deliberately: the polygon clipper is the sort of code that
 * *looks* right and is out by a winding order or an inverted transform, and both of those mistakes
 * produce plausible-looking numbers — a common area that shrinks at exactly the right rate in
 * exactly the wrong direction is invisible in a readout.
 */
class CommonAreaTest {

    private val w = 4000.0
    private val h = 3000.0
    private val centreX = (w - 1) / 2.0
    private val centreY = (h - 1) / 2.0

    private fun shift(dx: Double, dy: Double = 0.0) =
        RigidTransform(0.0, dx, dy, centreX, centreY)

    private fun turn(deg: Double) = RigidTransform(deg, 0.0, 0.0, centreX, centreY)

    // ------------------------------------------------------------------ arithmetic cases

    @Test
    fun `an untouched session keeps the whole frame`() {
        val tracker = CommonAreaTracker(w, h)
        tracker.include(null)
        repeat(5) { tracker.include(RigidTransform(0.0, 0.0, 0.0, centreX, centreY)) }
        assertEquals(1.0, tracker.fraction, 1e-9)
        assertEquals(100, tracker.percent)
    }

    @Test
    fun `a pure sideways drift costs exactly its own width`() {
        // 100 px of drift on a 4000 px frame leaves 3900/4000 = 97.5 %. No geometry required.
        val tracker = CommonAreaTracker(w, h)
        tracker.include(null)
        tracker.include(shift(100.0))
        assertEquals(0.975, tracker.fraction, 1e-9)
    }

    @Test
    fun `drift in both axes multiplies`() {
        val tracker = CommonAreaTracker(w, h)
        tracker.include(null)
        tracker.include(shift(200.0, 150.0))
        val expected = (3800.0 / 4000.0) * (2850.0 / 3000.0)
        assertEquals(expected, tracker.fraction, 1e-9)
    }

    @Test
    fun `drift the other way costs the same`() {
        // The sign of the drift cannot matter, and an inverted transform is exactly the bug that
        // would make it matter.
        val a = CommonAreaTracker(w, h).apply { include(null); include(shift(120.0, 90.0)) }
        val b = CommonAreaTracker(w, h).apply { include(null); include(shift(-120.0, -90.0)) }
        assertEquals(a.fraction, b.fraction, 1e-9)
    }

    @Test
    fun `drift accumulates across a session rather than resetting`() {
        // Each frame is measured against the reference, so the last frame is the furthest away and
        // it is the one that decides the answer.
        val tracker = CommonAreaTracker(w, h)
        tracker.include(null)
        (1..10).forEach { tracker.include(shift(it * 20.0)) }
        // The worst frame is 200 px out; nothing before it constrains anything further.
        assertEquals(3800.0 / 4000.0, tracker.fraction, 1e-9)
    }

    @Test
    fun `drift in opposite directions costs the sum, not the maximum`() {
        // Two frames either side of the reference: the sky common to *both* has lost ground at
        // each edge. This is the case a max-of-offsets shortcut would get wrong.
        val tracker = CommonAreaTracker(w, h)
        tracker.include(null)
        tracker.include(shift(100.0))
        tracker.include(shift(-150.0))
        assertEquals((4000.0 - 250.0) / 4000.0, tracker.fraction, 1e-9)
    }

    // ------------------------------------------------------------------ rotation

    @Test
    fun `rotation costs area at the corners`() {
        val tracker = CommonAreaTracker(w, h)
        tracker.include(null)
        tracker.include(turn(2.0))
        assertTrue(tracker.fraction < 1.0)
        assertTrue(tracker.fraction > 0.9) { "2 degrees should be cheap, was ${tracker.fraction}" }
    }

    @Test
    fun `more rotation costs more`() {
        fun at(deg: Double) = CommonAreaTracker(w, h).apply { include(null); include(turn(deg)) }.fraction
        val fractions = listOf(0.5, 1.0, 2.0, 5.0, 10.0).map { at(it) }
        fractions.zipWithNext().forEach { (a, b) ->
            assertTrue(b < a) { "not monotonic: $fractions" }
        }
    }

    @Test
    fun `the sign of the rotation does not matter`() {
        val cw = CommonAreaTracker(w, h).apply { include(null); include(turn(3.0)) }
        val ccw = CommonAreaTracker(w, h).apply { include(null); include(turn(-3.0)) }
        assertEquals(cw.fraction, ccw.fraction, 1e-9)
    }

    @Test
    fun `the measured overlap is never smaller than the planner's inscribed crop`() {
        // The two numbers answer different questions and must not be confused, but they are related
        // and the relation is checkable: SessionPlanner asks how large a *centred rectangle*
        // survives the rotation, and any such rectangle lies inside the true overlap. So the
        // prediction is a lower bound on the measurement, and if it ever were not, one of the two
        // would be wrong.
        listOf(1.0, 2.0, 5.0, 8.0).forEach { deg ->
            val measured = CommonAreaTracker(w, h)
                .apply { include(null); include(turn(deg)) }.fraction
            val predicted = SessionPlanner.commonAreaFraction(deg, 4000, 3000)
            assertTrue(measured >= predicted - 1e-9) {
                "at $deg deg: measured $measured below predicted crop $predicted"
            }
        }
    }

    // ------------------------------------------------------------------ behaviour

    @Test
    fun `a session that wandered off has nothing in common`() {
        val tracker = CommonAreaTracker(w, h)
        tracker.include(null)
        tracker.include(shift(w + 10.0))
        assertEquals(0.0, tracker.fraction, 1e-9)
        assertEquals(0, tracker.percent)
    }

    @Test
    fun `nothing measured is distinguishable from nothing lost`() {
        // Both read 100 %, and they mean completely different things: one is a session that has
        // not started, the other a session in perfect alignment.
        val tracker = CommonAreaTracker(w, h)
        assertFalse(tracker.measured)
        assertEquals(1.0, tracker.fraction, 1e-9)
        tracker.include(null)
        assertTrue(tracker.measured)
    }

    @Test
    fun `a frame with no transform does not narrow anything`() {
        // The reference frame itself, and any accepted frame whose transform is unknown. Guessing
        // a footprint for it would invent a constraint nobody measured.
        val tracker = CommonAreaTracker(w, h)
        tracker.include(null)
        tracker.include(shift(100.0))
        val before = tracker.fraction
        tracker.include(null)
        assertEquals(before, tracker.fraction, 1e-9)
    }

    @Test
    fun `the readout says what it counted`() {
        val tracker = CommonAreaTracker(w, h)
        assertEquals("not measured yet", tracker.describe())
        tracker.include(null)
        tracker.include(shift(100.0))
        assertEquals("98% common to all 2", tracker.describe())
    }

    // ------------------------------------------------------------------ the clipper itself

    @Test
    fun `clipping is unaffected by which way the polygons are wound`() {
        // The failure this guards: a polygon wound the other way clips to *nothing*, which reports
        // as 0 % and looks exactly like a session that drifted off target.
        val square = CommonArea.rectangle(10.0, 10.0)
        val overlapping = listOf(
            CommonArea.Point(5.0, 5.0),
            CommonArea.Point(15.0, 5.0),
            CommonArea.Point(15.0, 15.0),
            CommonArea.Point(5.0, 15.0),
        )
        val forward = CommonArea.area(CommonArea.clip(square, overlapping))
        val backward = CommonArea.area(CommonArea.clip(square, overlapping.reversed()))
        assertEquals(25.0, forward, 1e-9)
        assertEquals(25.0, backward, 1e-9)
    }

    @Test
    fun `clipping by something that does not overlap gives nothing`() {
        val square = CommonArea.rectangle(10.0, 10.0)
        val elsewhere = listOf(
            CommonArea.Point(50.0, 50.0),
            CommonArea.Point(60.0, 50.0),
            CommonArea.Point(60.0, 60.0),
            CommonArea.Point(50.0, 60.0),
        )
        assertEquals(0.0, CommonArea.area(CommonArea.clip(square, elsewhere)), 1e-9)
    }

    @Test
    fun `clipping by something larger changes nothing`() {
        val square = CommonArea.rectangle(10.0, 10.0)
        val bigger = CommonArea.rectangle(100.0, 100.0)
        assertEquals(100.0, CommonArea.area(CommonArea.clip(square, bigger)), 1e-9)
    }

    @Test
    fun `area is independent of where the polygon starts`() {
        val square = CommonArea.rectangle(10.0, 10.0)
        val rotatedStart = square.drop(2) + square.take(2)
        assertEquals(CommonArea.area(square), CommonArea.area(rotatedStart), 1e-9)
    }
}
