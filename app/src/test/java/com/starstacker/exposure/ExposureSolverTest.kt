package com.starstacker.exposure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-3.3 acceptance. FR-5.2's two named regimes are the two tests that matter:
 *
 * > under dark skies the result is typically read-noise limited → recommend more, shorter subs.
 * > under light-polluted skies the sky limit is reached quickly → shorter subs are sufficient and
 * > highlight headroom matters more.
 *
 * A solver that returns a plausible number under both without distinguishing them has not
 * implemented FR-5.2, it has implemented a formula.
 */
class ExposureSolverTest {

    private val whiteLevel = 1023
    private val blackLevel = 64.0
    private val aduSpan = 959.0

    /** A sensor whose full scale halves per stop and whose read noise improves slowly. */
    private fun sensor(iso: Int, fullScale: Double, readNoise: Double) = SensorNoise(
        iso = iso,
        fullScaleElectrons = fullScale,
        readNoiseElectrons = readNoise,
        electronsPerAdu = fullScale / aduSpan,
        interpolated = false,
    )

    private val noiseModel = FixedNoiseModel(
        points = listOf(
            sensor(400, 16_000.0, 5.0),
            sensor(800, 8_000.0, 4.0),
            sensor(1600, 4_000.0, 3.4),
            sensor(3200, 2_000.0, 3.0),
        ),
        source = "test sensor",
    )

    private val isos = listOf(400, 800, 1600, 3200)

    /** The reference device's main camera on the equator: 7.399 s at 1.5 px. */
    private val trailing = TrailingLimit.solve(
        pixelPitchUm = 2.00,
        focalLengthMm = 5.56,
        sensorWidthMm = 8.192,
        sensorHeightMm = 6.144,
        fieldCentreDecDeg = 0.0,
    )

    /**
     * @param backgroundAdu the test frame's median level, black included.
     */
    private fun skyOf(backgroundAdu: Double, exposureSeconds: Double, iso: Int = 800) =
        SkyMeasurement.from(
            backgroundAdu = backgroundAdu,
            blackLevelAdu = blackLevel,
            whiteLevelAdu = whiteLevel,
            iso = iso,
            exposureSeconds = exposureSeconds,
            noise = noiseModel.at(iso)!!,
        )

    private fun solve(sky: SkyMeasurement, pinnedIso: Int? = null) = ExposureSolver.solve(
        sky = sky,
        noiseModel = noiseModel,
        trailing = trailing,
        isoCandidates = isos,
        maxExposureSeconds = 49.6,
        pinnedIso = pinnedIso,
    )

    @Test
    fun `sky rate is measured in electrons and does not depend on the test ISO`() {
        // 20 ADU above black at ISO 800 over 4 s: 20 * (8000/959) = 166.8 e-, so 41.7 e-/s.
        val sky = skyOf(backgroundAdu = blackLevel + 20.0, exposureSeconds = 4.0, iso = 800)
        assertEquals(41.71, sky.electronsPerSecond, 0.02)

        // The same sky measured at ISO 1600 gives twice the ADU for the same electrons, and
        // half the electrons per ADU. The rate must come out identical.
        val atHigherIso = skyOf(backgroundAdu = blackLevel + 40.0, exposureSeconds = 4.0, iso = 1600)
        assertEquals(sky.electronsPerSecond, atHigherIso.electronsPerSecond, 0.02)
    }

    /**
     * A moderate sky: sky-limited comfortably, and with enough well depth that trailing — not
     * clipping — is what ends the sub. The exposure should therefore run all the way to the
     * trailing limit, since past the sky-limited floor a longer sub is free.
     */
    @Test
    fun `a moderate sky is sky-limited and runs to the trailing limit`() {
        // 41.7 e-/s. At ISO 800, R = 4 so the floor is 4 * 16 / 41.7 = 1.53 s -- cleared well
        // inside the 7.399 s trailing limit. Clipping at ISO 400 would not arrive until
        // 0.33 * 16000 / 41.7 = 127 s, so trailing is the binding constraint.
        val solution = solve(skyOf(blackLevel + 20.0, 4.0))

        val chosen = solution.chosen
        assertNotNull(chosen, solution.failureReason)
        assertEquals(ExposureSolver.Verdict.SKY_LIMITED, chosen!!.verdict)
        assertEquals(trailing.maxExposureSeconds, chosen.exposureSeconds, 1e-9)
        assertFalse(chosen.clippingLimited, "trailing should bind here, not clipping")
        assertTrue(
            chosen.skyToReadVariance >= 3.0,
            "sky/read variance was ${chosen.skyToReadVariance}, below FR-5.2's 3x floor",
        )

        // Every candidate that reached sky-limited is equivalent in noise, so the winner must be
        // the one with the most room before the sky itself clips — which is the lowest ISO here.
        val usable = solution.candidates.filter { it.usable }
        assertEquals(
            usable.maxOf { it.clippingHeadroomStops },
            chosen.clippingHeadroomStops,
            1e-9,
        )
        assertEquals(400, chosen.iso, "the lowest sky-limited ISO has the most headroom")
    }

    /**
     * A dark sky. FR-5.2: read-noise limited — no ISO reaches the criterion inside the trailing
     * budget, and the honest answer is to shoot at the limit and stack more frames.
     */
    @Test
    fun `a dark sky is read-noise limited and says so instead of inventing an exposure`() {
        // 2 ADU above black over 10 s at ISO 800: 2 * 8.34 = 16.7 e-, so 1.67 e-/s.
        // At ISO 3200 (R = 3, the best) required t = 4 * 9 / 1.67 = 21.6 s, far past 7.4 s.
        val solution = solve(skyOf(blackLevel + 2.0, 10.0))

        assertTrue(
            solution.candidates.all {
                it.verdict == ExposureSolver.Verdict.READ_NOISE_LIMITED
            },
            solution.candidates.joinToString("\n") { "${it.iso}: ${it.verdict} ${it.reason}" },
        )

        // An answer is still given — FR-5.3 promises a one-line recommendation, and "your sky is
        // too dark to be sky-limited" is not one.
        val chosen = solution.chosen
        assertNotNull(chosen)
        assertEquals(ExposureSolver.Verdict.READ_NOISE_LIMITED, chosen!!.verdict)
        assertEquals(3200, chosen.iso, "the lowest read noise is the least-bad choice here")
        assertEquals(trailing.maxExposureSeconds, chosen.exposureSeconds, 1e-9)

        val advisory = solution.advisory
        assertTrue(advisory.contains("dark sky"), advisory)
        assertTrue(advisory.contains("stack more frames"), advisory)
        assertNull(solution.failureReason)
    }

    /**
     * The regression this file's first failure exposed. FR-5.2's sky-limited criterion is a
     * **floor**: reaching 4x read noise in variance costs only ~4R² electrons, which under a
     * bright sky happens in tens of milliseconds. Treating it as the target recommends 20 ms
     * subs — 24 MB of DNG every 20 ms — when a longer sub costs nothing in noise and saves
     * frames, storage and write bandwidth.
     */
    @Test
    fun `a bright sky runs the sub to the clipping limit, not to the sky-limited floor`() {
        // 300 ADU above black over 0.5 s at ISO 800: 300 * 8.342 = 2502.6 e- in 0.5 s
        //   -> 5005 e-/s.
        // Sky-limited floor at ISO 800: 4 * 16 / 5005 = 0.0128 s.
        // Clipping at 33% of an 8000 e- well: 2640 / 5005 = 0.527 s.
        val solution = solve(skyOf(blackLevel + 300.0, 0.5))
        val chosen = solution.chosen

        assertNotNull(chosen, solution.failureReason)
        assertEquals(ExposureSolver.Verdict.SKY_LIMITED, chosen!!.verdict)
        assertTrue(chosen.clippingLimited, "clipping should be what stops this sub, not trailing")
        assertTrue(
            chosen.exposureSeconds > 0.4,
            "recommended ${chosen.exposureSeconds} s — the sky-limited floor was treated as a target",
        )
        assertTrue(
            chosen.exposureSeconds < trailing.maxExposureSeconds,
            "a bright sky must be stopped by clipping before trailing",
        )
        // The background is held at the headroom ceiling rather than allowed to fill the well.
        assertEquals(
            ExposureSolver.MAX_BACKGROUND_FRACTION,
            chosen.backgroundFraction,
            1e-6,
        )
        assertTrue(solution.advisory.contains("bright sky"), solution.advisory)
    }

    @Test
    fun `ISOs below the dual gain point are excluded with that named as the reason`() {
        val solution = ExposureSolver.solve(
            sky = skyOf(blackLevel + 20.0, 4.0),
            noiseModel = noiseModel,
            trailing = trailing,
            isoCandidates = isos,
            maxExposureSeconds = 49.6,
            dualGainIso = 1600,
        )

        val excluded = solution.candidates.filter { it.iso < 1600 }
        assertTrue(excluded.isNotEmpty())
        assertTrue(excluded.all { it.verdict == ExposureSolver.Verdict.BELOW_DUAL_GAIN })
        assertTrue(excluded.all { it.reason.contains("dual conversion gain") })
        assertEquals(1600, solution.chosen?.iso)
    }

    /** FR-5.3: pinning ISO re-solves around it, and nothing downstream is disabled by the pin. */
    @Test
    fun `pinning an ISO forces the choice but keeps the whole derivation`() {
        val sky = skyOf(blackLevel + 20.0, 4.0)
        val free = solve(sky)
        val pinned = solve(sky, pinnedIso = 3200)

        assertEquals(400, free.chosen?.iso)
        assertEquals(3200, pinned.chosen?.iso)
        assertEquals(3200, pinned.pinnedIso)

        // The derivation is unchanged in size and still explains every candidate.
        assertEquals(free.candidates.size, pinned.candidates.size)
        assertTrue(pinned.candidates.all { it.reason.isNotBlank() })
        assertNotNull(pinned.chosen!!.exposureSeconds)
    }

    @Test
    fun `every candidate carries the reason it won or lost`() {
        val solution = solve(skyOf(blackLevel + 20.0, 4.0))

        assertEquals(isos.size, solution.candidates.size)
        assertEquals(isos, solution.candidates.map { it.iso })
        for (candidate in solution.candidates) {
            assertTrue(
                candidate.reason.isNotBlank(),
                "ISO ${candidate.iso} lost without recording why",
            )
        }
        assertEquals("test sensor", solution.noiseSource)
    }

    @Test
    fun `a clipped test frame is called out rather than silently solved`() {
        val sky = skyOf(whiteLevel.toDouble(), 4.0)
        assertTrue(sky.clipped)

        val solution = solve(sky)
        assertTrue(
            solution.advisory.contains("clipped"),
            "a clipped measurement must say so: ${solution.advisory}",
        )
    }

    @Test
    fun `the headline reads as FR-5_3's one-liner`() {
        val solution = solve(skyOf(blackLevel + 20.0, 4.0))
        assertTrue(solution.headline.startsWith("ISO 400 · "), solution.headline)
    }
}
