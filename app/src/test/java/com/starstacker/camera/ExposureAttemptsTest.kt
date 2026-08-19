package com.starstacker.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule that decides whether a night is abandoned, tested on a laptop.
 *
 * It exists because the sensor's stated exposure ceiling turned out to be advertised rather than
 * enforced (§1.20): the app now asks for long exposures and *checks*, instead of refusing them on
 * the strength of a number the hardware ignores. This is the check.
 */
class ExposureAttemptsTest {

    @Test
    fun `without a budget it never gives up`() {
        // The original behaviour, kept for requests inside the stated ceiling: skip until the
        // timeout, because there the device is contractually bound and a mismatch is settling.
        val attempts = ExposureAttempts(refuseAfter = null)
        repeat(500) {
            assertFalse(attempts.skipped(exposureMatched = false, appliedNs = 49_640_587_500L))
        }
    }

    @Test
    fun `it gives up after the budgeted number of wrong exposures`() {
        val attempts = ExposureAttempts(refuseAfter = 3)
        assertFalse(attempts.skipped(false, 49_640_587_500L))
        assertFalse(attempts.skipped(false, 49_640_587_500L))
        assertTrue(attempts.skipped(false, 49_640_587_500L))
    }

    @Test
    fun `the exposure it kept returning is remembered for the message`() {
        // Without this the failure says "the sensor would not take this exposure" and cannot say
        // what it took instead — which is the one number that identifies a clamping device.
        val attempts = ExposureAttempts(refuseAfter = 2)
        attempts.skipped(false, 49_640_587_500L)
        attempts.skipped(false, 49_640_587_500L)
        assertEquals(49_640_587_500L, attempts.lastAppliedNs)
    }

    @Test
    fun `a frame skipped only for its generation is not evidence of refusal`() {
        // The darks path. After the sensor is restarted with the lens covered, frames from before
        // the cover are still in flight: right exposure, wrong generation. Counting those would
        // abandon every session that takes darks — the opposite of the failure this guards.
        val attempts = ExposureAttempts(refuseAfter = 3)
        repeat(50) {
            assertFalse(attempts.skipped(exposureMatched = true, appliedNs = 7_400_000_000L))
        }
        assertEquals(0, attempts.wrongExposures)
    }

    @Test
    fun `one good exposure clears the count`() {
        // Two wrong frames then a right one means the sensor was settling, not refusing, so the
        // budget starts over rather than carrying a grudge across the whole session.
        val attempts = ExposureAttempts(refuseAfter = 3)
        assertFalse(attempts.skipped(false, 1_000_000L))
        assertFalse(attempts.skipped(false, 1_000_000L))
        assertFalse(attempts.skipped(exposureMatched = true, appliedNs = 7_400_000_000L))

        assertEquals(0, attempts.wrongExposures)
        assertFalse(attempts.skipped(false, 1_000_000L))
        assertFalse(attempts.skipped(false, 1_000_000L))
        assertTrue(attempts.skipped(false, 1_000_000L))
    }

    @Test
    fun `a budget of one refuses on the first wrong frame`() {
        assertTrue(ExposureAttempts(refuseAfter = 1).skipped(false, 49_640_587_500L))
    }

    @Test
    fun `a frame with no exposure metadata at all counts as wrong`() {
        // `appliedExposureNs` is null when the result carried no SENSOR_EXPOSURE_TIME. That is
        // not a frame we can verify, so it cannot be a frame we accept (D-21).
        val attempts = ExposureAttempts(refuseAfter = 2)
        assertFalse(attempts.skipped(false, null))
        assertTrue(attempts.skipped(false, null))
        assertEquals(null, attempts.lastAppliedNs)
    }
}
