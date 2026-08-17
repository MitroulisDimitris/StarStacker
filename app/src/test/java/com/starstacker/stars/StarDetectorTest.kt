package com.starstacker.stars

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * T-2.3 acceptance: synthetic frames with known star positions and widths. Centroids must
 * recover to better than 0.1 px, because registration residuals are measured in fractions of a
 * pixel and a biased centroid would show up as a phantom tracking error.
 */
class StarDetectorTest {

    private val width = 256
    private val height = 192

    @Test
    fun `recovers centroids of well separated stars to better than a tenth of a pixel`() {
        val truth = listOf(
            Triple(40.3, 30.7, 2.0),
            Triple(128.5, 96.25, 2.0),
            Triple(200.8, 150.1, 2.0),
        )
        val frame = frameOf(truth, background = 100f, noiseSigma = 2f, seed = 1)

        val found = StarDetector().detect(frame, width, height)

        assertEquals(3, found.count, "expected 3 stars, found ${found.count}")
        for ((tx, ty, _) in truth) {
            val star = found.stars.minByOrNull { abs(it.x - tx) + abs(it.y - ty) }!!
            assertTrue(abs(star.x - tx) < 0.1, "x off by ${abs(star.x - tx)} for ($tx,$ty)")
            assertTrue(abs(star.y - ty) < 0.1, "y off by ${abs(star.y - ty)} for ($tx,$ty)")
        }
    }

    @Test
    fun `HFR grows with defocus and is monotonic - the property the focus sweep relies on`() {
        val hfrs = listOf(1.2, 2.0, 3.0, 4.5).map { sigma ->
            val frame = frameOf(listOf(Triple(128.0, 96.0, sigma)), 100f, 2f, seed = 2)
            StarDetector().detect(frame, width, height).stars.single().hfr
        }
        for (i in 1 until hfrs.size) {
            assertTrue(hfrs[i] > hfrs[i - 1], "HFR not monotonic in defocus: $hfrs")
        }
    }

    @Test
    fun `a round star reads as low eccentricity and a trailed one as high`() {
        val round = frameOf(listOf(Triple(128.0, 96.0, 2.0)), 100f, 1f, seed = 3)
        val trailed = frameOf(
            listOf(Triple(128.0, 96.0, 2.0)), 100f, 1f, seed = 3, sigmaYScale = 4.0,
        )

        val roundEcc = StarDetector().detect(round, width, height).stars.single().eccentricity
        val trailedEcc = StarDetector().detect(trailed, width, height).stars.single().eccentricity

        assertTrue(roundEcc < 0.3, "round star measured eccentricity $roundEcc")
        assertTrue(trailedEcc > 0.8, "trailed star measured eccentricity $trailedEcc")
    }

    @Test
    fun `a light pollution gradient does not manufacture stars`() {
        // Background ramps 80 -> 400 ADU across the frame, far more than the star threshold.
        val frame = FloatArray(width * height)
        val rng = Random(4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val ramp = 80f + 320f * x / width
                frame[y * width + x] = ramp + (rng.nextDouble() * 4 - 2).toFloat()
            }
        }
        addStar(frame, 200.0, 96.0, 2.0, 1.0, peak = 900f)

        val found = StarDetector().detect(frame, width, height)

        assertEquals(1, found.count, "gradient produced ${found.count} detections")
        assertTrue(abs(found.stars.single().x - 200.0) < 0.2)
    }

    @Test
    fun `single hot pixels are rejected`() {
        val frame = frameOf(emptyList(), background = 100f, noiseSigma = 2f, seed = 5)
        frame[96 * width + 128] = 5000f
        frame[50 * width + 60] = 4000f

        assertEquals(0, StarDetector().detect(frame, width, height).count)
    }

    @Test
    fun `saturated stars are flagged`() {
        val frame = frameOf(emptyList(), background = 100f, noiseSigma = 1f, seed = 6)
        addStar(frame, 128.0, 96.0, 2.0, 1.0, peak = 1023f)

        val star = StarDetector(saturationLevel = 1023.0).detect(frame, width, height).stars.single()
        assertTrue(star.saturated)
    }

    @Test
    fun `an oversized blob such as the Moon or a cloud is not a star`() {
        val frame = frameOf(emptyList(), background = 100f, noiseSigma = 1f, seed = 7)
        for (y in 60 until 130) {
            for (x in 90 until 170) frame[y * width + x] = 800f
        }
        assertEquals(0, StarDetector().detect(frame, width, height).count)
    }

    @Test
    fun `an empty sky reports zero stars rather than noise peaks`() {
        val frame = frameOf(emptyList(), background = 100f, noiseSigma = 3f, seed = 8)
        val found = StarDetector().detect(frame, width, height)
        assertEquals(0, found.count)
        assertTrue(abs(found.background - 100.0) < 3.0, "background ${found.background}")
        assertTrue(found.noise in 1.5..5.0, "noise ${found.noise}")
    }

    @Test
    fun `median HFR and eccentricity summarise the frame`() {
        val frame = frameOf(
            listOf(
                Triple(40.0, 40.0, 2.0),
                Triple(120.0, 80.0, 2.0),
                Triple(200.0, 140.0, 2.0),
            ),
            100f, 1f, seed = 9,
        )
        val found = StarDetector().detect(frame, width, height)
        assertEquals(3, found.count)
        assertTrue(found.medianHfr!! in 0.5..6.0, "median HFR ${found.medianHfr}")
        assertTrue(found.medianEccentricity!! < 0.4)
    }

    // ---- CFA binning ---------------------------------------------------------------

    @Test
    fun `green binning averages only the green samples of a GRBG frame`() {
        // 4x4 GRBG frame: greens = 10, reds = 200, blues = 250.
        val cfa = listOf(1, 0, 2, 1) // G R / B G
        val w = 4
        val h = 4
        val pixels = ShortArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val code = cfa[(y % 2) * 2 + (x % 2)]
                pixels[y * w + x] = when (code) {
                    1 -> 10; 0 -> 200; else -> 250
                }.toShort()
            }
        }

        val plane = CfaBinner.binGreen(pixels, w, h, cfa, factor = 2)

        assertEquals(2, plane.width)
        assertEquals(2, plane.height)
        assertTrue(plane.data.all { abs(it - 10f) < 1e-3 }, plane.data.toList().toString())
    }

    @Test
    fun `binning maps measurements back to sensor scale`() {
        val plane = BinnedPlane(FloatArray(4), 2, 2, binFactor = 4)
        assertEquals(8.0, plane.toSensorPixels(2.0), 1e-9)
        assertEquals(9.5, plane.toSensorCoordinate(2.0), 1e-9)
    }

    @Test
    fun `a star survives binning with its position preserved in sensor coordinates`() {
        val w = 256
        val h = 256
        val cfa = listOf(1, 0, 2, 1)
        val pixels = ShortArray(w * h) { 100 }
        // Put a star centred on raw pixel (128, 128), spread over a few Bayer cells.
        for (dy in -6..6) {
            for (dx in -6..6) {
                val v = 900.0 * exp(-(dx * dx + dy * dy) / (2.0 * 3.0 * 3.0))
                val x = 128 + dx
                val y = 128 + dy
                pixels[y * w + x] = (100 + v).toInt().coerceAtMost(65535).toShort()
            }
        }

        val plane = CfaBinner.binGreen(pixels, w, h, cfa, factor = 4)
        val found = StarDetector(backgroundTile = 16).detect(plane.data, plane.width, plane.height)

        assertEquals(1, found.count)
        val sensorX = plane.toSensorCoordinate(found.stars.single().x)
        val sensorY = plane.toSensorCoordinate(found.stars.single().y)
        assertTrue(abs(sensorX - 128.0) < 2.0, "sensor x $sensorX")
        assertTrue(abs(sensorY - 128.0) < 2.0, "sensor y $sensorY")
        assertFalse(found.stars.single().saturated)
    }

    // ---- synthetic frame helpers ---------------------------------------------------

    private fun frameOf(
        stars: List<Triple<Double, Double, Double>>,
        background: Float,
        noiseSigma: Float,
        seed: Int,
        sigmaYScale: Double = 1.0,
    ): FloatArray {
        val rng = Random(seed)
        val frame = FloatArray(width * height) {
            background + (gaussian(rng) * noiseSigma).toFloat()
        }
        for ((x, y, sigma) in stars) addStar(frame, x, y, sigma, sigmaYScale, peak = 800f)
        return frame
    }

    /**
     * Measured on the device 2026-08-17, indoors: a fully clipped frame reported 24–41 stars
     * with a median HFR of 0.95 px — numbers that look exactly like a well-focused sky.
     *
     * The mechanism is that saturation flattens the frame, the MAD noise estimate goes to zero,
     * and a threshold expressed as a multiple of the noise goes to zero with it. Everything the
     * bilinear background model dips below 1023 then reads as a detection. This matters well
     * beyond the focus sweep: FR-7.5 diagnoses a collapse in star count as cloud, and phantom
     * stars would keep that collapse from ever being seen.
     */
    @Test
    fun `a clipped frame reports saturation rather than phantom stars`() {
        val white = 1023.0
        val clipped = FloatArray(width * height) { white.toFloat() }

        val found = StarDetector(saturationLevel = white).detect(clipped, width, height)

        assertTrue(found.saturatedFrame, "a clipped frame should be diagnosed as clipped")
        assertEquals(0, found.count, "found ${found.count} phantom stars in a clipped frame")
    }

    /**
     * The other half of the same guard: the threshold floor must be a statement about the sensor,
     * not about floating point. Sub-ADU structure is quantisation.
     */
    @Test
    fun `a flat frame just short of saturation still finds nothing`() {
        val flat = FloatArray(width * height) { 900f }

        val found = StarDetector(saturationLevel = 1023.0).detect(flat, width, height)

        assertFalse(found.saturatedFrame, "900 ADU is not saturated")
        assertEquals(0, found.count, "found ${found.count} stars in a featureless frame")
    }

    private fun addStar(
        frame: FloatArray,
        cx: Double,
        cy: Double,
        sigma: Double,
        sigmaYScale: Double,
        peak: Float,
    ) {
        val sigmaY = sigma * sigmaYScale
        val radius = (maxOf(sigma, sigmaY) * 4).toInt() + 1
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val x = (cx + dx).toInt()
                val y = (cy + dy).toInt()
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                val ex = (x - cx) * (x - cx) / (2 * sigma * sigma)
                val ey = (y - cy) * (y - cy) / (2 * sigmaY * sigmaY)
                frame[y * width + x] += (peak * exp(-(ex + ey))).toFloat()
            }
        }
    }

    /** Box-Muller, so the noise is actually Gaussian rather than uniform. */
    private fun gaussian(rng: Random): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
            kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
