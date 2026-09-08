package com.starstacker.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A lunar frame, for the tests that moon mode's arithmetic cannot be checked without.
 *
 * ### Why the star generator will not do
 *
 * [SyntheticSky] renders point sources on a sky background, which is exactly what a lunar frame is
 * not. Every property moon mode depends on is absent from it: a large extended object, a black
 * background, surface detail at the scale seeing blurs, and a *phase* — the thing that makes the
 * centroid sit somewhere other than the middle.
 *
 * ### What is modelled, and why each piece is here
 *
 * - **The disc**, with a hard limb. The limb is the sharpest edge in the frame and is what focus
 *   and sharpness are mostly measuring.
 * - **Limb darkening**, mildly. Not physically exact — the moon is a rough scatterer and does not
 *   follow the solar law — but enough that the disc is not a flat plateau, which would make the
 *   intensity-weighted centroid degenerate.
 * - **Craters**, as darkened circles with bright rims. These are the mid-frequency detail that
 *   separates a sharp frame from a soft one, so a sharpness metric that ignores them is not
 *   measuring anything useful.
 * - **Phase**, so a crescent can be generated. The terminator is modelled as the projection of the
 *   day/night boundary, which is an ellipse rather than a straight line.
 * - **Blur**, as a Gaussian, which is the seeing that lucky imaging is picking through.
 * - **Noise**, so sharpness metrics have to survive it rather than measuring it.
 *
 * All values are in ADU on top of a black pedestal, matching what the pipeline reads from a DNG.
 */
object SyntheticMoon {

    const val BLACK_LEVEL = 64.0
    const val WHITE_LEVEL = 1023

    data class Frame(
        val pixels: DoubleArray,
        val width: Int,
        val height: Int,
        /** Where the disc's geometric centre was placed — not the centroid, which phase moves. */
        val trueCentreX: Double,
        val trueCentreY: Double,
        val radiusPx: Double,
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * @param phase 1.0 is full, 0.5 a half moon, 0.1 a thin crescent. The lit fraction of the
     *   diameter along the illumination axis.
     * @param peakAdu what the brightest part of the disc reaches, before clipping
     * @param blurSigma seeing, in pixels. 0 is a perfect frame.
     * @param clip when true, values above [WHITE_LEVEL] are pinned there, as a sensor does
     */
    fun render(
        width: Int = 256,
        height: Int = 192,
        centreX: Double = width / 2.0,
        centreY: Double = height / 2.0,
        radiusPx: Double = 40.0,
        phase: Double = 1.0,
        peakAdu: Double = 700.0,
        blurSigma: Double = 0.0,
        craters: Int = 14,
        noiseAdu: Double = 2.0,
        seed: Int = 7,
        clip: Boolean = true,
    ): Frame {
        val pixels = DoubleArray(width * height) { BLACK_LEVEL }
        val rng = Random(seed)

        // Craters are placed in disc-relative coordinates so that moving the disc moves them with
        // it — otherwise a translated frame would have different detail and the sharpness ranking
        // would be measuring the crater layout rather than the blur.
        val craterList = (0 until craters).map {
            val a = rng.nextDouble() * 2 * PI
            val r = sqrt(rng.nextDouble()) * radiusPx * 0.85
            Triple(cos(a) * r, sin(a) * r, radiusPx * (0.06 + rng.nextDouble() * 0.10))
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centreX
                val dy = y - centreY
                val r = hypot(dx, dy)
                if (r > radiusPx) continue

                // Mild limb darkening: bright in the middle, falling toward the edge.
                val mu = sqrt(1.0 - (r / radiusPx) * (r / radiusPx))
                var value = peakAdu * (0.55 + 0.45 * mu)

                // Phase. The terminator is where the illumination angle crosses zero, which
                // projects to an ellipse of half-width |2*phase - 1| across the disc.
                if (phase < 1.0) {
                    val k = 2.0 * phase - 1.0
                    val terminator = k * sqrt((radiusPx * radiusPx - dy * dy).coerceAtLeast(0.0))
                    if (dx < terminator) {
                        // Unlit side. Not perfectly black: earthshine is real and keeps the test
                        // honest about a detector that assumed the dark limb was background.
                        value *= 0.02
                    }
                }

                for ((cx, cy, cr) in craterList) {
                    val cd = hypot(dx - cx, dy - cy)
                    if (cd < cr) {
                        // Dark floor with a bright rim — the mid-frequency detail that sharpness
                        // is supposed to find.
                        value *= if (cd > cr * 0.75) 1.18 else 0.72
                    }
                }
                pixels[y * width + x] = BLACK_LEVEL + value
            }
        }

        if (blurSigma > 0.0) gaussianBlur(pixels, width, height, blurSigma)

        if (noiseAdu > 0.0) {
            for (i in pixels.indices) pixels[i] += gaussian(rng) * noiseAdu
        }
        if (clip) {
            for (i in pixels.indices) pixels[i] = pixels[i].coerceIn(0.0, WHITE_LEVEL.toDouble())
        }

        return Frame(pixels, width, height, centreX, centreY, radiusPx)
    }

    /** Separable Gaussian, wide enough to be honest at the tails. */
    private fun gaussianBlur(pixels: DoubleArray, width: Int, height: Int, sigma: Double) {
        val radius = maxOf(1, (sigma * 3).toInt())
        val kernel = DoubleArray(2 * radius + 1)
        var sum = 0.0
        for (i in kernel.indices) {
            val d = (i - radius).toDouble()
            kernel[i] = exp(-d * d / (2 * sigma * sigma))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum

        val tmp = DoubleArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0.0
                for (k in kernel.indices) {
                    val sx = (x + k - radius).coerceIn(0, width - 1)
                    acc += pixels[y * width + sx] * kernel[k]
                }
                tmp[y * width + x] = acc
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0.0
                for (k in kernel.indices) {
                    val sy = (y + k - radius).coerceIn(0, height - 1)
                    acc += tmp[sy * width + x] * kernel[k]
                }
                pixels[y * width + x] = acc
            }
        }
    }

    private fun gaussian(rng: Random): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)
    }
}
