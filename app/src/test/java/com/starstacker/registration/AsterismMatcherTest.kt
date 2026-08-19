package com.starstacker.registration

import com.starstacker.synth.SyntheticSky
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * T-4.2 — asterism matching, checked against ground truth.
 *
 * **This is the first task that could not have been tested honestly without T-4.0.** Correctness
 * here is not "the stars look lined up"; it is "star 7 in this frame is star 12 in that one", and
 * only a synthetic field knows the answer. Against a real sky the best available check would be to
 * stack the result and squint, which cannot tell a correct matcher from one that is right most of
 * the time — and most of the time is what quietly ruins a stack.
 *
 * The field is built directly here rather than rendered and re-detected, because the matcher's job
 * begins *after* detection. Feeding it detector output would test both at once and blame the wrong
 * one on failure; there is a separate end-to-end case at the bottom that does exactly that, on
 * purpose.
 */
class AsterismMatcherTest {

    private val frameW = 512.0
    private val frameH = 384.0

    private fun field(count: Int, seed: Int): List<AsterismMatcher.Detection> {
        val rng = Random(seed)
        return List(count) {
            AsterismMatcher.Detection(
                x = 20.0 + rng.nextDouble() * (frameW - 40),
                y = 20.0 + rng.nextDouble() * (frameH - 40),
                flux = 100.0 + rng.nextDouble() * 900.0,
            )
        }
    }

    /** Applies a rigid transform about the frame centre — the same convention SkyDrift uses. */
    private fun transform(
        stars: List<AsterismMatcher.Detection>,
        rotationDeg: Double = 0.0,
        dx: Double = 0.0,
        dy: Double = 0.0,
    ): List<AsterismMatcher.Detection> {
        val cx = (frameW - 1) / 2.0
        val cy = (frameH - 1) / 2.0
        val t = Math.toRadians(rotationDeg)
        return stars.map {
            val ox = it.x - cx
            val oy = it.y - cy
            it.copy(
                x = cx + ox * cos(t) - oy * sin(t) + dx,
                y = cy + ox * sin(t) + oy * cos(t) + dy,
            )
        }
    }

    /** How many correspondences point at the star they should. */
    private fun correct(match: AsterismMatcher.Match, expected: Map<Int, Int>): Int =
        match.pairs.count { expected[it.referenceIndex] == it.targetIndex }

    // ------------------------------------------------------------------ the invariants

    @Test
    fun `a pure translation is matched`() {
        val ref = field(24, seed = 1)
        val moved = transform(ref, dx = 37.0, dy = -21.0)

        val match = AsterismMatcher.match(ref, moved, frameWidth = frameW, frameHeight = frameH)

        assertEquals(AsterismMatcher.Method.ASTERISM, match.method)
        assertTrue(match.usable)
        // Shuffled indices would be the real test of correspondence, but with an untouched order
        // identity is the truth: pair i must map to i.
        assertTrue(correct(match, ref.indices.associateWith { it }) >= 15) {
            "only ${correct(match, ref.indices.associateWith { it })} of ${match.count} correct"
        }
    }

    @Test
    fun `a rotation is matched, which translation-only methods cannot do`() {
        val ref = field(24, seed = 2)
        val turned = transform(ref, rotationDeg = 12.0)

        val match = AsterismMatcher.match(ref, turned, frameWidth = frameW, frameHeight = frameH)

        assertTrue(match.usable)
        assertTrue(correct(match, ref.indices.associateWith { it }) >= 15)
    }

    @Test
    fun `rotation and translation together are matched`() {
        // What a session actually does: an alt-az mount drifts and rotates at once.
        val ref = field(26, seed = 3)
        val moved = transform(ref, rotationDeg = -7.5, dx = 44.0, dy = 30.0)

        val match = AsterismMatcher.match(ref, moved, frameWidth = frameW, frameHeight = frameH)

        assertTrue(match.usable)
        assertTrue(correct(match, ref.indices.associateWith { it }) >= 15)
    }

    @Test
    fun `the order stars arrive in does not matter`() {
        // Detection order follows brightness and segmentation, so it is not stable between frames.
        // A matcher that quietly relied on it would pass every test above and fail on the sky.
        val ref = field(24, seed = 4)
        val moved = transform(ref, rotationDeg = 9.0, dx = 15.0, dy = -8.0)

        val order = moved.indices.shuffled(Random(99))
        val shuffled = order.map { moved[it] }
        // shuffled[newIndex] == moved[order[newIndex]], so reference i belongs at this position:
        val expected = order.withIndex().associate { (newIndex, oldIndex) -> oldIndex to newIndex }

        val match = AsterismMatcher.match(ref, shuffled, frameWidth = frameW, frameHeight = frameH)

        assertTrue(match.usable)
        assertTrue(correct(match, expected) >= 15) {
            "only ${correct(match, expected)} of ${match.count} survived a shuffle"
        }
    }

    @Test
    fun `a mirrored field is refused, because the sky never reflects`() {
        // Side ratios alone cannot tell a triangle from its mirror image. The sky applies rotation
        // and translation and never a reflection (FR-7.3), so every mirror match is a false one —
        // and handedness is what throws them away.
        val ref = field(24, seed = 5)
        val mirrored = ref.map { it.copy(x = frameW - it.x) }

        val match = AsterismMatcher.match(ref, mirrored, frameWidth = frameW, frameHeight = frameH)

        assertTrue(correct(match, ref.indices.associateWith { it }) <= 2) {
            "a reflection should not match: ${correct(match, ref.indices.associateWith { it })} pairs agreed"
        }
    }

    @Test
    fun `every correspondence is one to one`() {
        // Two target stars claiming the same reference star is a set no transform can satisfy, and
        // it damages the fit differently from an outlier: RANSAC discards outliers gracefully and
        // is dragged by contradictions.
        val ref = field(28, seed = 6)
        val moved = transform(ref, rotationDeg = 4.0, dx = 12.0, dy = 9.0)

        val match = AsterismMatcher.match(ref, moved, frameWidth = frameW, frameHeight = frameH)

        assertEquals(match.pairs.map { it.referenceIndex }.distinct().size, match.count)
        assertEquals(match.pairs.map { it.targetIndex }.distinct().size, match.count)
    }

    @Test
    fun `matching survives stars appearing and disappearing`() {
        // Real frames disagree about their faint end: cloud, noise and the threshold move stars in
        // and out between one sub and the next.
        val ref = field(30, seed = 7)
        val moved = transform(ref, rotationDeg = 6.0, dx = 20.0, dy = -14.0)
        // The target loses six stars and gains four that were never there.
        val partial = moved.drop(6) + field(4, seed = 71)
        val expected = (6 until 30).associateWith { it - 6 }

        val match = AsterismMatcher.match(ref, partial, frameWidth = frameW, frameHeight = frameH)

        assertTrue(match.usable)
        assertTrue(correct(match, expected) >= 12) {
            "only ${correct(match, expected)} correct with stars added and removed"
        }
    }

    @Test
    fun `centroid noise does not break the shapes`() {
        // Detection is good to about a tenth of a pixel (T-2.3). The descriptor has to tolerate
        // that, or it would only work on synthetic data.
        val rng = Random(12)
        val ref = field(26, seed = 8)
        val moved = transform(ref, rotationDeg = 5.0, dx = 25.0, dy = 11.0)
            .map { it.copy(x = it.x + rng.nextDouble(-0.3, 0.3), y = it.y + rng.nextDouble(-0.3, 0.3)) }

        val match = AsterismMatcher.match(ref, moved, frameWidth = frameW, frameHeight = frameH)

        assertTrue(match.usable)
        assertTrue(correct(match, ref.indices.associateWith { it }) >= 12)
    }

    @Test
    fun `two unrelated fields do not match`() {
        // The failure that matters most: a confident wrong answer. A cloud-covered frame should
        // produce nothing rather than a transform, because nothing downstream can tell the
        // difference afterwards.
        val ref = field(24, seed = 9)
        val unrelated = field(24, seed = 10)

        val match = AsterismMatcher.match(ref, unrelated, frameWidth = frameW, frameHeight = frameH)

        assertTrue(match.count <= 2) { "matched ${match.count} pairs between unrelated fields" }
    }

    @Test
    fun `too few stars yields nothing rather than a guess`() {
        val ref = field(2, seed = 11)
        assertEquals(AsterismMatcher.Method.NONE, AsterismMatcher.match(ref, ref).method)
        assertFalse(AsterismMatcher.match(ref, ref).usable)
    }

    // ------------------------------------------------------------------ the seeded path

    @Test
    fun `a trustworthy seed matches a field far too sparse for triangles`() {
        // The robustness win T-4.1 exists for. Four stars cannot support triangle statistics; a
        // seed and a nearest-neighbour search need almost nothing.
        val ref = field(4, seed = 13)
        val moved = transform(ref, rotationDeg = 0.4, dx = 5.0, dy = -3.0)
        val seed = SkyDrift.Seed(rotationDeg = 0.4, dx = 5.0, dy = -3.0, trustworthy = true)

        val match = AsterismMatcher.match(ref, moved, seed, frameW, frameH)

        assertEquals(AsterismMatcher.Method.SEEDED, match.method)
        assertEquals(4, match.count)
        assertEquals(4, correct(match, ref.indices.associateWith { it }))
    }

    @Test
    fun `an untrustworthy seed is ignored and asterisms take over`() {
        // Near the zenith SkyDrift refuses to answer. The matcher must not use the zeroes it
        // returns as though they were a prediction of no movement.
        val ref = field(24, seed = 14)
        val moved = transform(ref, rotationDeg = 8.0, dx = 30.0, dy = 20.0)
        val refused = SkyDrift.Seed.NONE

        val match = AsterismMatcher.match(ref, moved, refused, frameW, frameH)

        assertEquals(AsterismMatcher.Method.ASTERISM, match.method)
        assertTrue(match.usable)
    }

    @Test
    fun `a badly wrong seed falls back rather than matching nonsense`() {
        // A stale compass reading. The seeded path finds a few coincidences at most, and half a
        // correspondence set is worse than none — so it must not be accepted on that basis.
        val ref = field(24, seed = 15)
        val moved = transform(ref, rotationDeg = 8.0, dx = 30.0, dy = 20.0)
        val wrong = SkyDrift.Seed(rotationDeg = -25.0, dx = -140.0, dy = 90.0, trustworthy = true)

        val match = AsterismMatcher.match(ref, moved, wrong, frameW, frameH)

        assertEquals(AsterismMatcher.Method.ASTERISM, match.method)
        assertTrue(correct(match, ref.indices.associateWith { it }) >= 12)
    }

    @Test
    fun `an ambiguous seed match is dropped rather than guessed`() {
        // Two stars equally close to the prediction: choosing either is a coin toss, and an
        // outlier fed to RANSAC with a confident label is worse than an absent pair.
        val ref = listOf(
            AsterismMatcher.Detection(100.0, 100.0, 900.0),
            AsterismMatcher.Detection(300.0, 200.0, 800.0),
            AsterismMatcher.Detection(150.0, 300.0, 700.0),
        )
        val crowded = listOf(
            // Both sit 2 px from the prediction at (102, 100): genuinely a coin toss.
            AsterismMatcher.Detection(104.0, 100.0, 900.0),
            AsterismMatcher.Detection(100.0, 100.0, 880.0),
            AsterismMatcher.Detection(302.0, 200.0, 800.0),
            AsterismMatcher.Detection(152.0, 300.0, 700.0),
        )
        val seed = SkyDrift.Seed(0.0, 2.0, 0.0, trustworthy = true)

        val match = AsterismMatcher.match(ref, crowded, seed, frameW, frameH)

        // The two unambiguous stars pair; the crowded one is left alone. Three pairs would mean it
        // guessed, which is the behaviour this test exists to forbid.
        assertTrue(match.pairs.none { it.referenceIndex == 0 }) { "the ambiguous star was guessed" }
    }

    // ------------------------------------------------------------------ end to end

    @Test
    fun `it matches real detections from rendered frames`() {
        // The whole pipeline on synthetic data: render two frames with a known transform, bin,
        // detect, match. Everything above isolates the matcher; this one deliberately does not,
        // because the parts have to agree about coordinates as well as about algorithms.
        val sky = SyntheticSky()
        val stars = sky.field(count = 40, seed = 21)
        val shift = SyntheticSky.Transform(rotationDeg = 3.0, dx = 18.0, dy = -11.0)

        val a = detections(sky, sky.render(stars, 7.4, seed = 1))
        val b = detections(sky, sky.render(stars, 7.4, shift, seed = 2))

        val match = AsterismMatcher.match(
            a, b, frameWidth = sky.width.toDouble(), frameHeight = sky.height.toDouble(),
        )

        assertTrue(match.usable) { "no usable match from ${a.size} and ${b.size} detections" }

        // Ground truth: a correspondence is right when the two detections are the same *injected*
        // star. Checked by position against the transform, since detection order is not meaningful.
        val cx = (sky.width - 1) / 2.0
        val cy = (sky.height - 1) / 2.0
        val agreeing = match.pairs.count { pair ->
            val r = a[pair.referenceIndex]
            val t = b[pair.targetIndex]
            val (px, py) = shift.apply(r.x, r.y, cx, cy)
            hypot(t.x - px, t.y - py) < 3.0
        }
        assertTrue(agreeing >= match.count * 0.8) {
            "$agreeing of ${match.count} correspondences agree with the truth"
        }
        assertTrue(agreeing >= 8) { "only $agreeing correspondences agreed" }
    }

    private fun detections(
        sky: SyntheticSky,
        frame: SyntheticSky.Frame,
    ): List<AsterismMatcher.Detection> {
        val plane = com.starstacker.stars.CfaBinner.binGreen(
            frame.pixels, frame.width, frame.height, sky.cfaCodes, 2,
        )
        return com.starstacker.stars.StarDetector(saturationLevel = sky.whiteLevel.toDouble())
            .detect(plane.data, plane.width, plane.height)
            .stars
            .map {
                AsterismMatcher.Detection(
                    x = plane.toSensorCoordinate(it.x),
                    y = plane.toSensorCoordinate(it.y),
                    flux = it.flux,
                )
            }
    }
}
