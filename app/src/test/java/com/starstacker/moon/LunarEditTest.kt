package com.starstacker.moon

import com.starstacker.stacking.FrameQuality
import com.starstacker.session.FrameKind
import com.starstacker.session.FrameRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-11.7's edit profile and T-11.6's quality cut — the two ends of the pipeline that had to differ
 * from deep sky, tested for the ways they differ rather than for the code they share.
 */
class LunarEditTest {

    // ----------------------------------------------------------------- T-11.7, the edit profile

    @Test
    fun `gradient removal is off at every slider position`() {
        // Not a default to be overridden: there is no sky background to model, so a polynomial fit
        // has nothing constraining it. Off at 0, off at 1.
        for (strength in listOf(0.0, 0.25, 0.55, 1.0)) {
            assertEquals(0, LunarEdit.settings(strength).gradientDegree, "at strength $strength")
        }
    }

    @Test
    fun `saturation is never boosted`() {
        for (strength in listOf(0.0, 0.5, 1.0)) {
            assertEquals(
                1.0, LunarEdit.settings(strength).saturationBoost, 1e-9,
                "the moon is grey; a nebula boost turns its noise into confetti",
            )
        }
    }

    @Test
    fun `the sky stays dark, unlike the deep-sky profile`() {
        // Deep sky lifts the background to 0.08-0.35 to bring a faint target into view. Doing that
        // here would grey the sky and amplify read noise across 99% of the frame.
        val lunar = LunarEdit.settings(1.0)
        assertTrue(lunar.background <= 0.05, "lunar background was ${lunar.background}")

        val deepSky = com.starstacker.edit.AutoEdit.Settings(strength = 1.0)
        assertTrue(
            deepSky.background > lunar.background * 4,
            "the two profiles should differ sharply: ${deepSky.background} vs ${lunar.background}",
        )
    }

    // ----------------------------------------------------------------- the sharpening

    @Test
    fun `unsharp masking increases local contrast at an edge`() {
        val width = 32
        val height = 32
        val rgb = ByteArray(width * height * 3)
        // A vertical step edge, deliberately soft over three pixels.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = when {
                    x < 15 -> 60
                    x == 15 -> 110
                    x == 16 -> 160
                    else -> 210
                }
                for (c in 0 until 3) rgb[(y * width + x) * 3 + c] = v.toByte()
            }
        }

        fun at(x: Int) = rgb[(16 * width + x) * 3].toInt() and 0xFF
        val before = at(17) - at(14)
        LunarEdit.unsharpMask(rgb, width, height, amount = 1.0, radius = 2)
        val after = at(17) - at(14)

        assertTrue(after > before, "edge contrast $before should have grown, got $after")
    }

    @Test
    fun `an amount of zero leaves the image untouched`() {
        val rgb = ByteArray(16 * 16 * 3) { (it % 200).toByte() }
        val copy = rgb.copyOf()
        LunarEdit.unsharpMask(rgb, 16, 16, amount = 0.0)
        assertTrue(rgb.contentEquals(copy))
    }

    @Test
    fun `a flat image is unchanged by sharpening`() {
        // Nothing to sharpen: in - blur(in) is zero everywhere, so the output must be the input.
        val rgb = ByteArray(24 * 24 * 3) { 128.toByte() }
        LunarEdit.unsharpMask(rgb, 24, 24, amount = 1.2, radius = 2)
        assertTrue(rgb.all { (it.toInt() and 0xFF) == 128 })
    }

    @Test
    fun `an image smaller than the kernel is left alone rather than throwing`() {
        val rgb = ByteArray(3 * 3 * 3) { 100.toByte() }
        LunarEdit.unsharpMask(rgb, 3, 3, amount = 1.0, radius = 4)
        assertTrue(rgb.all { (it.toInt() and 0xFF) == 100 })
    }

    @Test
    fun `sharpening clamps rather than wrapping`() {
        // A near-white edge sharpened hard would overflow a byte; wrapping would put black pixels
        // on the bright side of the limb, which is the classic artefact.
        val width = 16
        val rgb = ByteArray(width * width * 3)
        for (i in 0 until width * width) {
            val v = if (i % width < 8) 5 else 250
            for (c in 0 until 3) rgb[i * 3 + c] = v.toByte()
        }
        LunarEdit.unsharpMask(rgb, width, width, amount = 4.0, radius = 2)
        assertTrue(rgb.all { (it.toInt() and 0xFF) in 0..255 })
        // The bright side must still be bright — a wrap would have made it dark.
        val bright = rgb[(8 * width + 12) * 3].toInt() and 0xFF
        assertTrue(bright > 200, "bright side wrapped to $bright")
    }

    // ----------------------------------------------------------------- T-11.6, the quality cut

    private fun frame(index: Int, sharpness: Double?) = FrameRecord(
        index = index,
        fileName = "light_%03d.dng".format(index),
        kind = FrameKind.LIGHT,
        capturedAtEpochMs = index * 1000L,
        iso = 400,
        exposureNs = 500_000L,
        temperatureC = null,
        hfr = null,
        starCount = null,
        eccentricity = null,
        backgroundAdu = null,
        sharpness = sharpness,
        accepted = true,
    )

    @Test
    fun `lunar scoring ranks by sharpness, which deep-sky scoring cannot see`() {
        val frames = listOf(frame(0, 10.0), frame(1, 40.0), frame(2, 20.0))

        val lunar = FrameQuality.score(frames, FrameQuality.Mode.LUNAR)
        assertEquals(0.25, lunar[0].weight, 1e-9, "10/40")
        assertEquals(1.0, lunar[1].weight, 1e-9, "the best frame scores 1")
        assertEquals(0.50, lunar[2].weight, 1e-9, "20/40")

        // Deep-sky mode reads hfr/starCount/background, all null here, so every frame weighs 1 —
        // which is the honest degradation, not a bug, but it is why the mode has to be chosen.
        val deepSky = FrameQuality.score(frames, FrameQuality.Mode.DEEP_SKY)
        assertTrue(deepSky.all { it.weight == 1.0 })
    }

    @Test
    fun `a frame with no sharpness recorded weighs 1 rather than being ranked last`() {
        val frames = listOf(frame(0, 40.0), frame(1, null))
        val scores = FrameQuality.score(frames, FrameQuality.Mode.LUNAR)
        assertEquals(1.0, scores[1].weight, 1e-9)
    }

    @Test
    fun `the lunar keep-best cut is far harsher than the deep-sky one`() {
        // 20% against 95%: lucky imaging is picking moments, not removing anomalies.
        assertTrue(FrameQuality.LUNAR_KEEP_PERCENT < FrameQuality.DEFAULT_KEEP_PERCENT / 3)

        val frames = (0 until 200).map { frame(it, it.toDouble() + 1) }
        val kept = FrameQuality.keepBest(
            FrameQuality.score(frames, FrameQuality.Mode.LUNAR),
            FrameQuality.LUNAR_KEEP_PERCENT,
        )
        assertEquals(40, kept.size, "20% of 200")
        // And it must keep the *sharpest*, which here are the highest indices.
        assertTrue(kept.all { it.index >= 160 }, "kept ${kept.first().index}..${kept.last().index}")
    }

    @Test
    fun `the cut returns frames in capture order, not quality order`() {
        // The caller pairs these with files and transforms; reordering would silently change which
        // frame is the reference.
        val frames = listOf(frame(0, 5.0), frame(1, 100.0), frame(2, 50.0), frame(3, 80.0))
        val kept = FrameQuality.keepBest(FrameQuality.score(frames, FrameQuality.Mode.LUNAR), 75)
        assertEquals(listOf(1, 2, 3), kept.map { it.index })
    }
}
