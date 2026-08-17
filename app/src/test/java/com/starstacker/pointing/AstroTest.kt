package com.starstacker.pointing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * T-2.6 acceptance. The geometry is checked against positions whose answer is known by
 * inspection rather than by running the same code twice: pointing at the pole must give +90°,
 * pointing at the zenith must give your own latitude, and the field-rotation rate at the example
 * in the requirements (§7.1) must come out at the ~16″/s quoted there.
 */
class AstroTest {

    @Test
    fun `pointing at the celestial pole reads plus ninety degrees declination`() {
        // Due north, at an altitude equal to your latitude, is the pole — from anywhere.
        for (latitude in listOf(0.0, 23.5, 40.0, 51.5, 68.0)) {
            val dec = Astro.declinationDeg(altDeg = latitude, azDeg = 0.0, latDeg = latitude)
            // arcsin is ill-conditioned at ±1, so a millionth of a degree is the honest bar
            // here — and four orders of magnitude finer than a phone compass.
            assertEquals(90.0, dec, 1e-4, "pole missed from latitude $latitude")
        }
    }

    @Test
    fun `pointing at the zenith reads your own latitude as declination`() {
        for (latitude in listOf(-33.9, 0.0, 40.0, 60.0)) {
            val dec = Astro.declinationDeg(altDeg = 90.0, azDeg = 137.0, latDeg = latitude)
            assertEquals(latitude, dec, 1e-6, "zenith declination wrong at latitude $latitude")
        }
    }

    @Test
    fun `the eastern horizon is rising, so its hour angle is negative`() {
        val ha = Astro.hourAngleDeg(altDeg = 0.0, azDeg = 90.0, latDeg = 0.0)
        assertEquals(-90.0, ha, 1e-9)

        val west = Astro.hourAngleDeg(altDeg = 0.0, azDeg = 270.0, latDeg = 0.0)
        assertEquals(90.0, west, 1e-9)
    }

    @Test
    fun `the zenith is on the meridian`() {
        val ha = Astro.hourAngleDeg(altDeg = 90.0, azDeg = 0.0, latDeg = 40.0)
        assertEquals(0.0, ha, 1e-6)
    }

    @Test
    fun `field rotation matches the worked example in the requirements`() {
        // §7.1: "at ~40°N pointing south at 45° altitude this is roughly 16 arcsec/sec".
        val rate = Astro.fieldRotationArcsecPerSec(altDeg = 45.0, azDeg = 180.0, latDeg = 40.0)
        assertEquals(16.29, abs(rate), 0.05, "field rotation was $rate")
    }

    @Test
    fun `field rotation vanishes due east and west, and diverges towards the zenith`() {
        val east = Astro.fieldRotationArcsecPerSec(altDeg = 45.0, azDeg = 90.0, latDeg = 40.0)
        assertEquals(0.0, east, 1e-9)

        val low = abs(Astro.fieldRotationArcsecPerSec(30.0, 180.0, 40.0))
        val high = abs(Astro.fieldRotationArcsecPerSec(80.0, 180.0, 40.0))
        assertTrue(high > low * 3, "rate should climb steeply towards the zenith: $low -> $high")

        // Clamped rather than infinite, and flagged as such.
        val overhead = abs(Astro.fieldRotationArcsecPerSec(90.0, 180.0, 40.0))
        assertTrue(overhead.isFinite(), "rate at the zenith must stay finite for the readout")
        assertTrue(Astro.divergesNearZenith(90.0))
        assertFalse(Astro.divergesNearZenith(60.0))
    }

    @Test
    fun `at the equator the pole is on the horizon and rotation is at the sidereal rate`() {
        val rate = Astro.fieldRotationArcsecPerSec(altDeg = 0.0, azDeg = 0.0, latDeg = 0.0)
        assertEquals(Astro.SIDEREAL_ARCSEC_PER_SEC, rate, 1e-9)
    }

    @Test
    fun `sidereal time at J2000 matches the published epoch value`() {
        // 2000-01-01 12:00:00 UT: GMST = 18h 41m 50.5s.
        val j2000 = 946_728_000_000L
        assertEquals(18.697374558, Astro.greenwichMeanSiderealTimeHours(j2000), 1e-6)

        // A sidereal day is about four minutes short of a solar day, so 24 h later GMST has
        // advanced by ~3m 56s rather than returning to the same value.
        val nextDay = Astro.greenwichMeanSiderealTimeHours(j2000 + 86_400_000L)
        val advance = (nextDay - 18.697374558) * 3600.0
        assertEquals(236.6, advance, 1.0, "sidereal day drift was ${advance}s")
    }

    @Test
    fun `local sidereal time shifts by one hour per fifteen degrees of longitude`() {
        val epoch = 1_700_000_000_000L
        val greenwich = Astro.localSiderealTimeHours(epoch, 0.0)
        val east = Astro.localSiderealTimeHours(epoch, 15.0)
        assertEquals(1.0, Astro.normaliseHours(east - greenwich), 1e-9)
    }

    @Test
    fun `right ascension of the zenith is the local sidereal time`() {
        val epoch = 1_700_000_000_000L
        val lst = Astro.localSiderealTimeHours(epoch, -0.13)
        val ra = Astro.rightAscensionHours(altDeg = 90.0, azDeg = 0.0, latDeg = 51.5, lstHours = lst)
        assertEquals(lst, ra, 1e-6)
    }

    @Test
    fun `formatting is readable in the dark`() {
        assertEquals("+02° 41′", Astro.formatDeclination(2.6833))
        assertEquals("-15° 00′", Astro.formatDeclination(-15.0))
        assertEquals("20h 41m", Astro.formatHours(20.6833))
        assertEquals("SSE", Astro.compassPoint(160.0))
        assertEquals("N", Astro.compassPoint(359.0))
    }
}
