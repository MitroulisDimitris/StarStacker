package com.starstacker.exposure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-3.35 — the exposure dial, the defect the wider range exposed, and the clamp that was removed
 * once the device contradicted the assumption behind it.
 */
class ExposureCompensationTest {

    /** What the reference device *says* its ceiling is — advertised, not enforced (§1.20). */
    private val statedMax = 49.6406

    @Test
    fun `the compensated sub is not clamped to the sensor's stated maximum`() {
        // The reversal. This used to clamp to `statedMax` on the assumption that the HAL would
        // truncate anything longer; measured 2026-08-19, it returned 119.999987713 s for a 120 s
        // request. Clamping refused exposures the hardware was willing to take — and past
        // dec 81.5 the sky permits longer subs than the stated ceiling does.
        assertEquals(64.0, ExposureCompensation.apply(4.0, 4.0), 1e-9)
        assertEquals(16.0, ExposureCompensation.apply(4.0, 2.0), 1e-9)
        assertEquals(1.0, ExposureCompensation.apply(4.0, -2.0), 1e-9)
    }

    @Test
    fun `the solver may go past the stated ceiling but not past the sanity bound`() {
        // The stated ceiling is a floor on what we will consider, never a cap: the other half of
        // the solver's `min` is the trailing limit, which is the real constraint.
        val ceiling = ExposureCompensation.solverCeilingSeconds(statedMax)
        assertTrue(ceiling > statedMax) { "was $ceiling" }
        assertEquals(ExposureCompensation.SANITY_CEILING_SECONDS, ceiling, 1e-9)
    }

    @Test
    fun `a camera claiming more than the sanity bound keeps its own ceiling`() {
        // The bound is a floor, not a cap on the hardware — a sensor that genuinely offers ten
        // minutes should not be talked down to four by a constant of ours.
        assertEquals(600.0, ExposureCompensation.solverCeilingSeconds(600.0), 1e-9)
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
            ExposureCompensation.apply(solved, 2.0), overhead, hours,
        )
        // 9000 s of night at 4.01 s and at 16.01 s a frame.
        assertEquals(2244, atZero)
        assertEquals(562, atPlusTwo)
        // The whole point: four times the exposure is a quarter of the frames in the same night.
        // Within a frame of exact, since the per-frame overhead does not scale with the exposure.
        assertEquals(4.0, atZero.toDouble() / atPlusTwo, 0.01)
    }

    @Test
    fun `a camera reporting no usable ceiling still gets the sanity bound`() {
        // `exposureMaxSeconds` can be absent or nonsense. Falling back to 0 would cap every sub at
        // nothing, which is a far worse failure than not capping at all.
        assertEquals(
            ExposureCompensation.SANITY_CEILING_SECONDS,
            ExposureCompensation.solverCeilingSeconds(0.0),
            1e-9,
        )
        assertEquals(
            ExposureCompensation.SANITY_CEILING_SECONDS,
            ExposureCompensation.solverCeilingSeconds(Double.NaN),
            1e-9,
        )
    }

    @Test
    fun `a sub below the frame-duration limit costs its own exposure`() {
        // Confirmed on three real sessions at 0.951 s and 7.399 s, and a probe at 40 s: the gap
        // between consecutive frames is 1.00x the sub (§1.21).
        assertEquals(7.409, ExposureCompensation.frameCostSeconds(7.399, 49.64, 0.01), 1e-9)
        assertEquals(40.01, ExposureCompensation.frameCostSeconds(40.0, 49.64, 0.01), 1e-9)
    }

    @Test
    fun `a sub past the frame-duration limit costs about 2 point 6 times its exposure`() {
        // Measured at 60 s against a 49.64 s limit: gaps of 2.89x, 2.01x and 2.87x. Without this
        // a 60 s plan would count 60 s a frame and take 156 — the session length, the end time
        // and the storage estimate all out by the same factor.
        assertEquals(156.0, ExposureCompensation.frameCostSeconds(60.0, 49.64, 0.01), 1e-9)
    }

    @Test
    fun `a camera that reports no frame-duration limit keeps the flat overhead`() {
        // Null is "unknown", and inventing a 2.6x penalty on an unknown limit would inflate every
        // plan on a device that never told us anything.
        assertEquals(60.01, ExposureCompensation.frameCostSeconds(60.0, null, 0.01), 1e-9)
        assertEquals(60.01, ExposureCompensation.frameCostSeconds(60.0, 0.0, 0.01), 1e-9)
    }

    @Test
    fun `the frame bound accounts for the long-sub cadence`() {
        // 2.5 hours of night at 60 s subs is 9000/156 = 57 frames, not 9000/60.01 = 149.
        assertEquals(57, ExposureCompensation.maxFrames(60.0, 0.01, 2.5, 49.64))
        assertEquals(149, ExposureCompensation.maxFrames(60.0, 0.01, 2.5, null))
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
