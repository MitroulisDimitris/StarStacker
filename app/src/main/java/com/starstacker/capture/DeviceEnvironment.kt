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
import kotlin.math.acos
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
 */
class DeviceEnvironment(private val context: Context) : CaptureEngine.Environment, AutoCloseable {

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Peak angular deviation of the gravity vector during the frame, degrees. */
    @Volatile
    private var peakTiltDeg = 0.0

    /**
     * The smoothed gravity direction. **A direction, not a magnitude** — and that distinction is
     * the whole measurement.
     *
     * Rotating a phone barely changes `|a|`: gravity is still 9.81 m/s² whichever way the phone
     * faces. So a check on the magnitude is nearly blind to tilt, which is precisely the motion
     * that moves the star field, while being sensitive to linear shake, which largely does not.
     * Measured 2026-08-17: a phone lying still on a desk produced magnitude deviations of
     * 0.4 m/s², enough to trip a magnitude threshold on frame after frame while the phone had not
     * moved at all. The angle between the current gravity vector and the smoothed one is the
     * quantity that actually corresponds to the field moving.
     */
    private var steadyGravity: DoubleArray? = null

    private var lastHeadroomAtMs = 0L
    private var lastHeadroom: Double? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0].toDouble()
            val y = event.values[1].toDouble()
            val z = event.values[2].toDouble()
            val magnitude = sqrt(x * x + y * y + z * z)
            if (magnitude < 1e-3) return

            val steady = steadyGravity
            if (steady == null) {
                steadyGravity = doubleArrayOf(x, y, z)
                return
            }

            val steadyMagnitude = sqrt(
                steady[0] * steady[0] + steady[1] * steady[1] + steady[2] * steady[2],
            )
            if (steadyMagnitude > 1e-3) {
                val cosine = (x * steady[0] + y * steady[1] + z * steady[2]) /
                    (magnitude * steadyMagnitude)
                val tilt = Math.toDegrees(acos(cosine.coerceIn(-1.0, 1.0)))
                if (tilt > peakTiltDeg) peakTiltDeg = tilt
            }

            // Track slowly, so a genuine bump stands out but a tripod settling over a minute
            // does not become the new definition of "still".
            steady[0] = steady[0] * SMOOTHING + x * (1 - SMOOTHING)
            steady[1] = steady[1] * SMOOTHING + y * (1 - SMOOTHING)
            steady[2] = steady[2] * SMOOTHING + z * (1 - SMOOTHING)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        accelerometer?.let {
            sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun reading(): ThermalPolicy.Reading = ThermalPolicy.Reading(
        headroom = headroom(),
        batteryTempC = batteryTempC(),
        batteryPercent = batteryPercent(),
    )

    override fun consumePeakTiltDeg(): Double? {
        if (accelerometer == null) return null
        val peak = peakTiltDeg
        peakTiltDeg = 0.0
        return peak
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
        /** Per-sample weight on the existing estimate. At ~5 Hz this is a few seconds of memory. */
        const val SMOOTHING = 0.98

        const val HEADROOM_INTERVAL_MS = 10_000L
        const val HEADROOM_FORECAST_SECONDS = 30
    }
}
