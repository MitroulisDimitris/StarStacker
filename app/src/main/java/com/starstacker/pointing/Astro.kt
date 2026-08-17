package com.starstacker.pointing

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * T-2.6 — the spherical astronomy behind pointing.
 *
 * Two numbers justify this file. **Declination at the field centre** sets the trailing limit
 * (FR-5.1: a star at the pole barely moves, one on the equator moves at the full sidereal rate),
 * and **field rotation rate** says how much of the frame survives a session on an alt-az mount
 * (§7.1). Both are derived from altitude, azimuth and latitude alone, so they cost nothing but
 * the compass reading the app already has.
 *
 * Pure Kotlin, no Android, no ephemeris: the sky positions here are geometry, not a catalogue.
 */
object Astro {

    /**
     * Earth's rotation in arcseconds of sky per second of time — the sidereal rate.
     * 360° × 1.0027379 (sidereal days per solar day) / 86400 s = 15.041″/s.
     */
    const val SIDEREAL_ARCSEC_PER_SEC = 15.041_067

    /** Beyond this altitude the field-rotation rate diverges and the number stops meaning much. */
    const val ZENITH_GUARD_DEG = 88.0

    private const val DEG = Math.PI / 180.0

    /**
     * Declination of the field centre, degrees.
     *
     * sin δ = sin(alt)·sin(lat) + cos(alt)·cos(lat)·cos(az), with azimuth measured from true
     * north through east.
     */
    fun declinationDeg(altDeg: Double, azDeg: Double, latDeg: Double): Double {
        val alt = altDeg * DEG
        val az = azDeg * DEG
        val lat = latDeg * DEG
        val sinDec = sin(alt) * sin(lat) + cos(alt) * cos(lat) * cos(az)
        return asin(sinDec.coerceIn(-1.0, 1.0)) / DEG
    }

    /**
     * Hour angle of the field centre, degrees, in (-180, 180]. Negative is east of the meridian
     * — the target is still rising.
     */
    fun hourAngleDeg(altDeg: Double, azDeg: Double, latDeg: Double): Double {
        val alt = altDeg * DEG
        val az = azDeg * DEG
        val lat = latDeg * DEG
        val sinH = -sin(az) * cos(alt)
        val cosH = sin(alt) * cos(lat) - cos(alt) * sin(lat) * cos(az)
        return atan2(sinH, cosH) / DEG
    }

    /** Right ascension of the field centre, hours in [0,24). */
    fun rightAscensionHours(altDeg: Double, azDeg: Double, latDeg: Double, lstHours: Double): Double {
        val ha = hourAngleDeg(altDeg, azDeg, latDeg) / 15.0
        return normaliseHours(lstHours - ha)
    }

    /**
     * Field rotation rate in arcsec/s, signed. The magnitude is what matters to the planner;
     * the sign says which way the frame turns.
     *
     * ρ = 15.04 × cos(lat) × cos(az) / cos(alt)   (§7.1)
     *
     * It diverges at the zenith, which is real physics rather than a bug: an alt-az mount cannot
     * track through the zenith. The altitude is clamped at [ZENITH_GUARD_DEG] so the readout
     * stays finite; use [divergesNearZenith] to say so rather than printing a huge number as if
     * it were a measurement.
     */
    fun fieldRotationArcsecPerSec(altDeg: Double, azDeg: Double, latDeg: Double): Double {
        val alt = altDeg.coerceIn(-ZENITH_GUARD_DEG, ZENITH_GUARD_DEG) * DEG
        return SIDEREAL_ARCSEC_PER_SEC * cos(latDeg * DEG) * cos(azDeg * DEG) / cos(alt)
    }

    fun divergesNearZenith(altDeg: Double): Boolean = abs(altDeg) > ZENITH_GUARD_DEG

    /**
     * Rotation accumulated over a span, in degrees — the input to the common-area estimate the
     * session planner shows before you start (T-3.5).
     */
    fun fieldRotationDegrees(altDeg: Double, azDeg: Double, latDeg: Double, seconds: Double): Double =
        fieldRotationArcsecPerSec(altDeg, azDeg, latDeg) * seconds / 3600.0

    /** Julian date from a Unix epoch millisecond count. */
    fun julianDate(epochMillis: Long): Double =
        epochMillis / 86_400_000.0 + 2_440_587.5

    /**
     * Greenwich mean sidereal time, hours in [0,24).
     *
     * The linear form is accurate to about a second of time per century here, which is four
     * orders of magnitude finer than a phone compass — the limiting error in this whole chain is
     * the magnetometer, not the sidereal clock.
     */
    fun greenwichMeanSiderealTimeHours(epochMillis: Long): Double {
        val daysSinceJ2000 = julianDate(epochMillis) - 2_451_545.0
        return normaliseHours(18.697_374_558 + 24.065_709_824_419_08 * daysSinceJ2000)
    }

    /** Local sidereal time, hours in [0,24). East longitude positive. */
    fun localSiderealTimeHours(epochMillis: Long, longitudeDeg: Double): Double =
        normaliseHours(greenwichMeanSiderealTimeHours(epochMillis) + longitudeDeg / 15.0)

    fun normaliseHours(hours: Double): Double {
        val h = hours - 24.0 * floor(hours / 24.0)
        return if (h < 0) h + 24.0 else h
    }

    fun normaliseDegrees(degrees: Double): Double {
        val d = degrees - 360.0 * floor(degrees / 360.0)
        return if (d < 0) d + 360.0 else d
    }

    /** "+02° 41′" — the form the prototype's Pointing card uses. */
    fun formatDeclination(decDeg: Double): String {
        val sign = if (decDeg < 0) "-" else "+"
        val total = abs(decDeg)
        var degrees = floor(total).toInt()
        var minutes = Math.round((total - degrees) * 60.0).toInt()
        if (minutes == 60) {
            minutes = 0
            degrees += 1
        }
        return "%s%02d° %02d′".format(sign, degrees, minutes)
    }

    /** "20h 41m" */
    fun formatHours(hours: Double): String {
        val h = normaliseHours(hours)
        var whole = floor(h).toInt()
        var minutes = Math.round((h - whole) * 60.0).toInt()
        if (minutes == 60) {
            minutes = 0
            whole = (whole + 1) % 24
        }
        return "%02dh %02dm".format(whole, minutes)
    }

    /** Compass point for an azimuth, for the readout — "168° SSE" reads faster than "168°". */
    fun compassPoint(azimuthDeg: Double): String {
        val points = arrayOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
        )
        val index = Math.round(normaliseDegrees(azimuthDeg) / 22.5).toInt() % 16
        return points[index]
    }
}
