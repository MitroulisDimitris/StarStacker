package com.starstacker.exposure

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * T-3.35 — the exposure-compensation dial, as arithmetic.
 *
 * ### Why ±4 and not ±2
 *
 * `MAX_STOPS = 2.0` was justified as *"past that the solve is not being adjusted, it is being
 * ignored"*. The premise was that a large override is a mistake — but the predicted histogram sits
 * directly above this control and shows the consequence, clipped or read-noise limited, so the
 * range can be as wide as a camera's is and the picture does the arguing. ±4 stops in sixths,
 * marked at whole stops, like every exposure-compensation dial ever made (§1.17).
 *
 * ### The defect the wider range exposed
 *
 * **The session-length slider's upper bound came off the *uncompensated* sub.** At +2 stops the
 * 2.5-hour bound was already wrong by 4×, promising a session four times longer than the frames it
 * counted; at +4 it would be 16×. [maxFrames] takes the sub that will actually be shot. It lives
 * here rather than in [com.starstacker.ui.SetupController] so it can be tested without a Context —
 * a frame bound 4× too generous looks exactly like an ordinary number on screen.
 *
 * ### The sensor ceiling is soft, because it is not real — measured 2026-08-19
 *
 * This object used to clamp the compensated sub to `SENSOR_INFO_EXPOSURE_TIME_RANGE`'s upper
 * bound, on the reasoning that asking for more "gets silence or a truncated frame, not an error".
 * **That was an assumption from the Camera2 contract, and the device disagrees.** Asked for 120 s
 * against a stated maximum of 49.6406 s, the reference device returned a frame of
 * **119.999987713 s** — 12 µs short of the request, honestly reported, 2.4× beyond the advertised
 * ceiling (§1.20).
 *
 * The ceiling is therefore advertised rather than enforced, and clamping to it refused exposures
 * the hardware was willing to take. That is not academic: the trailing limit scales as
 * 1/cos(declination), so past **dec 81.5°** on this lens the *sky* permits longer subs than the
 * stated ceiling allows, and every circumpolar target was being capped by a number the sensor
 * ignores.
 *
 * What replaces the clamp is not trust but **verification**: `SequenceSession.nextVerifiedFrame`
 * checks every frame's own metadata against what was asked, and beyond the stated ceiling it now
 * gives up with `ExposureRefused` rather than skipping frames until the session times out. A
 * device that really does clamp is caught on the first frame and says so. That is portable in a
 * way a hardcoded number is not — it works on the phone that honours the request *and* on the one
 * that does not, without either being special-cased.
 */
object ExposureCompensation {

    /** Four stops either way. The histogram above the control shows what that costs. */
    const val MAX_STOPS = 4.0

    /**
     * Sixths of a stop.
     *
     * Thirds are the photographic convention for a dial with detents, but this one is a drag on
     * glass with a 64 px picture above it responding to the value — the granularity that matters
     * is fine enough that the histogram moves smoothly under your thumb. Sixths give 48 positions
     * across the range, which a thumb can hit and a marked scale can still be read against.
     */
    const val STEP = 1.0 / 6.0

    /** Slider detents between the two ends: 48 intervals, so 47 interior positions. */
    const val SLIDER_STEPS = 47

    /** The whole stops the scale is marked at: −4 … +4. */
    val MARKS: List<Int> = (-MAX_STOPS.toInt()..MAX_STOPS.toInt()).toList()

    /** Clamps to the range and snaps to [STEP], so the value is always one the scale can show. */
    fun snap(stops: Double): Double {
        val bounded = stops.coerceIn(-MAX_STOPS, MAX_STOPS)
        return (bounded / STEP).roundToInt() * STEP
    }

    /**
     * A sanity bound on any single sub, in seconds. **Not the sensor's limit** — that is unknown
     * and at least 120 s (§1.20) — but the point past which one long frame is the wrong shape for
     * this app regardless of what the silicon allows.
     *
     * Three reasons, none of them about the sensor: dark current accumulates linearly with time
     * and the phone is at 32 °C in the middle of a session; one aeroplane ruins the whole frame,
     * and four minutes is a lot to lose to an aeroplane; and an alt-az mount rotates the field
     * throughout, which no exposure length can undo. Stacking is what buys integration here.
     */
    const val SANITY_CEILING_SECONDS = 240.0

    /**
     * The sub actually planned: the solved answer shifted by [stops].
     *
     * **No clamp to the sensor's stated maximum** — see the class note. What used to guard this is
     * now `SequenceSession`'s per-frame check, which is a measurement rather than an assumption.
     */
    fun apply(solvedSeconds: Double, stops: Double): Double = solvedSeconds * 2.0.pow(stops)

    /**
     * The upper bound to hand the solver: the sensor's stated ceiling is a floor on what we will
     * consider, not a cap, because it is advertised rather than enforced (§1.20).
     *
     * The other half of the solver's `min` is the trailing limit, which is the *real* constraint
     * on an untracked phone — a few seconds at the equator, minutes near the pole. Letting this
     * side go soft is what allows a circumpolar target to use the exposure the sky actually
     * permits; [SANITY_CEILING_SECONDS] stops it running away where the trailing limit diverges.
     */
    fun solverCeilingSeconds(sensorMaxSeconds: Double): Double =
        if (sensorMaxSeconds.isFinite() && sensorMaxSeconds > SANITY_CEILING_SECONDS) {
            sensorMaxSeconds
        } else {
            SANITY_CEILING_SECONDS
        }

    /**
     * How long one frame really costs, wall clock, at this sub length — measured 2026-08-19.
     *
     * **A sub past `SENSOR_INFO_MAX_FRAME_DURATION` costs about 2.6× its own exposure**, and one
     * below it costs exactly its exposure. Measured cadence between consecutive frames:
     *
     * | sub | frame duration limit | gap between frames |
     * |---|---|---|
     * | 0.951 s | 49.64 s | 1.00× (a real session, 14 lights) |
     * | 7.399 s | 49.64 s | 1.00× (two real sessions, 105 and 49 lights) |
     * | 40 s | 49.64 s | 1.00× |
     * | 60 s | 49.64 s | **2.89×, 2.01×, 2.87×** |
     *
     * So `SENSOR_INFO_MAX_FRAME_DURATION` — the sibling of the exposure ceiling that §1.20 showed
     * is not enforced — turns out to describe something real after all: the sensor sustains a
     * *repeating* stream up to that frame duration, and past it spends two or three sensor periods
     * per delivered frame. The exposure ceiling governs one frame; this governs the cadence.
     *
     * That distinction matters because the app now lets a user ask for subs past the ceiling
     * (**D-28**). Without this, a plan at 60 s subs would count 60 s a frame and take 156, and the
     * session length, the end time and the storage estimate would all be out by the same 2.6× —
     * exactly the class of defect T-3.35 fixed for the frame-count bound.
     */
    fun frameCostSeconds(
        subSeconds: Double,
        maxFrameDurationSeconds: Double?,
        overheadSeconds: Double,
    ): Double {
        val limit = maxFrameDurationSeconds?.takeIf { it.isFinite() && it > 0.0 }
            ?: return subSeconds + overheadSeconds
        return if (subSeconds > limit) {
            subSeconds * LONG_SUB_CADENCE_FACTOR
        } else {
            subSeconds + overheadSeconds
        }
    }

    /**
     * The measured penalty past the frame-duration limit: 2.89, 2.01 and 2.87 across three
     * consecutive 60 s frames, so 2.6 is the mean rather than a round number chosen for looks.
     *
     * It is deliberately **not** rounded down. The spread is real — the sensor spends two or three
     * periods per frame and it is not obvious which — and a session planner that under-promises
     * the clock finishes early, while one that over-promises it runs into the dawn.
     */
    const val LONG_SUB_CADENCE_FACTOR = 2.6

    /**
     * The most frames the session-length slider offers: whatever fills [hours] at this sub length,
     * so the right-hand end is always the same amount of *night* rather than the same number.
     *
     * Takes the **compensated** sub. That is the whole fix: the bound has to follow the frame that
     * will actually be shot, or the slider counts one exposure and promises another.
     */
    fun maxFrames(
        subSeconds: Double,
        overheadSeconds: Double,
        hours: Double,
        maxFrameDurationSeconds: Double? = null,
    ): Int {
        val perFrame = frameCostSeconds(subSeconds, maxFrameDurationSeconds, overheadSeconds)
        if (perFrame <= 0.0 || !perFrame.isFinite()) return 2
        return ((hours * 3600.0) / perFrame).toInt().coerceAtLeast(2)
    }

    /**
     * `+1 1/3` — the dial's own reading.
     *
     * Sixths are shown as the fraction rather than as `+1.33`, because the scale is marked in
     * stops and a decimal invites the reader to compare it against a number of seconds instead.
     */
    fun format(stops: Double): String {
        if (abs(stops) < STEP / 2) return "0"
        val sign = if (stops > 0) "+" else "−"
        val sixths = (abs(stops) / STEP).roundToInt()
        val whole = sixths / 6
        val fraction = when (sixths % 6) {
            0 -> ""
            1 -> "1/6"
            2 -> "1/3"
            3 -> "1/2"
            4 -> "2/3"
            else -> "5/6"
        }
        return when {
            fraction.isEmpty() -> "$sign$whole"
            whole == 0 -> "$sign$fraction"
            else -> "$sign$whole $fraction"
        }
    }
}
