package com.starstacker.registration

import com.starstacker.capture.FrameGate
import com.starstacker.session.RejectReason
import com.starstacker.stars.CfaBinner
import com.starstacker.stars.StarDetector
import com.starstacker.synth.SyntheticSky
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-4.4 — live registration, the residual monitor, and what the gate does with them.
 *
 * The session-shaped tests below run whole sequences through the real pipeline — render, bin,
 * detect, register — because the thing being tested is **state carried across frames**, which no
 * single-frame test can exercise. Reference selection, baseline drift and spike rejection are all
 * properties of a session rather than of a frame.
 */
class LiveRegistrationTest {

    private val sky = SyntheticSky()

    private fun detect(frame: SyntheticSky.Frame) =
        CfaBinner.binGreen(frame.pixels, frame.width, frame.height, sky.cfaCodes, 2).let { plane ->
            plane to StarDetector(saturationLevel = sky.whiteLevel.toDouble())
                .detect(plane.data, plane.width, plane.height)
        }

    private fun register(
        live: LiveRegistration,
        frame: SyntheticSky.Frame,
    ): LiveRegistration.Outcome {
        val (plane, stars) = detect(frame)
        return live.register(stars.stars, plane, sky.width, sky.height)
    }

    // ------------------------------------------------------------------ the residual monitor

    @Test
    fun `the monitor declines to judge before it has a baseline`() {
        // The lesson from 1.16, applied again: a phase too short to judge is inconclusive, not a
        // pass. Reporting STEADY here would be an assertion nobody measured.
        val monitor = ResidualMonitor()
        // Five frames *form* the baseline, so the fifth is still unjudged and the sixth is the
        // first with anything to be judged against.
        repeat(ResidualMonitor.DEFAULT_MIN_FRAMES) {
            assertEquals(ResidualMonitor.Verdict.UNKNOWN, monitor.observe(0.3))
        }
        assertEquals(ResidualMonitor.Verdict.STEADY, monitor.observe(0.3))
        assertNotNull(monitor.baselinePx)
    }

    @Test
    fun `a steady session stays steady`() {
        val monitor = ResidualMonitor()
        val jitter = listOf(0.28, 0.31, 0.26, 0.33, 0.29, 0.30, 0.27, 0.32, 0.31, 0.28)
        val verdicts = jitter.map { monitor.observe(it) }
        assertTrue(verdicts.drop(5).all { it == ResidualMonitor.Verdict.STEADY }) { "$verdicts" }
    }

    @Test
    fun `a spike is caught against the session's own baseline`() {
        val monitor = ResidualMonitor()
        repeat(6) { monitor.observe(0.30) }
        assertEquals(ResidualMonitor.Verdict.SPIKE, monitor.observe(1.6))
        // And the session carries on afterwards rather than staying alarmed.
        assertEquals(ResidualMonitor.Verdict.STEADY, monitor.observe(0.31))
    }

    @Test
    fun `a spike does not join the baseline that judges it`() {
        // FrameGate learned this for star counts: a baseline that absorbs the frames it rejected
        // drifts towards whatever went wrong, and after a few bumps a bumped frame looks normal.
        val monitor = ResidualMonitor()
        repeat(6) { monitor.observe(0.30) }
        val before = monitor.baselinePx
        repeat(4) { assertEquals(ResidualMonitor.Verdict.SPIKE, monitor.observe(2.0)) }
        assertEquals(before!!, monitor.baselinePx!!, 1e-9) { "the baseline moved towards the spikes" }
    }

    @Test
    fun `a very clean session does not become a twitchy one`() {
        // At a baseline of 0.05 px a bare multiple would call 0.16 px a bump, and ordinary
        // centroid jitter would trip it every few frames. The absolute floor is what stops that.
        val monitor = ResidualMonitor()
        repeat(6) { monitor.observe(0.05) }
        assertEquals(ResidualMonitor.Verdict.STEADY, monitor.observe(0.20))
        assertEquals(ResidualMonitor.Verdict.SPIKE, monitor.observe(1.2))
    }

    @Test
    fun `a nonsense residual is not a verdict`() {
        val monitor = ResidualMonitor()
        assertEquals(ResidualMonitor.Verdict.UNKNOWN, monitor.observe(Double.NaN))
        assertEquals(ResidualMonitor.Verdict.UNKNOWN, monitor.observe(-1.0))
    }

    // ------------------------------------------------------------------ live registration

    @Test
    fun `the first good frame becomes the reference and the rest register against it`() {
        val live = LiveRegistration()
        val stars = sky.field(count = 40, seed = 41)

        val first = register(live, sky.render(stars, 7.4, seed = 1))
        assertTrue(first.isReference)
        assertFalse(first.failed)
        assertTrue(live.hasReference)

        val second = register(
            live,
            sky.render(stars, 7.4, SyntheticSky.Transform(rotationDeg = 1.0, dx = 6.0, dy = -4.0), 2),
        )
        assertNotNull(second.transform)
        assertEquals(1.0, second.transform!!.rotationDeg, 0.2)
        assertEquals(6.0, second.transform!!.dx, 0.8)
        assertFalse(second.failed)
    }

    @Test
    fun `the transform comes back in sensor coordinates, not analysis ones`() {
        // Detection runs on a 2x binned plane. If the conversion were skipped the shift would come
        // back half its true size — a stack aligned to the wrong scale by exactly the bin factor,
        // which is the sort of error that looks like slightly soft focus.
        val live = LiveRegistration()
        val stars = sky.field(count = 40, seed = 42)
        register(live, sky.render(stars, 7.4, seed = 1))
        val moved = register(live, sky.render(stars, 7.4, SyntheticSky.Transform(dx = 20.0), 2))

        assertEquals(20.0, moved.transform!!.dx, 1.0) { "looks like plane coordinates" }
    }

    @Test
    fun `a starless first frame is not adopted as the reference`() {
        // The first frame might be cloud. Adopting it anyway would make every later frame fail
        // against a field of noise — a whole session rejected for starting badly.
        val live = LiveRegistration()
        val overcast = SyntheticSky(skyElectronsPerSecond = 4000.0, hotPixelCount = 0)
        val blank = overcast.render(emptyList(), 7.4, seed = 3)
        val (plane, stars) = CfaBinner.binGreen(
            blank.pixels, blank.width, blank.height, overcast.cfaCodes, 2,
        ).let { p -> p to StarDetector(saturationLevel = 1023.0).detect(p.data, p.width, p.height) }

        val outcome = live.register(stars.stars, plane, overcast.width, overcast.height)

        assertFalse(live.hasReference) { "cloud must not become the reference" }
        // Starved, not failed. There was nothing to register against, so calling it a registration
        // failure would blame the mount for the weather (§1.29).
        assertTrue(outcome.tooFewStars)
        assertFalse(outcome.failed)

        // And the session recovers the moment a real frame arrives.
        val good = register(live, sky.render(sky.field(40, seed = 43), 7.4, seed = 4))
        assertTrue(good.isReference)
    }

    @Test
    fun `a frame of cloud fails registration rather than inventing a transform`() {
        val live = LiveRegistration()
        val stars = sky.field(count = 40, seed = 44)
        register(live, sky.render(stars, 7.4, seed = 1))

        // A completely different star field: what an unrelated patch of sky, or a frame after the
        // tripod was knocked right over, would look like.
        val elsewhere = register(live, sky.render(sky.field(40, seed = 999), 7.4, seed = 2))

        assertTrue(elsewhere.failed) { "invented a transform for an unrelated field" }
        assertNull(elsewhere.transform)
    }

    @Test
    fun `a whole session registers frame after frame against one reference`() {
        // Registering against the reference rather than the previous frame is what keeps errors
        // independent; this walks a drifting session to show the last frame is still placed as
        // well as the first.
        val live = LiveRegistration()
        val stars = sky.field(count = 40, seed = 45)
        val frames = sky.sequence(
            stars, frames = 8, exposureSeconds = 7.4,
            perFrame = SyntheticSky.Transform(rotationDeg = 0.35, dx = 2.0, dy = -1.2),
        )

        val outcomes = frames.map { register(live, it) }

        assertTrue(outcomes.first().isReference)
        val rest = outcomes.drop(1)
        assertTrue(rest.none { it.failed }) { "a frame failed mid-session" }
        rest.forEachIndexed { i, outcome ->
            val step = i + 1
            assertEquals(0.35 * step, outcome.transform!!.rotationDeg, 0.25) { "frame $step rotation" }
            assertEquals(2.0 * step, outcome.transform!!.dx, 1.2) { "frame $step dx" }
        }
        // The last frame is placed as precisely as the first: no accumulated drift.
        assertTrue(rest.last().residualRmsPx < 1.0) { "rms ${rest.last().residualRmsPx}" }
    }

    // ------------------------------------------------------------------ the gate

    @Test
    fun `the gate rejects a fine bump that the accelerometer never saw`() {
        // The whole point of T-4.4: peakTiltDeg is null, so nothing else in the pipeline has any
        // reason to doubt this frame.
        val gate = FrameGate()
        repeat(6) { gate.accept(FrameGate.Metrics(starCount = 120, medianEccentricity = 0.2, saturated = false)) }

        val verdict = gate.accept(
            FrameGate.Metrics(
                starCount = 118,
                medianEccentricity = 0.25,
                saturated = false,
                peakTiltDeg = null,
                registrationBumped = true,
                registrationDetail = "1.60 px against a baseline of 0.30 px",
            ),
        )

        assertFalse(verdict.accepted)
        assertEquals(RejectReason.BUMPED, verdict.reason)
        assertTrue(verdict.detail!!.contains("1.60 px")) { verdict.detail!! }
    }

    @Test
    fun `a thin frame that will not register is called cloud, not a registration failure`() {
        // Ordering matters. Both diagnoses would be true; only one is useful, and telling someone
        // to steady their tripod while a cloud goes over is the wrong advice at 2 a.m.
        val gate = FrameGate()
        repeat(6) { gate.accept(FrameGate.Metrics(starCount = 120, medianEccentricity = 0.2, saturated = false)) }

        val verdict = gate.accept(
            FrameGate.Metrics(
                starCount = 9,
                medianEccentricity = 0.2,
                saturated = false,
                registrationFailed = true,
            ),
        )

        assertEquals(RejectReason.CLOUD, verdict.reason)
    }

    @Test
    fun `a full frame that will not register is a registration failure`() {
        // Normal star count and still unplaceable: that is genuinely registration, not weather.
        val gate = FrameGate()
        repeat(6) { gate.accept(FrameGate.Metrics(starCount = 120, medianEccentricity = 0.2, saturated = false)) }

        val verdict = gate.accept(
            FrameGate.Metrics(
                starCount = 121,
                medianEccentricity = 0.2,
                saturated = false,
                registrationFailed = true,
                registrationDetail = "could not be registered against the reference frame",
            ),
        )

        assertFalse(verdict.accepted)
        assertEquals(RejectReason.REGISTRATION, verdict.reason)
    }

    @Test
    fun `registration silence changes nothing for a frame that was never registered`() {
        // Darks, and the reference frame itself. Neither has a registration result, and neither
        // may be punished for it.
        val gate = FrameGate()
        val verdict = gate.accept(
            FrameGate.Metrics(starCount = 100, medianEccentricity = 0.2, saturated = false),
        )
        assertTrue(verdict.accepted)
    }
}
