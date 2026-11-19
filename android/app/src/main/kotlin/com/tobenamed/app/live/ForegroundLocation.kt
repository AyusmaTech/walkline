package com.tobenamed.app.live

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
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.model.LocationSample

/** Activity-owned; start/stop follow STARTED lifecycle. No background location service. */
class ForegroundLocation(
    private val context: Context,
    private val rotation: () -> Int,
    private val onFix: (LocationSample, Boolean) -> Unit,
    private val onProblem: (String) -> Unit,
    private val onFacing: (DeviceFacing?) -> Unit = {},
) : SensorEventListener {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var running = false
    private var magneticHeading: Double? = null
    private var headingTimeNs = 0L
    private var headingReliable = true
    private var declination: Double? = null
    private var lastFacingEmissionMs = 0L
    private val facingFilter = FacingFilter()
    private val isEmulator = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!running) return
            for (location in result.locations) convert(location)?.let { sample ->
                @Suppress("DEPRECATION")
                val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
                onFix(sample, mock || isEmulator)
            }
        }
        override fun onLocationAvailability(availability: LocationAvailability) {
            if (running && !availability.isLocationAvailable) onProblem("Waiting for GPS. Move outdoors or check device location settings.")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!hasLocationPermission(context)) { onProblem("Location permission is required. Enable it in app settings."); return }
        if (!LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))) {
            onProblem("Device location is off. Turn it on in location settings."); return
        }
        running = true
        magneticHeading = null
        headingTimeNs = 0
        headingReliable = true; declination = null; lastFacingEmissionMs = 0; facingFilter.reset()
        (sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR))?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMinUpdateIntervalMillis(500).setMaxUpdateDelayMillis(0).setMaxUpdateAgeMillis(0).build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener {
                    if (running) { stop(); onProblem("Could not start location updates. Check permission and Google Play services.") }
                }.addOnSuccessListener { if (!running) client.removeLocationUpdates(callback) }
        } catch (_: SecurityException) { stop(); onProblem("Location permission was revoked. Enable it in app settings.") }
    }

    fun stop() {
        running = false
        client.removeLocationUpdates(callback)
        sensors.unregisterListener(this)
        magneticHeading = null
        declination = null; facingFilter.reset(); onFacing(null)
    }

    private fun convert(location: Location): LocationSample? {
        if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy < 0) return null
        if (!location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return null
        val time = location.elapsedRealtimeNanos / 1_000_000
        if (SystemClock.elapsedRealtime() - time > 15_000) return null
        declination = GeomagneticField(location.latitude.toFloat(), location.longitude.toFloat(),
            location.altitude.takeIf { it.isFinite() }?.toFloat() ?: 0f, location.time).declination.toDouble()
        val heading = trueHeading()
        publishFacing()
        val course = location.bearing.toDouble().takeIf {
            location.hasBearing() && location.hasSpeed() && location.speed >= 0.7f && it.isFinite() &&
                (!location.hasBearingAccuracy() || location.bearingAccuracyDegrees <= 45)
        }
        return runCatching { LocationSample(time, LatLng(location.latitude, location.longitude),
            location.accuracy.toDouble(), courseDeg = course, headingDeg = heading) }.getOrNull()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR && event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) return
        val estimatedError = event.values.getOrNull(4)
        if (!headingReliable || event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            estimatedError?.let { it >= 0 && it > Math.toRadians(45.0) } == true) {
            magneticHeading = null; facingFilter.reset(); onFacing(null); return
        }
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        magneticHeading = FacingDirection.magneticBearing(matrix, rotation())
        headingTimeNs = event.timestamp
        publishFacing()
    }
    private fun trueHeading(): Double? = magneticHeading?.takeIf {
        headingReliable && SystemClock.elapsedRealtimeNanos() - headingTimeNs in 0..3_000_000_000L
    }?.let { magnetic -> declination?.let { Geo.normalizeBearing(magnetic + it) } }

    private fun publishFacing() {
        val heading = trueHeading() ?: return
        val sample = facingFilter.update(heading, headingTimeNs / 1_000_000) ?: return
        if (sample.timeMs - lastFacingEmissionMs < 80) return
        lastFacingEmissionMs = sample.timeMs
        onFacing(sample)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        headingReliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
        if (!headingReliable) { magneticHeading = null; facingFilter.reset(); onFacing(null) }
    }

    companion object {
        fun hasLocationPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
