package com.starstacker.focus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * T-2.4 acceptance, minus the camera. The sweep's job is to find the bottom of a V and to be
 * honest when there isn't one — a confident answer from a curve that never turned around is how
 * a whole session ends up soft.
 */
class FocusSweepTest {

    /** HFR against defocus: a parabola about [minimumAt], which is what a real curve looks like. */
    private fun vCurve(
        positions: List<Float>,
        minimumAt: Float,
        floor: Double = 1.6,
        curvature: Double = 400.0,
        stars: Int = 120,
    ) = positions.map { d ->
        val dx = (d - minimumAt).toDouble()
        FocusSample(d, floor + curvature * dx * dx, stars)
    }

    @Test
    fun `a sweep runs downhill towards infinity so every position is approached the same way`() {
        val positions = FocusSweep.infinitySweep(span = 0.4f, steps = 9)

        assertEquals(9, positions.size)
        assertEquals(0.4f, positions.first(), 1e-6f)
        assertEquals(0.0f, positions.last(), 1e-6f)
        for (i in 1 until positions.size) {
            assertTrue(positions[i] < positions[i - 1], "positions are not descending: $positions")
        }
    }

    @Test
    fun `the sweep never asks for a position the lens cannot reach`() {
        val positions = FocusSweep.infinitySweep(span = 0.4f, steps = 5, maxDiopters = 0.1f)
        assertTrue(positions.all { it <= 0.1f }, "positions past the near limit: $positions")
        assertTrue(positions.all { it >= 0f })

        // The backlash park is clamped too — a park the HAL ignores is backlash left untaken.
        assertEquals(0.1f, FocusSweep.parkPosition(0.1f, maxDiopters = 0.1f), 1e-6f)
        assertEquals(
            0.1f + FocusSweep.BACKLASH_DIOPTERS,
            FocusSweep.parkPosition(0.1f),
            1e-6f,
        )
    }

    @Test
    fun `a local sweep brackets the stored position and stops at infinity`() {
        val around = FocusSweep.localSweep(centre = 0.05f, span = 0.12f, steps = 5)
        assertTrue(around.first() > 0.05f)
        assertTrue(around.last() >= 0f)
        assertTrue(around.any { it < 0.05f }, "local sweep did not bracket the centre: $around")

        val atInfinity = FocusSweep.localSweep(centre = 0.0f, span = 0.12f, steps = 5)
        assertEquals(0.0f, atInfinity.last(), 1e-6f)
    }

    @Test
    fun `the minimum is interpolated to better than the step size`() {
        val positions = FocusSweep.infinitySweep(span = 0.24f, steps = 7) // 0.04 dioptre steps
        val truth = 0.10f
        val curve = FocusSweep.analyse(vCurve(positions, truth))

        assertEquals(FocusVerdict.CLEAR_MINIMUM, curve.verdict)
        assertTrue(curve.interpolated, "expected an interpolated vertex, got ${curve.note}")
        assertTrue(
            abs(curve.bestDiopters - truth) < 0.01f,
            "vertex off by ${abs(curve.bestDiopters - truth)} (step is 0.04)",
        )
    }

    @Test
    fun `a curve that never turns around is reported as such, not as a focus position`() {
        val positions = FocusSweep.infinitySweep(span = 0.4f, steps = 9)
        // Monotonic: still improving at 0.0, so the true minimum is past the hard stop.
        val samples = positions.map { FocusSample(it, 2.0 + 8.0 * it, 100) }

        val curve = FocusSweep.analyse(samples)

        assertEquals(FocusVerdict.MINIMUM_AT_EDGE, curve.verdict)
        assertEquals(0.0f, curve.bestDiopters, 1e-6f)
        assertFalse(curve.interpolated)
        assertTrue(curve.usable, "an edge minimum is still the best position available")
        assertTrue(curve.note.contains("infinity stop"), "note was: ${curve.note}")
    }

    @Test
    fun `a flat curve is not mistaken for perfect focus`() {
        val positions = FocusSweep.infinitySweep(span = 0.4f, steps = 9)
        val samples = positions.map { FocusSample(it, 3.0, 100) }

        val curve = FocusSweep.analyse(samples)

        assertEquals(FocusVerdict.FLAT, curve.verdict)
        assertFalse(curve.usable)
    }

    @Test
    fun `a starless sky is diagnosed as a starless sky, not as bad focus`() {
        val positions = FocusSweep.infinitySweep(span = 0.4f, steps = 9)
        val samples = positions.map { FocusSample(it, 4.2, starCount = 1) }

        val curve = FocusSweep.analyse(samples)

        assertEquals(FocusVerdict.TOO_FEW_STARS, curve.verdict)
        assertFalse(curve.usable)
    }

    @Test
    fun `positions that failed to measure are skipped rather than counted as good`() {
        val positions = FocusSweep.infinitySweep(span = 0.24f, steps = 7)
        val truth = 0.08f
        val samples = vCurve(positions, truth).mapIndexed { index, s ->
            if (index == 0) FocusSample(s.diopters, null, 0) else s
        }

        val curve = FocusSweep.analyse(samples)

        assertEquals(FocusVerdict.CLEAR_MINIMUM, curve.verdict)
        assertTrue(abs(curve.bestDiopters - truth) < 0.02f)
        assertEquals(7, curve.samples.size, "the dropped position stays in the record")
    }

    @Test
    fun `noise does not move the answer by more than a step`() {
        val positions = FocusSweep.infinitySweep(span = 0.24f, steps = 7)
        val truth = 0.10f
        val jitter = listOf(0.03, -0.02, 0.01, -0.03, 0.02, -0.01, 0.02)
        val samples = vCurve(positions, truth).mapIndexed { i, s ->
            FocusSample(s.diopters, s.hfr!! + jitter[i], s.starCount)
        }

        val curve = FocusSweep.analyse(samples)

        assertTrue(
            abs(curve.bestDiopters - truth) < 0.04f,
            "noise moved the vertex to ${curve.bestDiopters}",
        )
    }

    @Test
    fun `the parabola fit refuses a curve opening the wrong way`() {
        // A maximum, not a minimum.
        assertNull(FocusSweep.parabolaVertex(0.0, 1.0, 1.0, 5.0, 2.0, 1.0))
        // Collinear — no curvature to find a vertex in.
        assertNull(FocusSweep.parabolaVertex(0.0, 1.0, 1.0, 2.0, 2.0, 3.0))
    }
}
