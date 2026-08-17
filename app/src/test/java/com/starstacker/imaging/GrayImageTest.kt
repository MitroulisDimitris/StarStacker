package com.starstacker.imaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The preview is rotated once, in pixels, rather than by the layout — so the rotation has to be
 * right. A frame shown 90° from the sky in front of you is worse than no preview: you would
 * frame confidently on the wrong part of the sky.
 */
class GrayImageTest {

    /** 3x2, values encoding their own position as 10*x + y. */
    private fun sample(): GrayImage {
        val pixels = ByteArray(6)
        for (y in 0 until 2) {
            for (x in 0 until 3) pixels[y * 3 + x] = (10 * x + y).toByte()
        }
        return GrayImage(pixels, 3, 2)
    }

    @Test
    fun `ninety degrees clockwise swaps the axes and moves the top-left to the top-right`() {
        val rotated = sample().rotated(90)

        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        // The sensor's (0,0) ends up at the top-right corner of a clockwise turn.
        assertEquals(0, rotated[1, 0])
        // The sensor's (2,0) — top-right — ends up at the bottom-right.
        assertEquals(20, rotated[1, 2])
        // The sensor's (0,1) — bottom-left — ends up at the top-left.
        assertEquals(1, rotated[0, 0])
    }

    @Test
    fun `four quarter turns are the identity`() {
        val original = sample()
        val turned = original.rotated(90).rotated(90).rotated(90).rotated(90)
        assertEquals(original.width, turned.width)
        assertEquals(original.height, turned.height)
        for (y in 0 until original.height) {
            for (x in 0 until original.width) {
                assertEquals(original[x, y], turned[x, y], "differs at ($x,$y)")
            }
        }
    }

    @Test
    fun `two quarter turns equal one half turn`() {
        val half = sample().rotated(180)
        val twice = sample().rotated(90).rotated(90)
        for (y in 0 until half.height) {
            for (x in 0 until half.width) {
                assertEquals(half[x, y], twice[x, y], "differs at ($x,$y)")
            }
        }
    }

    @Test
    fun `zero rotation does not copy`() {
        val original = sample()
        assertEquals(original, original.rotated(0))
        assertEquals(original, original.rotated(360))
    }

    @Test
    fun `grey expands to opaque ARGB`() {
        val argb = GrayImage(byteArrayOf(0, 127.toByte(), 255.toByte(), 64), 2, 2).toArgb()
        assertEquals(0xFF000000.toInt(), argb[0])
        assertEquals(0xFFFFFFFF.toInt(), argb[2])
        assertEquals(0xFF404040.toInt(), argb[3])
    }
}
