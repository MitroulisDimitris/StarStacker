package com.starstacker.synth

import com.starstacker.stars.CfaBinner
import com.starstacker.stars.FrameStars
import com.starstacker.stars.StarDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * T-4.0 — the generator's own tests.
 *
 * These matter more than they look. Phase 2 will assert that registration recovers a transform to
 * a fraction of a pixel, and **that assertion is only worth anything if the frames really carry the
 * transform they claim**. A generator with a half-pixel bias in its star placement would make a
 * correct registrator look broken, or — far worse — make a broken one look correct. So the ground
 * truth is checked here, before anything is built on it.
 */
class SyntheticSkyTest {

    // The default size, deliberately: a smaller frame starves StarDetector's 64 px background
    // tiles and turns the light-pollution gradient into noise. See SyntheticSky's class note.
    private val sky = SyntheticSky()

    /** The pipeline a real frame takes: mosaic → binned green plane → detections. */
    private fun detect(frame: SyntheticSky.Frame, factor: Int = 2): FrameStars {
        val plane = CfaBinner.binGreen(
            frame.pixels, frame.width, frame.height, sky.cfaCodes, factor,
        )
        // The white level has to be passed or nothing is ever flagged as saturated:
        // StarDetector defaults saturationLevel to Double.MAX_VALUE, and the app supplies it.
        return StarDetector(saturationLevel = sky.whiteLevel.toDouble())
            .detect(plane.data, plane.width, plane.height)
    }

    /**
     * Detections in **sensor** coordinates.
     *
     * `StarDetector` works on the binned analysis plane and reports positions there;
     * `BinnedPlane.toSensorCoordinate` is the documented way back. Comparing an analysis
     * coordinate against an injected sensor coordinate would look like a binFactor-sized
     * registration error, which is exactly the kind of phantom this generator exists to prevent.
     */
    private fun detectInSensorSpace(
        frame: SyntheticSky.Frame,
        factor: Int = 2,
    ): List<Pair<Double, Double>> {
        val plane = CfaBinner.binGreen(
            frame.pixels, frame.width, frame.height, sky.cfaCodes, factor,
        )
        return StarDetector(saturationLevel = sky.whiteLevel.toDouble())
            .detect(plane.data, plane.width, plane.height).stars.map {
            Pair(plane.toSensorCoordinate(it.x), plane.toSensorCoordinate(it.y))
        }
    }

    @Test
    fun `a frame is a valid mosaic in ADU with the black pedestal in place`() {
        val frame = sky.render(sky.field(count = 30, seed = 1), exposureSeconds = 7.4)

        assertEquals(sky.width * sky.height, frame.pixels.size)
        assertTrue(frame.pixels.all { it >= 0 && it <= 1023 }) { "values must fit the 10-bit ADC" }
        // Nothing sits at zero: the black level is a pedestal precisely so noise can go both ways
        // without being chopped off, and a synthetic frame that ignores it would let every
        // background estimator off the hook.
        val median = frame.pixels.map { it.toInt() }.sorted()[frame.pixels.size / 2]
        assertTrue(median > 64) { "median $median should sit above the black level of 64" }
    }

    @Test
    fun `an empty sky produces read noise of the requested size and no stars`() {
        // The generator's noise has to be right in *electrons*, so the check converts back: the
        // measured spread in ADU times the gain should return the read noise it was given.
        val dark = SyntheticSky(
            width = 128, height = 128,
            skyElectronsPerSecond = 0.0,
            gradientFraction = 0.0,
            vignettingFraction = 0.0,
            hotPixelCount = 0,
        )
        val frame = dark.render(stars = emptyList(), exposureSeconds = 1.0, seed = 7)

        val values = frame.pixels.map { it.toDouble() }
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)

        assertEquals(64.0, mean, 0.5) { "an unexposed frame sits at the black level" }
        assertEquals(2.07, sd * dark.electronsPerAdu, 0.25) { "read noise in electrons" }
        assertEquals(0, detect(frame).count) { "noise must not manufacture stars" }
    }

    @Test
    fun `shot noise grows as the square root of the signal`() {
        // The property that makes the generator physically honest, and the reason the scene is
        // accumulated in electrons: four times the sky should give twice the noise, not four times.
        fun spreadElectrons(rate: Double): Double {
            val flat = SyntheticSky(
                width = 128, height = 128,
                skyElectronsPerSecond = rate,
                gradientFraction = 0.0,
                vignettingFraction = 0.0,
                hotPixelCount = 0,
                readNoiseElectrons = 0.0,
            )
            val v = flat.render(emptyList(), exposureSeconds = 1.0, seed = 3).pixels.map { it.toDouble() }
            val m = v.average()
            return sqrt(v.sumOf { (it - m) * (it - m) } / v.size) * flat.electronsPerAdu
        }

        val low = spreadElectrons(100.0)
        val high = spreadElectrons(400.0)
        assertEquals(10.0, low, 1.5)
        assertEquals(20.0, high, 2.5)
        assertEquals(2.0, high / low, 0.25) { "4x the signal must give 2x the noise" }
    }

    @Test
    fun `stars land where the ground truth says they do`() {
        // The load-bearing property. Everything Phase 2 asserts rests on this being exact.
        val stars = sky.field(count = 20, seed = 5)
        val frame = sky.render(stars, exposureSeconds = 7.4)
        val found = detectInSensorSpace(frame, factor = 2)

        // Not all 20: the power law puts most stars near the detection floor, which is what a
        // real sky looks like and what makes the fixture worth having. Recall of 12/20 with no
        // false positives is the property that matters.
        assertTrue(found.size >= 12) { "only ${found.size} of 20 stars detected" }

        found.forEach { (fx, fy) ->
            val nearest = stars.minOf { hypot(it.x - fx, it.y - fy) }
            assertTrue(nearest < 3.0) { "detection at ($fx, $fy) matches no injected star" }
        }
    }

    @Test
    fun `a pure translation moves every star by exactly that much`() {
        // Ground truth for T-4.3's rigid fit. If this drifts, a correct registrator will look
        // biased and the residual it reports will be the generator's error, not the sky's.
        val stars = sky.field(count = 25, seed = 11)
        val reference = detectInSensorSpace(sky.render(stars, 7.4, seed = 1))
        val shifted = detectInSensorSpace(
            sky.render(stars, 7.4, SyntheticSky.Transform(dx = 6.0, dy = -4.0), seed = 1),
        )

        val matched = reference.mapNotNull { (rx, ry) ->
            shifted
                .minByOrNull { hypot(it.first - (rx + 6.0), it.second - (ry - 4.0)) }
                ?.takeIf { hypot(it.first - (rx + 6.0), it.second - (ry - 4.0)) < 3.0 }
                ?.let { Pair(it.first - rx, it.second - ry) }
        }

        assertTrue(matched.size >= 8) { "only ${matched.size} stars matched across the shift" }
        assertEquals(6.0, matched.map { it.first }.average(), 0.3)
        assertEquals(-4.0, matched.map { it.second }.average(), 0.3)
    }

    @Test
    fun `a rotation about the centre leaves the centre alone and moves the edges`() {
        val centreX = (sky.width - 1) / 2.0
        val centreY = (sky.height - 1) / 2.0
        val t = SyntheticSky.Transform(rotationDeg = 5.0)

        val (cx, cy) = t.apply(centreX, centreY, centreX, centreY)
        assertEquals(centreX, cx, 1e-9)
        assertEquals(centreY, cy, 1e-9)

        // A point on the edge sweeps an arc of r * theta.
        val r = 100.0
        val (ex, ey) = t.apply(centreX + r, centreY, centreX, centreY)
        val moved = hypot(ex - (centreX + r), ey - centreY)
        assertEquals(r * 5.0 * Math.PI / 180.0, moved, 0.05)
    }

    @Test
    fun `a sequence accumulates its drift rather than repeating it`() {
        // The case registration finds hardest: frame 20 is twenty steps from the reference, not
        // one. A generator that reset each frame would quietly make cold-start matching look easy.
        val stars = sky.field(count = 20, seed = 2)
        val frames = sky.sequence(
            stars, frames = 5, exposureSeconds = 7.4,
            perFrame = SyntheticSky.Transform(rotationDeg = 0.2, dx = 1.5, dy = 0.5),
        )

        assertEquals(5, frames.size)
        assertEquals(0.0, frames[0].truth.dx, 1e-9)
        assertEquals(6.0, frames[4].truth.dx, 1e-9)
        assertEquals(0.8, frames[4].truth.rotationDeg, 1e-9)
    }

    @Test
    fun `hot pixels stay put across frames while noise does not`() {
        // What makes them hot pixels rather than noise, and the reason a dark frame is worth
        // taking at all (D-16): they survive stacking because they land in the same place.
        val hot = SyntheticSky(
            width = 64, height = 64,
            skyElectronsPerSecond = 0.0,
            gradientFraction = 0.0,
            vignettingFraction = 0.0,
            hotPixelCount = 10,
        )
        val a = hot.render(emptyList(), exposureSeconds = 30.0, seed = 1)
        val b = hot.render(emptyList(), exposureSeconds = 30.0, seed = 999)

        val brightA = a.pixels.indices.filter { a.pixels[it] > 400 }.toSet()
        val brightB = b.pixels.indices.filter { b.pixels[it] > 400 }.toSet()

        assertTrue(brightA.isNotEmpty()) { "no hot pixels rendered" }
        assertEquals(brightA, brightB) { "hot pixels must not move between frames" }
    }

    @Test
    fun `the light pollution gradient brightens one side without inventing stars`() {
        val gradient = SyntheticSky(
            width = 128, height = 128,
            gradientFraction = 0.5,
            gradientAngleDeg = 0.0,
            vignettingFraction = 0.0,
            hotPixelCount = 0,
        )
        val frame = gradient.render(emptyList(), exposureSeconds = 7.4, seed = 4)

        fun columnMean(range: IntRange): Double {
            var sum = 0.0
            var n = 0
            for (y in 0 until 128) for (x in range) { sum += frame.pixels[y * 128 + x]; n++ }
            return sum / n
        }

        assertTrue(columnMean(96 until 128) > columnMean(0 until 32)) { "gradient runs the wrong way" }
        // The check StarDetectorTest already makes at plane level, repeated at mosaic level:
        // a smooth ramp is not a star field.
        assertEquals(0, detect(frame).count) { "a gradient must not manufacture stars" }
    }

    @Test
    fun `vignetting darkens the corners relative to the centre`() {
        val v = SyntheticSky(
            width = 128, height = 128,
            gradientFraction = 0.0,
            vignettingFraction = 0.4,
            hotPixelCount = 0,
        )
        val frame = v.render(emptyList(), exposureSeconds = 7.4, seed = 6)

        fun patchMean(cx: Int, cy: Int): Double {
            var sum = 0.0
            var n = 0
            for (y in cy - 6 until cy + 6) for (x in cx - 6 until cx + 6) {
                sum += frame.pixels[y * 128 + x]; n++
            }
            return sum / n
        }

        val centre = patchMean(64, 64) - v.blackLevel
        val corner = patchMean(10, 10) - v.blackLevel
        assertTrue(corner < centre) { "corner $corner should be darker than centre $centre" }
        assertTrue(abs(corner / centre - 0.6) < 0.15) { "expected roughly 40% falloff at the corner" }
    }

    @Test
    fun `the same seed renders the same frame`() {
        // A registration failure that cannot be replayed is a bug report nobody can act on.
        val stars = sky.field(count = 15, seed = 8)
        val a = sky.render(stars, 7.4, seed = 42)
        val b = sky.render(stars, 7.4, seed = 42)
        assertTrue(a.pixels.contentEquals(b.pixels))

        val c = sky.render(stars, 7.4, seed = 43)
        assertTrue(!a.pixels.contentEquals(c.pixels)) { "a different seed must differ" }
    }

    @Test
    fun `a bright enough sky clips, and says so through the detector`() {
        // The failure §1.8 caught on real hardware: a saturated frame reports plausible stars.
        // The generator has to be able to produce that case, or the guard cannot be regression
        // tested indoors.
        val bright = SyntheticSky(
            width = 128, height = 128,
            skyElectronsPerSecond = 5000.0,
            gradientFraction = 0.0,
            vignettingFraction = 0.0,
            hotPixelCount = 0,
        )
        val frame = bright.render(emptyList(), exposureSeconds = 7.4, seed = 2)

        assertTrue(frame.pixels.all { it.toInt() == 1023 }) { "expected a fully clipped frame" }
        assertTrue(detect(frame).saturatedFrame) { "the detector must flag saturation" }
    }
}
