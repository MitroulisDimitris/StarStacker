package com.starstacker.capture

import com.starstacker.session.RejectReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-3.10. FR-7.5's point is that the three failures call for three different actions — shorten
 * the sub, wait for the cloud, steady the tripod — so the tests are mostly about the gate telling
 * them apart rather than about it rejecting things.
 */
class FrameGateTest {

    private fun good(stars: Int = 180, ecc: Double = 0.22) =
        FrameGate.Metrics(starCount = stars, medianEccentricity = ecc, saturated = false)

    private fun settle(gate: FrameGate, frames: Int = 5, stars: Int = 180) {
        repeat(frames) { assertTrue(gate.accept(good(stars)).accepted) }
    }

    @Test
    fun `a good frame is accepted`() {
        assertTrue(FrameGate().accept(good()).accepted)
    }

    @Test
    fun `elongated stars are trailing, not cloud`() {
        val gate = FrameGate()
        settle(gate)

        val verdict = gate.accept(good(stars = 175, ecc = 0.78))

        assertEquals(RejectReason.TRAILED, verdict.reason)
        assertTrue(verdict.detail!!.contains("shorten the sub"), verdict.detail!!)
    }

    @Test
    fun `a collapse in star count is cloud, not trailing`() {
        val gate = FrameGate()
        settle(gate, stars = 200)

        val verdict = gate.accept(good(stars = 40))

        assertEquals(RejectReason.CLOUD, verdict.reason)
        assertTrue(verdict.detail!!.contains("baseline"), verdict.detail!!)
    }

    @Test
    fun `movement during the exposure is a bump`() {
        val gate = FrameGate()
        settle(gate)

        val verdict = gate.accept(
            good().copy(peakAccelerationMps2 = 1.4),
        )

        assertEquals(RejectReason.BUMPED, verdict.reason)
    }

    @Test
    fun `a clipped frame is its own diagnosis`() {
        val verdict = FrameGate().accept(
            FrameGate.Metrics(starCount = 0, medianEccentricity = null, saturated = true),
        )

        assertEquals(RejectReason.SATURATED, verdict.reason)
    }

    /**
     * The start of a session has no baseline, and inventing one from a single frame would throw
     * away the first frames of every session.
     */
    @Test
    fun `the star check is skipped until there is a baseline to check against`() {
        val gate = FrameGate()

        assertNull(gate.baselineStarCount)
        assertTrue(gate.accept(good(stars = 12)).accepted, "rejected before a baseline existed")
        assertTrue(gate.accept(good(stars = 400)).accepted)
    }

    /**
     * The property that makes the cloud check work at all: a rejected frame must not join the
     * baseline, or a bank of cloud drags the baseline down to meet it and the frames that follow
     * are accepted as normal.
     */
    @Test
    fun `rejected frames do not drag the baseline down to meet them`() {
        val gate = FrameGate()
        settle(gate, frames = 6, stars = 200)
        val before = gate.baselineStarCount

        repeat(8) {
            assertEquals(RejectReason.CLOUD, gate.accept(good(stars = 30)).reason)
        }

        assertEquals(before, gate.baselineStarCount, "the baseline followed the cloud down")
        assertEquals(RejectReason.CLOUD, gate.accept(good(stars = 30)).reason)
        assertTrue(gate.accept(good(stars = 190)).accepted, "clear sky was not accepted again")
    }

    /**
     * The baseline is relative, so the same star count is fine on one rig and cloud on another —
     * which is the reason it is not an absolute threshold.
     */
    @Test
    fun `the same star count passes on a sparse field and fails on a rich one`() {
        val sparse = FrameGate().also { settle(it, stars = 60) }
        val rich = FrameGate().also { settle(it, stars = 600) }

        assertTrue(sparse.accept(good(stars = 55)).accepted)
        assertEquals(RejectReason.CLOUD, rich.accept(good(stars = 55)).reason)
    }

    @Test
    fun `the baseline follows a slowly improving sky`() {
        val gate = FrameGate(baselineWindow = 5)
        settle(gate, frames = 5, stars = 100)
        assertEquals(100, gate.baselineStarCount)

        repeat(5) { gate.accept(good(stars = 300)) }

        assertEquals(300, gate.baselineStarCount, "the baseline did not follow the sky clearing")
    }

    @Test
    fun `a starless frame with no eccentricity to measure is cloud rather than trailing`() {
        val gate = FrameGate()
        settle(gate, stars = 200)

        val verdict = gate.accept(
            FrameGate.Metrics(starCount = 0, medianEccentricity = null, saturated = false),
        )

        assertEquals(RejectReason.CLOUD, verdict.reason)
    }
}
