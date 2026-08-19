package com.starstacker.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * T-4.0 — a sky that can be shot indoors, on a cloudy night, in a unit test.
 *
 * ### Why this is the first task of Phase 2 rather than a convenience
 *
 * Everything still unticked in Phases 0, 1B, 1C and 1E is blocked on the same thing: **a clear
 * night**. Registration (T-4.2, T-4.3) and stacking (Phase 3) cannot be developed that way at all,
 * because they need something stronger than a sky — they need a sky whose **true** transform is
 * known. You cannot measure a 0.2 px registration residual against a real star field; you can only
 * look at the result and decide whether it seems sharp. Ground truth is the whole point, and it is
 * the one thing a real sky can never provide.
 *
 * ### It renders a mosaic, not a plane
 *
 * `StarDetectorTest` already synthesises stars, but it does so on a **binned mono plane** — the
 * thing `StarDetector` consumes. That is the right level for testing the detector and the wrong
 * level for everything downstream, because it skips the Bayer mosaic, the black level, the ADU
 * quantisation and the clipping, all of which the real pipeline hits first. This renders what the
 * *sensor* produces: a GRBG mosaic of 10-bit ADU values with a black pedestal, which
 * [com.starstacker.stars.CfaBinner] bins and everything else reads.
 *
 * ### Everything is built in electrons and converted once
 *
 * Noise is only correct if it is applied to a physical quantity. Photons arrive as a Poisson
 * process, so the shot noise on a signal of *N* electrons is √N — a fact about electrons that
 * becomes wrong the moment you apply it to ADU, because ADU are electrons divided by a gain that
 * changes with ISO. So the scene is accumulated in electrons, both noise sources are applied there,
 * and the conversion to ADU happens exactly once at the end. The defaults are the reference
 * device's measured figures at ISO 3200 (§1.8): 0.74 e⁻/ADU, 2.07 e⁻ read noise, 707 e⁻ full scale.
 *
 * ### Frames have to be big enough for the background model, and this bit twice
 *
 * `StarDetector` fits its background on **64 px tiles** of the binned plane and estimates noise
 * from the residual. Give it a plane only one or two tiles across and the fit cannot follow the
 * light-pollution gradient, so the gradient lands in the *noise* estimate instead — measured here
 * at 33 ADU against a true pixel noise of 17, which doubles the 5-sigma threshold and silently
 * loses every faint star. A 256x192 frame found 5 of 20 stars; the same field at 512x384 found 15.
 *
 * So the frame must satisfy `width / binFactor >= several x 64`. [MIN_USEFUL_WIDTH] is the default
 * for that reason, and it is why a generator that renders a *small* frame to keep tests fast is a
 * false economy: it does not test the pipeline, it tests the pipeline's failure mode.
 *
 * ### Determinism
 *
 * Every random draw comes from a seeded [Random]. A regression test that cannot reproduce its own
 * input is not a regression test, and a registration failure that cannot be replayed is a bug
 * report nobody can act on.
 */
class SyntheticSky(
    val width: Int = MIN_USEFUL_WIDTH,
    val height: Int = MIN_USEFUL_HEIGHT,
    /** Row-major over the 2×2 cell: 0 = red, 1 = green, 2 = blue. GRBG, as the reference device. */
    val cfaCodes: List<Int> = listOf(1, 0, 2, 1),
    val blackLevel: Int = 64,
    val whiteLevel: Int = 1023,
    /** Measured at ISO 3200 on the reference sensor (§1.8). */
    val electronsPerAdu: Double = 0.74,
    val readNoiseElectrons: Double = 2.07,
    /** Sky background rate. 40 e⁻/s is a fair suburban sky; a dark site is nearer 2. */
    val skyElectronsPerSecond: Double = 40.0,
    /**
     * Fractional brightness ramp across the frame — light pollution from one horizon.
     * 0.35 means the bright edge sits 35 % above the mean. Zero for a flat sky.
     */
    val gradientFraction: Double = 0.25,
    /** Direction the gradient brightens towards, degrees clockwise from +x. */
    val gradientAngleDeg: Double = 30.0,
    /** Fractional falloff at the frame corners from lens vignetting. 0.0 disables it. */
    val vignettingFraction: Double = 0.30,
    val hotPixelCount: Int = 24,
) {
    init {
        require(width % 2 == 0 && height % 2 == 0) { "a Bayer mosaic needs even dimensions" }
        require(cfaCodes.size == 4) { "expected a 2x2 CFA pattern, got ${cfaCodes.size}" }
    }

    /** One star, in frame coordinates, with its total flux in electrons per second. */
    data class Star(
        val x: Double,
        val y: Double,
        val electronsPerSecond: Double,
        /** Gaussian PSF sigma in pixels. Focus is what moves this. */
        val sigma: Double = 1.6,
    )

    /**
     * The rigid 3-DoF transform Phase 2 has to recover: a rotation about the frame centre and a
     * translation. **This is the ground truth** — [render] applies it and a registration test
     * asserts that the estimate matches.
     *
     * Rigid rather than affine because that is what the sky does to a phone on a tripod: an alt-az
     * mount rotates the field and the mount drifts, but nothing scales or shears it (FR-7.3).
     */
    data class Transform(
        val rotationDeg: Double = 0.0,
        val dx: Double = 0.0,
        val dy: Double = 0.0,
    ) {
        fun apply(x: Double, y: Double, centreX: Double, centreY: Double): Pair<Double, Double> {
            val t = rotationDeg * PI / 180.0
            val ox = x - centreX
            val oy = y - centreY
            return Pair(
                centreX + ox * cos(t) - oy * sin(t) + dx,
                centreY + ox * sin(t) + oy * cos(t) + dy,
            )
        }

        companion object {
            val IDENTITY = Transform()
        }
    }

    /** A rendered frame and the transform that produced it. */
    class Frame(
        val pixels: ShortArray,
        val width: Int,
        val height: Int,
        val truth: Transform,
        /** Where each star actually landed, for tests that want to check the renderer itself. */
        val placedStars: List<Star>,
    )

    private val centreX get() = (width - 1) / 2.0
    private val centreY get() = (height - 1) / 2.0

    /**
     * A star field with a realistic brightness distribution: many faint, few bright.
     *
     * Real magnitudes are logarithmic and star counts rise steeply towards the faint end, so a
     * uniform distribution of brightness would produce a field of near-identical blobs — and a
     * detector tuned against that would fall over on a real sky, where the interesting question is
     * always whether the faint ones cleared the threshold. Flux here goes as a power law.
     *
     * Stars are placed with a margin so none is clipped by the frame edge, which would bias its
     * centroid and make a registration residual look like a tracking error.
     */
    fun field(count: Int, seed: Int, margin: Double = 12.0): List<Star> {
        val rng = Random(seed)
        return List(count) {
            val faintness = rng.nextDouble()
            Star(
                x = margin + rng.nextDouble() * (width - 2 * margin),
                y = margin + rng.nextDouble() * (height - 2 * margin),
                // Power law: most stars near the floor, a handful several times brighter.
                //
                // The range is chosen against the numbers that decide detectability, not picked
                // for looks. At the default 7.4 s and sigma 1.6, a Gaussian spreads its flux over
                // 2*pi*sigma^2 = 16 px, so these rates give peaks of roughly 180 to 410 e-. Sky
                // shot noise at 40 e-/s over 7.4 s is sqrt(296) = 17 e-, halved again by 2x
                // binning — so even the faint end clears StarDetector's 5-sigma threshold, and
                // the bright end stays under the 707 e- full scale rather than clipping.
                electronsPerSecond = FAINTEST_STAR_ELECTRONS_PER_SECOND +
                    (1.0 - faintness).pow(2.2) * STAR_BRIGHTNESS_SPAN,
                sigma = 1.6,
            )
        }
    }

    /**
     * Renders one frame.
     *
     * Order matters and follows the physics: the scene is accumulated in electrons (sky, gradient,
     * stars, all attenuated by vignetting), *then* shot noise is drawn on the total, *then* read
     * noise is added, and only then is the result converted to ADU and clipped. Applying shot noise
     * after the conversion, or to the star and the sky separately, would both give the wrong
     * variance — and a synthetic frame with the wrong noise is worse than no synthetic frame,
     * because every threshold tuned against it is tuned against a fiction.
     */
    fun render(
        stars: List<Star>,
        exposureSeconds: Double,
        transform: Transform = Transform.IDENTITY,
        seed: Int = 1,
    ): Frame {
        val rng = Random(seed)
        val scene = DoubleArray(width * height)

        // Sky, with the light-pollution ramp.
        val skyElectrons = skyElectronsPerSecond * exposureSeconds
        val angle = gradientAngleDeg * PI / 180.0
        val gx = cos(angle)
        val gy = sin(angle)
        val halfSpan = (kotlin.math.abs(gx) * width + kotlin.math.abs(gy) * height) / 2.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val along = ((x - centreX) * gx + (y - centreY) * gy) / halfSpan
                scene[y * width + x] = skyElectrons * (1.0 + gradientFraction * along)
            }
        }

        // Stars, at their transformed positions.
        val placed = stars.map { star ->
            val (sx, sy) = transform.apply(star.x, star.y, centreX, centreY)
            star.copy(x = sx, y = sy)
        }
        placed.forEach { addStar(scene, it, exposureSeconds) }

        // Vignetting attenuates everything the lens delivered — sky and stars alike. It is applied
        // before noise because it is an optical effect, not a sensor one.
        if (vignettingFraction > 0.0) {
            val maxR = hypot(centreX, centreY)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val r = hypot(x - centreX, y - centreY) / maxR
                    scene[y * width + x] *= 1.0 - vignettingFraction * r * r
                }
            }
        }

        // Hot pixels: fixed per sensor, not per frame, so they stack coherently and calibration
        // can remove them. Drawn from their own generator so the frame seed does not move them.
        val hot = hotPixels()

        val out = ShortArray(width * height)
        for (i in scene.indices) {
            var electrons = scene[i]
            hot[i]?.let { electrons += it * exposureSeconds }
            // Shot noise on the total signal, then read noise. Poisson approximated by a Gaussian
            // of matching variance, which is accurate well below the electron counts here.
            val shot = if (electrons > 0) gaussian(rng) * sqrt(electrons) else 0.0
            val read = gaussian(rng) * readNoiseElectrons
            val adu = (electrons + shot + read) / electronsPerAdu + blackLevel
            out[i] = adu.roundToInt().coerceIn(0, whiteLevel).toShort()
        }
        return Frame(out, width, height, transform, placed)
    }

    /**
     * A sequence of frames drifting and rotating, which is what an alt-az tripod actually does —
     * and what Phase 2 has to follow.
     *
     * The transform is *cumulative*, so frame N is N steps from frame 0. That is the case
     * registration finds hardest and the one that matters: a cold-start match against the reference
     * frame after twenty minutes of drift, not a match against the previous frame.
     */
    fun sequence(
        stars: List<Star>,
        frames: Int,
        exposureSeconds: Double,
        perFrame: Transform,
        seed: Int = 1,
    ): List<Frame> = (0 until frames).map { i ->
        render(
            stars = stars,
            exposureSeconds = exposureSeconds,
            transform = Transform(
                rotationDeg = perFrame.rotationDeg * i,
                dx = perFrame.dx * i,
                dy = perFrame.dy * i,
            ),
            seed = seed + i,
        )
    }

    /**
     * The CFA response. A star is broadly white, so it lands on all three channels — but not
     * equally, because the filters differ in transmission and the sensor's QE is not flat. Green
     * is the reference at 1.0, which is also why green is the channel the pipeline bins.
     */
    private fun channelGain(x: Int, y: Int): Double =
        when (cfaCodes[(y % 2) * 2 + (x % 2)]) {
            RED -> 0.72
            BLUE -> 0.63
            else -> 1.0
        }

    private fun addStar(scene: DoubleArray, star: Star, exposureSeconds: Double) {
        val total = star.electronsPerSecond * exposureSeconds
        val sigma = star.sigma
        // A Gaussian is unbounded; past four sigma it contributes less than a ten-thousandth of
        // the flux, which is far below the read noise and not worth the loop.
        val reach = kotlin.math.ceil(sigma * 4).toInt()
        val norm = total / (2.0 * PI * sigma * sigma)
        val x0 = (star.x - reach).toInt().coerceAtLeast(0)
        val x1 = (star.x + reach).toInt().coerceAtMost(width - 1)
        val y0 = (star.y - reach).toInt().coerceAtLeast(0)
        val y1 = (star.y + reach).toInt().coerceAtMost(height - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - star.x
                val dy = y - star.y
                val v = norm * exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma))
                scene[y * width + x] += v * channelGain(x, y)
            }
        }
    }

    /**
     * Hot pixels, as an electrons-per-second map. Sparse, so it is a lookup rather than a plane.
     *
     * Fixed for the life of the generator rather than per frame, because that is what makes them
     * *hot pixels* and not noise: they land in the same place every frame, survive stacking, and
     * are the reason a dark frame is worth taking (D-16).
     */
    private fun hotPixels(): Map<Int, Double> {
        if (hotPixelCount <= 0) return emptyMap()
        val rng = Random(HOT_PIXEL_SEED)
        return buildMap {
            repeat(hotPixelCount) {
                val i = rng.nextInt(width * height)
                put(i, 20.0 + rng.nextDouble() * 400.0)
            }
        }
    }

    /** Box–Muller. `kotlin.random` has no Gaussian, and the JDK's needs a `java.util.Random`. */
    private fun gaussian(rng: Random): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    companion object {
        const val RED = 0
        const val GREEN = 1
        const val BLUE = 2

        /**
         * The smallest frame that still exercises the pipeline rather than its failure mode.
         *
         * At the default 2x binning this gives a 256x192 plane — four by three of
         * `StarDetector`'s 64 px background tiles, which is enough for the fit to follow a
         * gradient instead of mistaking it for noise. See the class note; going smaller does not
         * make a test faster, it makes it wrong.
         */
        const val MIN_USEFUL_WIDTH = 512
        const val MIN_USEFUL_HEIGHT = 384

        /** The floor of [field]'s power law — still comfortably detectable at 7.4 s. */
        private const val FAINTEST_STAR_ELECTRONS_PER_SECOND = 400.0

        /**
         * How far above the floor the brightest stars reach.
         *
         * Capped so the brightest star's peak plus the sky background stays under the 1023 ADU
         * white level: clipping is realistic, but a fixture where the brightest stars saturate
         * gives registration a biased centroid to chase and makes every residual look worse than
         * it is. `SyntheticSkyTest` has a dedicated clipping case for when that is the point.
         */
        private const val STAR_BRIGHTNESS_SPAN = 500.0

        private const val HOT_PIXEL_SEED = 99
    }
}
