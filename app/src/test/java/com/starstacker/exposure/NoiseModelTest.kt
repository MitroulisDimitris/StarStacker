package com.starstacker.exposure

import com.starstacker.device.NoiseProfileEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-3.1: the conversion from Android's normalised noise profile into electrons.
 *
 * Everything the solver decides rests on this arithmetic, and it is the kind of arithmetic that
 * is wrong quietly — a read noise off by the ADU span still produces a plausible-looking ISO
 * recommendation. So the tests start from electrons, construct the profile that *should*
 * describe them, and check the model recovers what went in.
 */
class NoiseModelTest {

    /** GRBG, as on the reference device: indices 0 and 3 are green. */
    private val grbg = listOf(1, 0, 2, 1)

    private val whiteLevel = 1023
    private val blackLevel = 64.0

    /**
     * Builds the (S, O) pair that describes a sensor with [fullScale] electrons at full scale and
     * [readNoise] electrons of read noise — the inverse of what the model does.
     *
     * S = 1/K, and O = (R/K)^2.
     */
    private fun profileFor(fullScale: Double, readNoise: Double): NoiseProfileEntry {
        val s = 1.0 / fullScale
        val o = (readNoise / fullScale) * (readNoise / fullScale)
        return NoiseProfileEntry(s, o)
    }

    @Test
    fun `recovers full scale electrons and read noise from a normalised profile`() {
        // 10000 electrons at full scale, 5 electrons of read noise.
        // S = 1e-4; O = (5/10000)^2 = 2.5e-7. Then K = 1/S = 10000, R = sqrt(O)/S = 5e-4/1e-4 = 5.
        val entry = profileFor(fullScale = 10_000.0, readNoise = 5.0)
        val model = OemNoiseModel.from(
            profilesByIso = mapOf(800 to List(4) { entry }),
            cfaCodes = grbg,
            whiteLevel = whiteLevel,
            blackLevel = blackLevel,
        )

        val noise = model.at(800)!!
        assertEquals(10_000.0, noise.fullScaleElectrons, 1e-6)
        assertEquals(5.0, noise.readNoiseElectrons, 1e-9)
        assertEquals(25.0, noise.readVarianceElectrons, 1e-9)
        assertFalse(noise.interpolated)

        // e-/ADU spans black to white, not 0 to white: 10000 / (1023 - 64) = 10.427
        assertEquals(10_000.0 / 959.0, noise.electronsPerAdu, 1e-9)
    }

    /**
     * D-9 makes the green channel the one that matters — it is the only channel the analysis
     * plane contains. A model that averaged red and blue in would describe a signal the app
     * never measures.
     */
    @Test
    fun `uses the green channels only`() {
        val green = profileFor(fullScale = 10_000.0, readNoise = 5.0)
        val other = profileFor(fullScale = 1_000.0, readNoise = 50.0)
        // GRBG: green, red, blue, green.
        val entries = listOf(green, other, other, green)

        val model = OemNoiseModel.from(mapOf(800 to entries), grbg, whiteLevel, blackLevel)

        val noise = model.at(800)!!
        assertEquals(10_000.0, noise.fullScaleElectrons, 1e-6)
        assertEquals(5.0, noise.readNoiseElectrons, 1e-9)
    }

    @Test
    fun `interpolates between measured ISOs in stops, and says that it did`() {
        val model = OemNoiseModel.from(
            profilesByIso = mapOf(
                400 to List(4) { profileFor(16_000.0, 8.0) },
                1600 to List(4) { profileFor(4_000.0, 4.0) },
            ),
            cfaCodes = grbg,
            whiteLevel = whiteLevel,
            blackLevel = blackLevel,
        )

        // ISO 800 is the midpoint in stops between 400 and 1600, so t = 0.5.
        val noise = model.at(800)!!
        assertTrue(noise.interpolated)
        assertEquals(10_000.0, noise.fullScaleElectrons, 1.0)
        assertEquals(6.0, noise.readNoiseElectrons, 0.01)

        // A measured ISO is returned as measured.
        assertFalse(model.at(400)!!.interpolated)
        assertEquals(listOf(400, 1600), model.measuredIsos)
    }

    /**
     * Outside the measured range, full-scale electrons still scale as 1/ISO — that is what analog
     * gain *is*. Read noise is deliberately not extrapolated: it is the quantity that steps
     * discontinuously at a dual-gain switch, so extrapolating it would invent exactly the feature
     * the model is supposed to detect.
     */
    @Test
    fun `extrapolates gain but refuses to extrapolate read noise`() {
        val model = OemNoiseModel.from(
            mapOf(800 to List(4) { profileFor(8_000.0, 4.0) }),
            grbg, whiteLevel, blackLevel,
        )

        val higher = model.at(1600)!!
        assertEquals(4_000.0, higher.fullScaleElectrons, 1e-6)
        assertEquals(4.0, higher.readNoiseElectrons, 1e-9)
        assertTrue(higher.interpolated)
    }

    @Test
    fun `finds the dual conversion gain point where read noise steps down`() {
        val model = OemNoiseModel.from(
            profilesByIso = mapOf(
                100 to List(4) { profileFor(64_000.0, 9.0) },
                200 to List(4) { profileFor(32_000.0, 8.6) },
                400 to List(4) { profileFor(16_000.0, 8.2) },
                800 to List(4) { profileFor(8_000.0, 2.4) },   // the switch
                1600 to List(4) { profileFor(4_000.0, 2.2) },
            ),
            cfaCodes = grbg,
            whiteLevel = whiteLevel,
            blackLevel = blackLevel,
        )

        assertEquals(800, model.dualGainIso())
    }

    @Test
    fun `reports no dual gain point on a sensor that simply drifts`() {
        val model = OemNoiseModel.from(
            profilesByIso = mapOf(
                100 to List(4) { profileFor(64_000.0, 6.0) },
                400 to List(4) { profileFor(16_000.0, 5.6) },
                1600 to List(4) { profileFor(4_000.0, 5.2) },
            ),
            cfaCodes = grbg,
            whiteLevel = whiteLevel,
            blackLevel = blackLevel,
        )

        assertNull(model.dualGainIso())
    }

    @Test
    fun `a nonsensical profile is dropped rather than turned into a huge gain`() {
        val model = OemNoiseModel.from(
            profilesByIso = mapOf(
                800 to List(4) { NoiseProfileEntry(0.0, 1e-7) },   // S = 0 would divide by zero
                1600 to List(4) { profileFor(4_000.0, 4.0) },
            ),
            cfaCodes = grbg,
            whiteLevel = whiteLevel,
            blackLevel = blackLevel,
        )

        assertEquals(listOf(1600), model.measuredIsos)
    }

    @Test
    fun `an empty profile set answers nothing rather than guessing`() {
        val model = OemNoiseModel.from(emptyMap(), grbg, whiteLevel, blackLevel)
        assertNull(model.at(800))
    }
}
