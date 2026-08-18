package com.starstacker.exposure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-3.25. The histogram's job is to make "sky-limited" visible, so the tests are about the hump
 * landing where the solver's verdict says it should.
 */
class PredictedHistogramTest {

    private fun sky(electronsPerSecond: Double) = SkyMeasurement(
        backgroundAdu = 81.0,
        blackLevelAdu = 64.25,
        whiteLevelAdu = 1023,
        iso = 1600,
        exposureSeconds = 7.4,
        electronsPerSecond = electronsPerSecond,
        backgroundFraction = 0.02,
    )

    private val noise = SensorNoise(
        iso = 1600,
        fullScaleElectrons = 1550.0,
        readNoiseElectrons = 2.07,
        electronsPerAdu = 1.6,
        interpolated = false,
    )

    @Test
    fun `a dark sky puts the hump near the left wall`() {
        val prediction = PredictedHistogram.of(sky(1.5), noise, exposureSeconds = 7.4)

        assertTrue(prediction.peakFraction < 0.15, "peak at ${prediction.peakFraction}")
        assertFalse(prediction.clipped)
    }

    @Test
    fun `a bright sky pushes it right and eventually clips`() {
        val dark = PredictedHistogram.of(sky(1.5), noise, exposureSeconds = 7.4)
        val bright = PredictedHistogram.of(sky(400.0), noise, exposureSeconds = 7.4)

        assertTrue(bright.peakFraction > dark.peakFraction)
        assertTrue(bright.clipped, "peak at ${bright.peakFraction} was not called clipped")
    }

    /** FR-5.2's whole point: below ~3x read variance the frame is read-noise limited. */
    @Test
    fun `a short sub on a dark sky is read-noise limited`() {
        val short = PredictedHistogram.of(sky(0.4), noise, exposureSeconds = 0.5)
        val long = PredictedHistogram.of(sky(0.4), noise, exposureSeconds = 60.0)

        assertTrue(short.readNoiseLimited, "sky/read was ${short.skyToReadVariance}")
        assertFalse(long.readNoiseLimited, "sky/read was ${long.skyToReadVariance}")
    }

    @Test
    fun `headroom shrinks as the sub lengthens`() {
        val short = PredictedHistogram.of(sky(2.0), noise, exposureSeconds = 4.0)
        val long = PredictedHistogram.of(sky(2.0), noise, exposureSeconds = 16.0)

        // Four times the light is two stops of headroom gone.
        assertEquals(2.0, short.headroomStops - long.headroomStops, 0.05)
    }

    /** A longer sub is noisier in absolute terms but *relatively* tighter — shot noise is sqrt. */
    @Test
    fun `the hump does not get wider than the signal it sits on`() {
        val prediction = PredictedHistogram.of(sky(2.0), noise, exposureSeconds = 30.0)

        assertTrue(
            prediction.noiseFraction < prediction.peakFraction,
            "noise ${prediction.noiseFraction} swamped signal ${prediction.peakFraction}",
        )
    }

    @Test
    fun `bins are normalised and peak in one place`() {
        val prediction = PredictedHistogram.of(sky(2.0), noise, exposureSeconds = 7.4)

        assertEquals(1.0, prediction.bins.max(), 1e-6)
        assertTrue(prediction.bins.all { it in 0.0..1.0 })
    }
}
