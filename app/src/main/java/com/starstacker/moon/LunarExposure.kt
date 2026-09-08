package com.starstacker.moon

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * T-11.1 — metering the moon, which is a daylight subject at night.
 *
 * ### Why this is metered and not calculated
 *
 * The moon is sunlit rock at our distance from the sun, so its surface brightness is roughly a grey
 * card in daylight: the **Looney 11** rule, `f/11` at `1/ISO` seconds. That gives a starting point
 * good to a stop or two in the open.
 *
 * It is *only* a starting point. On session `2026-09-06_0118` the truth was five stops away from
 * it, because a thin crescent is dimmed by the low sun angle across the surface (~2 stops) and 2°
 * of altitude costs another ~3 to atmospheric extinction. A calculator that knew Looney 11 and
 * nothing else would still have blown the disc out.
 *
 * So the mode probes and corrects, exactly as `FlatCheck` does for flats (§1.44), where the same
 * loop closed a five-stop error in two probes. That code was written for a different subject and
 * is the same problem: put the bright end of a histogram in a band.
 *
 * ### The band, and why it is where it is
 *
 * [TARGET_PEAK] is 70% of the way from black to white. Below clipping with room for the seeing to
 * flicker the brightness of the limb frame to frame, and high enough that the disc is not being
 * recorded in the noisy bottom of the range. [ACCEPT_LOW]–[ACCEPT_HIGH] is the band a frame is
 * accepted in, so the loop stops rather than hunting for a number it cannot hit exactly.
 *
 * ### Why the correction is a single scale rather than a search
 *
 * Sensor response is linear in exposure time, so one probe measures the constant and one
 * multiplication lands on the target.
 *
 * **Except when the probe clipped**, which is the case that matters here and the one a naive
 * linear correction gets badly wrong. A clipped frame's peak reads as white whether the truth is
 * one stop over or fourteen, so `target / measured` asks for 0.7x — a third of a stop — on a
 * session that was 14.2 stops out. So a clipped probe ignores its own measurement and steps down
 * by a fixed factor, and the linear correction takes over as soon as a real number exists.
 */
object LunarExposure {

    /** Where the brightest part of the disc should sit, as a fraction of black-to-white. */
    const val TARGET_PEAK = 0.70

    /** The band a frame is accepted in — T-11.1's acceptance is the peak within 60–80%. */
    const val ACCEPT_LOW = 0.60
    const val ACCEPT_HIGH = 0.80

    /** The most one correction may scale the exposure, in either direction. */
    const val MAX_STEP = 64.0

    /** Give up rather than loop forever; each probe costs a frame. */
    const val MAX_PROBES = 8

    /** The f-number Looney 11 is stated at. */
    private const val LOONEY_APERTURE = 11.0

    enum class Verdict {
        /** The peak is inside the band. Shoot at this exposure. */
        SETTLED,

        /** Pixels are pinned at white — the measurement is a lower bound and must go again. */
        CLIPPED,

        /** Below the band; correct upward. */
        DIM,

        /** Above the band but not clipped; correct downward. */
        BRIGHT,

        /** The correction wants an exposure the sensor will not take. */
        UNREACHABLE,
    }

    data class Probe(
        val exposureNs: Long,
        val iso: Int,
        /** Peak of the disc as a fraction of black-to-white, 0–1. */
        val peakFraction: Double,
        /** How many pixels sat at the white level. Any at all makes the peak a lower bound. */
        val clippedPixels: Int,
        val verdict: Verdict,
        /** What to try next, or null when [verdict] is [Verdict.SETTLED] or [Verdict.UNREACHABLE]. */
        val nextExposureNs: Long?,
    ) {
        fun describe(): String = buildString {
            append("%.3f ms @ ISO %d → peak %.1f%% of scale".format(exposureNs / 1e6, iso, peakFraction * 100))
            if (clippedPixels > 0) append(", $clippedPixels px clipped")
            append(" · $verdict")
            nextExposureNs?.let { append(" → try %.3f ms".format(it / 1e6)) }
        }
    }

    /**
     * Looney 11 as a starting exposure, in nanoseconds.
     *
     *     t = (N / 11)^2 / ISO   seconds
     *
     * The `(N/11)²` term is the light an aperture of `N` gathers relative to `f/11`. Nothing here
     * knows what lens it is looking through — it reads the profile's own aperture and ISO.
     */
    fun looneyElevenNs(apertureF: Float, iso: Int): Long {
        require(apertureF > 0f) { "aperture must be positive" }
        require(iso > 0) { "ISO must be positive" }
        val ratio = apertureF / LOONEY_APERTURE
        val seconds = ratio * ratio / iso
        return (seconds * 1e9).roundToLong().coerceAtLeast(1L)
    }

    /** How many stops [measuredNs] is away from [wantedNs] — for saying *how wrong* out loud. */
    fun stopsBetween(measuredNs: Long, wantedNs: Long): Double {
        if (measuredNs <= 0 || wantedNs <= 0) return 0.0
        return ln(measuredNs.toDouble() / wantedNs) / ln(2.0)
    }

    /**
     * Reads one probe and says what to do next.
     *
     * @param peakAdu the brightest pixel of the disc, raw
     * @param blackLevel the sensor's black level
     * @param whiteLevel the sensor's white level
     * @param clippedPixels how many pixels sat at [whiteLevel]
     * @param exposureRange what the sensor will actually accept, so an unreachable ask is named
     *   rather than silently clamped into another clipped frame
     */
    fun assess(
        exposureNs: Long,
        iso: Int,
        peakAdu: Double,
        blackLevel: Double,
        whiteLevel: Int,
        clippedPixels: Int,
        exposureRange: LongRange? = null,
    ): Probe {
        val span = whiteLevel - blackLevel
        val fraction = if (span <= 0.0) 0.0 else ((peakAdu - blackLevel) / span).coerceIn(0.0, 1.0)

        // A clipped frame carries no usable measurement at all. Its peak reads as white whether
        // the truth is one stop over or fourteen, so the linear correction would ask for 0.7x — a
        // third of a stop — on the very session that was 14.2 stops out. Step down by a fixed
        // [CLIPPED_STEP] instead and probe again: four stops a probe reaches 2026-09-06's error in
        // four frames, and the linear correction takes over the moment a real number exists.
        if (clippedPixels > 0) {
            val next = clamp(exposureNs, 1.0 / CLIPPED_STEP, exposureRange)
            return Probe(
                exposureNs, iso, fraction, clippedPixels,
                if (next == null) Verdict.UNREACHABLE else Verdict.CLIPPED,
                next,
            )
        }

        if (fraction in ACCEPT_LOW..ACCEPT_HIGH) {
            return Probe(exposureNs, iso, fraction, 0, Verdict.SETTLED, null)
        }

        // Linear response: one multiplication lands on the target.
        if (fraction <= 0.0) {
            val next = clamp(exposureNs, MAX_STEP, exposureRange)
            return Probe(
                exposureNs, iso, fraction, 0,
                if (next == null) Verdict.UNREACHABLE else Verdict.DIM,
                next,
            )
        }
        val next = clamp(exposureNs, TARGET_PEAK / fraction, exposureRange)
        val verdict = when {
            next == null -> Verdict.UNREACHABLE
            fraction < ACCEPT_LOW -> Verdict.DIM
            else -> Verdict.BRIGHT
        }
        return Probe(exposureNs, iso, fraction, 0, verdict, next)
    }

    /**
     * The correction, capped and pinned to what the sensor will take.
     *
     * Returns null when the wanted exposure is outside the sensor's range *and* the clamped value
     * would not move — which is the honest "this camera cannot do it" rather than a clamp that
     * produces the same clipped frame forever.
     */
    private fun clamp(currentNs: Long, scale: Double, range: LongRange?): Long? {
        val capped = scale.coerceIn(1.0 / MAX_STEP, MAX_STEP)
        val wanted = (currentNs * capped).roundToLong().coerceAtLeast(1L)
        if (range == null) return wanted
        val pinned = wanted.coerceIn(range.first, range.last)
        // No movement means the sensor is already at the end it needs to go past.
        return if (pinned == currentNs) null else pinned
    }

    /** True when a probe's peak is close enough that another would only measure the same thing. */
    fun settled(fraction: Double): Boolean = fraction in ACCEPT_LOW..ACCEPT_HIGH

    /** How far off target a fraction is, for logging a loop's progress. */
    fun error(fraction: Double): Double = abs(fraction - TARGET_PEAK)

    /**
     * How far down a clipped probe steps, as a factor — 16x is four stops.
     *
     * Chosen against the worst case actually seen: 2026-09-06 was 14.2 stops over, which four
     * stops a probe clears in four frames, inside [MAX_PROBES] with room for the linear correction
     * that follows. A gentler step would be safer against overshoot and would also have needed
     * fifteen probes to rescue that session.
     */
    private const val CLIPPED_STEP = 16.0
}
