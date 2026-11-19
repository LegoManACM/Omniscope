package dev.freeapi.explorer.core

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low-precision Sun and Moon positions, computed on the phone (Sun ~0.01°, Moon ~0.3°): good enough to use as
 * AR calibration targets. Formulas from the Astronomical Almanac's low-precision series.
 */
object Astro {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun daysSinceJ2000(ms: Long) = ms / 86_400_000.0 + 2_440_587.5 - 2_451_545.0

    private fun sunLongitude(d: Double): Double {
        val g = rad(357.529 + 0.98560028 * d)
        return 280.459 + 0.98564736 * d + 1.915 * sin(g) + 0.020 * sin(2 * g)
    }

    fun sun(ms: Long, lat: Double, lon: Double): SkyPos {
        val d = daysSinceJ2000(ms)
        val l = rad(sunLongitude(d))
        val e = rad(23.439 - 0.00000036 * d)
        return azEl(atan2(cos(e) * sin(l), cos(l)), asin(sin(e) * sin(l)), d, lat, lon)
    }

    data class Moon(val pos: SkyPos, val illumination: Double, val waxing: Boolean)

    fun moon(ms: Long, lat: Double, lon: Double): Moon {
        val d = daysSinceJ2000(ms)
        val t = d / 36_525.0
        fun s(a: Double) = sin(rad(a))
        fun c(a: Double) = cos(rad(a))
        val lam = 218.32 + 481267.881 * t + 6.29 * s(135.0 + 477198.87 * t) - 1.27 * s(259.3 - 413335.36 * t) +
            0.66 * s(235.7 + 890534.22 * t) + 0.21 * s(269.9 + 954397.74 * t) - 0.19 * s(357.5 + 35999.05 * t) -
            0.11 * s(186.5 + 966404.03 * t)
        val bet = 5.13 * s(93.3 + 483202.02 * t) + 0.28 * s(228.2 + 960400.89 * t) - 0.28 * s(318.3 + 6003.15 * t) -
            0.17 * s(217.6 - 407332.21 * t)
        val hp = 0.9508 + 0.0518 * c(135.0 + 477198.87 * t) + 0.0095 * c(259.3 - 413335.36 * t) +
            0.0078 * c(235.7 + 890534.22 * t) + 0.0028 * c(269.9 + 954397.74 * t)
        val l = c(bet) * c(lam)
        val m = 0.9175 * c(bet) * s(lam) - 0.3978 * s(bet)
        val n = 0.3978 * c(bet) * s(lam) + 0.9175 * s(bet)
        val geo = azEl(atan2(m, l), asin(n), d, lat, lon)
        val topoEl = geo.elDeg - hp * cos(rad(geo.elDeg)) // parallax: the Moon is close enough to matter
        val sunLam = sunLongitude(d)
        val elongation = acos((c(bet) * c(lam - sunLam)).coerceIn(-1.0, 1.0))
        val illum = (1 - cos(elongation)) / 2
        val waxing = (((lam - sunLam) % 360) + 360) % 360 < 180
        return Moon(SkyPos(geo.azDeg, topoEl), illum, waxing)
    }

    private fun azEl(ra: Double, dec: Double, d: Double, lat: Double, lon: Double): SkyPos {
        val gmst = 280.46061837 + 360.98564736629 * d
        val h = rad(gmst + lon) - ra
        val phi = rad(lat)
        val el = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))
        val az = atan2(-sin(h) * cos(dec), sin(dec) * cos(phi) - cos(dec) * sin(phi) * cos(h))
        return SkyPos((deg(az) + 360) % 360, deg(el))
    }
}
