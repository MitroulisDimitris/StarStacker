package com.starstacker.stacking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-6.8 / FR-10.5 — combining nights.
 *
 * The tests are mostly about **refusals**, which is what the feature mostly is: adding a second
 * night doubles the integration and can halve the quality, and the costly failure is the silent
 * one — a composite that quietly includes a night pointed somewhere else looks deeper and is
 * smeared.
 */
class MultiNightTest {

    private fun night(
        name: String,
        camera: String = "0",
        seconds: Double = 600.0,
        frames: Int = 100,
        background: Double? = 20.0,
        altitude: Double? = 45.0,
        azimuth: Double? = 120.0,
    ) = MultiNight.Night(
        folderName = name,
        cameraId = camera,
        label = name,
        integrationSeconds = seconds,
        frames = frames,
        backgroundAdu = background,
        altitudeDeg = altitude,
        azimuthDeg = azimuth,
    )

    // ------------------------------------------------------------------ the hard rejection

    @Test
    fun `a different camera is rejected and cannot be talked round`() {
        val verdict = MultiNight.judge(night("a", camera = "0"), night("b", camera = "3"))
        val reject = verdict as MultiNight.Verdict.Reject
        assertTrue(reject.reason.contains("different instrument"), reject.reason)
    }

    @Test
    fun `the same camera on a different night is fine`() {
        assertTrue(MultiNight.judge(night("a"), night("b")).included)
    }

    // ------------------------------------------------------------------ pointing

    @Test
    fun `a night pointed somewhere else is rejected`() {
        val verdict = MultiNight.judge(
            night("a", altitude = 45.0, azimuth = 120.0),
            night("b", altitude = 45.0, azimuth = 200.0),
        )
        assertTrue(verdict is MultiNight.Verdict.Reject, "got $verdict")
    }

    @Test
    fun `the same target at a different hour is not rejected`() {
        // A target rises and sets; the same object at two hours is genuinely at different alt-az,
        // which is why the tolerance is loose and registration is the real test.
        assertTrue(
            MultiNight.judge(
                night("a", altitude = 40.0, azimuth = 120.0),
                night("b", altitude = 48.0, azimuth = 128.0),
            ).included,
        )
    }

    /**
     * Azimuth converges toward the pole. Without the cosine a target near the zenith looks wildly
     * separated from itself.
     */
    @Test
    fun `azimuth is weighted by altitude`() {
        val low = MultiNight.separationDegrees(
            night("a", altitude = 5.0, azimuth = 100.0),
            night("b", altitude = 5.0, azimuth = 110.0),
        )!!
        val high = MultiNight.separationDegrees(
            night("a", altitude = 80.0, azimuth = 100.0),
            night("b", altitude = 80.0, azimuth = 110.0),
        )!!
        assertTrue(high < low, "10° of azimuth is less sky at 80° than at 5°: $high vs $low")
        assertEquals(10.0, low, 0.2)
    }

    @Test
    fun `azimuth wraps rather than reading 350 degrees apart`() {
        val separation = MultiNight.separationDegrees(
            night("a", altitude = 45.0, azimuth = 5.0),
            night("b", altitude = 45.0, azimuth = 355.0),
        )!!
        assertTrue(separation < 15.0, "10° across north read as $separation°")
    }

    @Test
    fun `a night with no fix is included with a warning rather than refused`() {
        // Most sessions have no fix. Refusing them would make the feature useless.
        val verdict = MultiNight.judge(night("a"), night("b", altitude = null, azimuth = null))
        val include = verdict as MultiNight.Verdict.Include
        assertTrue(include.warnings.single().contains("no pointing recorded"), include.warnings.toString())
    }

    // ------------------------------------------------------------------ sky brightness

    @Test
    fun `a much brighter sky is a warning, not a refusal`() {
        val verdict = MultiNight.judge(night("a", background = 20.0), night("b", background = 80.0))
        val include = verdict as MultiNight.Verdict.Include
        assertTrue(include.warnings.single().contains("moonlight"), include.warnings.toString())
    }

    @Test
    fun `a slightly brighter sky says nothing`() {
        val verdict = MultiNight.judge(night("a", background = 20.0), night("b", background = 30.0))
        assertTrue((verdict as MultiNight.Verdict.Include).warnings.isEmpty())
    }

    @Test
    fun `the background ratio is symmetric and refuses nonsense`() {
        assertEquals(4.0, MultiNight.backgroundRatio(20.0, 80.0)!!, 1e-9)
        assertEquals(4.0, MultiNight.backgroundRatio(80.0, 20.0)!!, 1e-9)
        assertNull(MultiNight.backgroundRatio(0.0, 20.0))
        assertNull(MultiNight.backgroundRatio(null, 20.0))
    }

    // ------------------------------------------------------------------ the plan

    @Test
    fun `the deepest night becomes the reference`() {
        val plan = MultiNight.plan(
            listOf(night("a", seconds = 300.0), night("b", seconds = 900.0), night("c", seconds = 600.0)),
        )!!
        assertEquals("b", plan.reference.folderName)
    }

    @Test
    fun `integration accumulates across the nights that made it in`() {
        val plan = MultiNight.plan(
            listOf(
                night("a", seconds = 300.0, frames = 50),
                night("b", seconds = 900.0, frames = 150),
                night("wrong-camera", camera = "3", seconds = 600.0, frames = 100),
            ),
        )!!

        assertEquals(1200.0, plan.integrationSeconds, 1e-9, "the rejected night must not count")
        assertEquals(200, plan.frames)
        assertEquals(1, plan.rejected.size)
    }

    @Test
    fun `the composite records which nights made it and which did not`() {
        val plan = MultiNight.plan(
            listOf(night("a"), night("b"), night("other", camera = "2")),
        )!!
        val provenance = plan.provenance()

        assertTrue(provenance.getValue("nights").contains("a"))
        assertTrue(provenance.getValue("nights").contains("b"))
        assertTrue(provenance.getValue("rejected").contains("other"), provenance.toString())
        assertEquals("0", provenance.getValue("camera"))
    }

    @Test
    fun `a night whose own gate kept nothing is rejected`() {
        val verdict = MultiNight.judge(night("a"), night("b", frames = 0))
        assertTrue(verdict is MultiNight.Verdict.Reject, "got $verdict")
    }

    @Test
    fun `nothing to combine gives no plan rather than an empty one`() {
        assertNull(MultiNight.plan(emptyList()))
        assertNull(MultiNight.plan(listOf(night("a", frames = 0))))
    }

    // ------------------------------------------------------------------ normalisation

    @Test
    fun `normalisation matches both the sky and the scale`() {
        // The reference sits on a sky of 20 with signal reaching 120: a span of 100.
        // The night sits on 60 with signal reaching 110: a span of 50, so it needs doubling.
        val (scale, offset) = MultiNight.normalisation(
            referenceBackground = 20.0, referenceSignal = 120.0,
            nightBackground = 60.0, nightSignal = 110.0,
        )!!
        assertEquals(2.0, scale, 1e-9)
        assertEquals(20.0, offset, 1e-9)

        // Applying it puts the night's sky and its signal where the reference's are.
        fun apply(v: Double) = (v - 60.0) * scale + offset
        assertEquals(20.0, apply(60.0), 1e-9, "its sky lands on the reference's sky")
        assertEquals(120.0, apply(110.0), 1e-9, "and its signal on the reference's signal")
    }

    @Test
    fun `a night with no range above its own sky is refused rather than scaled hugely`() {
        assertNull(
            MultiNight.normalisation(
                referenceBackground = 20.0, referenceSignal = 120.0,
                nightBackground = 60.0, nightSignal = 60.0,
            ),
            "a cloudy hour must not be multiplied up until it dominates",
        )
    }
}
