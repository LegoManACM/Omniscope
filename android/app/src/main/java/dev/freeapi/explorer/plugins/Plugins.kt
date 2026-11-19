package dev.freeapi.explorer.plugins

import dev.freeapi.explorer.R
import dev.freeapi.explorer.core.Astro
import dev.freeapi.explorer.core.Category
import dev.freeapi.explorer.core.Cost
import dev.freeapi.explorer.core.Ctx
import dev.freeapi.explorer.core.Freshness
import dev.freeapi.explorer.core.Geo
import dev.freeapi.explorer.core.Item
import dev.freeapi.explorer.core.KeySpec
import dev.freeapi.explorer.core.Plugin
import dev.freeapi.explorer.core.PluginEnv
import dev.freeapi.explorer.core.RadiusSpec
import dev.freeapi.explorer.core.SkyPos
import dev.freeapi.explorer.core.arr
import dev.freeapi.explorer.core.enc
import dev.freeapi.explorer.core.num
import dev.freeapi.explorer.core.obj
import dev.freeapi.explorer.core.objects
import dev.freeapi.explorer.core.str
import dev.freeapi.explorer.scan.allDetectors
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

fun allPlugins(): List<Plugin> = listOf(
    PlacePlugin, WeatherPlugin, AirPlugin, SkyPlugin, PeaksPlugin, WikipediaPlugin, AircraftPlugin, IssPlugin,
    NearbyPlacesPlugin, BuildingsPlugin, AlprPlugin, NaturePlugin, PhotosPlugin, QuakesPlugin, RadioPlugin, WebcamsPlugin,
) + allDetectors()

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

private fun JSONObject.latLon(): Pair<Double, Double>? {
    val lat = num("lat") ?: obj("center")?.num("lat") ?: return null
    val lon = num("lon") ?: obj("center")?.num("lon") ?: return null
    return lat to lon
}

// ---------------------------------------------------------------------------------------------------------------
// Conditions: readings about "here"

object PlacePlugin : Plugin {
    override val id = "place"; override val name = "Address"; override val icon = R.drawable.ph_map_pin
    override val category = Category.CONDITIONS; override val source = "OpenStreetMap Nominatim"
    override val description = "The address and areas you're in (max 1 request a second)."
    override val tags = listOf("place"); override val cost = Cost.FREE; override val freshness = Freshness.SLOW
    override val ambient = true

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val j = JSONObject(env.http.text(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${ctx.lat}&lon=${ctx.lon}&zoom=18&addressdetails=1&accept-language=en",
            freshness.ttlMs))
        j.str("error")?.let { throw Exception(it) }
        val a = j.obj("address") ?: JSONObject()
        val title = j.str("name") ?: a.str("road") ?: a.str("suburb") ?: "Unnamed place"
        return listOf(Item("place:here", id, title, j.str("display_name"),
            links = listOfNotNull(j.str("osm_type")?.let { osmLink(it, j.optLong("osm_id")) }, osmHereLink(ctx.lat, ctx.lon)),
            fields = pairs(
                "Road" to a.str("road"), "Area" to (a.str("neighbourhood") ?: a.str("suburb")),
                "City" to (a.str("city") ?: a.str("town") ?: a.str("village") ?: a.str("hamlet")),
                "County" to a.str("county"), "State" to a.str("state"), "Postcode" to a.str("postcode"), "Country" to a.str("country"),
            )))
    }
}

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

object AirPlugin : Plugin {
    override val id = "air"; override val name = "Air & pollen"; override val icon = R.drawable.ph_wind
    override val category = Category.CONDITIONS; override val source = "Open-Meteo (CAMS)"
    override val description = "Air quality, UV and pollen. Pollen is Europe-only."
    override val tags = listOf("environment"); override val cost = Cost.FREE; override val freshness = Freshness.SLOW
    override val ambient = true

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val j = JSONObject(env.http.text("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${ctx.lat}&longitude=${ctx.lon}" +
            "&current=us_aqi,european_aqi,pm2_5,pm10,ozone,nitrogen_dioxide,uv_index,grass_pollen,birch_pollen,ragweed_pollen", freshness.ttlMs))
        val c = j.obj("current") ?: throw Exception("No air data")
        fun v(k: String, unit: String) = c.num(k)?.let { "${it.f1()} $unit".trim() }
        val aqi = c.num("us_aqi")
        val label = when { aqi == null -> ""; aqi <= 50 -> "good"; aqi <= 100 -> "moderate"; aqi <= 150 -> "unhealthy for some"; else -> "unhealthy" }
        return listOf(Item("air:now", id, "Air quality ${aqi?.f0() ?: "?"}, $label", "US AQI · EU AQI ${c.num("european_aqi")?.f0() ?: "?"}",
            fields = pairs("PM2.5" to v("pm2_5", "µg/m³"), "PM10" to v("pm10", "µg/m³"), "Ozone" to v("ozone", "µg/m³"),
                "NO₂" to v("nitrogen_dioxide", "µg/m³"), "UV index" to v("uv_index", ""), "Grass pollen" to v("grass_pollen", "/m³"),
                "Birch pollen" to v("birch_pollen", "/m³"), "Ragweed pollen" to v("ragweed_pollen", "/m³"))))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Things with a direction or a place

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

object PeaksPlugin : Plugin {
    override val id = "peaks"; override val name = "Peaks"; override val icon = R.drawable.ph_mountains
    override val category = Category.TERRAIN; override val source = "OpenStreetMap via Overpass"
    override val description = "Named peaks and volcanoes, with their elevations."
    override val tags = listOf("terrain", "nature"); override val cost = Cost.FREE; override val freshness = Freshness.STATIC
    override val radius = RadiusSpec(40_000.0, 1_000.0, 150_000.0)

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val j = overpass(env, "[out:json][timeout:25];node(around:${ctx.radiusM.toInt()},${ctx.lat},${ctx.lon})[natural~\"^(peak|volcano)$\"][name];out 300;", freshness.ttlMs)
        return j.arr("elements").objects().mapNotNull { e ->
            val t = e.obj("tags") ?: return@mapNotNull null
            val ele = t.str("ele")?.let { Regex("^-?[0-9.]+").find(it)?.value?.toDoubleOrNull() }
            Item("peaks:${e.optLong("id")}", id, t.str("name") ?: return@mapNotNull null, ele?.let { "${it.f0()} m" },
                lat = e.num("lat"), lon = e.num("lon"), altM = ele,
                links = listOfNotNull(osmLink("node", e.optLong("id")), wikipediaTagLink(t.str("wikipedia"))),
                fields = pairs("Elevation" to ele?.let { "${it.f0()} m" }, "Prominence" to t.str("prominence")))
        }
    }
}

object WikipediaPlugin : Plugin {
    override val id = "wikipedia"; override val name = "Wikipedia"; override val icon = R.drawable.ph_book_open
    override val category = Category.HISTORY; override val source = "Wikipedia GeoSearch"
    override val description = "Articles about places near you."
    override val tags = listOf("history", "culture"); override val cost = Cost.FREE; override val freshness = Freshness.STATIC
    override val radius = RadiusSpec(10_000.0, 50.0, 10_000.0) // the API maximum is 10 km

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val j = JSONObject(env.http.text("https://en.wikipedia.org/w/api.php?action=query&generator=geosearch&ggscoord=${ctx.lat}%7C${ctx.lon}" +
            "&ggsradius=${ctx.radiusM.toInt().coerceIn(10, 10_000)}&ggslimit=50&prop=coordinates%7Cpageimages%7Cextracts&exintro=1&explaintext=1&exsentences=3&exlimit=50" +
            "&pithumbsize=640&pilimit=50&colimit=50&format=json", freshness.ttlMs))
        val pages = j.obj("query")?.obj("pages") ?: return emptyList()
        return pages.keys().asSequence().mapNotNull { k ->
            val p = pages.getJSONObject(k)
            val co = p.arr("coordinates").objects().firstOrNull() ?: return@mapNotNull null
            val lat = co.num("lat"); val lon = co.num("lon")
            Item("wikipedia:$k", id, p.optString("title"), p.str("extract")?.take(160),
                lat = lat, lon = lon, image = p.obj("thumbnail")?.str("source"),
                links = listOfNotNull("Wikipedia" to "https://en.wikipedia.org/?curid=$k", if (lat != null && lon != null) osmHereLink(lat, lon) else null),
                fields = pairs("Summary" to p.str("extract")))
        }.toList()
    }
}

object AircraftPlugin : Plugin {
    override val id = "aircraft"; override val name = "Aircraft"; override val icon = R.drawable.ph_airplane_tilt
    override val category = Category.TRANSPORT; override val source = "adsb.lol (fallback airplanes.live)"
    override val description = "Live ADS-B positions with altitude."
    override val tags = listOf("sky", "transport", "live"); override val cost = Cost.FREE; override val freshness = Freshness.LIVE
    override val radius = RadiusSpec(75_000.0, 1_000.0, 400_000.0)

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val nm = (ctx.radiusM / 1852.0).coerceIn(1.0, 250.0).toInt()
        val path = "v2/point/${ctx.lat}/${ctx.lon}/$nm"
        val j = try {
            JSONObject(env.http.text("https://api.adsb.lol/$path", freshness.ttlMs))
        } catch (e: Exception) {
            JSONObject(env.http.text("https://api.airplanes.live/$path", freshness.ttlMs))
        }
        return j.arr("ac").objects().mapNotNull { a ->
            val lat = a.num("lat") ?: return@mapNotNull null
            val lon = a.num("lon") ?: return@mapNotNull null
            val feet = a.num("alt_geom") ?: a.num("alt_baro") // alt_baro is the string "ground" on the ground
            val hex = a.optString("hex")
            val call = a.str("flight")
            Item("aircraft:$hex", id, call ?: a.str("r") ?: hex, listOfNotNull(a.str("desc") ?: a.str("t"),
                feet?.let { "%,d m".format((it * 0.3048).roundToInt()) } ?: "on the ground", a.num("gs")?.let { "${it.f0()} kt" }).joinToString(" · "),
                lat = lat, lon = lon, altM = feet?.times(0.3048),
                links = listOfNotNull("ADS-B track" to "https://globe.adsb.lol/?icao=$hex",
                    call?.let { "FlightAware" to "https://www.flightaware.com/live/flight/${it.trim()}" }),
                fields = pairs("Type" to (a.str("desc") ?: a.str("t")), "Registration" to a.str("r"), "Squawk" to a.str("squawk"),
                    "Ground speed" to a.num("gs")?.let { "${it.f0()} kt" }, "Track" to a.num("track")?.let { "${it.f0()}°" }))
        }
    }
}

object IssPlugin : Plugin {
    override val id = "iss"; override val name = "ISS"; override val icon = R.drawable.ph_planet
    override val category = Category.SKY; override val source = "wheretheiss.at"
    override val description = "The International Space Station right now. In AR it shows its true direction, even below the horizon."
    override val tags = listOf("sky", "space", "live"); override val cost = Cost.FREE; override val freshness = Freshness.LIVE

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val s = JSONObject(env.http.text("https://api.wheretheiss.at/v1/satellites/25544", freshness.ttlMs))
        val lat = s.optDouble("latitude"); val lon = s.optDouble("longitude")
        return listOf(Item("iss:25544", id, "International Space Station", "${s.optDouble("altitude").f0()} km up · ${s.str("visibility")}",
            lat = lat, lon = lon, altM = s.optDouble("altitude") * 1000,
            links = listOf("Where the ISS is" to "https://wheretheiss.at", "NASA: Spot the Station" to "https://spotthestation.nasa.gov"),
            fields = pairs("Speed" to "%,d km/h".format(s.optDouble("velocity").roundToInt()), "In sunlight" to s.str("visibility"),
                "Ground distance" to Geo.fmtDistance(Geo.distanceM(ctx.lat, ctx.lon, lat, lon)))))
    }
}

object NearbyPlacesPlugin : Plugin {
    override val id = "osm_places"; override val name = "Nearby places"; override val icon = R.drawable.ph_storefront
    override val category = Category.PLACES; override val source = "OpenStreetMap via Overpass"
    override val description = "Named amenities, shops, sights and historic features."
    override val tags = listOf("place", "history"); override val cost = Cost.FREE; override val freshness = Freshness.STATIC
    override val radius = RadiusSpec(500.0, 25.0, 3_000.0)

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val a = "around:${ctx.radiusM.toInt()},${ctx.lat},${ctx.lon}"
        val q = "[out:json][timeout:25];(nwr($a)[amenity][name];nwr($a)[tourism][name];nwr($a)[historic][name];nwr($a)[shop][name];nwr($a)[leisure][name];);out center tags 200;"
        return overpass(env, q, freshness.ttlMs).arr("elements").objects().mapNotNull { e ->
            val t = e.obj("tags") ?: return@mapNotNull null
            val (lat, lon) = e.latLon() ?: return@mapNotNull null
            val kind = t.str("historic")?.let { "historic $it" } ?: t.str("tourism") ?: t.str("amenity") ?: t.str("shop") ?: t.str("leisure")
            val website = t.str("website") ?: t.str("contact:website")
            Item("osm:${e.optString("type")}${e.optLong("id")}", id, t.str("name") ?: return@mapNotNull null, kind?.replace('_', ' '),
                lat = lat, lon = lon,
                links = listOfNotNull(websiteLink(website), osmLink(e.optString("type"), e.optLong("id")), wikipediaTagLink(t.str("wikipedia"))),
                fields = pairs("Address" to listOfNotNull(t.str("addr:housenumber"), t.str("addr:street")).joinToString(" ").ifBlank { null },
                    "Opening hours" to t.str("opening_hours"), "Phone" to (t.str("phone") ?: t.str("contact:phone")),
                    "Cuisine" to t.str("cuisine")?.replace(';', ','), "Website" to website))
        }
    }
}

object NaturePlugin : Plugin {
    override val id = "nature"; override val name = "Wildlife sightings"; override val icon = R.drawable.ph_butterfly
    override val category = Category.NATURE; override val source = "iNaturalist"
    override val description = "Recent wildlife and plant sightings."
    override val tags = listOf("nature"); override val cost = Cost.FREE; override val freshness = Freshness.SLOW
    override val radius = RadiusSpec(3_000.0, 10.0, 50_000.0)

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val j = JSONObject(env.http.text("https://api.inaturalist.org/v1/observations?lat=${ctx.lat}&lng=${ctx.lon}&radius=${"%.3f".format(java.util.Locale.US, (ctx.radiusM / 1000).coerceAtLeast(0.05))}" +
            "&per_page=60&order=desc&order_by=observed_on&photos=true&geo=true", freshness.ttlMs))
        return j.arr("results").objects().mapNotNull { o ->
            val c = o.obj("geojson")?.arr("coordinates") ?: return@mapNotNull null
            val taxon = o.obj("taxon")
            val name = taxon?.str("preferred_common_name") ?: taxon?.str("name") ?: o.str("species_guess") ?: return@mapNotNull null
            val photos = o.arr("photos").objects().mapNotNull { it.str("url")?.replace("square", "medium") }
            Item("nature:${o.optLong("id")}", id, name.replaceFirstChar { it.uppercase() },
                listOfNotNull(taxon?.str("name"), o.str("observed_on")).joinToString(" · "),
                lat = c.optDouble(1), lon = c.optDouble(0), image = photos.firstOrNull(), gallery = photos.drop(1),
                links = listOfNotNull(o.str("uri")?.let { "iNaturalist observation" to it },
                    taxon?.optLong("id")?.takeIf { it > 0 }?.let { "Species page" to "https://www.inaturalist.org/taxa/$it" }),
                fields = pairs("Scientific name" to taxon?.str("name"), "Group" to taxon?.str("iconic_taxon_name"),
                    "Observed" to o.str("observed_on"), "Quality" to o.str("quality_grade")?.replace('_', ' ')))
        }
    }
}

object PhotosPlugin : Plugin {
    override val id = "photos"; override val name = "Photos taken here"; override val icon = R.drawable.ph_image
    override val category = Category.MEDIA; override val source = "Wikimedia Commons"
    override val description = "Geotagged photos taken near you."
    override val tags = listOf("culture", "history"); override val cost = Cost.FREE; override val freshness = Freshness.STATIC
    override val radius = RadiusSpec(2_000.0, 10.0, 10_000.0) // the API maximum is 10 km

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val j = JSONObject(env.http.text("https://commons.wikimedia.org/w/api.php?action=query&generator=geosearch" +
            "&ggscoord=${ctx.lat}%7C${ctx.lon}&ggsradius=${ctx.radiusM.toInt().coerceIn(10, 10_000)}&ggsnamespace=6&ggslimit=40&prop=imageinfo%7Ccoordinates" +
            "&iiprop=url&iiurlwidth=800&format=json", freshness.ttlMs))
        val pages = j.obj("query")?.obj("pages") ?: return emptyList()
        return pages.keys().asSequence().mapNotNull { k ->
            val p = pages.getJSONObject(k)
            val co = p.arr("coordinates").objects().firstOrNull() ?: return@mapNotNull null
            val info = p.arr("imageinfo").objects().firstOrNull()
            Item("photos:$k", id, p.optString("title").removePrefix("File:").substringBeforeLast('.'),
                lat = co.num("lat"), lon = co.num("lon"), image = info?.str("thumburl"),
                links = listOfNotNull(info?.str("descriptionurl")?.let { "Wikimedia Commons" to it }))
        }.toList()
    }
}

object QuakesPlugin : Plugin {
    override val id = "quakes"; override val name = "Earthquakes"; override val icon = R.drawable.ph_warning
    override val category = Category.HAZARDS; override val source = "USGS"
    override val description = "Magnitude 2.5 and up in the last 14 days."
    override val tags = listOf("hazard", "terrain"); override val cost = Cost.FREE; override val freshness = Freshness.SLOW
    override val radius = RadiusSpec(1_000_000.0, 10_000.0, 5_000_000.0)

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val start = java.time.LocalDate.now().minusDays(14)
        val j = JSONObject(env.http.text("https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson&latitude=${ctx.lat}" +
            "&longitude=${ctx.lon}&maxradiuskm=${(ctx.radiusM / 1000).toInt().coerceIn(1, 20_000)}&starttime=$start&minmagnitude=2.5&orderby=magnitude&limit=40", freshness.ttlMs))
        return j.arr("features").objects().mapNotNull { f ->
            val p = f.obj("properties") ?: return@mapNotNull null
            val c = f.obj("geometry")?.arr("coordinates") ?: return@mapNotNull null
            val date = java.time.Instant.ofEpochMilli(p.optLong("time")).toString().take(10)
            Item("quakes:${f.optString("id")}", id, "Magnitude ${p.optDouble("mag").f1()}", "${p.optString("place")} · $date",
                lat = c.optDouble(1), lon = c.optDouble(0), links = listOfNotNull(p.str("url")?.let { "USGS event page" to it }),
                fields = pairs("Where" to p.str("place"), "Depth" to "${c.optDouble(2).f0()} km", "Date" to date, "Felt reports" to p.str("felt")))
        }
    }
}

object RadioPlugin : Plugin {
    override val id = "radio"; override val name = "Radio stations"; override val icon = R.drawable.ph_radio
    override val category = Category.MEDIA; override val source = "Radio Browser"
    override val description = "Internet radio stations based near you. Their stream link plays live."
    override val tags = listOf("culture", "media"); override val cost = Cost.FREE; override val freshness = Freshness.STATIC
    override val radius = RadiusSpec(150_000.0, 1_000.0, 1_000_000.0)

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val arr = JSONArray(env.http.text("https://de1.api.radio-browser.info/json/stations/search?geo_lat=${ctx.lat}&geo_long=${ctx.lon}" +
            "&geo_distance=${ctx.radiusM.toInt()}&has_geo_info=true&hidebroken=true&order=clickcount&reverse=true&limit=60", freshness.ttlMs))
        return arr.objects().mapNotNull { s ->
            val lat = s.num("geo_lat") ?: return@mapNotNull null
            val lon = s.num("geo_long") ?: return@mapNotNull null
            if (Geo.distanceM(ctx.lat, ctx.lon, lat, lon) > ctx.radiusM) return@mapNotNull null
            val stream = s.str("url_resolved") ?: s.str("url")
            Item("radio:${s.optString("stationuuid")}", id, s.optString("name").trim(),
                s.str("tags")?.split(',')?.take(3)?.joinToString(", "), lat = lat, lon = lon, image = s.str("favicon"),
                links = listOfNotNull(stream?.let { "Listen live" to it }, websiteLink(s.str("homepage"))),
                fields = pairs("Codec" to s.str("codec"), "Bitrate" to s.optInt("bitrate").takeIf { it > 0 }?.let { "$it kbps" }))
        }
    }
}

object WebcamsPlugin : Plugin {
    override val id = "webcams"; override val name = "Webcams"; override val icon = R.drawable.ph_webcam
    override val category = Category.MEDIA; override val source = "Windy Webcams API"
    override val description = "Public webcams with live preview images."
    override val tags = listOf("media", "weather"); override val cost = Cost.FREEMIUM; override val freshness = Freshness.SLOW
    override val key = KeySpec("Windy Webcams API key", "https://api.windy.com/keys")
    override val dailyLimit = 300
    override val radius = RadiusSpec(50_000.0, 1_000.0, 250_000.0)

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val j = JSONObject(env.http.text("https://api.windy.com/webcams/api/v3/webcams?nearby=${ctx.lat},${ctx.lon},${(ctx.radiusM / 1000).toInt().coerceIn(1, 250)}" +
            "&include=images,location,urls&limit=50", freshness.ttlMs, mapOf("x-windy-api-key" to (env.key ?: ""))))
        return j.arr("webcams").objects().mapNotNull { w ->
            val loc = w.obj("location") ?: return@mapNotNull null
            val page = w.obj("urls")?.str("detail") ?: "https://www.windy.com/webcams/${w.optLong("webcamId")}"
            Item("webcams:${w.optLong("webcamId")}", id, w.optString("title"), loc.str("city"),
                lat = loc.num("latitude"), lon = loc.num("longitude"),
                image = w.obj("images")?.obj("current")?.str("preview"), links = listOf("Windy webcam page" to page))
        }
    }
}
