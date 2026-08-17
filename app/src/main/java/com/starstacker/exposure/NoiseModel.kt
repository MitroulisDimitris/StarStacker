package com.starstacker.exposure

import com.starstacker.device.NoiseProfileEntry
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * What the sensor costs you, per ISO — the two numbers the sky-limited solver (T-3.3) runs on.
 *
 * FR-5.2's criterion is a comparison between two *variances*: sky shot noise against read noise.
 * Both have to be in the same units to be compared at all, and the only unit both live in is
 * electrons. So everything here converts to electrons, and the ADU the app actually measures are
 * a display detail.
 *
 * One interface, two providers, per the plan's §6 note. At **Functional** tier the numbers come
 * from the OEM's own `SENSOR_NOISE_PROFILE` ([OemNoiseModel]); from Phase 6 they come from a
 * measured bias series (FR-4.1.1). The solver cannot tell the difference and must not: whether
 * the OEM's figures are good enough to pick a sane ISO is **OI-9**, and the way that question
 * gets answered is by running the same solver against both models and comparing.
 */

/** The sensor's behaviour at one ISO, in electrons. */
data class SensorNoise(
    val iso: Int,
    /**
     * Electrons corresponding to a full-scale pixel at this ISO — the effective well depth as
     * the ADC sees it. Halves as ISO doubles, because ISO is gain applied before the converter.
     */
    val fullScaleElectrons: Double,
    /** Read noise, electrons RMS. The quantity a longer exposure is trying to drown out. */
    val readNoiseElectrons: Double,
    val electronsPerAdu: Double,
    /** True when this ISO was not measured directly and the value was interpolated. */
    val interpolated: Boolean,
) {
    /** FR-5.2 compares variances, not standard deviations. */
    val readVarianceElectrons: Double get() = readNoiseElectrons * readNoiseElectrons
}

interface NoiseModel {
    /** Human-readable provenance. It ends up in the derivation the user can expand (FR-5.3). */
    val source: String

    /** ISOs the model was actually measured at, ascending. */
    val measuredIsos: List<Int>

    /** Null when the ISO is outside anything the model can honestly speak to. */
    fun at(iso: Int): SensorNoise?
}

/**
 * The Functional-tier provider: Android's own per-channel noise profile.
 *
 * `SENSOR_NOISE_PROFILE` gives a `(S, O)` pair per CFA channel such that, for a pixel value `x`
 * normalised to `[0,1]`, the noise variance is `S·x + O`. Turning that into electrons is a
 * two-line derivation worth writing down, because every number downstream depends on it:
 *
 * Let `K` be the electrons at full scale, so `x = e/K`. Shot noise is Poisson in electrons —
 * `var(e) = e` — and variance scales by the square of a change of units, so
 * `var(x) = var(e)/K² = e/K² = x/K`. Matching against `S·x` gives
 *
 *     K = 1/S
 *
 * The constant term is the read noise, similarly converted: `O = R²/K²`, so
 *
 *     R = √O · K = √O / S
 *
 * Only the **green** channels are used. The analysis plane is green (D-9), the sky background is
 * measured on it, and on sensors where Gr and Gb differ, averaging red and blue into the figure
 * would describe a signal the app never looks at.
 */
class OemNoiseModel private constructor(
    private val points: List<SensorNoise>,
    private val aduSpan: Double,
    override val source: String,
) : NoiseModel {

    override val measuredIsos: List<Int> = points.map { it.iso }

    override fun at(iso: Int): SensorNoise? {
        if (points.isEmpty()) return null
        points.firstOrNull { it.iso == iso }?.let { return it }

        val below = points.lastOrNull { it.iso < iso }
        val above = points.firstOrNull { it.iso > iso }

        // Outside the measured range the honest answer is the nearest measurement scaled by the
        // one thing that is certain — ISO is analog gain, so full-scale electrons go as 1/ISO.
        // Read noise is *not* extrapolated: it is the number that behaves least predictably
        // across a dual-gain switch point, which is exactly what an extrapolation would miss.
        if (below == null || above == null) {
            val nearest = below ?: above!!
            return SensorNoise(
                iso = iso,
                fullScaleElectrons = nearest.fullScaleElectrons * nearest.iso / iso.toDouble(),
                readNoiseElectrons = nearest.readNoiseElectrons,
                electronsPerAdu = nearest.electronsPerAdu * nearest.iso / iso.toDouble(),
                interpolated = true,
            )
        }

        // Linear in log ISO: gain is multiplicative, so the stops are the natural axis.
        val t = (ln(iso.toDouble()) - ln(below.iso.toDouble())) /
            (ln(above.iso.toDouble()) - ln(below.iso.toDouble()))
        return SensorNoise(
            iso = iso,
            fullScaleElectrons = lerp(below.fullScaleElectrons, above.fullScaleElectrons, t),
            readNoiseElectrons = lerp(below.readNoiseElectrons, above.readNoiseElectrons, t),
            electronsPerAdu = lerp(below.electronsPerAdu, above.electronsPerAdu, t),
            interpolated = true,
        )
    }

    /**
     * The ISO where read noise falls off a cliff — the dual conversion gain switch (FR-4.1.1).
     *
     * FR-5.2 only considers ISOs *at or above* this point, and the reason is worth stating: below
     * it the sensor is in low-gain mode with markedly worse read noise, so a shorter exposure at
     * a lower ISO is strictly worse than the same total light collected above the switch. Null
     * when no such step is visible, which is the normal case on a sensor without dual gain and
     * also the normal case when only one ISO has been measured.
     */
    fun dualGainIso(dropFactor: Double = 1.3): Int? {
        for (i in 1 until points.size) {
            val previous = points[i - 1].readNoiseElectrons
            val current = points[i].readNoiseElectrons
            // Read noise in electrons normally drifts *down* slowly with ISO; a sharp drop
            // between adjacent stops is the conversion-gain switch rather than the drift.
            if (previous > 0 && previous / current >= dropFactor) return points[i].iso
        }
        return null
    }

    companion object {

        /**
         * @param profilesByIso `SENSOR_NOISE_PROFILE` as read from a capture result at each ISO.
         *   These are metadata, not pixels: a result at a given ISO can be had from a frame of
         *   any length, so the whole per-ISO curve costs a burst of the shortest exposures the
         *   sensor supports rather than a series of real ones.
         * @param cfaCodes row-major over the 2x2 cell, 1 = green — the same convention `CfaBinner`
         *   uses, so "the green channels" means the same set of samples in both places.
         */
        fun from(
            profilesByIso: Map<Int, List<NoiseProfileEntry>>,
            cfaCodes: List<Int>,
            whiteLevel: Int,
            blackLevel: Double,
            source: String = "OEM SENSOR_NOISE_PROFILE",
        ): OemNoiseModel {
            val aduSpan = (whiteLevel - blackLevel).coerceAtLeast(1.0)
            val points = profilesByIso.entries
                .mapNotNull { (iso, entries) -> pointFor(iso, entries, cfaCodes, aduSpan) }
                .sortedBy { it.iso }
            return OemNoiseModel(points, aduSpan, source)
        }

        private fun pointFor(
            iso: Int,
            entries: List<NoiseProfileEntry>,
            cfaCodes: List<Int>,
            aduSpan: Double,
        ): SensorNoise? {
            val green = entries.filterIndexed { index, _ -> cfaCodes.getOrNull(index) == 1 }
                .ifEmpty { entries }
            if (green.isEmpty()) return null

            val s = green.map { it.s }.average()
            val o = green.map { it.o }.average()
            if (s <= 0.0 || !s.isFinite() || o < 0.0 || !o.isFinite()) return null

            val fullScale = 1.0 / s
            return SensorNoise(
                iso = iso,
                fullScaleElectrons = fullScale,
                readNoiseElectrons = sqrt(o) / s,
                electronsPerAdu = fullScale / aduSpan,
                interpolated = false,
            )
        }

        private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    }
}

/**
 * A model built from figures supplied directly, for tests and for the Phase 6 measured provider
 * to slot into without the solver noticing.
 */
class FixedNoiseModel(
    private val points: List<SensorNoise>,
    override val source: String,
) : NoiseModel {
    override val measuredIsos: List<Int> = points.map { it.iso }.sorted()

    override fun at(iso: Int): SensorNoise? =
        points.minByOrNull { abs(it.iso - iso) }?.let {
            if (it.iso == iso) it else it.copy(iso = iso, interpolated = true)
        }
}
