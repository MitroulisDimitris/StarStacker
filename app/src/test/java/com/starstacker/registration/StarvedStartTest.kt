package com.starstacker.registration

import com.starstacker.capture.FrameGate
import com.starstacker.session.RejectReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §1.29 — the first frames of a session, which is where the gate had no opinion worth having.
 *
 * Found on the device rather than in a test: a six-frame session indoors reported its first four
 * frames as `REGISTRATION` failures. Frame one *is* the reference and cannot fail to register
 * against itself, and the real problem was that there were no stars. The cloud check could not say
 * so because it speaks only relative to a baseline that does not exist for the first five frames —
 * so the registration check was the only one left with an opinion, and it gave the wrong one.
 *
 * The existing tests missed it because they all primed the gate with good frames first, which is
 * the state a session reaches *after* the moment this goes wrong.
 */
class StarvedStartTest {

    @Test
    fun `the very first frame of a session is never a registration failure`() {
        val gate = FrameGate()
        val verdict = gate.accept(
            FrameGate.Metrics(
                starCount = 2,
                medianEccentricity = null,
                saturated = false,
                registrationFailed = false,
                registrationDetail = "too few stars to start a session on",
            ),
        )
        assertFalse(verdict.accepted)
        assertEquals(RejectReason.CLOUD, verdict.reason) { "was ${verdict.detail}" }
    }

    @Test
    fun `an overcast start is called cloud from the first frame, with no baseline`() {
        // The device case, reproduced: four starless frames before any baseline exists.
        val gate = FrameGate()
        repeat(4) {
            val verdict = gate.accept(
                FrameGate.Metrics(starCount = 1, medianEccentricity = null, saturated = false),
            )
            assertEquals(RejectReason.CLOUD, verdict.reason)
        }
    }

    @Test
    fun `a starved frame does not report itself as a registration failure`() {
        val outcome = LiveRegistration.Outcome.STARVED
        assertTrue(outcome.tooFewStars)
        assertFalse(outcome.failed) { "no reference to fail against" }
    }

    @Test
    fun `a genuine registration failure still reports as one`() {
        // The distinction has to survive: plenty of stars and still unplaceable is the mount or
        // the pointing, not the sky.
        val gate = FrameGate()
        repeat(6) {
            gate.accept(FrameGate.Metrics(starCount = 120, medianEccentricity = 0.2, saturated = false))
        }
        val verdict = gate.accept(
            FrameGate.Metrics(
                starCount = 118,
                medianEccentricity = 0.2,
                saturated = false,
                registrationFailed = true,
            ),
        )
        assertEquals(RejectReason.REGISTRATION, verdict.reason)
    }

    @Test
    fun `a normal frame is unaffected by the floor`() {
        val gate = FrameGate()
        assertTrue(
            gate.accept(
                FrameGate.Metrics(starCount = 40, medianEccentricity = 0.2, saturated = false),
            ).accepted,
        )
    }
}
