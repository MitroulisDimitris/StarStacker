package com.starstacker.moon

import com.starstacker.stacking.FrameQuality
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** T-11.4 — the frame-count decision, and the storage tension §1.45 named. */
class LunarPlanTest {

    private val teleArcsecPerPx = 24.8
    private val gb = 1_000_000_000L

    @Test
    fun `with plenty of space the run is capped by diminishing returns, not storage`() {
        val plan = LunarPlan.plan(
            exposureNs = 130_000L, iso = 400, freeBytes = 200 * gb,
            arcsecPerPixel = teleArcsecPerPx,
        )
        assertEquals(LunarPlan.MAX_FRAMES, plan.frameCount)
        assertTrue(plan.note.contains("sqrt(N)"), plan.note)
    }

    @Test
    fun `a nearly full phone is capped by storage and told so`() {
        // 3 GB free, a quarter claimable = 750 MB, at 25 MB a frame = 29 frames.
        val plan = LunarPlan.plan(
            exposureNs = 130_000L, iso = 400, freeBytes = 3 * gb,
            arcsecPerPixel = teleArcsecPerPx,
        )
        assertEquals(29, plan.frameCount)
        assertTrue(plan.note.contains("Storage caps"), plan.note)
        assertTrue(plan.note.contains("3.0 GB free"), plan.note)
    }

    @Test
    fun `too little space to be worth shooting says so rather than shooting anyway`() {
        val plan = LunarPlan.plan(
            exposureNs = 130_000L, iso = 400, freeBytes = 400_000_000L,
            arcsecPerPixel = teleArcsecPerPx,
        )
        assertTrue(plan.frameCount < LunarPlan.MIN_FRAMES)
        assertTrue(plan.note.contains("Free some space"), plan.note)
    }

    /**
     * The cadence has to come from OI-26's measured readout floor, not from the exposure.
     *
     * A planner that used the exposure would promise 400 frames in 0.05 s.
     */
    @Test
    fun `the run's duration is set by readout, not by the exposure`() {
        val plan = LunarPlan.plan(
            exposureNs = 130_000L, iso = 400, freeBytes = 200 * gb,
            arcsecPerPixel = teleArcsecPerPx,
        )
        // 400 frames x 33.2 ms = 13.3 s.
        assertEquals(13.28, plan.seconds, 0.1)
        assertTrue(plan.seconds > 100 * (130_000 / 1e9), "the exposure must not set the cadence")
    }

    @Test
    fun `a long exposure does set the cadence once it exceeds the readout floor`() {
        val plan = LunarPlan.plan(
            exposureNs = 100_000_000L, iso = 100, freeBytes = 200 * gb,
            arcsecPerPixel = teleArcsecPerPx, maxFrames = 10,
        )
        assertEquals(1.0, plan.seconds, 0.01, "10 frames x 100 ms")
    }

    @Test
    fun `drift over the run is reported in this camera's pixels`() {
        val plan = LunarPlan.plan(
            exposureNs = 130_000L, iso = 400, freeBytes = 200 * gb,
            arcsecPerPixel = teleArcsecPerPx,
        )
        // 13.28 s x 15.041 arcsec/s / 24.8 arcsec/px = 8.1 px.
        assertEquals(8.1, plan.driftPx, 0.3)
    }

    @Test
    fun `a wider lens sees less drift over the same run`() {
        fun driftAt(arcsecPerPx: Double) = LunarPlan.plan(
            exposureNs = 130_000L, iso = 400, freeBytes = 200 * gb, arcsecPerPixel = arcsecPerPx,
        ).driftPx

        assertTrue(driftAt(140.7) < driftAt(24.8), "an ultrawide's drift is smaller in pixels")
    }

    @Test
    fun `the stacked count matches what the keep-best cut will actually keep`() {
        val plan = LunarPlan.plan(
            exposureNs = 130_000L, iso = 400, freeBytes = 200 * gb,
            arcsecPerPixel = teleArcsecPerPx,
        )
        assertEquals(FrameQuality.LUNAR_KEEP_PERCENT, plan.keepPercent)
        assertEquals(80, plan.stackedCount, "20% of 400")

        // And it must agree with the cut itself rather than approximating it.
        val scores = (0 until plan.frameCount).map {
            FrameQuality.Score(it, "f$it", 1.0 - it / 1000.0, 1.0, 1.0, 1.0)
        }
        assertEquals(
            FrameQuality.keepBest(scores, plan.keepPercent).size,
            plan.stackedCount,
        )
    }

    // --- T-11.9's ground-set length, which the app must state before the shoot ---

    @Test
    fun `the ground set length reproduces the plan's six and eleven minute figures`() {
        // At sidereal on the reference tele: 75 px disc, 0.606 px/s, three crossings = 371 s.
        assertEquals(371.0, LunarPlan.groundSetSeconds(75, 24.8), 2.0)

        // At the rate 2026-09-06 actually saw — 8.16 arcsec/s, 54% of sidereal — it is nearly
        // twice as long, which is exactly why §1.45 insists the rate is measured.
        assertEquals(684.0, LunarPlan.groundSetSeconds(75, 24.8, driftArcsecPerSec = 8.16), 3.0)
    }

    /**
     * §1.45 quotes these as "~6 min" and "~11 min", which are the *rounded* values of 6.18 and
     * 11.40. This is a **minimum** the user has to meet, so it rounds up rather than to nearest:
     * telling someone six minutes when the sky needs 6.2 leaves a streak in the composite.
     */
    @Test
    fun `the stated minimum rounds up, because it is a minimum`() {
        assertEquals(7, LunarPlan.groundSetMinutes(75, 24.8))
        assertEquals(12, LunarPlan.groundSetMinutes(75, 24.8, driftArcsecPerSec = 8.16))
    }

    @Test
    fun `a bigger disc needs a longer ground set`() {
        assertTrue(
            LunarPlan.groundSetSeconds(150, 24.8) > LunarPlan.groundSetSeconds(75, 24.8),
            "twice the diameter takes twice as long to clear itself",
        )
    }

    @Test
    fun `nonsense inputs give zero rather than infinity`() {
        assertEquals(0.0, LunarPlan.groundSetSeconds(0, 24.8))
        assertEquals(0.0, LunarPlan.groundSetSeconds(75, 0.0))
        assertEquals(0.0, LunarPlan.groundSetSeconds(75, 24.8, driftArcsecPerSec = 0.0))
    }
}
