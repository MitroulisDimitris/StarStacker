package com.starstacker.stacking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * OI-25 — the integer round-trip between calibration and the debayer, which was destroying two
 * different things without saying so.
 *
 * The conversion itself lives inside `TiledStacker`'s register pass and needs OpenCV, so it cannot
 * run here. What *can* run here is the arithmetic, and the arithmetic is where both bugs were.
 * These tests pin the two properties the register pass now depends on.
 */
class DebayerRoundTripTest {

    private val pedestal = TiledStacker.DEBAYER_PEDESTAL

    /** The old conversion, kept so the regression is stated rather than remembered. */
    private fun old(value: Float): Short = value.toInt().coerceIn(0, 65535).toShort()

    /** The new one, minus the non-finite branch, which is counted separately. */
    private fun new(value: Float): Short =
        (value + pedestal).toInt().coerceIn(0, 65535).toShort()

    private fun back(raw: Short): Float = (raw.toInt() and 0xFFFF).toFloat() - pedestal

    /**
     * The bug that made OI-25's central evidence worthless.
     *
     * `Float.NaN.toInt()` is **0** in Kotlin, not an error and not a preserved NaN. So the flat's
     * hole path — the one place calibration deliberately produces NaN — arrived at the gather as a
     * legitimate-looking zero, and `skippedNonFinite` could never fire however many holes the flat
     * had. A run reporting "0 non-finite" was reading a counter incapable of saying anything else.
     */
    @Test
    fun `NaN converts to a legitimate-looking zero, which is why it must be counted first`() {
        assertEquals(0, Float.NaN.toInt(), "this is the language behaviour the bug rested on")
        assertEquals(0.toShort(), old(Float.NaN))

        // And the same for the infinities, which a division by a tiny gain can also produce.
        assertEquals(0.toShort(), old(Float.NEGATIVE_INFINITY))
        assertEquals(65535.toShort(), old(Float.POSITIVE_INFINITY))

        // The register pass now tests `isFinite()` *before* converting, so none of these can reach
        // the conversion at all — which is what makes the counter mean what it says.
        assertTrue(!Float.NaN.isFinite() && !Float.POSITIVE_INFINITY.isFinite())
    }

    /**
     * The second bug: half the sky noise was being thrown away.
     *
     * The sky is faint and the dark master is a mean, so roughly half the noise about it falls
     * below zero. Clamping at zero keeps the positive half and discards the negative one, which
     * biases the background upward and hands sigma clipping exactly the asymmetry it must not have.
     */
    @Test
    fun `the old conversion destroyed every negative value`() {
        for (v in listOf(-0.5f, -1f, -12.75f, -300f)) {
            assertEquals(0.toShort(), old(v), "$v should have been clamped away")
        }
    }

    @Test
    fun `the pedestal preserves negatives through the round trip`() {
        for (v in listOf(-300f, -12f, -1f, 0f, 1f, 250f, 5300f)) {
            val recovered = back(new(v))
            assertEquals(v, recovered, 1.0f, "$v did not survive the round trip")
        }
    }

    @Test
    fun `the pedestal covers the worst negative the flat can produce`() {
        // Sky tens of ADU above the dark, a few ADU of noise, and the reference flat's corner gain
        // of 0.193 multiplying by about five. A few hundred negative is the honest worst case.
        val worstNegative = -400f
        assertTrue(
            pedestal > -worstNegative,
            "a pedestal of $pedestal cannot carry $worstNegative",
        )
        assertNotEquals(0.toShort(), new(worstNegative), "it must not clamp")
        assertEquals(worstNegative, back(new(worstNegative)), 1.0f)
    }

    @Test
    fun `the pedestal leaves headroom at the top of the range`() {
        // The brightest calibrated value on a 10-bit sensor, amplified fivefold by the flat.
        val brightest = 1023f * 5.18f
        assertTrue(
            brightest + pedestal < 65535f,
            "%.0f + %.0f must fit in 16 bits".format(brightest, pedestal),
        )
        assertEquals(brightest, back(new(brightest)), 1.0f)
    }

    /**
     * Why a pedestal is exact rather than approximate.
     *
     * Debayer interpolation is a weighted mean with weights summing to one, so adding a constant
     * before and removing it after commutes with it. This states the property the register pass
     * relies on; if a future debayer used a non-linear kernel, this reasoning would break and so
     * would the fix.
     */
    @Test
    fun `a constant offset commutes with a weight-summing-to-one interpolation`() {
        val neighbours = floatArrayOf(-3f, 10f, 240f, 7f)
        val weights = floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f)

        fun interpolate(values: FloatArray): Float {
            var sum = 0f
            for (i in values.indices) sum += values[i] * weights[i]
            return sum
        }

        val direct = interpolate(neighbours)
        val viaPedestal = interpolate(FloatArray(4) { neighbours[it] + pedestal }) - pedestal
        assertEquals(direct, viaPedestal, 1e-2f)
    }

    /**
     * The hazard this leaves behind, recorded rather than fixed.
     *
     * `Resample.UNCOVERED` is **-1.0**, which is inside the range a calibrated pixel can legitimately
     * take now that negatives survive. A real sample of exactly -1.0f is indistinguishable from an
     * uncovered one and is dropped as a border. Float equality makes that vanishingly rare — but it
     * is rare by luck rather than by construction, and the honest sentinel for "no data" is `NaN`,
     * which no measurement can produce.
     */
    @Test
    fun `the uncovered sentinel sits inside the range of legitimate data`() {
        val legitimate = -1.0f
        assertEquals(Resample.UNCOVERED.toFloat(), legitimate)
        assertEquals(legitimate, back(new(legitimate)), 1.0f, "and it survives the round trip")
    }
}
