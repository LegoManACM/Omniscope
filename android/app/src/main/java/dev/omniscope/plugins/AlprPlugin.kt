package dev.omniscope.plugins

import dev.omniscope.R
import dev.omniscope.core.Category
import dev.omniscope.core.Cost
import dev.omniscope.core.Ctx
import dev.omniscope.core.Freshness
import dev.omniscope.core.Geo
import dev.omniscope.core.Item
import dev.omniscope.core.Plugin
import dev.omniscope.core.PluginEnv
import dev.omniscope.core.RadiusSpec
import dev.omniscope.core.arr
import dev.omniscope.core.enc
import dev.omniscope.core.num
import dev.omniscope.core.obj
import dev.omniscope.core.objects
import dev.omniscope.core.str
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

/**
 * Automated licence plate readers (Flock and others) as mapped by DeFlock volunteers in OpenStreetMap
 * (`man_made=surveillance` + `surveillance:type=ALPR`).
 *
 * Source 1: DeFlock's CDN, one JSON file per 20°×20° square (~350 KB), so a refresh costs one cached download.
 * Source 2: Overpass, live from OSM, if the CDN fails.
 */
object AlprPlugin : Plugin {
    override val id = "alpr"; override val name = "ALPR cameras"; override val icon = R.drawable.ph_security_camera
    override val category = Category.SURVEILLANCE; override val source = "DeFlock / OpenStreetMap contributors"
    override val description = "Licence-plate reader cameras, with the way each one faces. " +
        "Mounting height is guessed when it isn't tagged."
    override val tags = listOf("surveillance", "privacy"); override val cost = Cost.FREE; override val freshness = Freshness.STATIC

    override val radius = RadiusSpec(15_000.0, 100.0, 100_000.0)

    /** How far a plate reader is assumed to see, and its field of view, for the map's view cones. */
    const val VIEW_RANGE_M = 50.0
    const val VIEW_HALF_ANGLE_DEG = 30.0

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val nodes: List<JSONObject> = try {
            val lat = (floor(ctx.lat / 20) * 20).toInt()
            val lon = (floor(ctx.lon / 20) * 20).toInt()
            JSONArray(env.http.text("https://cdn.deflock.me/regions/$lat/$lon.json", freshness.ttlMs)).objects()
        } catch (e: Exception) {
            val q = "[out:json][timeout:25];nwr(around:${ctx.radiusM.toInt()},${ctx.lat},${ctx.lon})[\"surveillance:type\"=\"ALPR\"];out center tags 500;"
            overpass(env, q, freshness.ttlMs).arr("elements").objects()
        }
        return nodes.mapNotNull { n ->
            val lat = n.num("lat") ?: n.obj("center")?.num("lat") ?: return@mapNotNull null
            val lon = n.num("lon") ?: n.obj("center")?.num("lon") ?: return@mapNotNull null
            if (Geo.distanceM(ctx.lat, ctx.lon, lat, lon) > ctx.radiusM) return@mapNotNull null
            val t = n.obj("tags") ?: JSONObject()
            val facing = facingDeg(t)
            val (height, heightSource) = height(t)
            val make = t.str("manufacturer") ?: t.str("brand")
            Item(
                "alpr:${n.optLong("id")}", id, "Plate reader" + (make?.let { ", $it" } ?: ""),
                listOfNotNull(facing?.let { "faces ${Geo.compassPoint(it)} (${it.toInt()}°)" }, t.str("operator")).joinToString(" · ").ifEmpty { null },
                lat = lat, lon = lon, heightM = height,
                links = listOf(osmLink("node", n.optLong("id")), "DeFlock map" to "https://maps.deflock.org"),
                fields = listOfNotNull(
                    t.str("operator")?.let { "Operator" to it },
                    make?.let { "Manufacturer" to it },
                    facing?.let { "Facing" to "${it.toInt()}° ${Geo.compassPoint(it)}" },
                    "Mount height" to "%.1f m (%s)".format(height, heightSource),
                    t.str("camera:mount")?.let { "Mount" to it.replace('_', ' ') },
                    t.str("surveillance:zone")?.let { "Zone" to it },
                ),
            )
        }
    }

    /** The direction a camera item faces, read back from its fields (for the map cone and "in view" checks). */
    fun facingOf(item: Item): Double? = item.fields.firstOrNull { it.first == "Facing" }?.second?.substringBefore('°')?.toDoubleOrNull()

    /** Whether a point is inside a camera's assumed view cone. */
    fun sees(item: Item, lat: Double, lon: Double): Boolean {
        val f = facingOf(item) ?: return false
        val cLat = item.lat ?: return false; val cLon = item.lon ?: return false
        if (Geo.distanceM(cLat, cLon, lat, lon) > VIEW_RANGE_M) return false
        return angleDiff(f, Geo.bearingDeg(cLat, cLon, lat, lon)) <= VIEW_HALF_ANGLE_DEG
    }

    private fun facingDeg(t: JSONObject): Double? {
        val first = (t.str("direction") ?: t.str("camera:direction"))?.split(';', ',')?.firstOrNull()?.trim() ?: return null
        first.toDoubleOrNull()?.let { return ((it % 360) + 360) % 360 }
        val cardinals = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return cardinals.indexOf(first.uppercase()).takeIf { it >= 0 }?.let { it * 22.5 }
    }

    /** Tagged height if there is one, else a guess from the mount type (typical install heights). */
    private fun height(t: JSONObject): Pair<Double, String> {
        t.str("height")?.let { Regex("^[0-9.]+").find(it)?.value?.toDoubleOrNull() }?.let { return it to "tagged" }
        val guess = when (t.str("camera:mount")?.lowercase()) {
            "traffic_signals", "traffic_light", "mast_arm" -> 5.5
            "street_lamp", "streetlight", "lamp_post" -> 6.0
            "wall", "building" -> 3.5
            "ceiling" -> 3.0
            "pole" -> 3.5
            else -> 3.5 // Flock's own poles are typically 10 to 12 ft
        }
        return guess to "guessed"
    }

    fun angleDiff(a: Double, b: Double): Double = abs(((a - b + 540) % 360) - 180)
}
