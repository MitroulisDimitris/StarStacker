package com.starstacker.registration

import com.starstacker.exposure.TrailingLimit
import com.starstacker.pointing.Astro
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * T-4.1 — the sky drift seed.
 *
 * **Every test here is a case whose answer is known without any of the code under test.** That is
 * deliberate and it is the only honest way to check a sign convention: an inverted axis produces
 * numbers of exactly the right magnitude, so it cannot be caught by looking at the output, and a
 * seed that points the wrong way is worse than no seed at all — matching will converge confidently
 * on the wrong star and the frame will be accepted.
 */
class SkyDriftTest {

    private val w = Astro.SIDEREAL_ARCSEC_PER_SEC

    // ---------------------------------------------------------------- the rates

    @Test
    fun `at the north pole stars circle the zenith at constant altitude`() {
        // The strongest case available: at the pole the sky turns about the zenith, so nothing
        // rises or sets at any azimuth, and azimuth advances at exactly the sidereal rate.
        listOf(0.0, 45.0, 90.0, 180.0, 270.0).forEach { az ->
            val r = SkyDrift.rates(altitudeDeg = 45.0, azimuthDeg = az, latitudeDeg = 90.0)
            assertEquals(0.0, r.altitudeArcsecPerSec, 1e-9) { "altitude must not change at az $az" }
            // The great-circle rate carries cos(altitude); the raw azimuth rate is w.
            assertEquals(w * cos(Math.toRadians(45.0)), r.azimuthArcsecPerSec, 1e-9)
        }
    }

    @Test
    fun `at the equator a star due east rises vertically at the full rate`() {
        val r = SkyDrift.rates(altitudeDeg = 0.0, azimuthDeg = 90.0, latitudeDeg = 0.0)
        assertEquals(w, r.altitudeArcsecPerSec, 1e-9) { "due east it climbs at the sidereal rate" }
        assertEquals(0.0, r.azimuthArcsecPerSec, 1e-9) { "and it does not drift sideways" }
    }

    @Test
    fun `at the equator a star due west sets at the full rate`() {
        val r = SkyDrift.rates(altitudeDeg = 0.0, azimuthDeg = 270.0, latitudeDeg = 0.0)
        assertEquals(-w, r.altitudeArcsecPerSec, 1e-9) { "due west it descends" }
    }

    @Test
    fun `on the meridian a star is neither rising nor setting`() {
        // Culmination: altitude is stationary due south, which is what "meridian" means.
        listOf(0.0, 30.0, 51.5).forEach { lat ->
            val r = SkyDrift.rates(altitudeDeg = 40.0, azimuthDeg = 180.0, latitudeDeg = lat)
            assertEquals(0.0, r.altitudeArcsecPerSec, 1e-9) { "stationary in altitude at lat $lat" }
        }
    }

    @Test
    fun `a star crossing the southern meridian moves west`() {
        // Azimuth increases from 180 towards 270. This is the test that distinguishes the correct
        // azimuth formula from its sign-flipped twin, which is otherwise indistinguishable.
        val r = SkyDrift.rates(altitudeDeg = 45.0, azimuthDeg = 180.0, latitudeDeg = 0.0)
        assertTrue(r.azimuthArcsecPerSec > 0.0) { "was ${r.azimuthArcsecPerSec}" }
    }

    @Test
    fun `total speed is the sidereal rate times cos declination, wherever you stand`() {
        // The identity that ties the two components together: a star's motion is a property of the
        // star, not of the observer. If either rate had a wrong sign or a missing cosine, the two
        // routes to declination would disagree.
        val cases = listOf(
            Triple(30.0, 120.0, 51.5),
            Triple(65.0, 300.0, 40.0),
            Triple(10.0, 45.0, -33.9),
            Triple(70.0, 200.0, 0.0),
        )
        cases.forEach { (alt, az, lat) ->
            val viaRates = SkyDrift.impliedDeclinationDeg(alt, az, lat)
            val viaTrig = abs(Astro.declinationDeg(alt, az, lat))
            assertEquals(viaTrig, viaRates, 1e-6) { "alt $alt az $az lat $lat" }
        }
    }

    @Test
    fun `a star on the celestial equator moves at the full sidereal rate`() {
        // dec 0 => speed = w. Due east from the equator is dec 0 by construction.
        val r = SkyDrift.rates(altitudeDeg = 0.0, azimuthDeg = 90.0, latitudeDeg = 0.0)
        assertEquals(w, r.speedArcsecPerSec, 1e-9)
    }

    @Test
    fun `the rotation rate is the one Astro already computes`() {
        // Reused rather than reimplemented: two copies of a formula are two things to keep right.
        val r = SkyDrift.rates(35.0, 210.0, 51.5)
        assertEquals(
            Astro.fieldRotationArcsecPerSec(35.0, 210.0, 51.5),
            r.fieldRotationArcsecPerSec,
            1e-12,
        )
    }

    // ---------------------------------------------------------------- the seed

    /** The reference device: 2.0 µm pixels at 5.6 mm, so 73.7 arcsec/px (§1.20). */
    private val scale = TrailingLimit.arcsecPerPixel(pixelPitchUm = 2.0, focalLengthMm = 5.6)

    @Test
    fun `a rising star moves up the picture, which is negative y`() {
        // The convention that cannot be guessed, so it is pinned. Due east at the equator the star
        // climbs; the image has y running downwards; therefore dy is negative.
        val seed = SkyDrift.seed(
            altitudeDeg = 20.0, azimuthDeg = 90.0, latitudeDeg = 0.0,
            rollDeg = 0.0, arcsecPerPixel = scale, seconds = 60.0,
        )
        assertTrue(seed.dy < 0.0) { "a rising star must move towards the top of the frame" }
        assertEquals(0.0, seed.dx, 1e-9) { "and not sideways" }
        assertTrue(seed.trustworthy)
    }

    @Test
    fun `the predicted shift is the sky rate divided by the pixel scale`() {
        // Arithmetic anyone can repeat: 15.041 arcsec/s for 60 s is 902 arcsec, which at
        // 73.7 arcsec/px is 12.2 px.
        val seed = SkyDrift.seed(0.0, 90.0, 0.0, 0.0, scale, 60.0)
        assertEquals(w * 60.0 / scale, seed.shiftPixels, 1e-9)
        assertEquals(12.2, seed.shiftPixels, 0.1)
    }

    @Test
    fun `roll rotates the drift without changing its size`() {
        // The invariant that catches a bad rotation matrix: turning the phone about its optical
        // axis cannot change how far the sky moves, only which way it goes in the picture.
        val base = SkyDrift.seed(30.0, 120.0, 51.5, 0.0, scale, 120.0)
        listOf(30.0, 90.0, 180.0, 270.0, -45.0).forEach { roll ->
            val rolled = SkyDrift.seed(30.0, 120.0, 51.5, roll, scale, 120.0)
            assertEquals(base.shiftPixels, rolled.shiftPixels, 1e-9) { "roll $roll changed the size" }
            assertEquals(base.rotationDeg, rolled.rotationDeg, 1e-12) { "roll is not field rotation" }
        }
    }

    @Test
    fun `a quarter turn of roll swaps the axes`() {
        val upright = SkyDrift.seed(20.0, 90.0, 0.0, 0.0, scale, 60.0)
        val turned = SkyDrift.seed(20.0, 90.0, 0.0, 90.0, scale, 60.0)
        // Upright: pure -y. Turned by 90 degrees anticlockwise: the same motion now runs along x.
        assertEquals(0.0, upright.dx, 1e-9)
        assertEquals(0.0, turned.dy, 1e-9)
        assertEquals(abs(upright.dy), abs(turned.dx), 1e-9)
    }

    @Test
    fun `drift grows linearly with time`() {
        val a = SkyDrift.seed(30.0, 120.0, 51.5, 15.0, scale, 10.0)
        val b = SkyDrift.seed(30.0, 120.0, 51.5, 15.0, scale, 40.0)
        assertEquals(4.0, b.shiftPixels / a.shiftPixels, 1e-9)
        assertEquals(4.0, b.rotationDeg / a.rotationDeg, 1e-9)
    }

    @Test
    fun `the zenith is refused rather than answered enormously`() {
        // Within the guard the rates diverge, so a degree of compass error becomes an unbounded
        // transform error. A seed nobody should trust has to say so: the caller then falls back to
        // a blind search, which is slow and correct, instead of being confidently misled.
        val seed = SkyDrift.seed(89.0, 120.0, 51.5, 0.0, scale, 60.0)
        assertFalse(seed.trustworthy)
        assertEquals(0.0, seed.shiftPixels, 1e-12)
        assertTrue(Astro.divergesNearZenith(89.0))
    }

    @Test
    fun `a nonsense pixel scale yields no seed rather than infinity`() {
        assertFalse(SkyDrift.seed(30.0, 120.0, 51.5, 0.0, 0.0, 60.0).trustworthy)
        assertFalse(SkyDrift.seed(30.0, 120.0, 51.5, 0.0, Double.NaN, 60.0).trustworthy)
    }

    @Test
    fun `the seed agrees with the trailing limit about how far a star moves`() {
        // Two features derived from the same sky must not disagree. The trailing limit says how
        // long a star can be exposed before it smears by a given number of pixels; the seed says
        // how far it moves in a given time. One is the other's inverse, and they share
        // TrailingLimit.arcsecPerPixel precisely so they cannot drift apart.
        val alt = 40.0
        val az = 135.0
        val lat = 51.5
        val trailing = TrailingLimit.solve(
            pixelPitchUm = 2.0,
            focalLengthMm = 5.6,
            sensorWidthMm = 8.192,
            sensorHeightMm = 6.144,
            fieldCentreDecDeg = Astro.declinationDeg(alt, az, lat),
            tolerancePx = 1.5,
        )
        val seed = SkyDrift.seed(alt, az, lat, 0.0, scale, trailing.maxExposureSeconds)

        // Over exactly the trailing limit, the field centre should move about the tolerance. Not
        // exactly: the trailing limit is deliberately built on the worst corner of the frame, so
        // the centre moves a little less. Same order, and never more.
        assertTrue(seed.shiftPixels <= trailing.tolerancePx + 1e-9) {
            "centre moved ${seed.shiftPixels} px, budget ${trailing.tolerancePx}"
        }
        assertTrue(seed.shiftPixels > trailing.tolerancePx * 0.5) {
            "centre moved only ${seed.shiftPixels} px against a ${trailing.tolerancePx} px budget"
        }
    }

    @Test
    fun `two pointings a degree apart are the same field, ten degrees apart are not`() {
        assertTrue(SkyDrift.sameField(30.0, 120.0, 30.5, 120.5, toleranceDeg = 2.0))
        assertFalse(SkyDrift.sameField(30.0, 120.0, 30.0, 130.0, toleranceDeg = 2.0))
        // Azimuth wraps: 359 and 1 are two degrees apart, not 358.
        assertTrue(SkyDrift.sameField(30.0, 359.0, 30.0, 1.0, toleranceDeg = 3.0))
    }

    @Test
    fun `a realistic session drifts far enough to matter and is worth seeding`() {
        // The case the task exists for. At 51.5 degrees north, a 7.4 s sub at this pixel scale:
        // the drift between consecutive frames is a pixel or two, but across a 45 minute session
        // it is hundreds — which is why a blind matcher has to search so wide, and why a seed is
        // the difference between a cheap refinement and an expensive hunt.
        val perFrame = SkyDrift.seed(40.0, 135.0, 51.5, 0.0, scale, 7.4)
        val perSession = SkyDrift.seed(40.0, 135.0, 51.5, 0.0, scale, 45 * 60.0)

        assertTrue(perFrame.shiftPixels < 3.0) { "was ${perFrame.shiftPixels}" }
        assertTrue(perSession.shiftPixels > 100.0) { "was ${perSession.shiftPixels}" }
        assertTrue(abs(perSession.rotationDeg) > 1.0) { "was ${perSession.rotationDeg}" }
        assertEquals(
            perSession.shiftPixels,
            perFrame.shiftPixels * (45 * 60.0 / 7.4),
            1e-6,
        )
    }
}
