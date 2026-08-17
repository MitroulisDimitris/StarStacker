package com.starstacker.exposure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-3.2 acceptance: hand-computed values for several focal lengths and declinations.
 *
 * The arithmetic is short enough to do by hand, which is the point — every expected value below
 * is worked out in its own comment rather than by running the code and pasting what came back.
 * A test that asserts the implementation agrees with itself would pass just as happily with the
 * pixel pitch off by a factor of two, which is precisely the failure OI-17 was watching for.
 */
class TrailingLimitTest {

    // The reference device's main camera (§1.5): 2.00 µm pitch, 5.56 mm focal length,
    // 8.192 x 6.144 mm sensor.
    private val mainPitch = 2.00
    private val mainFocal = 5.56
    private val mainWidth = 8.192
    private val mainHeight = 6.144

    @Test
    fun `plate scale matches the hand computation for both real cameras`() {
        // 206264.806 * 2.00 / (5.56 * 1000) = 412529.6 / 5560 = 74.196 arcsec/px
        assertEquals(74.196, TrailingLimit.arcsecPerPixel(2.00, 5.56), 0.01)

        // The tele (camera 3): 206264.806 * 1.60 / 13300 = 330023.7 / 13300 = 24.814 arcsec/px
        assertEquals(24.814, TrailingLimit.arcsecPerPixel(1.60, 13.30), 0.01)
    }

    @Test
    fun `on the celestial equator the main camera holds 1_5 px for about seven seconds`() {
        // drift at delta = 0 is the full sidereal rate, 15.041 arcsec/s.
        // t = 1.5 px * 74.196 arcsec/px / 15.041 arcsec/s = 111.294 / 15.041 = 7.399 s
        val result = TrailingLimit.solve(
            pixelPitchUm = mainPitch,
            focalLengthMm = mainFocal,
            sensorWidthMm = mainWidth,
            sensorHeightMm = mainHeight,
            fieldCentreDecDeg = 0.0,
        )

        assertEquals(7.399, result.maxExposureSeconds, 0.01)
        assertEquals(15.041, result.driftArcsecPerSec, 0.01)
    }

    @Test
    fun `the tele trails four times faster than the main camera, as its focal length says`() {
        // t = 1.5 * 24.814 / 15.041 = 37.221 / 15.041 = 2.475 s
        val tele = TrailingLimit.solve(
            pixelPitchUm = 1.60,
            focalLengthMm = 13.30,
            sensorWidthMm = 6.55,
            sensorHeightMm = 4.92,
            fieldCentreDecDeg = 0.0,
        )

        assertEquals(2.475, tele.maxExposureSeconds, 0.01)
    }

    /**
     * The correctness point this whole file exists for.
     *
     * Putting the *centre* declination into cos(δ) sends the limit to infinity at the pole. On a
     * lens with an 85° diagonal field that is not merely optimistic, it is wrong by however much
     * the field spans: the corners are 43° from the pole and trailing at two-thirds of the
     * equatorial rate while the arithmetic says "expose as long as you like".
     */
    @Test
    fun `pointing at the pole relaxes the limit by what the field allows, not without bound`() {
        val result = TrailingLimit.solve(
            pixelPitchUm = mainPitch,
            focalLengthMm = mainFocal,
            sensorWidthMm = mainWidth,
            sensorHeightMm = mainHeight,
            fieldCentreDecDeg = 90.0,
        )

        // Half-diagonal field: atan(hypot(8.192, 6.144) / 2 / 5.56) = atan(5.12 / 5.56)
        //                    = atan(0.92086) = 42.64 degrees
        assertEquals(42.64, result.halfFieldDeg, 0.05)

        // The fastest star in frame sits at 90 - 42.64 = 47.36 degrees.
        assertEquals(47.36, result.effectiveDeclinationDeg, 0.05)

        // drift = 15.041 * cos(47.36) = 15.041 * 0.6773 = 10.187 arcsec/s
        // t = 111.294 / 10.187 = 10.925 s
        assertEquals(10.925, result.maxExposureSeconds, 0.05)

        assertTrue(
            result.maxExposureSeconds.isFinite(),
            "the pole must not produce an unbounded exposure on a wide field",
        )
        // Barely a stop and a half of relaxation — worth having, nowhere near "unlimited".
        assertTrue(
            result.maxExposureSeconds < 2.0 * 7.399,
            "relaxation of ${result.maxExposureSeconds / 7.399}x is larger than the field allows",
        )
    }

    @Test
    fun `a field that reaches the equator gets no relaxation even when its centre does not`() {
        // Centre at +30 degrees, half-field 42.64 degrees: the frame crosses delta = 0.
        val result = TrailingLimit.solve(
            pixelPitchUm = mainPitch,
            focalLengthMm = mainFocal,
            sensorWidthMm = mainWidth,
            sensorHeightMm = mainHeight,
            fieldCentreDecDeg = 30.0,
        )

        assertEquals(0.0, result.effectiveDeclinationDeg, 1e-9)
        assertEquals(7.399, result.maxExposureSeconds, 0.01)
        assertTrue(result.note.contains("reaches the celestial equator"), result.note)
    }

    /**
     * A narrow field *does* get the relaxation, which is why the rule is expressed in terms of
     * the field rather than hard-coded to "phones are wide".
     */
    @Test
    fun `a narrow field near the pole is relaxed substantially`() {
        // A 4 degree half-field at delta = 88: the fastest point is at 84 degrees.
        // drift = 15.041 * cos(84) = 15.041 * 0.10453 = 1.5723 arcsec/s
        val result = TrailingLimit.solve(
            pixelPitchUm = 2.0,
            focalLengthMm = 100.0,
            sensorWidthMm = 11.2,
            sensorHeightMm = 8.4,
            fieldCentreDecDeg = 88.0,
        )

        assertEquals(4.0, result.halfFieldDeg, 0.1)
        assertEquals(84.0, result.effectiveDeclinationDeg, 0.1)
        // scale = 206264.806 * 2.0 / 100000 = 4.1253 arcsec/px
        // t = 1.5 * 4.1253 / 1.5723 = 6.188 / 1.5723 = 3.936 s
        assertEquals(3.936, result.maxExposureSeconds, 0.02)
    }

    @Test
    fun `without a pointing fix the equator is assumed, never something more generous`() {
        val unknown = TrailingLimit.solve(
            pixelPitchUm = mainPitch,
            focalLengthMm = mainFocal,
            sensorWidthMm = mainWidth,
            sensorHeightMm = mainHeight,
            fieldCentreDecDeg = null,
        )
        val equator = TrailingLimit.solve(
            pixelPitchUm = mainPitch,
            focalLengthMm = mainFocal,
            sensorWidthMm = mainWidth,
            sensorHeightMm = mainHeight,
            fieldCentreDecDeg = 0.0,
        )

        assertTrue(unknown.assumedWorstCase)
        assertEquals(equator.maxExposureSeconds, unknown.maxExposureSeconds, 1e-9)
        assertTrue(unknown.note.contains("no pointing fix"), unknown.note)
    }

    @Test
    fun `the tolerance is the tolerance - doubling it doubles the exposure`() {
        fun at(tolerance: Double) = TrailingLimit.solve(
            pixelPitchUm = mainPitch,
            focalLengthMm = mainFocal,
            sensorWidthMm = mainWidth,
            sensorHeightMm = mainHeight,
            fieldCentreDecDeg = 0.0,
            tolerancePx = tolerance,
        ).maxExposureSeconds

        assertEquals(2.0 * at(1.5), at(3.0), 1e-9)
        assertEquals(at(1.5) / 3.0, at(0.5), 1e-9)
    }

    /**
     * A doubled pixel pitch doubles the allowed exposure. Stated as a test because OI-17's whole
     * risk was a pitch wrong by exactly this factor, and it passes every *internal* check.
     */
    @Test
    fun `a pitch error propagates straight into the limit, which is why it is measured`() {
        val correct = TrailingLimit.solve(
            2.00, mainFocal, mainWidth, mainHeight, fieldCentreDecDeg = 0.0,
        )
        val halfPitch = TrailingLimit.solve(
            1.00, mainFocal, mainWidth, mainHeight, fieldCentreDecDeg = 0.0,
        )

        assertEquals(correct.maxExposureSeconds / 2.0, halfPitch.maxExposureSeconds, 1e-9)
        assertFalse(correct.assumedWorstCase)
    }
}
