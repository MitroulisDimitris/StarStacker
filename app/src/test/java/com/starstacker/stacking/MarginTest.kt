package com.starstacker.stacking

import com.starstacker.registration.RigidTransform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §1.38's margin, at the size a real frame actually is.
 *
 * The stacking fixtures are 16x24, and a margin computed for a frame that small is a handful of
 * rows whatever the rotation — which is exactly why the constant 160 survived from §1.32 until a
 * device measured it. These are the real numbers: a 4096x3072 frame, and the rotation the first
 * real session actually reached.
 */
class MarginTest {

    private val w = 4096
    private val h = 3072

    private fun rotation(deg: Double) =
        RigidTransform(deg, 0.0, 0.0, w / 2.0, h / 2.0)

    @Test
    fun `rotation alone accounts for less than half of what the real session needed`() {
        // Session 2026-08-23_0006 rotates 3.72 degrees over 14.7 minutes, which is what an alt-az
        // mount gives in that time — and the corner displacement from rotation alone is 136 rows,
        // comfortably inside the old constant of 160. The measured requirement was 219.5.
        val margin = TiledStacker.marginRowsFor(w, h, listOf(rotation(3.72)))
        // 136.11 rows at the corner (132.9 from the width, 3.2 from the height), plus slack.
        assertEquals(141, margin)
        assertTrue(margin < 160, "rotation alone would not have overflowed the old constant")
    }

    @Test
    fun `rotation plus drift is what overflowed the old constant`() {
        // The rest of the 219.5 rows is the field walking across the sensor. §1.32's formula was
        // "the width of the frame times the angle" and ignored translation entirely, which is why
        // a constant chosen from it was too small on the first session that was measured.
        val drifting = RigidTransform(3.72, 0.0, 83.0, w / 2.0, h / 2.0)
        val margin = TiledStacker.marginRowsFor(w, h, listOf(drifting))

        assertTrue(margin > 160, "the real session needed more than the old constant, got $margin")
        assertTrue(margin in 215..225, "expected about the measured 219.5 rows, got $margin")
    }

    @Test
    fun `a session with no rotation needs almost nothing`() {
        // The other half of why a constant was wrong: a short, well-tracked session was paying
        // 160 rows of margin for a displacement of zero.
        val margin = TiledStacker.marginRowsFor(w, h, listOf(rotation(0.0)))
        assertTrue(margin <= 8, "an unrotated session should need almost no margin, got $margin")
    }

    @Test
    fun `pure translation counts too, not just rotation`() {
        // Drift moves rows as surely as rotation does, and 1.32's "width times the angle" formula
        // ignored it entirely.
        val drifted = RigidTransform(0.0, 0.0, 40.0, w / 2.0, h / 2.0)
        assertEquals(44, TiledStacker.marginRowsFor(w, h, listOf(drifted)))
    }

    @Test
    fun `the worst frame in the session sets it`() {
        val margin = TiledStacker.marginRowsFor(
            w, h,
            listOf(rotation(0.5), rotation(3.72), null, rotation(1.0)),
        )
        assertEquals(TiledStacker.marginRowsFor(w, h, listOf(rotation(3.72))), margin)
    }

    @Test
    fun `an unregistered session needs no margin at all`() {
        // Every transform null: nothing is warped, so nothing is displaced.
        val margin = TiledStacker.marginRowsFor(w, h, listOf(null, null))
        assertTrue(margin <= 8, "got $margin")
    }

    @Test
    fun `a wild transform cannot make every band the whole frame`() {
        val margin = TiledStacker.marginRowsFor(w, h, listOf(rotation(89.0)))
        assertTrue(margin <= h, "a margin larger than the frame would read everything, got $margin")
    }
}

/**
 * The band arithmetic, which is where §1.38's rewrite drew blood on the first real run.
 */
class RegisterBandTest {

    @Test
    fun `a register band always starts on an even row`() {
        // Odd output rows put every other band on an odd start, which debayers as the wrong CFA
        // pattern — the same defect §1.34 found in the tile path.
        val rows = TiledStacker.registerRowsFor(4096, 224)
        assertEquals(0, rows % 2, "register rows must be even, got $rows")
    }

    @Test
    fun `the band never exceeds what the buffers are sized for`() {
        // The first real run died here: `buffer holds 7426048, needs 7430144`. sourceRowsFor snaps
        // the band's first row *down* to an even one, so a band can be one row taller than
        // rows + 2 * margin. Both numbers were even in the old tile path, so it never fired.
        val h = 3072
        for (margin in listOf(4, 63, 64, 137, 220, 224)) {
            for (rows in listOf(1, 2, 7, 8, 1365, 1366)) {
                var top = 0
                while (top < h) {
                    val take = minOf(rows, h - top)
                    val (first, count) = TiledStacker.sourceRowsFor(top, take, margin, h)
                    assertEquals(0, first % 2, "band start must be even")
                    assertTrue(
                        count <= rows + 2 * margin + 2,
                        "band of $count exceeds the allocation for rows=$rows margin=$margin",
                    )
                    top += take
                }
            }
        }
    }
}

/**
 * §1.39's threading, and the property that makes it safe to have.
 *
 * Every pixel of the combine is independent of every other, so splitting the work across cores
 * cannot change the answer — not "should not", *cannot*. That makes the test unusually strong: the
 * master must come back **bit-identical**, not merely close. Anything less would mean state is
 * leaking between pixels, which would be a bug on one thread too.
 */
class CombineThreadingTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var scratch: java.io.File

    private val w = 32
    private val h = 40

    private fun frames() = object : TiledStacker.Frames {
        override val count = 9
        override val width = w
        override val height = h
        override val cfaCodes = listOf(1, 0, 2, 1)
        override val masters = Calibration.Masters.of(w, h)
        override val blackLevel = 0.0
        override fun transform(index: Int) = null
        override fun rows(index: Int, fromRow: Int, rowCount: Int, into: ShortArray): Int {
            val rows = minOf(rowCount, (h - fromRow).coerceAtLeast(0))
            for (r in 0 until rows) {
                for (x in 0 until w) {
                    val y = fromRow + r
                    // A background with structure, a few stars, and one satellite in one frame, so
                    // every branch of the clip is exercised somewhere in the frame.
                    val base = 900 + (x * 7 + y * 13) % 60
                    val star = if ((x * 31 + y * 17) % 91 == 0) 6_000 else 0
                    val hit = if (index == 4 && (x + y) % 37 == 0) 30_000 else 0
                    into[r * w + x] = (base + star + hit + index * 3).toShort()
                }
            }
            return rows
        }
    }

    private fun stack(threads: Int, budget: Long): FloatArray {
        val master = FloatArray(w * h * 3)
        val ok = TiledStacker(
            frames = frames(),
            resampler = PassThrough(),
            combiner = { Combine.SigmaClip() },
            memoryBudgetBytes = budget,
            threads = threads,
            scratchDirectory = scratch,
        ).stack(master)
        assertTrue(ok, "stack failed at $threads threads")
        return master
    }

    @Test
    fun `the master is bit-identical however many threads combine it`() {
        val serial = stack(1, 64L * 1024 * 1024)
        for (threads in listOf(2, 3, 8)) {
            val parallel = stack(threads, 64L * 1024 * 1024)
            for (i in serial.indices) {
                // toRawBits, not a delta: identical means identical.
                assertEquals(
                    serial[i].toRawBits(),
                    parallel[i].toRawBits(),
                    "sample $i differs at $threads threads",
                )
            }
        }
    }

    @Test
    fun `it stays identical when the tiling changes underneath it too`() {
        // Threads split a tile; the budget splits the frame into tiles. Neither may show up in the
        // answer, and the two interact — a worker's chunk is a fraction of a tile.
        val oracle = stack(1, 64L * 1024 * 1024)
        val awkward = stack(5, w * 3L * 9 * 8 * 3) // three rows a tile, five workers
        for (i in oracle.indices) {
            assertEquals(oracle[i].toRawBits(), awkward[i].toRawBits(), "sample $i")
        }
    }

    @Test
    fun `the rejection counters survive being split across workers`() {
        // Each worker has its own SigmaClip, so the rate a stack reports has to be reassembled or
        // it describes one core's share of the frame.
        fun countersFor(threads: Int): Pair<Long, Long> {
            val stacker = TiledStacker(
                frames = frames(),
                resampler = PassThrough(),
                combiner = { Combine.SigmaClip() },
                memoryBudgetBytes = 64L * 1024 * 1024,
                threads = threads,
                scratchDirectory = scratch,
            )
            stacker.stack(FloatArray(w * h * 3))
            val total = Combine.SigmaClip.Stats()
            stacker.workers.filterIsInstance<Combine.SigmaClip>().forEach { total.add(it.stats) }
            return total.pixels to total.rejected
        }

        val (serialPixels, serialRejected) = countersFor(1)
        val (parallelPixels, parallelRejected) = countersFor(4)

        assertEquals(w.toLong() * h * 3, serialPixels, "every pixel should be counted once")
        assertEquals(serialPixels, parallelPixels)
        assertEquals(serialRejected, parallelRejected)
        assertTrue(serialRejected > 0, "the fixture should give the clip something to reject")
    }

    /** Replicates each CFA sample into three channels and does not warp. */
    private class PassThrough : TiledStacker.Resampler {
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
            srcRows: Int,
            srcTop: Int,
            channels: Int,
            dstRows: Int,
            dstTop: Int,
            transform: com.starstacker.registration.RigidTransform,
            out: FloatArray,
        ): Boolean {
            val skip = (dstTop - srcTop) * width * channels
            src.copyInto(out, 0, skip, skip + dstRows * width * channels)
            return true
        }
    }
}
