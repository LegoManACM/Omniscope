package dev.omniscope.plugins

import dev.omniscope.R
import dev.omniscope.core.Category
import dev.omniscope.core.Cost
import dev.omniscope.core.Ctx
import dev.omniscope.core.Freshness
import dev.omniscope.core.Geo
import dev.omniscope.core.Item
import dev.omniscope.core.Mvt
import dev.omniscope.core.Plugin
import dev.omniscope.core.PluginEnv
import dev.omniscope.core.RadiusSpec
import dev.omniscope.core.Shape
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Building footprints with heights from OpenFreeMap's vector tiles (OpenMapTiles schema, OSM data): the same free,
 * keyless tiles the map uses. Zoom-14 tiles are immutable per planet version, so each is downloaded once.
 *
 * The same tiles also name the building where they can: a place (POI) inside the footprint, house numbers inside it,
 * and the nearest named street.
 */
object BuildingsPlugin : Plugin {
    override val id = "buildings"; override val name = "Buildings"; override val icon = R.drawable.ph_buildings
    override val category = Category.BUILDINGS; override val source = "OpenFreeMap vector tiles (OpenStreetMap)"
    override val description = "3D outlines of buildings near you. In AR, aim at one to highlight it and see what's inside."
    override val tags = listOf("3d", "place"); override val cost = Cost.FREE; override val freshness = Freshness.STATIC
    override val layer = true

    private const val Z = 14
    override val radius = RadiusSpec(450.0, 50.0, 800.0)
    private const val MAX_BUILDINGS = 900

    private class Poi(val x: Int, val y: Int, val name: String, val kind: String?)
    private class Line(val pts: IntArray, val name: String)

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val template = JSONObject(env.http.text("https://tiles.openfreemap.org/planet", 24 * 3_600_000L))
            .getJSONArray("tiles").getString(0)
        val radiusM = ctx.radiusM
        val dLat = radiusM / 110_540.0
        val dLon = radiusM / (111_320.0 * cos(Math.toRadians(ctx.lat)))
        val xs = Mvt.tileX(ctx.lon - dLon, Z)..Mvt.tileX(ctx.lon + dLon, Z)
        val ys = Mvt.tileY(ctx.lat + dLat, Z)..Mvt.tileY(ctx.lat - dLat, Z)

        val out = mutableListOf<Pair<Item, Double>>()
        for (tx in xs) for (ty in ys) {
            val url = template.replace("{z}", "$Z").replace("{x}", "$tx").replace("{y}", "$ty")
            val bytes = env.http.bytes(url, 30L * 24 * 3_600_000L)
            val layers = Mvt.decode(bytes, setOf("building", "poi", "housenumber", "transportation_name")).associateBy { it.name }
            val buildings = layers["building"] ?: continue
            val ext = buildings.extent.toDouble()
            val metresPerUnit = 40_075_016.0 * cos(Math.toRadians(ctx.lat)) / (1 shl Z) / ext

            val pois = layers["poi"]?.features.orEmpty().mapNotNull { f ->
                val name = f.tags["name:en"] as? String ?: f.tags["name"] as? String ?: return@mapNotNull null
                val p = Mvt.rings(f.geometry).firstOrNull() ?: return@mapNotNull null
                Poi(p[0], p[1], name, (f.tags["subclass"] as? String ?: f.tags["class"] as? String)?.replace('_', ' '))
            }
            val numbers = layers["housenumber"]?.features.orEmpty().mapNotNull { f ->
                val n = f.tags["housenumber"] as? String ?: return@mapNotNull null
                val p = Mvt.rings(f.geometry).firstOrNull() ?: return@mapNotNull null
                Poi(p[0], p[1], n, null)
            }
            val streets = layers["transportation_name"]?.features.orEmpty().flatMap { f ->
                val name = f.tags["name:en"] as? String ?: f.tags["name"] as? String ?: return@flatMap emptyList()
                Mvt.rings(f.geometry).map { Line(it, name) }
            }

            buildings.features.forEachIndexed { fi, f ->
                if (f.type != 3) return@forEachIndexed // polygons only
                if (f.tags["hide_3d"] == true) return@forEachIndexed
                val top = (f.tags["render_height"] as? Double) ?: 6.0
                val bottom = (f.tags["render_min_height"] as? Double) ?: 0.0
                Mvt.rings(f.geometry).forEachIndexed { ri, ring ->
                    if (ring.size < 6 || Mvt.signedArea(ring) <= 0) return@forEachIndexed // skip holes and slivers
                    val n = ring.size / 2
                    var cx = 0.0; var cy = 0.0
                    for (k in 0 until n) { cx += ring[2 * k]; cy += ring[2 * k + 1] }
                    cx /= n; cy /= n
                    val cLat = Mvt.tileLat(ty, cy / ext, Z); val cLon = Mvt.tileLon(tx, cx / ext, Z)
                    val d = Geo.distanceM(ctx.lat, ctx.lon, cLat, cLon)
                    if (d > radiusM) return@forEachIndexed

                    val ll = DoubleArray(n * 2)
                    for (k in 0 until n) {
                        ll[2 * k] = Mvt.tileLat(ty, ring[2 * k + 1] / ext, Z)
                        ll[2 * k + 1] = Mvt.tileLon(tx, ring[2 * k] / ext, Z)
                    }
                    val inside = pois.filter { contains(ring, it.x, it.y) }
                    val number = numbers.firstOrNull { contains(ring, it.x, it.y) }?.name
                    val street = streets.map { it.name to distToLine(cx, cy, it.pts) }.minByOrNull { it.second }
                        ?.takeIf { it.second * metresPerUnit < 80 }?.first
                    val address = listOfNotNull(number, street).joinToString(" ").ifBlank { null }
                    val floors = ((top - bottom) / 3.2).toInt().coerceAtLeast(1)
                    val title = inside.firstOrNull()?.name ?: address?.takeIf { number != null } ?: street?.let { "Building on $it" } ?: "Building"
                    out += Item(
                        "buildings:$tx/$ty/$fi/$ri", id, title,
                        listOfNotNull(inside.firstOrNull()?.kind, "%.0f m tall".format(top)).joinToString(" · "),
                        lat = cLat, lon = cLon, shape = Shape(ll, bottom, top),
                        links = listOf(osmHereLink(cLat, cLon)),
                        fields = pairs(
                            "Address" to address,
                            "Height" to "%.0f m, about %d floor%s".format(top, floors, if (floors == 1) "" else "s"),
                            "Starts at" to bottom.takeIf { it > 0 }?.let { "%.0f m up".format(it) },
                            "Inside" to inside.joinToString(", ") { it.name }.ifBlank { null },
                        ),
                    ) to d
                }
            }
        }
        return out.sortedBy { it.second }.take(MAX_BUILDINGS).map { it.first }
    }

    private fun contains(ring: IntArray, x: Int, y: Int): Boolean {
        var c = false
        val n = ring.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[2 * i].toDouble(); val yi = ring[2 * i + 1].toDouble()
            val xj = ring[2 * j].toDouble(); val yj = ring[2 * j + 1].toDouble()
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    private fun distToLine(px: Double, py: Double, pts: IntArray): Double {
        var best = Double.MAX_VALUE
        for (k in 0 until pts.size / 2 - 1) {
            val ax = pts[2 * k].toDouble(); val ay = pts[2 * k + 1].toDouble()
            val bx = pts[2 * k + 2].toDouble(); val by = pts[2 * k + 3].toDouble()
            val dx = bx - ax; val dy = by - ay
            val t = if (dx == 0.0 && dy == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(px - (ax + t * dx), py - (ay + t * dy)))
        }
        return best
    }
}
