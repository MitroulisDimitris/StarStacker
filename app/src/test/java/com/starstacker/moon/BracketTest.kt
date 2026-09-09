package com.starstacker.moon

import com.starstacker.session.ExposureSet
import com.starstacker.session.TargetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** T-11.8 and T-11.9 — the target type, and planning a session that shoots two exposures. */
class BracketTest {

    private val teleArcsecPerPx = 24.8
    private val moonPx = 75
    private val gb = 1_000_000_000L

    // ------------------------------------------------------------------ T-11.8

    @Test
    fun `the three target types differ in the two ways that matter`() {
        assertFalse(TargetType.DEEP_SKY.isLunar)
        assertTrue(TargetType.MOON_DISC.isLunar)
        assertTrue(TargetType.MOON_SCENE.isLunar)

        // The distinction the user cannot infer from a viewfinder: one exposure or two.
        assertFalse(TargetType.MOON_DISC.isBracketed, "a disc shot is one metered set")
        assertTrue(TargetType.MOON_SCENE.isBracketed, "a scene shot must bracket")
    }

    // ------------------------------------------------------------------ T-11.9

    private fun plan(
        groundSeconds: Double = 700.0,
        freeBytes: Long = 200 * gb,
        drift: Double = LunarPlan.SIDEREAL_ARCSEC_PER_SEC,
    ) = BracketPlan.plan(
        moonExposureNs = 130_000L,
        moonIso = 400,
        groundExposureNs = 2_500_000_000L,
        groundIso = 400,
        groundSeconds = groundSeconds,
        moonPixelsAcross = moonPx,
        arcsecPerPixel = teleArcsecPerPx,
        freeBytes = freeBytes,
        driftArcsecPerSec = drift,
    )

    @Test
    fun `both sets are planned, and the ground set paces the session`() {
        val p = plan(groundSeconds = 700.0)
        // 700 s of 2.5 s frames.
        assertEquals(280, p.groundFrames)
        assertTrue(p.moonFrames > 0, "the moon set rides along underneath")
        assertEquals(2_500_000_000L, p.groundExposureNs)
        assertEquals(130_000L, p.moonExposureNs)
    }

    @Test
    fun `the moon set is capped by the diminishing-returns ceiling, not by the session length`() {
        // 700 s at the 33.2 ms readout floor would be 21 000 frames, which is absurd.
        val p = plan(groundSeconds = 700.0)
        assertEquals(LunarPlan.MAX_FRAMES, p.moonFrames)
        assertTrue(p.notes.any { it.contains("capped") }, p.notes.toString())
    }

    /**
     * The property that makes the composite work, stated before the shoot rather than discovered
     * in the master.
     */
    @Test
    fun `a long enough ground set is told it will have clean sky behind the moon`() {
        // 6.2 min needed at sidereal on the tele; 700 s is 11.7 min.
        val p = plan(groundSeconds = 700.0)
        assertEquals(7, p.groundMinutesRequired)
        assertTrue(p.meetsGroundMinimum)
        assertTrue(p.notes.any { it.contains("clean sky") }, p.notes.toString())
    }

    @Test
    fun `a short ground set is warned that the moon will leave a streak`() {
        val p = plan(groundSeconds = 120.0)
        assertFalse(p.meetsGroundMinimum)
        assertTrue(p.notes.any { it.contains("streak") }, p.notes.toString())
    }

    @Test
    fun `a slower measured drift demands a longer ground set`() {
        // 2026-09-06's rate was 54% of sidereal because the moon was low, so the disc takes nearly
        // twice as long to clear itself. This is exactly why the rate must not be hard-coded.
        val fast = plan(drift = LunarPlan.SIDEREAL_ARCSEC_PER_SEC)
        val slow = plan(drift = 8.16)
        assertTrue(
            slow.groundMinutesRequired > fast.groundMinutesRequired,
            "${slow.groundMinutesRequired} should exceed ${fast.groundMinutesRequired}",
        )
    }

    @Test
    fun `the ground set is served first when storage is short`() {
        // The ground set cannot be shortened without failing the rejection window; the moon set
        // can. 3 GB free = 750 MB claimable = 29 frames, and all of them should be ground.
        val p = plan(groundSeconds = 700.0, freeBytes = 3 * gb)
        assertEquals(29, p.groundFrames)
        assertEquals(0, p.moonFrames)
        assertTrue(p.notes.any { it.contains("Storage cut") }, p.notes.toString())
    }

    @Test
    fun `ground frames are grouped into bursts, because each burst pays a latency once`() {
        val p = plan(groundSeconds = 700.0)
        assertEquals(28, p.groundBursts, "280 frames in bursts of 10")

        // OI-26 measured 5.19 s to a submission's first frame; the plan must charge for it.
        val exposureOnly = p.groundFrames * 2.5
        assertTrue(
            p.seconds > exposureOnly,
            "%.1f s should exceed the %.1f s of pure exposure".format(p.seconds, exposureOnly),
        )
        assertEquals(
            exposureOnly + 28 * BracketPlan.BURST_LATENCY_MS / 1000.0, p.seconds, 1.0,
        )
    }

    @Test
    fun `the calibration difference between the two sets is stated`() {
        val p = plan()
        assertTrue(
            p.notes.any { it.contains("full calibration") && it.contains("almost none") },
            "the ground set needs darks, flat and gradient removal; the moon set does not",
        )
    }

    @Test
    fun `the suggested ground set is the shortest that clears the rejection window`() {
        val frames = BracketPlan.suggestedGroundFrames(
            groundExposureNs = 2_500_000_000L,
            moonPixelsAcross = moonPx,
            arcsecPerPixel = teleArcsecPerPx,
        )
        // 371 s / 2.5 s = 149 frames.
        assertEquals(149, frames)

        // And taking the suggestion must actually satisfy the check.
        val p = BracketPlan.plan(
            moonExposureNs = 130_000L, moonIso = 400,
            groundExposureNs = 2_500_000_000L, groundIso = 400,
            groundSeconds = frames * 2.5,
            moonPixelsAcross = moonPx, arcsecPerPixel = teleArcsecPerPx,
            freeBytes = 200 * gb,
        )
        assertTrue(p.meetsGroundMinimum, p.notes.toString())
    }

    @Test
    fun `a frame knows which set it belongs to`() {
        val p = plan()
        assertEquals(ExposureSet.GROUND, p.setFor(isGround = true))
        assertEquals(ExposureSet.MOON, p.setFor(isGround = false))
    }
}
