package com.starstacker.moon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * T-11.1 — the metering loop, checked against the session that made it necessary.
 *
 * The anchor throughout is `2026-09-06_0118`: 2 474.6 ms shot at ISO 400 through `f/2.55`, when
 * Looney 11 wanted 0.13 ms. That is **14.2 stops** over and 1 597 pixels of the disc were pinned
 * at white. Every test that matters here asks whether this loop would have caught it.
 */
class LunarExposureTest {

    @Test
    fun `Looney 11 reproduces the plan's worked example`() {
        // f/2.55 gathers (11/2.55)^2 = 18.6x more light than f/11, so 1/100 s becomes 0.54 ms.
        val atIso100 = LunarExposure.looneyElevenNs(2.55f, 100)
        assertEquals(0.537, atIso100 / 1e6, 0.005, "0.54 ms at ISO 100 on f/2.55")

        // ISO is linear, so four times the sensitivity is a quarter of the time.
        val atIso400 = LunarExposure.looneyElevenNs(2.55f, 400)
        assertEquals(0.134, atIso400 / 1e6, 0.002, "0.13 ms at ISO 400")
        assertEquals(4.0, atIso100.toDouble() / atIso400, 0.01)
    }

    @Test
    fun `Looney 11 at f11 is one over ISO by definition`() {
        // The rule's own statement, which is the one value that must come out exactly.
        assertEquals(1.0 / 100, LunarExposure.looneyElevenNs(11f, 100) / 1e9, 1e-6)
        assertEquals(1.0 / 800, LunarExposure.looneyElevenNs(11f, 800) / 1e9, 1e-6)
    }

    @Test
    fun `the 2026-09-06 error measures as 14 point 2 stops`() {
        val shot = 2_474_600_000L
        val wanted = LunarExposure.looneyElevenNs(2.55f, 400)
        assertEquals(14.2, LunarExposure.stopsBetween(shot, wanted), 0.1)
    }

    /**
     * The regression that matters most: a clipped frame must not be corrected by its own peak.
     *
     * A clipped disc reads peak == white, so `target / measured` is 0.70 — a third of a stop. On a
     * frame 14 stops over that is not a correction, it is a rounding error, and the loop would
     * still be clipping after every probe it is allowed.
     */
    @Test
    fun `a clipped probe steps down hard rather than by its own measurement`() {
        val probe = LunarExposure.assess(
            exposureNs = 2_474_600_000L,
            iso = 400,
            peakAdu = 1023.0,
            blackLevel = 64.0,
            whiteLevel = 1023,
            clippedPixels = 1597,
        )
        assertEquals(LunarExposure.Verdict.CLIPPED, probe.verdict)
        val next = checkNotNull(probe.nextExposureNs)

        val stopsMoved = LunarExposure.stopsBetween(probe.exposureNs, next)
        assertTrue(stopsMoved >= 3.5, "a clipped probe must move whole stops, moved $stopsMoved")

        // And specifically NOT the linear correction, which would be the bug.
        val linear = (probe.exposureNs * LunarExposure.TARGET_PEAK).toLong()
        assertTrue(next < linear / 10, "$next should be far below the naive $linear")
    }

    /** The loop has to actually converge from 14 stops out, inside its probe budget. */
    @Test
    fun `the loop rescues the 2026-09-06 exposure within its probe budget`() {
        val black = 64.0
        val white = 1023
        val trueScaleAtOneMs = 0.70 / 0.134 // what fraction of scale 1 ms would give, if unclipped

        var exposureNs = 2_474_600_000L
        var probes = 0
        var settled = false

        while (probes < LunarExposure.MAX_PROBES) {
            probes++
            // A simulated sensor: linear in exposure, clipping at white.
            val unclipped = (exposureNs / 1e6) * trueScaleAtOneMs
            val fraction = minOf(unclipped, 1.0)
            val peak = black + fraction * (white - black)
            val clipped = if (unclipped >= 1.0) 5000 else 0

            val probe = LunarExposure.assess(exposureNs, 400, peak, black, white, clipped)
            if (probe.verdict == LunarExposure.Verdict.SETTLED) {
                settled = true
                assertEquals(0, probe.clippedPixels, "settled frames must not be clipping")
                assertTrue(
                    probe.peakFraction in LunarExposure.ACCEPT_LOW..LunarExposure.ACCEPT_HIGH,
                    "T-11.1 accepts 60-80%, got ${probe.peakFraction}",
                )
                break
            }
            exposureNs = checkNotNull(probe.nextExposureNs) { "probe $probes had nowhere to go" }
        }

        assertTrue(settled, "did not settle in ${LunarExposure.MAX_PROBES} probes")
        // Sanity: it should land near the 0.134 ms Looney 11 predicted, since the simulated sensor
        // was built from that number.
        assertTrue(
            abs(LunarExposure.stopsBetween(exposureNs, 134_000L)) < 1.0,
            "settled at ${exposureNs / 1e6} ms, expected within a stop of 0.134 ms",
        )
    }

    @Test
    fun `a frame inside the band is left alone`() {
        val probe = LunarExposure.assess(
            exposureNs = 500_000L, iso = 100,
            peakAdu = 64.0 + 0.70 * (1023 - 64), blackLevel = 64.0, whiteLevel = 1023,
            clippedPixels = 0,
        )
        assertEquals(LunarExposure.Verdict.SETTLED, probe.verdict)
        assertNull(probe.nextExposureNs, "a settled probe has nothing to try next")
    }

    @Test
    fun `a dim frame is corrected upward by the linear ratio`() {
        // 35% of scale wants exactly twice the exposure to reach 70%.
        val probe = LunarExposure.assess(
            exposureNs = 1_000_000L, iso = 100,
            peakAdu = 64.0 + 0.35 * (1023 - 64), blackLevel = 64.0, whiteLevel = 1023,
            clippedPixels = 0,
        )
        assertEquals(LunarExposure.Verdict.DIM, probe.verdict)
        assertEquals(2_000_000L, probe.nextExposureNs)
    }

    @Test
    fun `a bright but unclipped frame is corrected downward`() {
        val probe = LunarExposure.assess(
            exposureNs = 1_000_000L, iso = 100,
            peakAdu = 64.0 + 0.90 * (1023 - 64), blackLevel = 64.0, whiteLevel = 1023,
            clippedPixels = 0,
        )
        assertEquals(LunarExposure.Verdict.BRIGHT, probe.verdict)
        val next = checkNotNull(probe.nextExposureNs)
        assertTrue(next < 1_000_000L, "should shorten, went to $next")
        assertEquals(777_778.0, next.toDouble(), 2000.0, "0.70/0.90 of 1 ms")
    }

    /**
     * The "refuse to fail quietly" clause: a sensor already at its floor and still clipping has to
     * say so rather than return the same exposure forever.
     */
    @Test
    fun `a sensor pinned at its shortest exposure reports UNREACHABLE`() {
        val floor = 42_000L
        val probe = LunarExposure.assess(
            exposureNs = floor, iso = 50,
            peakAdu = 1023.0, blackLevel = 64.0, whiteLevel = 1023,
            clippedPixels = 900,
            exposureRange = floor..1_000_000_000L,
        )
        assertEquals(LunarExposure.Verdict.UNREACHABLE, probe.verdict)
        assertNull(probe.nextExposureNs)
    }

    @Test
    fun `corrections are capped so one wild measurement cannot run away`() {
        // A near-black frame implies an enormous scale; MAX_STEP has to contain it.
        val probe = LunarExposure.assess(
            exposureNs = 1_000_000L, iso = 100,
            peakAdu = 64.0, blackLevel = 64.0, whiteLevel = 1023,
            clippedPixels = 0,
        )
        val next = checkNotNull(probe.nextExposureNs)
        assertEquals((1_000_000L * LunarExposure.MAX_STEP).toLong(), next)
    }
}
