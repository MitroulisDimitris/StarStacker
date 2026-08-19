package com.starstacker.registration

import com.starstacker.pointing.Astro
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * T-4.1 / FR-7.2.1 — where the sky will have moved to, worked out rather than searched for.
 *
 * ### What this buys, and why it is the first thing Phase 2 needs
 *
 * Asterism matching (T-4.2) searches for the transform between two frames by comparing star
 * patterns. That search has to start somewhere, and it has two failure modes that a **seed** fixes:
 * it is slow when the search range is wide, and it fails outright when the frame is star-starved —
 * thin cloud, a bright moon, a passing aircraft's wake. Those are the frames you least want to
 * lose, because they are the ones in the middle of a session that would otherwise be continuous.
 *
 * The insight is that the sky's motion is **not unknown**. It is rigid-body rotation of the Earth,
 * at a rate known to nine figures, and the phone already measures everything else needed to predict
 * it: where it is (GPS), where it points (compass and accelerometer), and when each frame was taken
 * (`SENSOR_TIMESTAMP`). So the transform between two frames can be *computed* to within the
 * accuracy of the compass, and matching only has to refine it.
 *
 * ### The three rates, and the checks that pin them
 *
 * With observer latitude φ, a field centre at altitude *a* and azimuth *A* measured from **north
 * through east**, and ω the sidereal rate:
 *
 * ```
 * d(alt)/dt = ω · cos φ · sin A
 * d(az)/dt  = ω · (sin φ − cos φ · cos A · tan a)
 * rotation  = ω · cos φ · cos A / cos a          (already in Astro, §7.1)
 * ```
 *
 * Signs are the part that is easy to get backwards and impossible to notice afterwards — a seed
 * with an inverted axis is worse than no seed, because matching will confidently converge on the
 * wrong star. So each one is pinned by a case whose answer is known without any of this arithmetic:
 *
 * - **At the north pole** (φ = 90°) stars circle the zenith at constant altitude: `d(alt)/dt` must
 *   be 0 and `d(az)/dt` must be exactly ω, at every azimuth.
 * - **At the equator looking due east** a star rises vertically: `d(alt)/dt` must be the full ω and
 *   `d(az)/dt` must be 0.
 * - **At the meridian** a star is neither rising nor setting: `d(alt)/dt` must be 0 due south.
 * - **The total speed is always ω · cos δ**, whatever the pointing — a star's motion does not
 *   depend on who is watching it. That identity ties the two components together and would catch
 *   an error in either.
 *
 * ### The frame convention, stated because it cannot be guessed
 *
 * Pixel coordinates are **x right, y down**, which is how the arrays are indexed everywhere else in
 * this app. At [rollDeg] = 0 the frame's up direction points at increasing altitude, so a star
 * climbing in the sky moves towards **−y**. Roll rotates the frame relative to the sky, positive
 * anticlockwise as seen in the image.
 *
 * All of this is pure arithmetic on angles, so it is tested on a laptop against cases with known
 * answers — which is the only honest way to check a sign convention.
 */
object SkyDrift {

    private const val DEG = PI / 180.0

    /**
     * How fast the sky moves through a fixed field, in arcseconds per second.
     *
     * [azimuthArcsecPerSec] is a **great-circle** rate, already multiplied by cos(altitude): a
     * degree of azimuth is a smaller angle on the sky the higher you look, and the pixel scale
     * knows nothing about that. Reporting the raw `d(az)/dt` here would put a cos(alt) error into
     * every caller instead of one place.
     */
    data class Rates(
        val altitudeArcsecPerSec: Double,
        val azimuthArcsecPerSec: Double,
        val fieldRotationArcsecPerSec: Double,
    ) {
        /** Total angular speed of a star across the field, arcsec/s. Equals ω·cos δ. */
        val speedArcsecPerSec: Double
            get() = hypot(altitudeArcsecPerSec, azimuthArcsecPerSec)
    }

    /**
     * The predicted rigid transform between two frames — the seed T-4.2 refines and T-4.3 fits.
     *
     * [trustworthy] is false where the arithmetic is sound but the *answer* is not usable: within
     * [Astro.ZENITH_GUARD_DEG] of the zenith the azimuth and rotation rates diverge, so a small
     * pointing error becomes an unbounded transform error. A seed nobody should trust must say so
     * rather than being quietly enormous — the caller then falls back to a blind search, which is
     * slow but correct, instead of being confidently misled.
     */
    data class Seed(
        val rotationDeg: Double,
        val dx: Double,
        val dy: Double,
        val trustworthy: Boolean,
    ) {
        val shiftPixels: Double get() = hypot(dx, dy)

        companion object {
            val NONE = Seed(0.0, 0.0, 0.0, trustworthy = false)
        }
    }

    /**
     * The three rates at one pointing. Independent of the camera — this is the sky, not the lens.
     */
    fun rates(altitudeDeg: Double, azimuthDeg: Double, latitudeDeg: Double): Rates {
        val a = altitudeDeg * DEG
        val az = azimuthDeg * DEG
        val lat = latitudeDeg * DEG
        val w = Astro.SIDEREAL_ARCSEC_PER_SEC

        val dAlt = w * cos(lat) * sin(az)
        // Guarded because tan(90°) is infinite and the caller may be pointing at the zenith; the
        // guard mirrors Astro's, so the two agree about where the maths stops meaning anything.
        val dAz = if (Astro.divergesNearZenith(altitudeDeg)) {
            Double.NaN
        } else {
            w * (sin(lat) - cos(lat) * cos(az) * tan(a))
        }
        return Rates(
            altitudeArcsecPerSec = dAlt,
            // The great-circle rate: d(az)/dt shrinks by cos(altitude) as an angle on the sky.
            azimuthArcsecPerSec = dAz * cos(a),
            fieldRotationArcsecPerSec =
                Astro.fieldRotationArcsecPerSec(altitudeDeg, azimuthDeg, latitudeDeg),
        )
    }

    /**
     * The transform a star field undergoes over [seconds], in pixels and degrees.
     *
     * @param rollDeg the frame's rotation relative to sky-up, anticlockwise in the image. Zero
     *   means the top of the frame points at increasing altitude.
     * @param arcsecPerPixel from [com.starstacker.exposure.TrailingLimit.arcsecPerPixel] — the
     *   same number the trailing limit uses, deliberately, so a disagreement between what the app
     *   predicts and what it tolerates cannot come from two different pixel scales.
     */
    fun seed(
        altitudeDeg: Double,
        azimuthDeg: Double,
        latitudeDeg: Double,
        rollDeg: Double,
        arcsecPerPixel: Double,
        seconds: Double,
    ): Seed {
        if (arcsecPerPixel <= 0.0 || !arcsecPerPixel.isFinite()) return Seed.NONE
        val r = rates(altitudeDeg, azimuthDeg, latitudeDeg)
        if (!r.azimuthArcsecPerSec.isFinite() || !r.fieldRotationArcsecPerSec.isFinite()) {
            return Seed.NONE
        }

        // Displacement on the sky, in pixels, along the two horizon axes.
        val alongAzimuth = r.azimuthArcsecPerSec * seconds / arcsecPerPixel
        val alongAltitude = r.altitudeArcsecPerSec * seconds / arcsecPerPixel

        // Into frame axes. At roll 0: +azimuth is +x, and +altitude is -y because y runs down the
        // image. Roll turns the frame anticlockwise relative to the sky, so the sky's displacement
        // turns clockwise relative to the frame — hence the sign on the sine terms.
        val roll = rollDeg * DEG
        val dx = alongAzimuth * cos(roll) - alongAltitude * sin(roll)
        val dy = -(alongAltitude * cos(roll) + alongAzimuth * sin(roll))

        return Seed(
            rotationDeg = r.fieldRotationArcsecPerSec * seconds / 3600.0,
            dx = dx,
            dy = dy,
            trustworthy = !Astro.divergesNearZenith(altitudeDeg),
        )
    }

    /**
     * The declination implied by a pointing, recovered from the rates alone.
     *
     * Not needed by the seed — it is a **consistency check**, and it earns its place by testing the
     * two rate formulas against something neither of them contains. A star's speed across the sky
     * is ω·cos δ regardless of where the observer stands, so if the components are right this
     * returns the same declination [Astro.declinationDeg] gets from spherical trigonometry, and if
     * a sign is wrong the two disagree.
     */
    fun impliedDeclinationDeg(altitudeDeg: Double, azimuthDeg: Double, latitudeDeg: Double): Double {
        val speed = rates(altitudeDeg, azimuthDeg, latitudeDeg).speedArcsecPerSec
        val cosDec = (speed / Astro.SIDEREAL_ARCSEC_PER_SEC).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(cosDec))
    }

    /** True when two pointings are close enough that one seed serves for both. */
    fun sameField(altA: Double, azA: Double, altB: Double, azB: Double, toleranceDeg: Double) =
        abs(altA - altB) <= toleranceDeg && abs(Astro.normaliseDegrees(azA - azB)).let {
            it <= toleranceDeg || it >= 360.0 - toleranceDeg
        }
}
