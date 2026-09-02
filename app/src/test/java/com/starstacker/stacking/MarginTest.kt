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
