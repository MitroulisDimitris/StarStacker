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
import kotlin.math.abs
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

    @Volatile
    private var peakDeviation = 0.0

    /**
     * Deviation from the *steady* magnitude rather than from 9.81: the phone may be on a slope,
     * and what matters is that it moved, not which way it is pointing.
     */
    @Volatile
    private var steadyMagnitude: Double? = null

    private var lastHeadroomAtMs = 0L
    private var lastHeadroom: Double? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val magnitude = sqrt(
                (event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]).toDouble(),
            )
            val steady = steadyMagnitude
            if (steady == null) {
                steadyMagnitude = magnitude
                return
            }
            val deviation = abs(magnitude - steady)
            if (deviation > peakDeviation) peakDeviation = deviation
            // Track slowly, so a genuine bump stands out but a tripod settling does not become
            // the new definition of "moved".
            steadyMagnitude = steady * 0.98 + magnitude * 0.02
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

    override fun consumePeakAcceleration(): Double? {
        if (accelerometer == null) return null
        val peak = peakDeviation
        peakDeviation = 0.0
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
        const val HEADROOM_INTERVAL_MS = 10_000L
        const val HEADROOM_FORECAST_SECONDS = 30
    }
}
