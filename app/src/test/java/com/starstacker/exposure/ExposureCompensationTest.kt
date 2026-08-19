package com.starstacker.exposure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-3.35 — the exposure dial, and the two defects the wider range exposed.
 *
 * The first two tests below are the ones that matter: both describe behaviour that was **already
 * wrong at ±2 stops** and would have been four times wronger at ±4, and neither is visible from a
 * screenshot. A sub clamped by the sensor and a frame bound computed from the wrong exposure both
 * look like ordinary numbers.
 */
class ExposureCompensationTest {

    /** The reference device's ceiling, measured (§1.5). */
    private val sensorMax = 49.64

    @Test
    fun `the compensated sub never exceeds the sensor's longest exposure`() {
        // +4 stops on a 4 s solve asks for 64 s. This HAL answers an impossible request by
        // truncating rather than by failing, so an unclamped number would have propagated into
        // the plan, the storage estimate and the end time — all describing a frame that was
        // never going to be taken.
        assertEquals(sensorMax, ExposureCompensation.apply(4.0, 4.0, sensorMax), 1e-9)
        assertTrue(ExposureCompensation.isClampedAt(4.0, 4.0, sensorMax))
    }

    @Test
    fun `a sub inside the sensor's range is left alone`() {
        assertEquals(16.0, ExposureCompensation.apply(4.0, 2.0, sensorMax), 1e-9)
        assertFalse(ExposureCompensation.isClampedAt(4.0, 2.0, sensorMax))
    }

    @Test
    fun `the frame bound follows the compensated sub, not the solved one`() {
        // The defect: maxFrames read `solution.chosen.exposureSeconds`, so at +2 stops the
        // 2.5-hour slider offered a session built from 4 s frames while planning 16 s ones —
        // wrong by 4×, and by 16× at the new range.
        val solved = 4.0
        val overhead = 0.01
        val hours = 2.5

        val atZero = ExposureCompensation.maxFrames(solved, overhead, hours)
        val atPlusTwo = ExposureCompensation.maxFrames(
            ExposureCompensation.apply(solved, 2.0, sensorMax), overhead, hours,
        )
        // 9000 s of night at 4.01 s and at 16.01 s a frame.
        assertEquals(2244, atZero)
        assertEquals(562, atPlusTwo)
        // The whole point: four times the exposure is a quarter of the frames in the same night.
        // Within a frame of exact, since the per-frame overhead does not scale with the exposure.
        assertEquals(4.0, atZero.toDouble() / atPlusTwo, 0.01)
    }

    @Test
    fun `the frame bound respects the clamp too`() {
        // Past the ceiling the sub stops growing, so the bound stops shrinking. Without the clamp
        // inside `apply` this would keep falling for exposures the sensor cannot take.
        val fourStops = ExposureCompensation.apply(8.0, 4.0, sensorMax)
        val threeStops = ExposureCompensation.apply(8.0, 3.0, sensorMax)
        assertEquals(sensorMax, fourStops, 1e-9)
        assertEquals(sensorMax, threeStops, 1e-9)
        assertEquals(
            ExposureCompensation.maxFrames(threeStops, 0.01, 2.5),
            ExposureCompensation.maxFrames(fourStops, 0.01, 2.5),
        )
    }

    @Test
    fun `a camera that reports no ceiling is not clamped to zero`() {
        // `exposureMaxSeconds` can be absent. Treating a missing ceiling as 0 would clamp every
        // sub to nothing, which is a worse failure than not clamping at all.
        assertEquals(64.0, ExposureCompensation.apply(4.0, 4.0, 0.0), 1e-9)
        assertEquals(64.0, ExposureCompensation.apply(4.0, 4.0, Double.NaN), 1e-9)
        assertFalse(ExposureCompensation.isClampedAt(4.0, 4.0, 0.0))
    }

    @Test
    fun `the range is four stops either way`() {
        assertEquals(4.0, ExposureCompensation.snap(4.0), 1e-9)
        assertEquals(4.0, ExposureCompensation.snap(9.0), 1e-9)
        assertEquals(-4.0, ExposureCompensation.snap(-9.0), 1e-9)
    }

    @Test
    fun `values snap to sixths of a stop`() {
        assertEquals(1.0 / 6.0, ExposureCompensation.snap(0.14), 1e-9)
        assertEquals(0.5, ExposureCompensation.snap(0.51), 1e-9)
        assertEquals(0.0, ExposureCompensation.snap(0.03), 1e-9)
        // Every reachable value is an exact multiple of the step, so the scale can always show it.
        listOf(-3.77, -1.2, 0.4, 2.9, 3.99).forEach { raw ->
            val snapped = ExposureCompensation.snap(raw)
            val sixths = snapped / ExposureCompensation.STEP
            assertEquals(Math.round(sixths).toDouble(), sixths, 1e-9)
        }
    }

    @Test
    fun `the scale is marked at every whole stop`() {
        assertEquals(listOf(-4, -3, -2, -1, 0, 1, 2, 3, 4), ExposureCompensation.MARKS)
    }

    @Test
    fun `the slider has one detent per sixth of a stop`() {
        // 48 intervals across the range means 47 interior positions; a slider given the wrong
        // count silently snaps to values the scale cannot label.
        val intervals = (2 * ExposureCompensation.MAX_STOPS / ExposureCompensation.STEP)
        assertEquals(48, Math.round(intervals).toInt())
        assertEquals(47, ExposureCompensation.SLIDER_STEPS)
    }

    @Test
    fun `stops read as a photographer's fractions`() {
        assertEquals("0", ExposureCompensation.format(0.0))
        assertEquals("+1", ExposureCompensation.format(1.0))
        assertEquals("−2", ExposureCompensation.format(-2.0))
        assertEquals("+1 1/3", ExposureCompensation.format(1.0 + 2.0 / 6.0))
        assertEquals("+1/2", ExposureCompensation.format(0.5))
        assertEquals("−5/6", ExposureCompensation.format(-5.0 / 6.0))
        assertEquals("+4", ExposureCompensation.format(4.0))
    }
}
