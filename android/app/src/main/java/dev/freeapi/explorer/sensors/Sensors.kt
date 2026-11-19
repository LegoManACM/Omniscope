package dev.freeapi.explorer.sensors

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
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.atan2

data class Fix(val lat: Double, val lon: Double, val altM: Double?, val accuracyM: Float, val timeMs: Long, val provider: String)

/** Platform LocationManager (GPS + network): no Google Play Services. */
class LocationSource(private val context: Context) : LocationListener {
    private val lm = context.getSystemService(LocationManager::class.java)
    val fix = MutableStateFlow<Fix?>(null)

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Who wants location ("ui", "mcp"): updates run while anyone holds it. */
    private val holders = mutableSetOf<String>()

    @Synchronized fun acquire(who: String) { holders += who; start() }
    @Synchronized fun release(who: String) { holders -= who; if (holders.isEmpty()) stop() }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (!hasPermission()) return
        lm.removeUpdates(this)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { onLocationChanged(it) }
        for (p in providers) runCatching { lm.requestLocationUpdates(p, 2_000L, 1f, this, Looper.getMainLooper()) }
    }

    private fun stop() = lm.removeUpdates(this)

    override fun onLocationChanged(l: Location) {
        val cur = fix.value
        // Prefer accurate fixes; take a worse one only when the current one is getting old.
        if (cur != null && l.accuracy > cur.accuracyM * 1.5f && l.time - cur.timeMs < 15_000) return
        val alt = if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) l.mslAltitudeMeters
        else if (l.hasAltitude()) l.altitude else null
        fix.value = Fix(l.latitude, l.longitude, alt, l.accuracy, l.time, l.provider ?: "?")
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}

/**
 * Phone orientation from the platform's fused rotation-vector sensor (gyro + accelerometer + magnetometer).
 * [rotation] is the row-major matrix R with world = R · device, where world is East, magnetic North, Up.
 */
class OrientationSource(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val available = sensor != null
    val rotation = MutableStateFlow<FloatArray?>(null)
    /** The sensor's own heading-accuracy estimate in degrees, if it reports one. */
    val headingAccuracyDeg = MutableStateFlow<Float?>(null)
    val calibration = MutableStateFlow(SensorManager.SENSOR_STATUS_UNRELIABLE)

    private val raw = FloatArray(9)
    private var smooth: FloatArray? = null

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm.unregisterListener(this); smooth = null }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(raw, e.values)
        val s = smooth ?: raw.copyOf().also { smooth = it }
        for (i in 0 until 9) s[i] += 0.25f * (raw[i] - s[i]) // light low-pass against jitter
        rotation.value = s.copyOf()
        if (e.values.size > 4 && e.values[4] > 0f) headingAccuracyDeg.value = Math.toDegrees(e.values[4].toDouble()).toFloat()
    }

    override fun onAccuracyChanged(s: Sensor, accuracy: Int) { calibration.value = accuracy }

    companion object {
        /** Magnetic heading of the back camera's view direction (device −Z), degrees. */
        fun cameraMagneticHeading(r: FloatArray): Double = (Math.toDegrees(atan2(-r[2].toDouble(), -r[5].toDouble())) + 360) % 360

        /** Declination (true minus magnetic) from the World Magnetic Model built into Android: offline, no API. */
        fun declination(fix: Fix): Float =
            GeomagneticField(fix.lat.toFloat(), fix.lon.toFloat(), (fix.altM ?: 0.0).toFloat(), fix.timeMs).declination
    }
}
