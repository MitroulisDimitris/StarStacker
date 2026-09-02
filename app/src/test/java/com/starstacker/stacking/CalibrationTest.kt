package com.starstacker.stacking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * T-5.2 — calibration, checked against arithmetic and against the mistakes it is shaped to avoid.
 *
 * Calibration errors do not throw and do not look like errors. Subtract the black level twice and
 * the background goes slightly negative; forget it and the flat division reshapes the sky; clamp
 * the negatives and the master comes out with a raised, uneven background that the gradient removal
 * in FR-8.1 step 5 then spends its effort fighting. Each of those has a test here, because none of
 * them would be visible in the result.
 */
class CalibrationTest {

    private val w = 8
    private val h = 8
    private val n = w * h
    private val black = 64.0

    private fun frame(value: Int) = ShortArray(n) { value.toShort() }

    // ------------------------------------------------------------------ the pedestal

    @Test
    fun `with no masters at all, only the black level is removed`() {
        // Functional tier: no calibration library, and the pipeline still has to produce numbers
        // measured from zero rather than from 64.
        val masters = Calibration.Masters.of(w, h)
        val out = FloatArray(n)
        Calibration.apply(frame(1000), masters, black, out)

        assertTrue(out.all { abs(it - 936f) < 1e-3 }) { "was ${out[0]}" }
        assertFalse(masters.hasDark)
        assertFalse(masters.hasFlat)
    }

    @Test
    fun `with a dark, the pedestal leaves with it and is not subtracted twice`() {
        // The dark carries the pedestal too. Removing both would push the background negative by
        // exactly the black level — a uniform offset that survives stacking and looks like a
        // calibration that worked.
        val dark = FloatArray(n) { 64f + 12f } // pedestal plus 12 ADU of dark current
        val masters = Calibration.Masters.of(w, h, dark = dark)
        val out = FloatArray(n)
        Calibration.apply(frame(1000), masters, black, out)

        assertTrue(out.all { abs(it - 924f) < 1e-3 }) { "was ${out[0]}, expected 1000 - 76" }
    }

    @Test
    fun `a light equal to its dark calibrates to zero, not to minus the black level`() {
        // The cleanest statement of the same rule: an unexposed frame should come out at nothing.
        val dark = FloatArray(n) { 76f }
        val masters = Calibration.Masters.of(w, h, dark = dark)
        val out = FloatArray(n)
        Calibration.apply(frame(76), masters, black, out)

        assertTrue(out.all { abs(it) < 1e-3 }) { "was ${out[0]}" }
    }

    // ------------------------------------------------------------------ negatives

    @Test
    fun `negatives survive, because clamping them biases the master`() {
        // A starless pixel scatters either side of zero after dark subtraction. Clamping the
        // negative half lifts the mean — more where the noise is larger, so non-uniformly across
        // the frame — and that bias survives averaging into the finished master.
        val dark = FloatArray(n) { 100f }
        val masters = Calibration.Masters.of(w, h, dark = dark)
        val out = FloatArray(n)
        Calibration.apply(frame(90), masters, black, out)

        assertTrue(out.all { it < 0f }) { "negatives were clamped: ${out[0]}" }
        assertEquals(-10f, out[0], 1e-3f)
    }

    @Test
    fun `noise either side of zero averages back to zero`() {
        // The property the previous test protects, stated as the thing that actually matters.
        val rng = Random(4)
        val dark = FloatArray(n) { 100f }
        val masters = Calibration.Masters.of(w, h, dark = dark)
        val out = FloatArray(n)
        val light = ShortArray(n) { (100 + rng.nextInt(-20, 21)).toShort() }
        Calibration.apply(light, masters, black, out)

        val mean = out.average()
        assertTrue(abs(mean) < 4.0) { "mean drifted to $mean — something is clipping" }
    }

    // ------------------------------------------------------------------ the flat

    @Test
    fun `a flat is normalised to unit mean, so an even flat changes nothing`() {
        // A flat of all 2000 and a flat of all 1 describe the same optics: no vignetting. Only the
        // *shape* is information, so the level must be divided out.
        val flat = FloatArray(n) { 2000f }
        val masters = Calibration.Masters.of(w, h, rawFlat = flat)
        val out = FloatArray(n)
        Calibration.apply(frame(1000), masters, black, out)

        assertTrue(out.all { abs(it - 936f) < 1e-2 }) { "was ${out[0]}" }
    }

    @Test
    fun `vignetting is corrected, and the corners come back up`() {
        // Half the light at the corner means half the gain, so the corner is divided by 0.5 and
        // recovers to match the centre.
        val flat = FloatArray(n) { if (it == 0) 500f else 1000f }
        val masters = Calibration.Masters.of(w, h, rawFlat = flat)
        val out = FloatArray(n)
        Calibration.apply(frame(1000), masters, black, out)

        // The mean is dominated by the 1000s, so the centre stays near 936 and the corner doubles.
        assertTrue(out[0] > out[1] * 1.9) { "corner ${out[0]} against centre ${out[1]}" }
    }

    @Test
    fun `a dead flat pixel is marked bad rather than amplified into a star`() {
        // Dividing by a photosite that saw nothing turns noise into an enormous value, and an
        // enormous value in a linear frame is indistinguishable from a bright star.
        val flat = FloatArray(n) { if (it == 5) 0f else 1000f }
        val masters = Calibration.Masters.of(w, h, rawFlat = flat)
        val out = FloatArray(n)
        Calibration.apply(frame(1000), masters, black, out)

        assertTrue(out[5].isNaN()) { "dead pixel became ${out[5]}" }
        assertTrue(out.filterIndexed { i, _ -> i != 5 }.all { it.isFinite() })
    }

    @Test
    fun `a dead column does not drag the normalisation off`() {
        // Zeros must not join the mean, or every other pixel is inflated to compensate for them.
        val flat = FloatArray(n) { if (it % w == 0) 0f else 1000f }
        val masters = Calibration.Masters.of(w, h, rawFlat = flat)
        val live = masters.flat!!.filterIndexed { i, _ -> i % w != 0 }
        assertTrue(live.all { abs(it - 1f) < 1e-3 }) { "normalised to ${live.first()}" }
    }

    // ------------------------------------------------------------------ hot pixels

    @Test
    fun `a hot pixel is repaired from its own colour, two pixels away`() {
        // The detail that matters on CFA data: the nearest same-colour neighbours sit at plus or
        // minus two. Repairing from the adjacent pixels would mix red into a green photosite and
        // produce a plausible-looking wrong value, which is worse than leaving it hot.
        val light = ShortArray(n) { 100 }
        val hot = 3 * w + 3
        light[hot] = 4000
        // Give the immediate neighbours a very different level; the repair must ignore them.
        light[hot - 1] = 900
        light[hot + 1] = 900
        light[hot - w] = 900
        light[hot + w] = 900

        val masters = Calibration.Masters.of(w, h, hotPixels = intArrayOf(hot))
        val out = FloatArray(n)
        Calibration.apply(light, masters, black, out)

        // Same-colour neighbours are all 100, so the repair is 100 - 64 = 36.
        assertEquals(36f, out[hot], 1e-3f) { "repaired from the wrong colour: ${out[hot]}" }
    }

    @Test
    fun `a hot pixel beside another hot pixel is still repaired sensibly`() {
        // Clusters are common, which is why the repair is a median and not a mean: one bad
        // neighbour would otherwise be carried into the answer.
        val light = ShortArray(n) { 100 }
        val hot = 4 * w + 4
        light[hot] = 4000
        light[hot + 2] = 3800 // a same-colour neighbour that is also hot

        val masters = Calibration.Masters.of(w, h, hotPixels = intArrayOf(hot))
        val out = FloatArray(n)
        Calibration.apply(light, masters, black, out)

        assertTrue(out[hot] < 500f) { "the neighbour's fault was carried in: ${out[hot]}" }
    }

    @Test
    fun `a hot pixel at the frame edge is repaired from what exists`() {
        val light = ShortArray(n) { 100 }
        light[0] = 4000
        val masters = Calibration.Masters.of(w, h, hotPixels = intArrayOf(0))
        val out = FloatArray(n)
        Calibration.apply(light, masters, black, out)
        assertEquals(36f, out[0], 1e-3f)
    }

    // ------------------------------------------------------------------ building masters

    @Test
    fun `the master dark is a median, so a cosmic ray does not join it`() {
        // A mean would fold the outlier in and then subtract it from every light in the session,
        // punching a permanent hole where a single dark frame was hit.
        val darks = List(9) { frame(80) }.toMutableList()
        darks[4] = frame(80).also { it[10] = 4000 }

        val master = Calibration.masterDark(darks, n)!!
        assertEquals(80f, master[10], 1e-3f) { "the outlier survived: ${master[10]}" }
    }

    @Test
    fun `no darks means no master rather than an empty one`() {
        // An all-zero master would be subtracted happily and silently do nothing but remove the
        // pedestal twice.
        assertEquals(null, Calibration.masterDark(emptyList(), n))
    }

    @Test
    fun `hot pixels are found relative to the dark's own spread`() {
        // What counts as hot depends on ISO, exposure and temperature. A fixed ADU threshold finds
        // thousands on a warm 30 s dark and none on a cool 1 s one.
        val rng = Random(9)
        val dark = FloatArray(n) { 80f + rng.nextInt(-2, 3) }
        dark[7] = 900f
        dark[33] = 1200f

        val hot = Calibration.hotPixelsFrom(dark, n)

        assertTrue(hot.contains(7) && hot.contains(33)) { "missed the hot pixels: ${hot.toList()}" }
        assertTrue(hot.size <= 4) { "found ${hot.size} hot pixels in a clean dark" }
    }

    @Test
    fun `a perfectly flat dark does not report every pixel as hot`() {
        // MAD is zero here, and a threshold built on zero spread would call everything above the
        // median an outlier.
        val dark = FloatArray(n) { 80f }
        assertTrue(Calibration.hotPixelsFrom(dark, n).isEmpty())
    }

    // ------------------------------------------------------------------ the record

    @Test
    fun `what was applied is recorded for the restack`() {
        assertEquals("no dark · no flat", Calibration.Masters.of(w, h).describe())
        assertEquals(
            "dark · flat · 2 hot pixels",
            Calibration.Masters.of(
                w, h,
                dark = FloatArray(n),
                rawFlat = FloatArray(n) { 1f },
                hotPixels = intArrayOf(1, 2),
            ).describe(),
        )
    }

    @Test
    fun `the whole chain composes in the order FR-8_1 states`() {
        // dark first, then flat, then hot pixels — and the result is checkable by hand.
        val light = ShortArray(n) { 500 }
        val hot = 2 * w + 2
        light[hot] = 4000

        val masters = Calibration.Masters.of(
            w, h,
            dark = FloatArray(n) { 100f },
            rawFlat = FloatArray(n) { if (it == 9) 500f else 1000f },
            hotPixels = intArrayOf(hot),
        )
        val out = FloatArray(n)
        Calibration.apply(light, masters, black, out)

        // A plain pixel: (500 - 100) = 400 ADU of signal, then divided by its normalised gain.
        //
        // That gain is not exactly 1, and the difference is the normalisation doing its job: with
        // 63 pixels at 1000 and one at 500 the flat's mean is 992.19, so a full-gain pixel divides
        // by 1000/992.19 = 1.00787 and lands at 396.88. Pinning the exact number rather than a
        // round one is what makes this a test of the normalisation instead of a test of division.
        assertEquals(396.875f, out[0], 0.01f)
        // The half-gain pixel is lifted back to about twice that.
        assertEquals(793.75f, out[9], 0.02f) { "flat not applied: ${out[9]}" }
        // The hot one is replaced by its same-colour neighbours, which are plain pixels.
        assertEquals(396.875f, out[hot], 0.01f)
    }

    // ------------------------------------------------------------------ bands of a frame

    @Test
    fun `a band takes its own rows of the masters, not the frame's first rows`() {
        // The whole point of the band parameters. A dark whose value is its own row makes a wrong
        // offset visible; a constant dark would subtract the same amount either way and the test
        // would pass while the pipeline was broken.
        val dark = FloatArray(n) { (it / w).toFloat() }
        val masters = Calibration.Masters.of(w, h, dark = dark)

        val bandRows = 3
        val fromRow = 4
        val light = ShortArray(w * bandRows) { (500 + (fromRow + it / w)).toShort() }
        val out = FloatArray(w * bandRows)
        Calibration.apply(light, masters, black, out, fromRow = fromRow, rowCount = bandRows)

        // light - dark = (500 + y) - y = 500 on every row of the band.
        for (i in 0 until w * bandRows) assertEquals(500f, out[i], 1e-3f) { "index $i" }
    }

    @Test
    fun `whole-frame and band-at-a-time produce the same answer`() {
        // The property that defines banding, stated the same way T-5.3 states it for tiling: the
        // band boundaries must be invisible in the result.
        val dark = FloatArray(n) { (it % 7).toFloat() }
        val flat = FloatArray(n) { 0.8f + (it % 5) * 0.1f }
        val masters = Calibration.Masters.of(w, h, dark = dark, rawFlat = flat)
        val light = ShortArray(n) { (1000 + it).toShort() }

        val whole = FloatArray(n)
        Calibration.apply(light, masters, black, whole)

        val banded = FloatArray(n)
        var row = 0
        while (row < h) {
            val rows = minOf(3, h - row)
            val slice = ShortArray(w * rows) { light[row * w + it] }
            val out = FloatArray(w * rows)
            Calibration.apply(slice, masters, black, out, fromRow = row, rowCount = rows)
            out.copyInto(banded, row * w, 0, w * rows)
            row += rows
        }

        for (i in 0 until n) assertEquals(whole[i], banded[i], 1e-4f) { "index $i" }
    }

    @Test
    fun `hot pixels outside the band are left alone`() {
        // The list is in whole-frame indices; a band must repair the ones it holds and ignore the
        // rest rather than translating them onto innocent rows.
        val insideBand = 4 * w + 3
        val outsideBand = 1 * w + 3
        val masters = Calibration.Masters.of(w, h, hotPixels = intArrayOf(outsideBand, insideBand))

        val bandRows = 3
        val fromRow = 4
        val light = ShortArray(w * bandRows) { 100 }
        light[insideBand - fromRow * w] = 9000
        val out = FloatArray(w * bandRows)
        Calibration.apply(light, masters, black, out, fromRow = fromRow, rowCount = bandRows)

        assertEquals(36f, out[insideBand - fromRow * w], 1e-3f) { "the hot one was not repaired" }
        // The out-of-band index must not have been applied to whatever sits at that offset here.
        assertEquals(36f, out[3], 1e-3f)
    }

    @Test
    fun `a band running past the master is refused`() {
        val masters = Calibration.Masters.of(w, h)
        val light = ShortArray(w * 4)
        val out = FloatArray(w * 4)
        assertThrows(IllegalArgumentException::class.java) {
            Calibration.apply(light, masters, black, out, fromRow = h - 2, rowCount = 4)
        }
    }

    @Test
    fun `the master dark built in bands matches the whole-frame one`() {
        val darks = listOf(
            ShortArray(n) { (it % 13).toShort() },
            ShortArray(n) { ((it * 3) % 17).toShort() },
            ShortArray(n) { ((it * 5) % 11).toShort() },
        )
        val whole = Calibration.masterDark(darks, n)!!

        val banded = FloatArray(n)
        var row = 0
        while (row < h) {
            val rows = minOf(3, h - row)
            val slices = darks.map { d -> ShortArray(w * rows) { d[row * w + it] } }
            Calibration.masterDarkInto(slices, w * rows, banded, row * w)
            row += rows
        }

        for (i in 0 until n) assertEquals(whole[i], banded[i], 0f) { "index $i" }
    }
}
