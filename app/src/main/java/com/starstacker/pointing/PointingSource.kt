package com.starstacker.pointing

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** How much the compass can be trusted. Anything below [OK] and the azimuth is decorative. */
enum class CompassAccuracy { UNRELIABLE, LOW, MEDIUM, HIGH, UNKNOWN }

/**
 * Where the camera is pointing, and everything that follows from it (T-2.6).
 *
 * The nulls are meaningful. Without a location fix there is no latitude, and without latitude
 * there is no declination and no field-rotation rate — so the exposure engine falls back and the
 * UI says so (FR-3.1's "denial is survivable"), rather than quietly using a wrong number.
 */
data class PointingFix(
    val altitudeDeg: Double,
    val azimuthMagneticDeg: Double,
    /**
     * T-4.1 — how far the **device** is rolled about its optical axis, relative to "up on the sky",
     * anticlockwise as seen looking out along the lens. Null when it cannot be defined.
     *
     * The other two angles say where the camera points; this says which way up it is, and
     * [com.starstacker.registration.SkyDrift] needs all three: pointing decides how fast the sky
     * drifts and in which direction on the horizon, roll decides where that direction lands in the
     * picture. Without it the seed knows the size of the shift and not its sign.
     *
     * **Device roll, not image roll.** The sensor is usually mounted at 90° to the phone's long
     * axis (`SENSOR_ORIENTATION`), so turning this into a rotation of the *image* means adding that
     * per-camera constant. It is not folded in here because this class describes the phone, not a
     * camera — and the same fix serves cameras with different mounts.
     */
    val cameraRollDeg: Double?,
    val magneticDeclinationDeg: Double?,
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    val accuracy: CompassAccuracy,
    val epochMillis: Long,
) {
    /** Azimuth from *true* north — the one the sky is measured in. */
    val azimuthTrueDeg: Double?
        get() = magneticDeclinationDeg?.let { Astro.normaliseDegrees(azimuthMagneticDeg + it) }

    /** Declination at the field centre — the input to the dec-corrected trailing limit (FR-5.1). */
    val declinationDeg: Double?
        get() {
            val az = azimuthTrueDeg ?: return null
            val lat = latitudeDeg ?: return null
            return Astro.declinationDeg(altitudeDeg, az, lat)
        }

    val hourAngleDeg: Double?
        get() {
            val az = azimuthTrueDeg ?: return null
            val lat = latitudeDeg ?: return null
            return Astro.hourAngleDeg(altitudeDeg, az, lat)
        }

    val localSiderealTimeHours: Double?
        get() = longitudeDeg?.let { Astro.localSiderealTimeHours(epochMillis, it) }

    val rightAscensionHours: Double?
        get() {
            val az = azimuthTrueDeg ?: return null
            val lat = latitudeDeg ?: return null
            val lst = localSiderealTimeHours ?: return null
            return Astro.rightAscensionHours(altitudeDeg, az, lat, lst)
        }

    /** §7.1 — how fast the frame turns on an alt-az mount, arcsec/s. */
    val fieldRotationArcsecPerSec: Double?
        get() {
            val az = azimuthTrueDeg ?: return null
            val lat = latitudeDeg ?: return null
            return Astro.fieldRotationArcsecPerSec(altitudeDeg, az, lat)
        }

    val nearZenith: Boolean get() = Astro.divergesNearZenith(altitudeDeg)

    val hasLocation: Boolean get() = latitudeDeg != null && longitudeDeg != null
}

/**
 * Accelerometer + magnetometer + last known location, smoothed, as a flow of [PointingFix].
 *
 * The smoothing is on the *direction vector*, not on the azimuth angle: a phone pointing just
 * east of north produces azimuth readings that hop between 359° and 1°, and averaging those
 * gives 180° — pointing due south. Filtering the vector and deriving the angle afterwards makes
 * the wrap-around impossible rather than special-cased.
 */
class PointingSource(private val context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasRequiredSensors(): Boolean =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
            sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null

    fun fixes(minIntervalMs: Long = 100L): Flow<PointingFix> = callbackFlow {
        val manager = sensorManager
        val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (manager == null || accelerometer == null || magnetometer == null) {
            close(IllegalStateException("this device has no accelerometer or magnetometer"))
            return@callbackFlow
        }

        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var haveGravity = false
        var haveField = false
        val rotation = FloatArray(9)
        val inclination = FloatArray(9)
        val direction = DoubleArray(3)
        var haveDirection = false
        var accuracy = CompassAccuracy.UNKNOWN
        var lastEmit = 0L

        var location: Location? = lastKnownLocation()

        fun emitFix() {
            val now = System.currentTimeMillis()
            if (now - lastEmit < minIntervalMs) return
            if (!haveGravity || !haveField) return
            if (!SensorManager.getRotationMatrix(rotation, inclination, gravity, geomagnetic)) return

            // The rear camera looks along the device's -Z axis; the rotation matrix maps device
            // coordinates into the world frame (X east, Y north, Z up).
            val x = -rotation[2].toDouble()
            val y = -rotation[5].toDouble()
            val z = -rotation[8].toDouble()
            val length = sqrt(x * x + y * y + z * z)
            if (length <= 0.0) return

            if (haveDirection) {
                direction[0] += DIRECTION_ALPHA * (x / length - direction[0])
                direction[1] += DIRECTION_ALPHA * (y / length - direction[1])
                direction[2] += DIRECTION_ALPHA * (z / length - direction[2])
            } else {
                direction[0] = x / length
                direction[1] = y / length
                direction[2] = z / length
                haveDirection = true
            }

            val norm = sqrt(
                direction[0] * direction[0] + direction[1] * direction[1] +
                    direction[2] * direction[2],
            )
            if (norm <= 0.0) return

            val altitude = Math.toDegrees(asin((direction[2] / norm).coerceIn(-1.0, 1.0)))
            val azimuth = Astro.normaliseDegrees(
                Math.toDegrees(atan2(direction[0], direction[1])),
            )
            val roll = CameraRoll.degrees(
                rotation, direction[0] / norm, direction[1] / norm, direction[2] / norm,
            )

            val fix = location
            val declination = fix?.let {
                GeomagneticField(
                    it.latitude.toFloat(),
                    it.longitude.toFloat(),
                    it.altitude.toFloat(),
                    now,
                ).declination.toDouble()
            }

            lastEmit = now
            trySend(
                PointingFix(
                    altitudeDeg = altitude,
                    azimuthMagneticDeg = azimuth,
                    cameraRollDeg = roll,
                    magneticDeclinationDeg = declination,
                    latitudeDeg = fix?.latitude,
                    longitudeDeg = fix?.longitude,
                    accuracy = accuracy,
                    epochMillis = now,
                ),
            )
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        lowPass(event.values, gravity, haveGravity)
                        haveGravity = true
                    }

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        lowPass(event.values, geomagnetic, haveField)
                        haveField = true
                    }
                }
                emitFix()
            }

            override fun onAccuracyChanged(sensor: Sensor, value: Int) {
                if (sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
                accuracy = when (value) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassAccuracy.HIGH
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassAccuracy.MEDIUM
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassAccuracy.LOW
                    SensorManager.SENSOR_STATUS_UNRELIABLE -> CompassAccuracy.UNRELIABLE
                    else -> CompassAccuracy.UNKNOWN
                }
            }
        }

        manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        manager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI)

        // One location request, not a continuous stream: latitude changes the field-rotation
        // rate in the fourth decimal place over a night, and GPS is not free.
        val locationListener = android.location.LocationListener { updated ->
            location = updated
        }
        val provider = requestLocation(locationListener)

        awaitClose {
            manager.unregisterListener(listener)
            if (provider != null) {
                runCatching { locationManager?.removeUpdates(locationListener) }
            }
        }
    }.conflate()

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = locationManager ?: return null
        return LOCATION_PROVIDERS
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation(listener: android.location.LocationListener): String? {
        if (!hasLocationPermission()) return null
        val manager = locationManager ?: return null
        for (provider in LOCATION_PROVIDERS) {
            val available = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            if (!available) continue
            val started = runCatching {
                manager.requestLocationUpdates(
                    provider,
                    LOCATION_INTERVAL_MS,
                    LOCATION_MIN_METRES,
                    listener,
                )
            }
            if (started.isSuccess) return provider
            Log.i(TAG, "location provider $provider refused: ${started.exceptionOrNull()?.message}")
        }
        return null
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun lowPass(source: FloatArray, target: FloatArray, initialised: Boolean) {
        if (!initialised) {
            System.arraycopy(source, 0, target, 0, minOf(source.size, target.size))
            return
        }
        for (i in target.indices) {
            target[i] += SENSOR_ALPHA * (source[i] - target[i])
        }
    }

    private companion object {
        const val TAG = "PointingSource"
        const val SENSOR_ALPHA = 0.15f
        const val DIRECTION_ALPHA = 0.20
        const val LOCATION_INTERVAL_MS = 60_000L
        const val LOCATION_MIN_METRES = 100f
        val LOCATION_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}
