package com.starstacker.exposure

import com.starstacker.pointing.Astro
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot

/**
 * T-3.2 / FR-5.1 — how long a sub can be before the stars stop being points.
 *
 * **Not the 500 rule**, and not the NPF rule either, though it is NPF-*style* in the sense the
 * requirements mean: derived from the measured pixel pitch and focal length rather than from a
 * remembered constant. The difference is that NPF's `(35N + 30p)/f` bakes in an assumed print
 * size and viewing distance — it answers "will this look trailed in a print?" — whereas FR-5.1.1
 * asks the user for a **tolerance in pixels of star elongation**, which is a question about the
 * sensor and can be answered directly:
 *
 *     arcsec per pixel = 206265 · pitch(µm) / (focal(mm) · 1000)
 *     drift rate       = 15.041 · cos(δ)  arcsec/s          (the diurnal rate at declination δ)
 *     t_max            = tolerance(px) · arcsec-per-pixel / drift rate
 *
 * No fitted constants, and the tolerance the user set is the tolerance they get.
 *
 * ### The pole is not as forgiving as cos(δ) suggests
 *
 * The obvious reading of "relax the limit near the pole" is to put the field-centre declination
 * into `cos(δ)` and let the limit go to infinity as it approaches 90°. On a telescope that is
 * roughly right. On a phone it is badly wrong, and dangerously so, because a phone's field is
 * enormous: the reference device's main camera spans about **85° corner to corner**. Point it at
 * Polaris and the frame *centre* is at δ = 90° with a drift rate of zero — while the corners sit
 * at δ ≈ 47° and trail at two-thirds of the equatorial rate.
 *
 * So the limit is computed from the **fastest-moving star in the frame**, which is the point of
 * the field closest to the celestial equator, not from the centre. Near the pole that still
 * relaxes the limit — just by the amount the field actually allows rather than without bound.
 */
object TrailingLimit {

    /** FR-5.1.1's default tolerance, in pixels of elongation. */
    const val DEFAULT_TOLERANCE_PX = 1.5

    /** Radians to arcseconds. */
    private const val ARCSEC_PER_RADIAN = 206_264.806

    private const val DEG = Math.PI / 180.0

    /**
     * Everything the limit is computed from, kept together so the derivation shown under
     * FR-5.3's `Show work` is the same object the answer came out of rather than a retelling.
     */
    data class Result(
        val maxExposureSeconds: Double,
        val tolerancePx: Double,
        val arcsecPerPixel: Double,
        /** Declination of the field point that moves fastest — see the class note. */
        val effectiveDeclinationDeg: Double,
        val fieldCentreDeclinationDeg: Double?,
        val halfFieldDeg: Double,
        val driftArcsecPerSec: Double,
        /** True when pointing is unavailable and the equator was assumed. */
        val assumedWorstCase: Boolean,
        val note: String,
    )

    /**
     * Half the diagonal field of view, degrees, from sensor size and focal length.
     *
     * This is the number that decides how much the pole relaxation is worth, so it is computed
     * from the measured sensor dimensions rather than assumed.
     */
    fun halfDiagonalFieldDeg(sensorWidthMm: Double, sensorHeightMm: Double, focalLengthMm: Double):
        Double {
        if (focalLengthMm <= 0.0) return 0.0
        val halfDiagonalMm = hypot(sensorWidthMm, sensorHeightMm) / 2.0
        return atan(halfDiagonalMm / focalLengthMm) / DEG
    }

    /** Plate scale: how much sky one pixel covers. */
    fun arcsecPerPixel(pixelPitchUm: Double, focalLengthMm: Double): Double {
        require(focalLengthMm > 0.0) { "focal length must be positive, was $focalLengthMm" }
        return ARCSEC_PER_RADIAN * pixelPitchUm / (focalLengthMm * 1000.0)
    }

    /**
     * @param fieldCentreDecDeg null when there is no location or compass fix. The limit then
     *   assumes the equator — the shortest exposure any pointing could require. An unpointed
     *   guess that happened to be generous would trail frames the app had declared safe.
     */
    fun solve(
        pixelPitchUm: Double,
        focalLengthMm: Double,
        sensorWidthMm: Double,
        sensorHeightMm: Double,
        fieldCentreDecDeg: Double?,
        tolerancePx: Double = DEFAULT_TOLERANCE_PX,
    ): Result {
        require(tolerancePx > 0.0) { "tolerance must be positive, was $tolerancePx" }
        val scale = arcsecPerPixel(pixelPitchUm, focalLengthMm)
        val halfField = halfDiagonalFieldDeg(sensorWidthMm, sensorHeightMm, focalLengthMm)

        val effectiveDec = fieldCentreDecDeg?.let { fastestDeclinationInField(it, halfField) } ?: 0.0
        val drift = Astro.SIDEREAL_ARCSEC_PER_SEC * cos(effectiveDec * DEG)

        val seconds = if (drift <= 0.0) Double.POSITIVE_INFINITY else tolerancePx * scale / drift

        return Result(
            maxExposureSeconds = seconds,
            tolerancePx = tolerancePx,
            arcsecPerPixel = scale,
            effectiveDeclinationDeg = effectiveDec,
            fieldCentreDeclinationDeg = fieldCentreDecDeg,
            halfFieldDeg = halfField,
            driftArcsecPerSec = drift,
            assumedWorstCase = fieldCentreDecDeg == null,
            note = noteFor(fieldCentreDecDeg, effectiveDec, halfField),
        )
    }

    /**
     * The declination of the fastest-moving point in the frame: whichever point of the field
     * lies closest to the celestial equator.
     *
     * If the field reaches across the equator at all, that is δ = 0 and there is no relaxation
     * to be had.
     */
    fun fastestDeclinationInField(centreDecDeg: Double, halfFieldDeg: Double): Double =
        (abs(centreDecDeg) - abs(halfFieldDeg)).coerceAtLeast(0.0)

    private fun noteFor(centreDec: Double?, effectiveDec: Double, halfField: Double): String =
        when {
            centreDec == null ->
                "no pointing fix — assuming the celestial equator, the shortest limit any " +
                    "pointing could need"

            effectiveDec <= 0.0 && abs(centreDec) > 1.0 ->
                "the %.0f° field reaches the celestial equator, so no relaxation applies even " +
                    "though its centre is at %+.0f°".format(halfField * 2, centreDec)

            effectiveDec > 0.0 ->
                "relaxed for declination: the field centre is at %+.0f°, and its fastest corner " +
                    "at %.0f°".format(centreDec, effectiveDec)

            else -> "field centre on the celestial equator — the fastest drift there is"
        }
}
