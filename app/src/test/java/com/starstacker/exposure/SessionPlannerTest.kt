package com.starstacker.exposure

import com.starstacker.pointing.Astro
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-3.5 acceptance, including FR-5.4's "warn *before* starting": the deliberately
 * under-provisioned scenarios must block rather than discover the problem at 2 a.m.
 */
class SessionPlannerTest {

    private val gb = 1_073_741_824L
    private val startMs = 1_755_000_000_000L

    private fun plan(
        goal: SessionPlanner.Goal,
        subSeconds: Double = 12.0,
        freeBytes: Long = 64 * gb,
        batteryPercent: Double = 100.0,
        rotationRate: Double? = 16.0,
        overheadSeconds: Double = 0.0,
    ) = SessionPlanner.plan(
        goal = goal,
        iso = 800,
        subSeconds = subSeconds,
        frameAspectWidth = 4096,
        frameAspectHeight = 3072,
        freeBytes = freeBytes,
        batteryPercent = batteryPercent,
        startEpochMs = startMs,
        rotationRateArcsecPerSec = rotationRate,
        overheadSeconds = overheadSeconds,
    )

    @Test
    fun `a target integration time sets the frame count`() {
        // 30 minutes of integration at 12 s per sub = 150 frames.
        val p = plan(SessionPlanner.Goal.TargetIntegration(30 * 60.0))

        assertEquals(150, p.lightCount)
        assertEquals(1800.0, p.integrationSeconds, 1e-9)
        assertTrue(p.headline.startsWith("ISO 800 · 12 s · 150 frames · "), p.headline)
    }

    @Test
    fun `a total time budget fits the lights and the darks that follow them inside it`() {
        val budget = 45 * 60.0
        val p = plan(SessionPlanner.Goal.TotalTime(budget))

        assertTrue(p.totalSeconds <= budget, "session overruns its budget: ${p.totalSeconds}")
        // And it is not leaving a whole extra frame's worth of time unused.
        assertTrue(
            p.totalSeconds > budget - 2 * 12.0,
            "session leaves ${budget - p.totalSeconds} s of the budget unused",
        )
        assertEquals(p.darkCount, SessionPlanner.darkCountFor(p.lightCount))
    }

    @Test
    fun `per-frame overhead is charged against the time budget`() {
        val withoutOverhead = plan(SessionPlanner.Goal.TotalTime(1800.0))
        val withOverhead = plan(SessionPlanner.Goal.TotalTime(1800.0), overheadSeconds = 3.0)

        assertTrue(
            withOverhead.lightCount < withoutOverhead.lightCount,
            "overhead bought no fewer frames: ${withOverhead.lightCount}",
        )
        assertTrue(withOverhead.totalSeconds <= 1800.0)
    }

    @Test
    fun `darks are allocated as a fraction of the lights, clamped at both ends`() {
        assertEquals(SessionPlanner.MIN_DARKS, SessionPlanner.darkCountFor(1))
        assertEquals(SessionPlanner.MIN_DARKS, SessionPlanner.darkCountFor(60))  // 9 -> clamped
        assertEquals(15, SessionPlanner.darkCountFor(100))
        assertEquals(SessionPlanner.MAX_DARKS, SessionPlanner.darkCountFor(1000))
    }

    /** FR-5.4: storage must warn *before* the session starts. */
    @Test
    fun `an under-provisioned volume blocks the session rather than discovering it later`() {
        // 150 lights + 22 darks at 25.2 MB = about 4.3 GB.
        val p = plan(SessionPlanner.Goal.TargetIntegration(30 * 60.0), freeBytes = 2 * gb)

        assertEquals(SessionPlanner.Severity.BLOCK, p.storage.severity)
        assertTrue(p.blocked)
        assertTrue(p.storage.detail.contains("will not fit"), p.storage.detail)
    }

    @Test
    fun `a comfortable volume passes and states the numbers`() {
        val p = plan(SessionPlanner.Goal.TargetIntegration(30 * 60.0), freeBytes = 64 * gb)

        assertEquals(SessionPlanner.Severity.OK, p.storage.severity)
        assertEquals(
            (p.lightCount + p.darkCount).toLong() * SessionPlanner.DEFAULT_BYTES_PER_FRAME,
            p.bytesRequired,
        )
    }

    @Test
    fun `a nearly flat battery blocks a long session`() {
        val p = plan(SessionPlanner.Goal.TotalTime(3 * 3600.0), batteryPercent = 15.0)

        assertEquals(SessionPlanner.Severity.BLOCK, p.battery.severity)
        assertTrue(p.battery.detail.contains("plug in"), p.battery.detail)
    }

    @Test
    fun `the estimated end time is the start plus the whole session`() {
        val p = plan(SessionPlanner.Goal.TargetIntegration(600.0))
        assertEquals(startMs + (p.totalSeconds * 1000).toLong(), p.endsAtEpochMs)
    }

    // --- common area -------------------------------------------------------------------

    @Test
    fun `no rotation keeps the whole frame`() {
        assertEquals(1.0, SessionPlanner.commonAreaFraction(0.0, 4096, 3072), 1e-12)
    }

    /**
     * Hand-computed. For a 4:3 frame rotated by 5°:
     *   w = 4, h = 3, sin 5° = 0.087156, cos 5° = 0.996195
     *   s ≤ 3 / (4·0.087156 + 3·0.996195) = 3 / (0.348624 + 2.988584) = 3 / 3.337208 = 0.898955
     *   s ≤ 4 / (4·0.996195 + 3·0.087156) = 4 / (3.984778 + 0.261467) = 4 / 4.246245 = 0.941999
     * so s = 0.898955 and the retained area is s² = 0.808120.
     */
    @Test
    fun `five degrees of rotation on a 4 by 3 frame retains about 81 percent`() {
        assertEquals(0.80812, SessionPlanner.commonAreaFraction(5.0, 4096, 3072), 1e-4)
    }

    /**
     * §7.1's key property: rotational loss is a *fraction* of the frame and does not depend on
     * focal length — only on the aspect ratio. Wide and tele lose the same share.
     */
    @Test
    fun `common area depends on aspect ratio alone, not on the size of the frame`() {
        val big = SessionPlanner.commonAreaFraction(7.0, 4096, 3072)
        val small = SessionPlanner.commonAreaFraction(7.0, 1024, 768)

        assertEquals(big, small, 1e-12)
    }

    @Test
    fun `more rotation always retains less`() {
        val fractions = listOf(0.0, 2.0, 5.0, 10.0, 20.0, 40.0)
            .map { SessionPlanner.commonAreaFraction(it, 4096, 3072) }

        for (i in 1 until fractions.size) {
            assertTrue(
                fractions[i] < fractions[i - 1],
                "common area not monotonic in rotation: $fractions",
            )
        }
    }

    /**
     * The number that makes the case for the planner existing: at 40°N pointing south at 45°
     * altitude, §7.1's worked example gives ~16 arcsec/s, and a 45-minute session is then 12° of
     * rotation — over a third of the frame gone.
     */
    @Test
    fun `a 45 minute session at the requirements worked example loses a third of the frame`() {
        val rate = Astro.fieldRotationArcsecPerSec(altDeg = 45.0, azDeg = 180.0, latDeg = 40.0)
        val p = plan(SessionPlanner.Goal.TotalTime(45 * 60.0), rotationRate = rate)

        // 16.3 arcsec/s over 2700 s = 44010 arcsec = 12.2 degrees.
        assertEquals(12.2, p.rotationDegrees!!, 0.2)
        assertTrue(
            p.commonAreaFraction!! < 0.67,
            "expected a heavy crop, got ${p.commonAreaFraction}",
        )
        assertEquals(SessionPlanner.Severity.WARN, p.rotation.severity)
    }

    @Test
    fun `without a pointing fix the rotation prediction is withheld rather than guessed`() {
        val p = plan(SessionPlanner.Goal.TargetIntegration(600.0), rotationRate = null)

        assertNull(p.rotationDegrees)
        assertNull(p.commonAreaFraction)
        assertEquals(SessionPlanner.Severity.OK, p.rotation.severity)
        assertTrue(p.rotation.detail.contains("needs a pointing fix"), p.rotation.detail)
    }
}
