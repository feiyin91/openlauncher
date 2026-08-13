package com.openlauncher.app.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speedMps: Float = 0f
)

private const val TWO_MINUTES_MS = 2 * 60 * 1000L

class LocationCompassManager(context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager   = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _location  = MutableStateFlow<LocationData?>(null)
    private val _bearing   = MutableStateFlow(0f)
    val location: StateFlow<LocationData?> = _location
    val bearing: StateFlow<Float> = _bearing

    private val gravity      = FloatArray(3)
    private val geomagnetic  = FloatArray(3)
    // Circular low-pass filter for smooth bearing (avoids 0°/360° wrap artifacts)
    private var bearingSin   = 0f
    private var bearingCos   = 1f   // initial: pointing north
    private var lastLocationForBearing: Location? = null
    // Both GPS and Network providers are registered at once (see start()), and
    // Network fixes (cell/WiFi) can be hundreds of meters coarser than GPS —
    // without gating, a coarse Network update stomps over a good GPS fix and
    // the displayed position visibly jumps between neighbourhoods every time
    // the two providers alternate. Track the best-accepted fix instead of
    // blindly taking whichever callback fires most recently.
    private var bestLocation: Location? = null

    private fun isBetterLocation(location: Location, current: Location?): Boolean {
        if (current == null) return true
        val timeDeltaMs = location.time - current.time
        val isSignificantlyNewer = timeDeltaMs > TWO_MINUTES_MS
        val isSignificantlyOlder = timeDeltaMs < -TWO_MINUTES_MS
        if (isSignificantlyNewer) return true
        if (isSignificantlyOlder) return false

        val isNewer = timeDeltaMs > 0
        val accuracyDelta = location.accuracy - current.accuracy
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 200f
        val isFromSameProvider = location.provider == current.provider

        return when {
            isMoreAccurate -> true
            isNewer && accuracyDelta <= 0 -> true
            isNewer && !isSignificantlyLessAccurate && isFromSameProvider -> true
            else -> false
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                }
            }
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val azimuthRad = orientation[0].toDouble()
                // Circular low-pass filter — correctly handles 0°/360° wrap-around
                val alpha = 0.10f
                bearingSin = alpha * sin(azimuthRad).toFloat() + (1f - alpha) * bearingSin
                bearingCos = alpha * cos(azimuthRad).toFloat() + (1f - alpha) * bearingCos
                _bearing.value = ((Math.toDegrees(atan2(bearingSin.toDouble(), bearingCos.toDouble())) + 360) % 360).toFloat()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (!isBetterLocation(loc, bestLocation)) return
            bestLocation = loc

            _location.value = LocationData(
                latitude  = loc.latitude,
                longitude = loc.longitude,
                altitude  = loc.altitude,
                accuracy  = loc.accuracy,
                speedMps  = if (loc.hasSpeed()) loc.speed else 0f
            )

            // 1. If GPS has a hardware-computed bearing, use it (works offline)
            if (loc.hasBearing() && loc.bearing != 0f) {
                _bearing.value = loc.bearing
            } else {
                // 2. Math fallback: Calculate bearing between consecutive location points (works offline & sensor-less!)
                val lastLoc = lastLocationForBearing
                if (lastLoc != null) {
                    val distance = lastLoc.distanceTo(loc)
                    // Ensure the distance is enough to overcome GPS jitter (e.g. 3 meters)
                    if (distance > 3f) {
                        val computedBearing = lastLoc.bearingTo(loc)
                        // Normalize bearing to 0-360
                        _bearing.value = (computedBearing + 360f) % 360f
                        lastLocationForBearing = loc
                    }
                } else {
                    lastLocationForBearing = loc
                }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        // Must be overridden explicitly: the interface only gained default
        // implementations in API 30, so omitting them throws AbstractMethodError
        // on older head units when the GPS provider is toggled.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun start() {
        // Sensors
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Location — Robust offline-first registration
        // GPS Provider (Works 100% offline, sat-based)
        try {
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 3000L, 5f, locationListener
                )
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    locationListener.onLocationChanged(it)
                }
            }
        } catch (_: Exception) {}

        // Network Provider (Works online, cell/wifi-based)
        try {
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 5000L, 10f, locationListener
                )
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                    locationListener.onLocationChanged(it)
                }
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        sensorManager.unregisterListener(sensorListener)
        locationManager.removeUpdates(locationListener)
        lastLocationForBearing = null
        bestLocation = null
    }
}
