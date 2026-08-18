package com.starstacker.capture

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.starstacker.core.Clock
import com.starstacker.core.SystemClock
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * D-16's temperature chain and T-3.10's bump detector, behind [CaptureEngine.Environment] so the
 * engine itself stays testable without a phone.
 *
 * Camera2 exposes no standard sensor-temperature key, so the chain is: vendor key if one exists
 * (none on the reference device), **battery temperature** as the dark-matching key, and thermal
 * headroom for pacing. All of them are logged per frame — the one that turns out to correlate
 * with dark current is not known in advance, and a session log that recorded only the one we
 * guessed would be unable to answer the question later.
 *
 * ### The bump detector reads the gyroscope, and the reason is not a preference
 *
 * Only **rotation** moves a star field. Stars are at infinity, so translating the camera — even
 * by a centimetre — shifts the image by nothing at all. An accelerometer cannot tell the two
 * apart: it measures specific force, so a sideways nudge tips the apparent gravity vector
 * exactly as a real tilt does, and at this scale 1° of apparent tilt is only 0.17 m/s².
 *
 * That is not a theoretical objection. Session `2026-08-18_0050`, phone on a tripod extension
 * arm, rejected 49 of 105 frames on an accelerometer-derived tilt — and the frames it flagged
 * hardest were the *sharpest* in the session: frame 8 was called a 7.85° movement, which at the
 * reference camera's 74.2 arcsec/px would be a 382-pixel streak, while carrying 208 detected
 * stars at HFR 0.925. Median HFR was 0.946 on the flagged frames against 1.057 on the rest, so
 * the signal was not merely noisy, it was anti-correlated with the damage it claimed to find.
 * The arm was translating, not rotating, and the accelerometer had no way to know.
 *
 * A gyroscope measures angular rate directly and is blind to translation, which is precisely the
 * discrimination the check needs. Devices without one report null and the check is skipped.
 *
 * ### And it is asked about the exposure, not about "recently"
 *
 * The first gyro version accumulated a peak between reads, which meant the answer covered the
 * readout and the 25 MB DNG write as well as the exposure. Session `2026-08-18_0123` shows what
 * that costs: frames 47-49 were rejected for 19-38° of movement while carrying 93-200 stars at
 * HFR ~1.0 — pixels no such rotation could have left behind. The phone was picked up *between*
 * exposures, and three good frames were thrown away for it.
 *
 * So the buffer keeps a rolling record of cumulative rotation against time, and the engine asks
 * for the peak excursion across exactly the exposure. That the question can be asked at all rests
 * on this device reporting `timestampSource: REALTIME`, which puts `SENSOR_TIMESTAMP` and
 * [SensorEvent.timestamp] on the same clock. Without it there is no common base and the query
 * declines to answer.
 */
class DeviceEnvironment(
    private val context: Context,
    private val clock: Clock = SystemClock,
) : CaptureEngine.Environment, AutoCloseable {

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /**
     * Guards the sample buffer, [cumulative] and [bias], which the sensor thread writes and the
     * capture thread reads. Arrays cannot be made volatile the way a single double could.
     */
    private val lock = Any()

    /**
     * Cumulative rotation since the sensor started, radians, as a small-angle vector.
     *
     * Never reset. Summing rate × dt componentwise ignores that rotations do not commute; at the
     * magnitudes that matter — a degree is 0.017 rad — that error is far below the sensor's own
     * noise, and carrying a quaternion would buy nothing.
     */
    private val cumulative = DoubleArray(3)

    /** Ring buffer of the cumulative vector against the clock, so a past window can be queried. */
    private val sampleNs = LongArray(CAPACITY)
    private val sampleX = DoubleArray(CAPACITY)
    private val sampleY = DoubleArray(CAPACITY)
    private val sampleZ = DoubleArray(CAPACITY)
    private var written = 0L

    /**
     * When the record was last broken by a gap. A window spanning it cannot be answered, because
     * the rotation that happened during the gap was never integrated.
     */
    private var lastBreakNs = Long.MIN_VALUE

    /**
     * Slowly-tracked zero-rate offset, rad/s. Every MEMS gyro reads non-zero while perfectly
     * still, and over a 7.4 s sub even 0.05°/s of offset would integrate to 0.37° of phantom
     * rotation — enough to trip the gate on its own. Subtracting a slow average of the rate
     * removes it. The time constant is long ([BIAS_TAU_SECONDS]) so that a real bump, which is
     * over in well under a second, cannot be absorbed into the definition of "still".
     */
    private val bias = DoubleArray(3)
    private var biasReady = false
    private var settling = false
    private val seedSum = DoubleArray(3)
    private var seedCount = 0
    private var lastEventNs = 0L
    private var settlingUntilNs = 0L

    private var lastHeadroomAtMs = 0L
    private var lastHeadroom: Double? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0].toDouble()
            val y = event.values[1].toDouble()
            val z = event.values[2].toDouble()
            val now = event.timestamp

            synchronized(lock) {
                // The zero-rate estimate is the **mean over the settling window**, not the first
                // sample. Seeding it from one sample assumes the phone is still at the instant
                // the service starts, which is exactly when it is not: the user has just pressed
                // Start. Measured 2026-08-18 — seeded that way, a stationary phone integrated
                // 110° on the first frame and decayed 13° → 7.5° → 6.7° → 6.5° as the estimate
                // crawled toward the truth with a 30 s time constant. Every one of those degrees
                // was phantom.
                if (!settling && !biasReady) {
                    settling = true
                    settlingUntilNs = now + SETTLE_NS
                }
                if (settling) {
                    seedSum[0] += x; seedSum[1] += y; seedSum[2] += z
                    seedCount++
                    if (now < settlingUntilNs) return
                    settling = false
                    bias[0] = seedSum[0] / seedCount
                    bias[1] = seedSum[1] / seedCount
                    bias[2] = seedSum[2] / seedCount
                    biasReady = true
                    lastEventNs = now
                    return
                }

                val dt = (now - lastEventNs) / 1e9
                lastEventNs = now
                // A dropped batch or a suspended sensor is a break in the record, not a long
                // interval to integrate over — treating it as one would invent an enormous
                // rotation, and pretending it did not happen would hide a real one.
                if (dt <= 0.0 || dt > MAX_SAMPLE_GAP_SECONDS) {
                    if (dt > MAX_SAMPLE_GAP_SECONDS) lastBreakNs = now
                    return
                }

                cumulative[0] += (x - bias[0]) * dt
                cumulative[1] += (y - bias[1]) * dt
                cumulative[2] += (z - bias[2]) * dt

                if (biasReady) {
                    val i = (written % CAPACITY).toInt()
                    sampleNs[i] = now
                    sampleX[i] = cumulative[0]
                    sampleY[i] = cumulative[1]
                    sampleZ[i] = cumulative[2]
                    written++
                }

                // Rate-independent smoothing: the gyro is registered at GAME rate but delivery is
                // not guaranteed to be even, and a per-sample weight would make the time constant
                // depend on how fast samples happened to arrive.
                val alpha = exp(-dt / BIAS_TAU_SECONDS)
                bias[0] = bias[0] * alpha + x * (1 - alpha)
                bias[1] = bias[1] * alpha + y * (1 - alpha)
                bias[2] = bias[2] * alpha + z * (1 - alpha)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        gyroscope?.let {
            // An explicit period, not SENSOR_DELAY_GAME. The named constants are hints whose
            // real cadence is the device's business: measured 2026-08-18, GAME delivered fast
            // enough that a 4096-sample buffer spanned under ten seconds, so every query for a
            // 7.4 s exposure — asked after the readout and the 25 MB write — fell off the back
            // of the record and returned "unmeasured". A pinned period makes the buffer's span
            // a number this file controls rather than one it discovers in the field.
            sensors.registerListener(listener, it, SAMPLING_PERIOD_US)
        }
    }

    override fun reading(): ThermalPolicy.Reading = ThermalPolicy.Reading(
        headroom = headroom(),
        batteryTempC = batteryTempC(),
        batteryPercent = batteryPercent(),
    )

    /**
     * Peak rotation during **exactly** `[startNs, endNs]`, degrees, or null when it cannot be
     * answered — no gyroscope, the buffer does not span the window, or the record was broken
     * inside it. Null means "unknown", and [FrameGate] skips the check rather than guessing.
     *
     * Both bounds are on the clock [SensorEvent.timestamp] uses. That they are comparable to the
     * camera's `SENSOR_TIMESTAMP` at all is a property of this device: it reports
     * `timestampSource: REALTIME`, so both are `elapsedRealtimeNanos`. On a device reporting
     * `UNKNOWN` the two clocks share no base, the coverage check fails, and the bump check is
     * skipped — which is the right outcome, since the alternative is comparing two unrelated
     * numbers and believing the result.
     */
    override fun peakRotationDegDuring(startNs: Long, endNs: Long): Double? {
        if (gyroscope == null || endNs <= startNs) return null
        synchronized(lock) {
            val n = minOf(written, CAPACITY.toLong()).toInt()
            if (n < 2) return null
            val oldest = ((written - n) % CAPACITY).toInt()
            val newest = ((written - 1) % CAPACITY).toInt()

            // The window must be wholly inside the record, or the answer would describe a
            // different interval from the one asked about.
            if (sampleNs[oldest] > startNs || sampleNs[newest] < endNs) {
                // Silence here would disable the bump check without anyone noticing, which is
                // how the 4096-sample buffer went unnoticed until a 7.4 s sub was tried.
                Log.w(
                    TAG,
                    "rotation window [%d, %d] outside the record [%d, %d] — check skipped"
                        .format(startNs, endNs, sampleNs[oldest], sampleNs[newest]),
                )
                return null
            }
            if (lastBreakNs in startNs..endNs) return null

            var baseX = 0.0
            var baseY = 0.0
            var baseZ = 0.0
            var haveBase = false
            var peak = 0.0
            for (k in 0 until n) {
                val i = ((oldest + k) % CAPACITY)
                val t = sampleNs[i]
                if (t <= startNs) {
                    baseX = sampleX[i]; baseY = sampleY[i]; baseZ = sampleZ[i]
                    haveBase = true
                    continue
                }
                if (t > endNs) break
                if (!haveBase) return null
                val dx = sampleX[i] - baseX
                val dy = sampleY[i] - baseY
                val dz = sampleZ[i] - baseZ
                val excursion = sqrt(dx * dx + dy * dy + dz * dz)
                if (excursion > peak) peak = excursion
            }
            return Math.toDegrees(peak)
        }
    }

    override fun nowEpochMs(): Long = clock.nowEpochMs()

    /**
     * `getThermalHeadroom` refuses to answer more often than once every ten seconds, returning
     * NaN — so the last answer is cached and reused rather than being read as "no data".
     */
    private fun headroom(): Double? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val now = System.currentTimeMillis()
        if (now - lastHeadroomAtMs < HEADROOM_INTERVAL_MS) return lastHeadroom
        lastHeadroomAtMs = now
        val value = runCatching { power.getThermalHeadroom(HEADROOM_FORECAST_SECONDS) }.getOrNull()
        if (value != null && !value.isNaN()) lastHeadroom = value.toDouble()
        return lastHeadroom
    }

    private fun batteryStatus(): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    /** `ACTION_BATTERY_CHANGED` reports tenths of a degree. */
    private fun batteryTempC(): Double? =
        batteryStatus()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10.0 }

    private fun batteryPercent(): Int? {
        val status = batteryStatus() ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100f / scale).toInt()
    }

    override fun close() {
        runCatching { sensors.unregisterListener(listener) }
    }

    private companion object {
        /** Time constant of the zero-rate estimate. Long against a bump, short against drift. */
        const val BIAS_TAU_SECONDS = 30.0

        /** 100 Hz. A bump lasts tens of milliseconds; this resolves one in several samples. */
        const val SAMPLING_PERIOD_US = 10_000

        /**
         * Samples held: 8192 at 100 Hz is **82 s**.
         *
         * The span has to cover the whole distance from the *start* of an exposure to the moment
         * the frame is finally analysed — the sub itself, plus readout, plus a 25 MB DNG write.
         * At the 30 s ceiling that is comfortably under a minute, and the margin is cheap:
         * 8192 samples is 262 KB.
         */
        const val CAPACITY = 8192

        /** How long the bias estimate is given to converge before its output is trusted. */
        const val SETTLE_NS = 3_000_000_000L

        /**
         * Samples further apart than this are treated as a break in the record rather than a
         * long interval to integrate over — the phone suspending the sensor for a second would
         * otherwise appear as one enormous rotation.
         */
        const val MAX_SAMPLE_GAP_SECONDS = 0.5

        const val TAG = "DeviceEnvironment"

        const val HEADROOM_INTERVAL_MS = 10_000L
        const val HEADROOM_FORECAST_SECONDS = 30
    }
}
