package com.starstacker.exposure

/**
 * T-3.1's other half — how fast this sky is filling the well, in electrons per second.
 *
 * The rate is a property of the sky, the lens and the pixel, and **not** of the ISO: ISO is gain
 * applied after the photons have already been collected. So one test frame at any convenient ISO
 * measures it once, and the solver can then ask "how long at ISO 1600?" without shooting again.
 * That is the whole reason this is a separate step from the solver rather than folded into it.
 *
 * The background level comes from the same tiled-median estimate the star detector already
 * computes ([com.starstacker.stars.FrameStars.background]) — a median, not a mean, so the stars
 * in the frame do not inflate the sky it is trying to measure.
 */
data class SkyMeasurement(
    /** Median background of the test frame, ADU, **before** black-level subtraction. */
    val backgroundAdu: Double,
    val blackLevelAdu: Double,
    val whiteLevelAdu: Int,
    val iso: Int,
    val exposureSeconds: Double,
    /** Sky signal at the sensor, electrons per second per pixel. ISO-independent. */
    val electronsPerSecond: Double,
    /** Fraction of full scale the test frame's background sat at. */
    val backgroundFraction: Double,
) {
    /**
     * A test frame whose own background is already clipped measures nothing — the true level is
     * somewhere above the white level and unknowable from this frame.
     */
    val clipped: Boolean get() = backgroundAdu >= whiteLevelAdu

    /**
     * Sky electrons collected in a given exposure, at the pixel. The quantity FR-5.2 compares
     * against read noise.
     */
    fun electronsIn(seconds: Double): Double = electronsPerSecond * seconds

    companion object {

        /**
         * @param backgroundAdu the frame's median level, black included — the raw number the
         *   detector reports, so the subtraction happens in exactly one place.
         */
        fun from(
            backgroundAdu: Double,
            blackLevelAdu: Double,
            whiteLevelAdu: Int,
            iso: Int,
            exposureSeconds: Double,
            noise: SensorNoise,
        ): SkyMeasurement {
            require(exposureSeconds > 0.0) { "exposure must be positive, was $exposureSeconds" }
            val signalAdu = (backgroundAdu - blackLevelAdu).coerceAtLeast(0.0)
            val electrons = signalAdu * noise.electronsPerAdu
            val span = (whiteLevelAdu - blackLevelAdu).coerceAtLeast(1.0)
            return SkyMeasurement(
                backgroundAdu = backgroundAdu,
                blackLevelAdu = blackLevelAdu,
                whiteLevelAdu = whiteLevelAdu,
                iso = iso,
                exposureSeconds = exposureSeconds,
                electronsPerSecond = electrons / exposureSeconds,
                backgroundFraction = signalAdu / span,
            )
        }
    }
}
