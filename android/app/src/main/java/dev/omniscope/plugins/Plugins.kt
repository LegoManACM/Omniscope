package dev.omniscope.plugins

import dev.omniscope.R
import dev.omniscope.core.Astro
import dev.omniscope.core.Category
import dev.omniscope.core.Cost
import dev.omniscope.core.Ctx
import dev.omniscope.core.Freshness
import dev.omniscope.core.Geo
import dev.omniscope.core.Item
import dev.omniscope.core.KeySpec
import dev.omniscope.core.Plugin
import dev.omniscope.core.PluginEnv
import dev.omniscope.core.RadiusSpec
import dev.omniscope.core.SkyPos
import dev.omniscope.core.arr
import dev.omniscope.core.enc
import dev.omniscope.core.num
import dev.omniscope.core.obj
import dev.omniscope.core.objects
import dev.omniscope.core.str
import dev.omniscope.scan.allDetectors
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Plugins whose behaviour is code in the app. Their manifests (plugins/official/<id>/plugin.json, `"native": "<id>"`)
 * list them in the catalogue; everything else is a [DeclarativePlugin] read from JSON.
 */
val nativePlugins: Map<String, Plugin> by lazy {
    (listOf(WeatherPlugin, SkyPlugin, BuildingsPlugin, AlprPlugin) + allDetectors()).associateBy { it.id }
}

internal fun pairs(vararg p: Pair<String, String?>): List<Pair<String, String>> =
    p.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }

internal fun Double.f0() = roundToInt().toString()
internal fun Double.f1() = "%.1f".format(this)

internal fun osmLink(type: String, id: Long) = "OpenStreetMap" to "https://www.openstreetmap.org/$type/$id"
internal fun osmHereLink(lat: Double, lon: Double) =
    "OpenStreetMap: what's here" to "https://www.openstreetmap.org/query?lat=$lat&lon=$lon#map=19/$lat/$lon"

/** OSM `wikipedia=en:Title` tag to a link. */
internal fun wikipediaTagLink(tag: String?): Pair<String, String>? {
    val t = tag ?: return null
    val lang = t.substringBefore(':', "en").takeIf { it.length in 2..3 } ?: "en"
    val title = t.substringAfter(':').replace(' ', '_')
    return "Wikipedia" to "https://$lang.wikipedia.org/wiki/${enc(title).replace("+", "_")}"
}

internal fun websiteLink(url: String?): Pair<String, String>? =
    url?.let { "Website" to if (it.startsWith("http")) it else "https://$it" }

/** Overpass: the main instance is often overloaded, so fall back to a mirror. */
internal suspend fun overpass(env: PluginEnv, query: String, ttlMs: Long): JSONObject {
    var last: Exception? = null
    for (host in listOf("overpass-api.de", "overpass.private.coffee")) {
        try {
            return JSONObject(env.http.text("https://$host/api/interpreter?data=${enc(query)}", ttlMs))
        } catch (e: Exception) {
            last = e
        }
    }
    throw last ?: Exception("Overpass failed")
}


// ---------------------------------------------------------------------------------------------------------------
// Native plugins

private val WMO = mapOf(0 to "Clear", 1 to "Mostly clear", 2 to "Partly cloudy", 3 to "Overcast", 45 to "Fog", 48 to "Rime fog",
    51 to "Light drizzle", 53 to "Drizzle", 55 to "Heavy drizzle", 61 to "Light rain", 63 to "Rain", 65 to "Heavy rain",
    66 to "Freezing rain", 67 to "Freezing rain", 71 to "Light snow", 73 to "Snow", 75 to "Heavy snow", 77 to "Snow grains",
    80 to "Showers", 81 to "Showers", 82 to "Violent showers", 85 to "Snow showers", 86 to "Snow showers",
    95 to "Thunderstorm", 96 to "Thunderstorm and hail", 99 to "Thunderstorm and hail")

object WeatherPlugin : Plugin {
    override val id = "weather"; override val name = "Weather"; override val icon = R.drawable.ph_cloud_sun
    override val category = Category.CONDITIONS; override val source = "Open-Meteo"
    override val description = "Current conditions and a 5-day forecast."
    override val tags = listOf("weather"); override val cost = Cost.FREE; override val freshness = Freshness.SLOW
    override val ambient = true

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val j = JSONObject(env.http.text("https://api.open-meteo.com/v1/forecast?latitude=${ctx.lat}&longitude=${ctx.lon}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max&timezone=auto&forecast_days=5",
            freshness.ttlMs))
        val c = j.obj("current") ?: throw Exception("No current weather")
        val d = j.obj("daily")
        val days = d?.arr("time")?.let { t ->
            (0 until t.length()).map { i ->
                val day = java.time.LocalDate.parse(t.getString(i)).dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                day to "${WMO[d.arr("weather_code").optInt(i)] ?: "?"}, ${d.arr("temperature_2m_min").optDouble(i).f0()} to ${d.arr("temperature_2m_max").optDouble(i).f0()}°, " +
                    "${d.arr("precipitation_probability_max").optInt(i)}% rain, UV ${d.arr("uv_index_max").optDouble(i).f1()}"
            }
        }.orEmpty()
        val title = "${c.num("temperature_2m")?.f0()}°C, ${WMO[c.optInt("weather_code")] ?: ""}"
        return listOf(Item("weather:now", id, title, "Feels like ${c.num("apparent_temperature")?.f0()}° · ${j.str("timezone")}",
            links = listOf("Open-Meteo forecast" to "https://open-meteo.com/en/docs#latitude=${ctx.lat}&longitude=${ctx.lon}"),
            fields = pairs(
                "Humidity" to "${c.optInt("relative_humidity_2m")}%",
                "Wind" to "${c.num("wind_speed_10m")?.f0()} km/h from ${Geo.compassPoint(c.optDouble("wind_direction_10m"))}",
                "Clouds" to "${c.optInt("cloud_cover")}%",
            ) + days))
    }
}


object SkyPlugin : Plugin {
    override val id = "sky"; override val name = "Sun & Moon"; override val icon = R.drawable.ph_sun
    override val category = Category.SKY; override val source = "Computed on the phone"
    override val description = "Where the Sun and Moon are. Aim at either one and use Align compass to fix your heading."
    override val tags = listOf("sky", "calibration"); override val cost = Cost.FREE; override val freshness = Freshness.LIVE

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val sun = Astro.sun(ctx.timeMs, ctx.lat, ctx.lon)
        val moon = Astro.moon(ctx.timeMs, ctx.lat, ctx.lon)
        fun where(p: SkyPos) = "${p.azDeg.f0()}° ${Geo.compassPoint(p.azDeg)}, ${p.elDeg.f1()}° ${if (p.elDeg < 0) "below the horizon" else "up"}"
        return listOf(
            Item("sky:sun", id, "Sun", where(sun), sky = sun, fields = pairs("Azimuth" to "${sun.azDeg.f1()}°", "Elevation" to "${sun.elDeg.f1()}°")),
            Item("sky:moon", id, "Moon", "${(moon.illumination * 100).roundToInt()}% lit, ${if (moon.waxing) "waxing" else "waning"} · ${where(moon.pos)}",
                sky = moon.pos, fields = pairs("Azimuth" to "${moon.pos.azDeg.f1()}°", "Elevation" to "${moon.pos.elDeg.f1()}°",
                    "Illuminated" to "${(moon.illumination * 100).roundToInt()}%")),
        )
    }
}
