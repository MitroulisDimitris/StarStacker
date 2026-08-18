package com.starstacker.session

/**
 * Where the camera was pointed when **Start** was pressed, carried into the session log.
 *
 * ### Why a snapshot rather than a live reading
 *
 * The magnetometer is polled only on the framing and setup screens, deliberately — a compass read
 * through a 45-minute capture is battery spent on a number nobody is looking at. But the deeper
 * reason to freeze it is that the pointing which *matters* is the pointing that set the exposure.
 * The trailing limit was computed from a declination at solve time; re-reading the compass an hour
 * later would describe a sky that has moved and a limit that was never applied.
 *
 * ### Why the log needs it at all
 *
 * FR-9.2 wants everything needed to reproduce or audit a run, and the declination is the one input
 * to the sub length that leaves no trace in the result. Without it, `session.json` cannot answer
 * whether the trailing limit was relaxed for a high-declination field or worst-cased at the
 * equator — the two produce very different exposures from identical-looking logs. Diagnosing the
 * 2026-08-18 session meant inferring "no fix" from arithmetic (7.399 s matching cos δ = 1 exactly)
 * because the log itself was silent.
 *
 * [compassAccuracy] is recorded for the same reason. A skewed compass yields a wrong declination
 * and therefore a wrong trailing limit, and a metal tripod head sitting centimetres from the
 * magnetometer is an ordinary way to get one. A declination without the accuracy that produced it
 * cannot be judged afterwards.
 */
data class SessionPointing(
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    /** Elevation above the horizon, degrees. */
    val altitudeDeg: Double? = null,
    /** Azimuth from **true** north. Null when there was no magnetic declination to correct by. */
    val azimuthTrueDeg: Double? = null,
    /** Declination at the field centre — the input to FR-5.1's trailing limit. */
    val declinationDeg: Double? = null,
    /** §7.1's alt-az field rotation rate, arcsec/s. */
    val fieldRotationArcsecPerSec: Double? = null,
    val compassAccuracy: String? = null,
) {
    /** True when nothing usable was captured, so the log can say "no fix" rather than six nulls. */
    val isEmpty: Boolean
        get() = latitudeDeg == null && longitudeDeg == null && altitudeDeg == null &&
            azimuthTrueDeg == null && declinationDeg == null &&
            fieldRotationArcsecPerSec == null && compassAccuracy == null
}

/**
 * Freezes a live [com.starstacker.pointing.PointingFix] into the form the log keeps.
 *
 * Everything here is a *derived* quantity — declination, true azimuth and field rotation are
 * computed from altitude, azimuth and latitude, and each is null when one of its inputs is. The
 * derivation happens once, here, rather than being recomputed later from stored inputs, because
 * the numbers that should be auditable are the ones the exposure engine actually saw.
 */
fun com.starstacker.pointing.PointingFix.toSessionPointing() = SessionPointing(
    latitudeDeg = latitudeDeg,
    longitudeDeg = longitudeDeg,
    altitudeDeg = altitudeDeg,
    azimuthTrueDeg = azimuthTrueDeg,
    declinationDeg = declinationDeg,
    fieldRotationArcsecPerSec = fieldRotationArcsecPerSec,
    compassAccuracy = accuracy.name,
)
