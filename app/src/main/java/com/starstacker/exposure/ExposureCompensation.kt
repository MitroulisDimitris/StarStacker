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
 * ### The two defects the wider range exposed
 *
 * Both were already live at ±2 and would have been four times worse at ±4, and both are here
 * rather than in [com.starstacker.ui.SetupController] so they can be tested without a Context:
 *
 * - **The compensated sub was never clamped to the sensor's maximum.** Asking this HAL for longer
 *   than its 49.64 s ceiling (§1.5) gets silence or a truncated frame, not an error — D-21's whole
 *   family of "the HAL took the request and did something else". [apply] clamps, and
 *   [isClampedAt] lets the screen say so rather than showing a number the sensor will not honour.
 * - **The session-length slider's upper bound came off the *uncompensated* sub.** At +2 stops the
 *   2.5-hour bound was already wrong by 4×, promising a session four times longer than the frames
 *   it counted; at +4 it would be 16×. [maxFrames] takes the sub that will actually be shot.
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
     * The sub actually planned: the solved answer shifted by [stops], never past what the sensor
     * will take.
     *
     * @param maxSeconds the sensor's own ceiling, `SENSOR_INFO_EXPOSURE_TIME_RANGE`'s upper bound.
     */
    fun apply(solvedSeconds: Double, stops: Double, maxSeconds: Double): Double {
        val requested = solvedSeconds * 2.0.pow(stops)
        return if (maxSeconds > 0.0 && maxSeconds.isFinite()) {
            requested.coerceAtMost(maxSeconds)
        } else {
            requested
        }
    }

    /**
     * True when the clamp is what decided the answer — the compensation is asking for longer than
     * the sensor can expose, so turning the dial further changes nothing.
     *
     * The screen needs this because otherwise the control lies twice over: the number stops moving
     * while the dial keeps going, and the user cannot tell whether the app or the sensor said no.
     */
    fun isClampedAt(solvedSeconds: Double, stops: Double, maxSeconds: Double): Boolean {
        if (maxSeconds <= 0.0 || !maxSeconds.isFinite()) return false
        return solvedSeconds * 2.0.pow(stops) > maxSeconds + 1e-9
    }

    /**
     * The most frames the session-length slider offers: whatever fills [hours] at this sub length,
     * so the right-hand end is always the same amount of *night* rather than the same number.
     *
     * Takes the **compensated** sub. That is the whole fix: the bound has to follow the frame that
     * will actually be shot, or the slider counts one exposure and promises another.
     */
    fun maxFrames(subSeconds: Double, overheadSeconds: Double, hours: Double): Int {
        val perFrame = subSeconds + overheadSeconds
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
