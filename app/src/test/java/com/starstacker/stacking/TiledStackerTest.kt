package com.starstacker.stacking

import com.starstacker.registration.RigidTransform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * T-5.3 — the tiled accumulator.
 *
 * **The whole loop is testable here only because the resampler is injected** (§1.31: OpenCV is an
 * Android `.so` and cannot load in a JVM test). The stub below is a nearest-neighbour warp and a
 * trivial debayer — deliberately not good, because what is under test is the *machinery*: whether
 * every frame reaches every tile, whether tile boundaries seam, whether buffers are reused, and
 * whether the result is identical however the work is divided.
 *
 * That last property is the one that matters most and the one a visual check would never catch. A
 * stack is correct when **the tiling is invisible in the answer** — same master at one tile as at
 * twenty. Anything else is a seam, and a seam in a linear astro frame looks like a gradient.
 */
class TiledStackerTest {

    private val w = 16
    private val h = 24

    /** A frame source that makes its pixels up, so the loop can be driven without files. */
    private class FakeFrames(
        override val count: Int,
        override val width: Int,
        override val height: Int,
        override val masters: Calibration.Masters,
        override val blackLevel: Double = 0.0,
        override val cfaCodes: List<Int> = listOf(1, 0, 2, 1),
        val transforms: (Int) -> RigidTransform? = { null },
        val pixel: (frame: Int, x: Int, y: Int) -> Int,
    ) : TiledStacker.Frames {
        var rowRequests = 0
            private set

        override fun transform(index: Int) = transforms(index)

        override fun rows(index: Int, fromRow: Int, rowCount: Int, into: ShortArray): Int {
            rowRequests++
            val available = (height - fromRow).coerceAtLeast(0)
            val rows = minOf(rowCount, available)
            for (r in 0 until rows) {
                for (x in 0 until width) {
                    into[r * width + x] = pixel(index, x, fromRow + r).toShort()
                }
            }
            return rows
        }
    }

    /**
     * A stand-in for OpenCV: nearest-neighbour warp, replicate-the-value debayer.
     *
     * Crude on purpose. Interpolation quality is T-5.1's business and was measured on the device;
     * what this has to do is honour the *contract* — the same coordinate convention and the same
     * uncovered sentinel — so the machinery is exercised against the same rules the real one obeys.
     */
    private class StubResampler : TiledStacker.Resampler {
        override fun debayer(
            cfa: ShortArray,
            width: Int,
            height: Int,
            cfaCodes: List<Int>,
            out: FloatArray,
        ): Boolean {
            for (i in 0 until width * height) {
                val v = (cfa[i].toInt() and 0xFFFF).toFloat()
                out[i * 3] = v
                out[i * 3 + 1] = v
                out[i * 3 + 2] = v
            }
            return true
        }

        override fun warpBand(
            src: FloatArray,
            width: Int,
            height: Int,
            channels: Int,
            rowOffset: Int,
            transform: RigidTransform,
            out: FloatArray,
        ): Boolean {
            // Forward, not inverse — and this stub had it backwards at first, which is the same
            // confusion Resample's own note warns about. The contract is: the transform maps
            // reference -> frame, so an output pixel p takes the frame's value at T(p). OpenCV
            // expresses that as WARP_INVERSE_MAP over the forward matrix; here it is just T.
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val (sx, sy) = transform.apply(x.toDouble(), (y + rowOffset).toDouble())
                    val ix = sx.roundToInt()
                    val iy = sy.roundToInt() - rowOffset
                    val to = (y * width + x) * channels
                    if (ix in 0 until width && iy in 0 until height) {
                        val from = (iy * width + ix) * channels
                        for (c in 0 until channels) out[to + c] = src[from + c]
                    } else {
                        for (c in 0 until channels) out[to + c] = Resample.UNCOVERED.toFloat()
                    }
                }
            }
            return true
        }
    }

    private fun masters() = Calibration.Masters.of(w, h)

    /**
     * The plain mean, named rather than defaulted.
     *
     * Since T-5.4 the default combiner is [Combine.SigmaClip], and these tests are about the
     * *machinery* — whether every frame reaches every tile, whether boundaries seam. Letting
     * rejection run underneath them would mean a test asserting an average could pass while
     * something else produced the number, which is the failure mode where a test stops being
     * evidence. The rejection has its own tests in `CombineTest`.
     */
    private val mean = TiledStacker.Combiner.Mean

    // ------------------------------------------------------------------ the tiling itself

    @Test
    fun `the mean of identical frames is that frame`() {
        val frames = FakeFrames(count = 5, width = w, height = h, masters = masters()) { _, x, y ->
            100 + x + y
        }
        val master = FloatArray(w * h * 3)

        assertTrue(TiledStacker(frames, StubResampler(), mean).stack(master))

        for (y in 0 until h) {
            for (x in 0 until w) {
                assertEquals((100 + x + y).toFloat(), master[(y * w + x) * 3], 1e-3f) { "at $x,$y" }
            }
        }
    }

    @Test
    fun `frames are averaged, not the last one kept`() {
        // The mistake that looks right: writing each frame over the last produces a plausible
        // image that is one frame deep, and nothing about it says so.
        val frames = FakeFrames(count = 4, width = w, height = h, masters = masters()) { f, _, _ ->
            100 * (f + 1)
        }
        val master = FloatArray(w * h * 3)
        TiledStacker(frames, StubResampler(), mean).stack(master)

        // (100 + 200 + 300 + 400) / 4
        assertEquals(250f, master[0], 1e-3f)
    }

    @Test
    fun `the tiling is invisible in the answer`() {
        // The property that defines correctness here: the master must not depend on how the work
        // was divided. A seam at a tile boundary is invisible per tile and looks like a gradient
        // across the finished frame.
        val make = {
            FakeFrames(count = 6, width = w, height = h, masters = masters()) { f, x, y ->
                (50 + f * 7 + x * 3 + y * 5) % 1000
            }
        }
        val oneTile = FloatArray(w * h * 3)
        val manyTiles = FloatArray(w * h * 3)

        // A budget large enough for the whole frame, and one so small each tile is a single row.
        TiledStacker(make(), StubResampler(), mean, memoryBudgetBytes = 64L * 1024 * 1024).stack(oneTile)
        TiledStacker(make(), StubResampler(), mean, memoryBudgetBytes = w * 3L * 6 * 4).stack(manyTiles)

        for (i in oneTile.indices) {
            assertEquals(oneTile[i], manyTiles[i], 1e-3f) { "tiling changed the master at $i" }
        }
    }

    @Test
    fun `more frames means thinner tiles`() {
        // The relationship worth stating: a 20-frame session and a 200-frame night do not stack
        // the same way, and a tile size chosen for one exceeds the budget for the other.
        // Big enough that both cases stay in the linear regime; at a tight budget the
        // 200-frame case floors at one row and the ratio stops meaning anything.
        val budget = 64L * 1024 * 1024
        val few = TiledStacker.tileRowsFor(width = 4096, frames = 20, budgetBytes = budget)
        val many = TiledStacker.tileRowsFor(width = 4096, frames = 200, budgetBytes = budget)

        assertTrue(few > many) { "$few should exceed $many" }
        assertEquals(few / 10, many) { "tile height should scale inversely with frame count" }
    }

    @Test
    fun `a tile is never zero rows, however tight the budget`() {
        assertTrue(TiledStacker.tileRowsFor(4096, 500, budgetBytes = 1) >= 1)
        assertTrue(TiledStacker.tileRowsFor(4096, 0, budgetBytes = 1) >= 1)
    }

    // ------------------------------------------------------------------ the margin

    @Test
    fun `a tile asks for rows either side of itself`() {
        // Under rotation a band of output maps to a taller, sheared band of input. Fetching only
        // the tile's own rows would lose a sliver at every boundary.
        val (first, count) = TiledStacker.sourceRowsFor(top = 500, rows = 100, margin = 160, height = 3072)
        assertEquals(340, first)
        assertEquals(420, count)
    }

    @Test
    fun `the margin is clipped at the frame edges rather than running off`() {
        val top = TiledStacker.sourceRowsFor(top = 0, rows = 100, margin = 160, height = 3072)
        assertEquals(0, top.first)
        assertEquals(260, top.second)

        val bottom = TiledStacker.sourceRowsFor(top = 3000, rows = 72, margin = 160, height = 3072)
        assertEquals(2840, bottom.first)
        assertEquals(232, bottom.second)
    }

    @Test
    fun `a band always starts on an even row, whatever the tile height`() {
        // The band is debayered with the frame's CFA pattern, so a band starting on an odd row
        // presents the second row of the 2x2 as though it were the first and swaps red with blue.
        // tileRowsFor returns whatever the budget allows — odd about half the time — so `top`
        // walks through both parities and only this snap keeps the phase right.
        listOf(1, 7, 99, 333).forEach { rows ->
            var top = 0
            while (top < 3072) {
                val (first, _) = TiledStacker.sourceRowsFor(top, rows, margin = 160, height = 3072)
                assertEquals(0, first % 2, "band from top=$top rows=$rows starts on an odd row")
                top += rows
            }
        }
    }

    @Test
    fun `an odd margin cannot put a band on an odd row either`() {
        val (first, _) = TiledStacker.sourceRowsFor(top = 500, rows = 100, margin = 161, height = 3072)
        assertEquals(338, first)
    }

    // ------------------------------------------------------------------ calibration across bands

    @Test
    fun `masters are whole-frame while the light is a band, and the rows line up`() {
        // The defect this catches shipped in T-5.3 and could not be seen by any other test here:
        // Calibration.apply was handed a band and full-frame masters with no way to relate them,
        // so it threw on the first frame taller than the 160-row margin. Everything else in this
        // file uses a 24-row frame, where every band is the whole frame.
        val tall = 800
        val narrow = 32
        // A dark whose value is its own row, so applying it at the wrong offset is visible in the
        // answer rather than merely wrong by a constant.
        val dark = FloatArray(narrow * tall) { (it / narrow).toFloat() }
        val frames = FakeFrames(
            count = 2,
            width = narrow,
            height = tall,
            masters = Calibration.Masters.of(narrow, tall, dark = dark),
        ) { _, _, y -> 1000 + y }

        val master = FloatArray(narrow * tall * 3)
        // Small enough to force many tiles, so most bands are genuinely partial.
        val budget = narrow * 3L * 2 * 4 * 100
        assertTrue(
            TiledStacker(frames, StubResampler(), mean, memoryBudgetBytes = budget).stack(master),
        )

        // Every pixel is (1000 + y) - y = 1000, whatever tile it landed in.
        for (y in 0 until tall) {
            for (x in 0 until narrow) {
                assertEquals(
                    1000f,
                    master[(y * narrow + x) * 3],
                    1e-3f,
                    "row $y took the wrong row of the dark",
                )
            }
        }
    }

    // ------------------------------------------------------------------ transforms and coverage

    @Test
    fun `a shifted frame lands where its transform says`() {
        // One frame shifted by a known amount; the stub warp carries it back. If the direction were
        // inverted the feature would land at twice the offset the other way.
        val centreX = (w - 1) / 2.0
        val centreY = (h - 1) / 2.0
        val shift = RigidTransform(0.0, 3.0, 0.0, centreX, centreY)
        val frames = FakeFrames(
            count = 1, width = w, height = h, masters = masters(),
            transforms = { shift },
        ) { _, x, y ->
            // A bright column at x = 8 in *frame* coordinates, which the transform says is x = 5
            // in the reference.
            if (x == 8 && y in 8..15) 900 else 100
        }
        val master = FloatArray(w * h * 3)
        TiledStacker(frames, StubResampler()).stack(master)

        val row = 12
        // Finite only. Kotlin's Float.compareTo sorts NaN *above* every number, so a plain
        // maxByOrNull would pick the first uncovered pixel — which is where the frame ran off the
        // edge, not where the column landed.
        val brightest = (0 until w)
            .filter { master[(row * w + it) * 3].isFinite() }
            .maxByOrNull { master[(row * w + it) * 3] }
        assertEquals(5, brightest) { "the column landed at $brightest, not 5" }
    }

    @Test
    fun `pixels no frame covered come out as not-a-number, not as black`() {
        // A pixel outside every frame has no measurement. Writing zero would be a claim that it was
        // dark, which the stretch would then believe and the gradient removal would work around.
        val centreX = (w - 1) / 2.0
        val centreY = (h - 1) / 2.0
        val far = RigidTransform(0.0, w.toDouble(), 0.0, centreX, centreY)
        val frames = FakeFrames(
            count = 1, width = w, height = h, masters = masters(),
            transforms = { far },
        ) { _, _, _ -> 500 }
        val master = FloatArray(w * h * 3)
        TiledStacker(frames, StubResampler()).stack(master)

        assertTrue(master.any { it.isNaN() }) { "uncovered pixels were filled in" }
    }

    @Test
    fun `a frame that covers only part of the tile still contributes what it has`() {
        // Half in, half out: the covered half must be averaged in rather than the frame dropped.
        val centreX = (w - 1) / 2.0
        val centreY = (h - 1) / 2.0
        val half = RigidTransform(0.0, (w / 2).toDouble(), 0.0, centreX, centreY)
        val frames = FakeFrames(
            count = 2, width = w, height = h, masters = masters(),
            transforms = { i -> if (i == 0) null else half },
        ) { f, _, _ -> if (f == 0) 100 else 300 }
        val master = FloatArray(w * h * 3)
        TiledStacker(frames, StubResampler(), mean).stack(master)

        val left = master[(5 * w + 1) * 3]
        val right = master[(5 * w + w - 2) * 3]
        assertTrue(left != right) { "both halves averaged the same; coverage was ignored" }
        assertTrue(left == 200f || right == 200f) { "the overlapping half should average to 200" }
    }

    // ------------------------------------------------------------------ calibration in the loop

    @Test
    fun `calibration is applied before the frames are combined`() {
        // FR-8.1's order, checked through the loop rather than in isolation: a dark of 40 must be
        // gone from the master, not merely from an intermediate nobody sees.
        val dark = FloatArray(w * h) { 40f }
        val frames = FakeFrames(
            count = 3,
            width = w, height = h,
            masters = Calibration.Masters.of(w, h, dark = dark),
        ) { _, _, _ -> 340 }
        val master = FloatArray(w * h * 3)
        TiledStacker(frames, StubResampler(), mean).stack(master)

        assertEquals(300f, master[0], 1e-3f) { "the dark was not subtracted: ${master[0]}" }
    }

    // ------------------------------------------------------------------ rejection, in the loop

    /** Twelve frames of flat sky, with a bright streak across four rows of frame 3 only. */
    private fun framesWithASatellite() =
        FakeFrames(count = 12, width = w, height = h, masters = masters()) { f, _, y ->
            if (f == 3 && y in 8..11) 900 else 100
        }

    @Test
    fun `a satellite in one frame does not reach the master`() {
        // T-5.4 seen from the outside. The mean keeps a twelfth of it — 166.7 against a background
        // of 100, which after the stretch is a sharp bright line and unmistakably not sky.
        val averaged = FloatArray(w * h * 3)
        TiledStacker(framesWithASatellite(), StubResampler(), mean).stack(averaged)
        assertEquals(166.667f, averaged[(9 * w + 4) * 3], 1e-2f)

        val clipped = FloatArray(w * h * 3)
        TiledStacker(framesWithASatellite(), StubResampler(), Combine.SigmaClip()).stack(clipped)
        assertEquals(100f, clipped[(9 * w + 4) * 3], 1e-3f) { "the streak survived the clip" }

        // And the sky either side of it is untouched — a clip that flattened everything would pass
        // the assertion above for the wrong reason.
        assertEquals(100f, clipped[(2 * w + 4) * 3], 1e-3f)
    }

    @Test
    fun `the tiling is invisible with rejection running too`() {
        // §1.32's defining property, re-checked now that the combiner has state of its own. A
        // scratch buffer or a counter carried between tiles would show up here and nowhere else.
        val oneTile = FloatArray(w * h * 3)
        val manyTiles = FloatArray(w * h * 3)

        TiledStacker(
            framesWithASatellite(), StubResampler(), Combine.SigmaClip(),
            memoryBudgetBytes = 64L * 1024 * 1024,
        ).stack(oneTile)
        TiledStacker(
            framesWithASatellite(), StubResampler(), Combine.SigmaClip(),
            memoryBudgetBytes = w * 3L * 12 * 4,
        ).stack(manyTiles)

        for (i in oneTile.indices) {
            assertEquals(oneTile[i], manyTiles[i], 1e-3f) { "tiling changed the master at $i" }
        }
    }

    @Test
    fun `the master can say what its rejection did`() {
        // A stack that cannot report its rejection rate has to be trusted instead, and FR-9.2 wants
        // the figure in session.json so a restack is comparable rather than merely similar.
        val clip = Combine.SigmaClip()
        TiledStacker(framesWithASatellite(), StubResampler(), clip).stack(FloatArray(w * h * 3))

        // Four rows of the frame, three channels, one frame in twelve.
        assertEquals(w * 4L * 3, clip.stats.rejected)
        assertEquals(w * h * 3L, clip.stats.pixels)
    }

    // ------------------------------------------------------------------ housekeeping

    @Test
    fun `no frames is a failure rather than an empty master`() {
        val frames = FakeFrames(count = 0, width = w, height = h, masters = masters()) { _, _, _ -> 0 }
        assertFalse(TiledStacker(frames, StubResampler()).stack(FloatArray(w * h * 3)))
    }

    @Test
    fun `progress is reported once per tile and reaches the last row`() {
        val frames = FakeFrames(count = 3, width = w, height = h, masters = masters()) { _, _, _ -> 100 }
        val seen = mutableListOf<TiledStacker.Progress>()
        TiledStacker(frames, StubResampler(), memoryBudgetBytes = w * 3L * 3 * 4 * 4)
            .stack(FloatArray(w * h * 3)) { seen += it }

        assertTrue(seen.isNotEmpty())
        assertEquals(h, seen.last().rowsDone)
        assertEquals(seen.size, seen.last().tile)
    }

    @Test
    fun `every frame is asked for rows in every tile`() {
        // The loop's shape: tiles outermost, frames within. Getting it inverted would need the
        // whole accumulator in memory, which is the thing tiling exists to avoid.
        val frames = FakeFrames(count = 4, width = w, height = h, masters = masters()) { _, _, _ -> 100 }
        val tiny = w * 3L * 4 * 4 * 2 // two rows a tile
        TiledStacker(frames, StubResampler(), memoryBudgetBytes = tiny).stack(FloatArray(w * h * 3))

        val tiles = (h + 1) / 2
        assertEquals(tiles * 4, frames.rowRequests) { "expected one read per frame per tile" }
    }

    @Test
    fun `a master smaller than the frame is refused`() {
        val frames = FakeFrames(count = 1, width = w, height = h, masters = masters()) { _, _, _ -> 0 }
        val tooSmall = FloatArray(w * h)
        val error = runCatching { TiledStacker(frames, StubResampler()).stack(tooSmall) }
        assertTrue(error.isFailure && error.exceptionOrNull() is IllegalArgumentException)
    }
}
