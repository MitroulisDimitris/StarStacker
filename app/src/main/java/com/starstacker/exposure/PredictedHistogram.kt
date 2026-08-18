package com.starstacker.exposure

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * T-3.25 — what the frames will actually look like, before any are taken.
 *
 * ### Why a histogram and not another number
 *
 * The solver's answer is "sky-limited at ISO 1600, 7.4 s", which is correct and means nothing to
 * someone who has not read §5 of the requirements. The same answer drawn as a histogram says the
 * thing directly: **a hump sitting a little way off the left wall, with room to its right.** Too
 * far left is read-noise limited; jammed against the right is clipped. It is the one picture that
 * turns the exposure engine's reasoning into something a beginner can check at a glance.
 *
 * ### It is predicted, not measured
 *
 * Nothing here is a guess. The sky was measured once as [SkyMeasurement.electronsPerSecond], which
 * is ISO-independent, and the sensor's read noise and gain at any ISO come from the noise model.
 * So the frame at any other ISO and exposure follows:
 *
 *     sky electrons     = electronsPerSecond × exposure
 *     total variance    = sky electrons + readNoise²        (shot noise plus read noise)
 *     background in ADU = black level + sky electrons / electronsPerAdu
 *
 * The width is the noise, the position is the signal, and both come from measurements the app
 * already took. Stars are not drawn: they occupy a vanishing fraction of the pixels and would be
 * invisible at any honest scale — the sky *is* the histogram.
 */
object PredictedHistogram {

    /**
     * @param bins normalised bin heights, 0–1, left to right across the full ADU range.
     * @param peakFraction where the sky background sits, 0–1 of full scale.
     * @param headroomStops doublings between the background and clipping. Negative means clipped.
     */
    data class Prediction(
        val bins: List<Double>,
        val peakFraction: Double,
        val noiseFraction: Double,
        val headroomStops: Double,
    ) {
        val clipped: Boolean get() = peakFraction >= 1.0

        /**
         * The read-noise floor the exposure engine exists to clear (FR-5.2). Below this the sky is
         * not swamping the sensor's own noise and the frame is read-noise limited.
         */
        val readNoiseLimited: Boolean get() = skyToReadVariance < 3.0

        var skyToReadVariance: Double = 0.0
            internal set
    }

    fun of(
        sky: SkyMeasurement,
        noise: SensorNoise,
        exposureSeconds: Double,
        binCount: Int = 48,
    ): Prediction {
        require(exposureSeconds > 0.0) { "exposure must be positive" }
        require(binCount > 1) { "need more than one bin" }

        val skyElectrons = sky.electronsPerSecond * exposureSeconds
        val totalVariance = skyElectrons + noise.readVarianceElectrons
        val sigmaElectrons = sqrt(totalVariance)

        val span = (sky.whiteLevelAdu - sky.blackLevelAdu).coerceAtLeast(1.0)
        val backgroundAdu = skyElectrons / noise.electronsPerAdu
        val sigmaAdu = sigmaElectrons / noise.electronsPerAdu

        val peak = (backgroundAdu / span).coerceIn(0.0, 1.0)
        val width = (sigmaAdu / span).coerceAtLeast(1.0 / binCount)

        val bins = List(binCount) { i ->
            val x = (i + 0.5) / binCount
            val z = (x - peak) / width
            exp(-0.5 * z * z)
        }
        val tallest = bins.maxOrNull() ?: 1.0

        val headroom = if (backgroundAdu <= 0.0) {
            0.0
        } else {
            ln(span / backgroundAdu) / ln(2.0)
        }

        return Prediction(
            bins = bins.map { it / tallest },
            peakFraction = peak,
            noiseFraction = width,
            headroomStops = headroom,
        ).also {
            it.skyToReadVariance =
                if (noise.readVarianceElectrons > 0.0) {
                    skyElectrons / noise.readVarianceElectrons
                } else {
                    Double.POSITIVE_INFINITY
                }
        }
    }
}
