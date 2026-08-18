package com.starstacker.exposure

import com.starstacker.pointing.Astro
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * T-3.5 / FR-5.4 — turning a sub length into a session.
 *
 * The planner exists to answer the questions that decide whether to press Start, and every one of
 * them is a question the app can only answer *before* the session: will this fit on the phone,
 * will the battery last, when will it finish, and how much of the frame will survive the field
 * rotation. Finding out afterwards is finding out too late — a session that runs out of storage
 * at 2 a.m. has wasted the night, and FR-5.4 requires the warning to come first.
 */
object SessionPlanner {

    /** Measured on the reference device (§1.6): 25,195,876 B for a 4096x3072 DNG. */
    const val DEFAULT_BYTES_PER_FRAME = 25_195_876L

    /**
     * Darks as a fraction of the lights (FR-4.2.1), clamped to a sane count.
     *
     * Too few and the dark master is noisier than the thing it is correcting; too many and the
     * session spends its coldest, best hours shooting a covered lens.
     */
    const val DARK_FRACTION = 0.15
    const val MIN_DARKS = 10
    const val MAX_DARKS = 30

    /** Keep this much of the volume free rather than filling it to the last byte. */
    const val STORAGE_RESERVE_BYTES = 500L * 1024 * 1024

    /**
     * Estimated battery drain, percent per hour of capture.
     *
     * **Not measured yet** — see OI-21. The sensor reading out continuously with the screen off
     * is the dominant term, and it is device-specific. Held as a parameter with a deliberately
     * pessimistic default so the warning fires early rather than late; T-3.9's session log is
     * what will replace it with a number.
     */
    const val DEFAULT_BATTERY_PERCENT_PER_HOUR = 18.0

    sealed interface Goal {
        /** "I have this long." Frame count falls out of it. */
        data class TotalTime(val seconds: Double) : Goal

        /** "I want this much integration." Total time falls out of it. */
        data class TargetIntegration(val seconds: Double) : Goal

        /**
         * T-3.26 — a straight frame count.
         *
         * The slider counts frames because **the frame is the quantum**: asking for 30 minutes
         * rounds to a number of frames anyway, and rounding twice is how a session runs long.
         */
        data class Frames(val count: Int) : Goal
    }

    enum class Severity { OK, WARN, BLOCK }

    data class Budget(
        val label: String,
        val severity: Severity,
        val detail: String,
    )

    data class Plan(
        val iso: Int,
        val subSeconds: Double,
        val lightCount: Int,
        val darkCount: Int,
        /** Light frames only — the number that determines the depth of the result. */
        val integrationSeconds: Double,
        /** Everything, including darks and per-frame overhead. */
        val totalSeconds: Double,
        val bytesRequired: Long,
        val endsAtEpochMs: Long,
        /** Field rotation accumulated across the whole session, degrees. Null without pointing. */
        val rotationDegrees: Double?,
        /** Fraction of the frame surviving that rotation, 0–1. Null without pointing. */
        val commonAreaFraction: Double?,
        val storage: Budget,
        val battery: Budget,
        val rotation: Budget,
    ) {
        val blocked: Boolean
            get() = listOf(storage, battery, rotation).any { it.severity == Severity.BLOCK }

        /** FR-5.3's one-liner, completed: "ISO 800 · 12s · 150 frames · 30 min". */
        val headline: String
            get() = "ISO %d · %s · %d frames · %s".format(
                iso,
                ExposureSolver.formatSeconds(subSeconds),
                lightCount,
                ExposureSolver.formatSeconds(integrationSeconds),
            )
    }

    /**
     * @param overheadSeconds per-frame cost beyond the exposure itself — readout and writing a
     *   25 MB DNG. Measured at roughly nothing on the framing loop's 1 s cadence, but a full-size
     *   DNG write is not nothing, so it stays a parameter.
     * @param rotationRateArcsecPerSec from [Astro.fieldRotationArcsecPerSec]; null when there is
     *   no pointing fix, in which case the common-area figure is withheld rather than guessed.
     */
    fun plan(
        goal: Goal,
        iso: Int,
        subSeconds: Double,
        frameAspectWidth: Int,
        frameAspectHeight: Int,
        freeBytes: Long,
        batteryPercent: Double,
        startEpochMs: Long,
        rotationRateArcsecPerSec: Double?,
        overheadSeconds: Double = 0.0,
        bytesPerFrame: Long = DEFAULT_BYTES_PER_FRAME,
        batteryPercentPerHour: Double = DEFAULT_BATTERY_PERCENT_PER_HOUR,
        includeDarks: Boolean = true,
    ): Plan {
        require(subSeconds > 0.0) { "sub length must be positive, was $subSeconds" }
        val perFrame = subSeconds + overheadSeconds

        val lightCount = when (goal) {
            is Goal.TargetIntegration -> ceil(goal.seconds / subSeconds).toInt().coerceAtLeast(1)
            is Goal.TotalTime -> lightsThatFitIn(goal.seconds, perFrame)
            is Goal.Frames -> goal.count
        }.coerceAtLeast(1)

        val darkCount = if (includeDarks) darkCountFor(lightCount) else 0
        val integration = lightCount * subSeconds
        val total = (lightCount + darkCount) * perFrame
        val bytes = (lightCount + darkCount).toLong() * bytesPerFrame

        val rotationDeg = rotationRateArcsecPerSec?.let { abs(it) * total / 3600.0 }
        val commonArea = rotationDeg?.let { commonAreaFraction(it, frameAspectWidth, frameAspectHeight) }

        return Plan(
            iso = iso,
            subSeconds = subSeconds,
            lightCount = lightCount,
            darkCount = darkCount,
            integrationSeconds = integration,
            totalSeconds = total,
            bytesRequired = bytes,
            endsAtEpochMs = startEpochMs + (total * 1000).toLong(),
            rotationDegrees = rotationDeg,
            commonAreaFraction = commonArea,
            storage = storageBudget(bytes, freeBytes),
            battery = batteryBudget(total, batteryPercent, batteryPercentPerHour),
            rotation = rotationBudget(rotationDeg, commonArea),
        )
    }

    /**
     * How many lights fit in a wall-clock budget, once the darks that will follow them are paid
     * for. Solved rather than iterated: darks are a fraction of the lights, so
     *
     *     (n + darks(n)) · perFrame ≤ budget
     *
     * and the fraction form inverts directly. The clamps on the dark count make the relation
     * piecewise, so the result is checked and walked back if the clamp changed the answer.
     */
    private fun lightsThatFitIn(budgetSeconds: Double, perFrameSeconds: Double): Int {
        var n = (budgetSeconds / (perFrameSeconds * (1.0 + DARK_FRACTION))).toInt()
        while (n > 1 && (n + darkCountFor(n)) * perFrameSeconds > budgetSeconds) n--
        while ((n + 1 + darkCountFor(n + 1)) * perFrameSeconds <= budgetSeconds) n++
        return n
    }

    fun darkCountFor(lightCount: Int): Int =
        (lightCount * DARK_FRACTION).roundToInt().coerceIn(MIN_DARKS, MAX_DARKS)

    /**
     * The fraction of the frame that survives [rotationDeg] of field rotation — the largest
     * centred rectangle of the same aspect ratio that stays inside the sensor at every rotation
     * in the session.
     *
     * §7.1's key property is that this is **independent of focal length**: rotational displacement
     * at pixel radius `r` is `r·θ`, so wide and tele lose the same *fraction* of frame. Only
     * trailing scales with focal length. That is why this takes an aspect ratio and not a lens.
     *
     * A centred rectangle scaled by `s` survives rotation by θ when its corners stay inside the
     * original. Taking the two corners that move outward:
     *
     *     s ≤ h / (w·sinθ + h·cosθ)      and      s ≤ w / (w·cosθ + h·sinθ)
     *
     * and the retained area is `s²`.
     */
    fun commonAreaFraction(rotationDeg: Double, width: Int, height: Int): Double {
        val theta = abs(rotationDeg) * Math.PI / 180.0
        if (theta <= 0.0) return 1.0
        if (theta >= Math.PI / 2) return 0.0

        val w = width.toDouble()
        val h = height.toDouble()
        val s = min(
            h / (w * sin(theta) + h * cos(theta)),
            w / (w * cos(theta) + h * sin(theta)),
        )
        return (s * s).coerceIn(0.0, 1.0)
    }

    private fun storageBudget(required: Long, free: Long): Budget {
        val usable = free - STORAGE_RESERVE_BYTES
        val gb = required / 1_073_741_824.0
        val freeGb = free / 1_073_741_824.0
        return when {
            required > usable -> Budget(
                "Storage",
                Severity.BLOCK,
                "needs %.1f GB, %.1f GB free — this session will not fit".format(gb, freeGb),
            )

            required > usable * 0.8 -> Budget(
                "Storage",
                Severity.WARN,
                "needs %.1f GB of %.1f GB free — very little margin".format(gb, freeGb),
            )

            else -> Budget("Storage", Severity.OK, "%.1f GB of %.1f GB free".format(gb, freeGb))
        }
    }

    private fun batteryBudget(
        totalSeconds: Double,
        batteryPercent: Double,
        percentPerHour: Double,
    ): Budget {
        val needed = totalSeconds / 3600.0 * percentPerHour
        return when {
            needed > batteryPercent -> Budget(
                "Battery",
                Severity.BLOCK,
                "needs about %.0f%% and %.0f%% is left — plug in, or shorten the session"
                    .format(needed, batteryPercent),
            )

            needed > batteryPercent * 0.8 -> Budget(
                "Battery",
                Severity.WARN,
                "needs about %.0f%% of the %.0f%% left".format(needed, batteryPercent),
            )

            else -> Budget(
                "Battery",
                Severity.OK,
                "about %.0f%% of %.0f%%".format(needed, batteryPercent),
            )
        }
    }

    private fun rotationBudget(rotationDeg: Double?, commonArea: Double?): Budget = when {
        rotationDeg == null || commonArea == null -> Budget(
            "Field rotation",
            Severity.OK,
            "needs a pointing fix to predict",
        )

        commonArea < 0.5 -> Budget(
            "Field rotation",
            Severity.WARN,
            "%.0f° over the session leaves %.0f%% of the frame — shorten it, or expect a heavy crop"
                .format(rotationDeg, commonArea * 100),
        )

        commonArea < 0.8 -> Budget(
            "Field rotation",
            Severity.WARN,
            "%.0f° over the session — %.0f%% of the frame survives".format(
                rotationDeg, commonArea * 100,
            ),
        )

        else -> Budget(
            "Field rotation",
            Severity.OK,
            "%.1f° — %.0f%% of the frame survives".format(rotationDeg, commonArea * 100),
        )
    }
}
