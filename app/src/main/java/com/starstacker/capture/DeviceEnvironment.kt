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
 */
class DeviceEnvironment(private val context: Context) : CaptureEngine.Environment, AutoCloseable {

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /**
     * Guards [accumulated], [bias] and [peakRad], which the sensor thread writes and the capture
     * thread reads. A three-element array cannot be made volatile the way a single double could.
     */
    private val lock = Any()

    /**
     * Rotation accumulated since the last [consumePeakTiltDeg], radians, as a small-angle vector.
     *
     * Summing rate × dt componentwise ignores that rotations do not commute. At the magnitudes
     * that matter here — a degree is 0.017 rad — the error from that is far below the sensor's
     * own noise, and the alternative (carrying a quaternion) would buy nothing.
     */
    private val accumulated = DoubleArray(3)

    /** Largest excursion from the frame's starting orientation, radians. */
    private var peakRad = 0.0

    /**
     * Slowly-tracked zero-rate offset, rad/s. Every MEMS gyro reads non-zero while perfectly
     * still, and over a 7.4 s sub even 0.05°/s of offset would integrate to 0.37° of phantom
     * rotation — enough to trip the gate on its own. Subtracting a slow average of the rate
     * removes it. The time constant is long ([BIAS_TAU_SECONDS]) so that a real bump, which is
     * over in well under a second, cannot be absorbed into the definition of "still".
     */
    private val bias = DoubleArray(3)
    private var biasReady = false
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
                if (!biasReady) {
                    bias[0] = x; bias[1] = y; bias[2] = z
                    biasReady = true
                    lastEventNs = now
                    settlingUntilNs = now + SETTLE_NS
                    return
                }

                val dt = (now - lastEventNs) / 1e9
                lastEventNs = now
                // A dropped batch or a clock step would otherwise integrate as a huge rotation.
                if (dt <= 0.0 || dt > MAX_SAMPLE_GAP_SECONDS) return

                val rx = x - bias[0]
                val ry = y - bias[1]
                val rz = z - bias[2]

                accumulated[0] += rx * dt
                accumulated[1] += ry * dt
                accumulated[2] += rz * dt
                val excursion = sqrt(
                    accumulated[0] * accumulated[0] +
                        accumulated[1] * accumulated[1] +
                        accumulated[2] * accumulated[2],
                )
                if (excursion > peakRad) peakRad = excursion

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
            sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun reading(): ThermalPolicy.Reading = ThermalPolicy.Reading(
        headroom = headroom(),
        batteryTempC = batteryTempC(),
        batteryPercent = batteryPercent(),
    )

    /**
     * Peak rotation during the frame, degrees, and null when it was not measured — no gyroscope,
     * or the bias estimate has not settled yet. Null means "unknown", and [FrameGate] skips the
     * check rather than guessing; a check that cannot be made is better skipped than faked.
     */
    override fun consumePeakTiltDeg(): Double? {
        if (gyroscope == null) return null
        synchronized(lock) {
            if (!biasReady || lastEventNs < settlingUntilNs) return null
            val peak = Math.toDegrees(peakRad)
            peakRad = 0.0
            accumulated[0] = 0.0; accumulated[1] = 0.0; accumulated[2] = 0.0
            return peak
        }
    }

    override fun nowEpochMs(): Long = System.currentTimeMillis()

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

        /** How long the bias estimate is given to converge before its output is trusted. */
        const val SETTLE_NS = 3_000_000_000L

        /**
         * Samples further apart than this are treated as a break in the record rather than a
         * long interval to integrate over — the phone suspending the sensor for a second would
         * otherwise appear as one enormous rotation.
         */
        const val MAX_SAMPLE_GAP_SECONDS = 0.5

        const val HEADROOM_INTERVAL_MS = 10_000L
        const val HEADROOM_FORECAST_SECONDS = 30
    }
}
