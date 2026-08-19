package com.starstacker.stars

import com.starstacker.registration.LiveRegistration
import com.starstacker.synth.SyntheticSky
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-4.6 — does the preview actually stack sharper?
 *
 * Everything else about this task is checkable by arithmetic; this is the claim that motivates it,
 * and it can only be answered by stacking a rotating session both ways and measuring the result.
 * The old preview corrected translation only, so field rotation accumulated into it as a smear
 * that grew with the session — worst at the corners, invisible at the centre, and
 * indistinguishable from poor focus to anyone looking at it.
 */
class PreviewAlignmentTest {

    private val sky = SyntheticSky()
    private val bin = 2

    private fun plane(frame: SyntheticSky.Frame) =
        CfaBinner.binGreen(frame.pixels, frame.width, frame.height, sky.cfaCodes, bin)

    /** The finished preview, measured the way anyone would judge it: by its stars. */
    private fun sharpness(stack: PreviewStack): FrameStars {
        val argb = stack.toArgb()
        assertNotNull(argb)
        // Luminance from the stretched preview — the same picture the user is looking at.
        val luminance = FloatArray(PreviewStack.WIDTH * PreviewStack.HEIGHT) { i ->
            val p = argb!![i]
            (((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f)
        }
        return StarDetector().detect(luminance, PreviewStack.WIDTH, PreviewStack.HEIGHT)
    }

    @Test
    fun `a rotating session stacks sharper when the rotation is corrected`() {
        val stars = sky.field(count = 40, seed = 61)
        // Enough rotation to matter and little enough to be realistic: 0.4 deg a frame over eight
        // frames is under three degrees, which an alt-az mount reaches in minutes near the zenith.
        val frames = sky.sequence(
            stars, frames = 8, exposureSeconds = 7.4,
            perFrame = SyntheticSky.Transform(rotationDeg = 0.4, dx = 1.5, dy = -1.0),
        )

        val live = LiveRegistration()
        val aligned = PreviewStack(PreviewStack.WIDTH, PreviewStack.HEIGHT)
        val translationOnly = PreviewStack(PreviewStack.WIDTH, PreviewStack.HEIGHT)

        frames.forEach { frame ->
            val p = plane(frame)
            val detected = StarDetector(saturationLevel = sky.whiteLevel.toDouble())
                .detect(p.data, p.width, p.height)
            val outcome = live.register(detected.stars, p, sky.width, sky.height)

            val scaleX = p.width.toDouble() / PreviewStack.WIDTH
            val scaleY = p.height.toDouble() / PreviewStack.HEIGHT

            val mapping = outcome.transform?.let {
                PlaneMapping.fromSensorMatrix(
                    it.toMatrix(), scaleX, scaleY, bin, (bin - 1) / 2.0,
                )
            } ?: PlaneMapping.scaling(scaleX, scaleY)
            aligned.add(p.data, p.width, p.height, mapping)

            // What the preview did before T-4.6: the same shift, no rotation term at all.
            val shifted = outcome.transform?.let {
                PlaneMapping.shifted(scaleX, scaleY, it.dx / bin, it.dy / bin)
            } ?: PlaneMapping.scaling(scaleX, scaleY)
            translationOnly.add(p.data, p.width, p.height, shifted)
        }

        val good = sharpness(aligned)
        val smeared = sharpness(translationOnly)

        assertTrue(good.count > 0) { "the aligned preview has no stars at all" }
        assertNotNull(good.medianHfr)
        assertNotNull(smeared.medianHfr)

        // The aligned stack's stars are tighter. Rotation smears a star along an arc whose length
        // grows with its distance from the centre, so the median across the field rises.
        assertTrue(good.medianHfr!! < smeared.medianHfr!!) {
            "aligned HFR ${good.medianHfr} not better than smeared ${smeared.medianHfr}"
        }
        // And it finds more of them: a smeared star spreads its flux and drops below threshold.
        assertTrue(good.count >= smeared.count) {
            "aligned found ${good.count}, smeared found ${smeared.count}"
        }
    }

    @Test
    fun `a session with no rotation is unharmed by the new path`() {
        // The regression guard. Pure drift is the case the old preview handled correctly, and the
        // affine mapping must be at least as good on it — otherwise this task traded one set of
        // sessions for another.
        val stars = sky.field(count = 40, seed = 62)
        val frames = sky.sequence(
            stars, frames = 6, exposureSeconds = 7.4,
            perFrame = SyntheticSky.Transform(dx = 2.0, dy = 1.5),
        )

        val live = LiveRegistration()
        val stack = PreviewStack(PreviewStack.WIDTH, PreviewStack.HEIGHT)
        frames.forEach { frame ->
            val p = plane(frame)
            val detected = StarDetector(saturationLevel = sky.whiteLevel.toDouble())
                .detect(p.data, p.width, p.height)
            val outcome = live.register(detected.stars, p, sky.width, sky.height)
            val scaleX = p.width.toDouble() / PreviewStack.WIDTH
            val scaleY = p.height.toDouble() / PreviewStack.HEIGHT
            val mapping = outcome.transform?.let {
                PlaneMapping.fromSensorMatrix(it.toMatrix(), scaleX, scaleY, bin, (bin - 1) / 2.0)
            } ?: PlaneMapping.scaling(scaleX, scaleY)
            stack.add(p.data, p.width, p.height, mapping)
        }

        val result = sharpness(stack)
        assertTrue(result.count >= 8) { "only ${result.count} stars survived a pure-drift stack" }
        assertTrue(result.medianHfr!! < 4.0) { "stars are smeared: HFR ${result.medianHfr}" }
    }

    @Test
    fun `the shift-only entry point still works for callers that have no transform`() {
        // D-18's fallback path, kept because registration has never run under a real sky.
        val stack = PreviewStack(PreviewStack.WIDTH, PreviewStack.HEIGHT)
        val p = plane(sky.render(sky.field(30, seed = 63), 7.4, seed = 1))
        stack.add(p.data, p.width, p.height, 0.0, 0.0)
        assertNotNull(stack.toArgb())
    }
}
