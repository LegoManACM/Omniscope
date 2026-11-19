package dev.freeapi.explorer.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A direction from the observer: East-North-Up unit vector plus straight-line distance. */
data class Enu(val e: Double, val n: Double, val u: Double, val distM: Double) {
    val bearingDeg: Double get() = (Math.toDegrees(atan2(e, n)) + 360) % 360
    val elevationDeg: Double get() = Math.toDegrees(asin(u.coerceIn(-1.0, 1.0)))
}

object Geo {
    private const val R_MEAN = 6_371_008.8
    private const val A = 6_378_137.0 // WGS84
    private const val E2 = 6.69437999014e-3

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * R_MEAN * asin(sqrt(h))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun ecef(lat: Double, lon: Double, h: Double): DoubleArray {
        val p = Math.toRadians(lat); val l = Math.toRadians(lon)
        val n = A / sqrt(1 - E2 * sin(p) * sin(p))
        return doubleArrayOf((n + h) * cos(p) * cos(l), (n + h) * cos(p) * sin(l), (n * (1 - E2) + h) * sin(p))
    }

    /**
     * Direction from observer to target via ECEF, so Earth's curvature and altitude are handled exactly:
     * a peak 40 km away dips below level, and the ISS at 420 km shows up high in the sky.
     */
    fun enu(oLat: Double, oLon: Double, oAlt: Double, tLat: Double, tLon: Double, tAlt: Double): Enu {
        val o = ecef(oLat, oLon, oAlt); val t = ecef(tLat, tLon, tAlt)
        val dx = t[0] - o[0]; val dy = t[1] - o[1]; val dz = t[2] - o[2]
        val p = Math.toRadians(oLat); val l = Math.toRadians(oLon)
        val e = -sin(l) * dx + cos(l) * dy
        val n = -sin(p) * cos(l) * dx - sin(p) * sin(l) * dy + cos(p) * dz
        val u = cos(p) * cos(l) * dx + cos(p) * sin(l) * dy + sin(p) * dz
        val d = sqrt(e * e + n * n + u * u).coerceAtLeast(1e-6)
        return Enu(e / d, n / d, u / d, d)
    }

    fun fromSky(s: SkyPos): Enu {
        val az = Math.toRadians(s.azDeg); val el = Math.toRadians(s.elDeg)
        return Enu(cos(el) * sin(az), cos(el) * cos(az), sin(el), Double.POSITIVE_INFINITY)
    }

    fun round4(v: Double): Double = (v * 10_000).roundToLong() / 10_000.0

    fun fmtDistance(m: Double?): String = when {
        m == null || m.isInfinite() -> ""
        m < 1_000 -> "${m.toInt()} m"
        m < 100_000 -> "%.1f km".format(m / 1_000)
        else -> "%,d km".format((m / 1_000).toInt())
    }

    private val compass = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    fun compassPoint(deg: Double): String = compass[(((deg % 360) + 360) % 360 / 45 + 0.5).toInt() % 8]
}
