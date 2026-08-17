package com.starstacker.capture

/**
 * T-3.9 / FR-6.2 — thermal pacing, and deliberately the mildest version that can be defended.
 *
 * **OI-11 sets the default to "log only".** Tuning a pacing rule before a single real thermal
 * curve has been seen is guesswork, and guessing wrong in the aggressive direction costs sky:
 * every cooling gap is time the target is up and the app is not photographing it. So the policy
 * ships with pacing switched off, logs everything per frame, and the threshold gets set from the
 * curve T-3.9 records rather than from an intuition about phones.
 *
 * The signals are D-16's chain: thermal headroom where the platform provides it, battery
 * temperature as the always-available fallback and the dark-matching key.
 */
class ThermalPolicy(
    private val pacingEnabled: Boolean = false,
    private val headroomFloor: Double = DEFAULT_HEADROOM_FLOOR,
    private val batteryTempCeilingC: Double = DEFAULT_BATTERY_TEMP_CEILING_C,
    private val maxGapSeconds: Double = DEFAULT_MAX_GAP_SECONDS,
) {

    data class Reading(
        /**
         * `PowerManager.getThermalHeadroom()`: 0 is cool, 1 is at the throttling point, above 1
         * is past it. Null when the platform declines to answer, which it does if asked more
         * often than once every ten seconds.
         */
        val headroom: Double?,
        val batteryTempC: Double?,
        val batteryPercent: Int?,
    )

    data class Decision(
        /** Seconds to wait before the next frame. Zero means carry on. */
        val gapSeconds: Double,
        val throttling: Boolean,
        val note: String,
    ) {
        val pausing: Boolean get() = gapSeconds > 0.0
    }

    fun evaluate(reading: Reading): Decision {
        val headroom = reading.headroom
        val batteryTemp = reading.batteryTempC

        val hot = (headroom != null && headroom >= headroomFloor) ||
            (batteryTemp != null && batteryTemp >= batteryTempCeilingC)

        val note = buildString {
            append(
                headroom?.let { "headroom %.2f".format(it) } ?: "headroom unavailable",
            )
            batteryTemp?.let { append(" · battery %.1f°C".format(it)) }
            reading.batteryPercent?.let { append(" · %d%%".format(it)) }
        }

        if (!hot) return Decision(0.0, throttling = false, note = note)

        if (!pacingEnabled) {
            // OI-11: say so, do nothing. A warning the user can see is worth having before the
            // rule that acts on it exists.
            return Decision(
                0.0,
                throttling = true,
                note = "$note — hot, but pacing is off (OI-11): logging only",
            )
        }

        // Scale the gap with how far past the floor it is, capped. A fixed gap is either too
        // short to help or too long to be worth it, depending on how hot it actually got.
        val excess = when {
            headroom != null -> ((headroom - headroomFloor) / (1.0 - headroomFloor)).coerceIn(0.0, 1.0)
            else -> 0.5
        }
        return Decision(
            gapSeconds = maxGapSeconds * (0.3 + 0.7 * excess),
            throttling = true,
            note = "$note — cooling gap inserted",
        )
    }

    companion object {
        /** Headroom at or above this is close enough to throttling to act on. */
        const val DEFAULT_HEADROOM_FLOOR = 0.85

        /** Battery temperature is a proxy for the sensor's; 42 °C is hot for a phone in a field. */
        const val DEFAULT_BATTERY_TEMP_CEILING_C = 42.0

        const val DEFAULT_MAX_GAP_SECONDS = 30.0
    }
}
