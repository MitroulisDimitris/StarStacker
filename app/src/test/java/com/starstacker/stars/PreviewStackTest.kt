package com.starstacker.stars

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * T-3.14 / D-18. The preview exists for framing confidence, so the tests are about it being
 * legible and honest rather than about it being accurate — accuracy is Phase 3's problem.
 */
class PreviewStackTest {

    /** JUnit 5's assertNotNull returns void, so it cannot stand in for the value. */
    private fun <T : Any> assertPresent(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun star(x: Double, y: Double, flux: Double) = Star(
        x = x, y = y, flux = flux, peak = flux, hfr = 1.5,
        eccentricity = 0.2, pixelCount = 9, saturated = false,
    )

    /** A flat sky at [background] with a few bright points on it. */
    private fun frame(
        width: Int,
        height: Int,
        background: Float,
        noise: Float = 0f,
        seed: Int = 1,
        stars: List<Triple<Int, Int, Float>> = emptyList(),
    ): FloatArray {
        val random = Random(seed)
        val plane = FloatArray(width * height) {
            background + if (noise > 0f) (random.nextFloat() - 0.5f) * 2f * noise else 0f
        }
        stars.forEach { (x, y, peak) -> plane[y * width + x] = background + peak }
        return plane
    }

    // --- the stretch -----------------------------------------------------------------------

    /**
     * The one that matters for "is the app working": a linear astro frame is a black rectangle
     * shown linearly, because the sky sits a few hundred ADU up in a 1023 ADU range.
     */
    @Test
    fun `a linear sky is stretched into something visible`() {
        val stack = PreviewStack(64, 48)
        stack.add(frame(64, 48, background = 80f, noise = 2f), 64, 48, 0.0, 0.0)

        val argb = assertPresent(stack.toArgb())
        val luma = argb.map { it and 0xFF }
        val median = luma.sorted()[luma.size / 2]

        assertTrue(median in 40..120, "background landed at $median, not a visible midtone")
    }

    @Test
    fun `stars stay brighter than the sky they sit on`() {
        val stack = PreviewStack(64, 48)
        stack.add(
            frame(64, 48, background = 80f, noise = 1f, stars = listOf(Triple(32, 24, 400f))),
            64, 48, 0.0, 0.0,
        )

        val argb = assertPresent(stack.toArgb())
        val starLuma = argb[24 * 64 + 32] and 0xFF
        val skyLuma = argb[5 * 64 + 5] and 0xFF

        assertTrue(starLuma > skyLuma, "star $starLuma did not beat sky $skyLuma")
    }

    @Test
    fun `nothing to show before the first frame`() {
        assertNull(PreviewStack(16, 16).toArgb())
        assertFalse(PreviewStack(16, 16).hasImage)
    }

    // --- the mean --------------------------------------------------------------------------

    /**
     * D-18's capped mean. An uncapped mean converges and the preview stops responding exactly
     * when a session gets long enough for the user to want reassurance.
     */
    @Test
    fun `the mean stays responsive past the cap`() {
        val stack = PreviewStack(16, 16, cap = 4)
        repeat(50) { stack.add(frame(16, 16, background = 100f), 16, 16, 0.0, 0.0) }
        assertEquals(100.0, stack.meanAt(8, 8).toDouble(), 0.5)

        // Four frames at the cap must carry the mean most of the way to the new level. An
        // uncapped mean at depth 50 would have moved by about 7% and the preview would look stuck.
        repeat(4) { stack.add(frame(16, 16, background = 900f), 16, 16, 0.0, 0.0) }

        val moved = (stack.meanAt(8, 8) - 100.0) / 800.0
        assertTrue(moved > 0.6, "the mean only moved ${(moved * 100).toInt()}% toward the new level")
    }

    @Test
    fun `averaging identical frames leaves the image unchanged`() {
        val single = PreviewStack(32, 32)
        val many = PreviewStack(32, 32)
        val plane = frame(32, 32, background = 120f, noise = 3f, stars = listOf(Triple(16, 16, 500f)))

        single.add(plane, 32, 32, 0.0, 0.0)
        repeat(20) { many.add(plane, 32, 32, 0.0, 0.0) }

        val a = assertPresent(single.toArgb()).copyOf()
        val b = assertPresent(many.toArgb())
        val worst = a.indices.maxOf { abs((a[it] and 0xFF) - (b[it] and 0xFF)) }
        assertTrue(worst <= 1, "identical frames drifted by $worst levels")
    }

    @Test
    fun `depth counts what went in`() {
        val stack = PreviewStack(16, 16)
        repeat(7) { stack.add(frame(16, 16, background = 50f), 16, 16, 0.0, 0.0) }
        assertEquals(7, stack.depth)
        assertTrue(stack.hasImage)
    }

    /**
     * A shift leaves pixels with no data behind them. Filling them with zero would crawl a black
     * wedge in from one edge, which reads as a real gradient rather than as an artefact.
     */
    @Test
    fun `a shift does not drag a black edge into the frame`() {
        val stack = PreviewStack(32, 32)
        stack.add(frame(64, 64, background = 200f), 64, 64, 0.0, 0.0)
        assertEquals(200.0, stack.meanAt(1, 1).toDouble(), 0.5)

        // Shifted far enough that the top-left of the preview has no source pixel behind it.
        stack.add(frame(64, 64, background = 200f), 64, 64, -40.0, -40.0)

        assertEquals(
            200.0,
            stack.meanAt(1, 1).toDouble(),
            0.5,
            "the uncovered edge was overwritten instead of keeping what it had",
        )
    }

    // --- offsets ---------------------------------------------------------------------------

    @Test
    fun `a translated star field reports the translation`() {
        val reference = listOf(
            star(10.0, 10.0, 900.0), star(50.0, 20.0, 700.0), star(30.0, 60.0, 600.0),
            star(80.0, 75.0, 500.0), star(15.0, 90.0, 400.0),
        )
        val moved = reference.map { star(it.x + 7.5, it.y - 3.25, it.flux) }

        val offset = assertPresent(StarOffset.estimate(reference, moved))

        assertEquals(7.5, offset.dx, 0.01)
        assertEquals(-3.25, offset.dy, 0.01)
    }

    /** The vote is its own confidence: unrelated fields must not produce a plausible number. */
    @Test
    fun `an unrelated field reports no offset rather than a plausible one`() {
        val random = Random(7)
        val a = List(20) { star(random.nextDouble() * 500, random.nextDouble() * 500, 100.0) }
        val b = List(20) { star(random.nextDouble() * 500, random.nextDouble() * 500, 100.0) }

        assertNull(StarOffset.estimate(a, b))
    }

    /** Real frames gain and lose stars between subs; the offset has to survive that. */
    @Test
    fun `the offset survives stars appearing and disappearing`() {
        val common = listOf(
            star(10.0, 10.0, 900.0), star(50.0, 20.0, 800.0), star(30.0, 60.0, 700.0),
            star(80.0, 75.0, 600.0), star(15.0, 90.0, 500.0), star(65.0, 45.0, 450.0),
        )
        val reference = common + star(99.0, 99.0, 300.0)
        val current = common.map { star(it.x + 4.0, it.y + 4.0, it.flux) } +
            star(5.0, 200.0, 280.0) + star(120.0, 8.0, 260.0)

        val offset = assertPresent(StarOffset.estimate(reference, current))

        assertEquals(4.0, offset.dx, 0.01)
        assertEquals(4.0, offset.dy, 0.01)
        assertTrue(offset.votes >= 6, "only ${offset.votes} stars agreed")
    }

    @Test
    fun `an empty star list is not an offset of zero`() {
        assertNull(StarOffset.estimate(emptyList(), listOf(star(1.0, 1.0, 10.0))))
        assertNull(StarOffset.estimate(listOf(star(1.0, 1.0, 10.0)), emptyList()))
    }
}
