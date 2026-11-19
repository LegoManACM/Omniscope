package dev.omniscope.core

import dev.omniscope.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Icon names a manifest may use (Phosphor, bundled in the app). */
object PluginIcons {
    val byName: Map<String, Int> = mapOf(
        "airplane-tilt" to R.drawable.ph_airplane_tilt, "barcode" to R.drawable.ph_barcode, "binoculars" to R.drawable.ph_binoculars,
        "book-open" to R.drawable.ph_book_open, "broadcast" to R.drawable.ph_broadcast, "buildings" to R.drawable.ph_buildings,
        "butterfly" to R.drawable.ph_butterfly, "camera" to R.drawable.ph_camera, "cloud-sun" to R.drawable.ph_cloud_sun,
        "compass" to R.drawable.ph_compass, "crosshair" to R.drawable.ph_crosshair, "detective" to R.drawable.ph_detective,
        "eye" to R.drawable.ph_eye, "globe" to R.drawable.ph_globe, "house" to R.drawable.ph_house, "image" to R.drawable.ph_image,
        "info" to R.drawable.ph_info, "key" to R.drawable.ph_key, "leaf" to R.drawable.ph_leaf, "link" to R.drawable.ph_link,
        "map-pin" to R.drawable.ph_map_pin, "map-trifold" to R.drawable.ph_map_trifold, "moon" to R.drawable.ph_moon,
        "mountains" to R.drawable.ph_mountains, "navigation-arrow" to R.drawable.ph_navigation_arrow, "planet" to R.drawable.ph_planet,
        "radio" to R.drawable.ph_radio, "scan" to R.drawable.ph_scan, "security-camera" to R.drawable.ph_security_camera,
        "storefront" to R.drawable.ph_storefront, "sun" to R.drawable.ph_sun, "tag" to R.drawable.ph_tag, "text-t" to R.drawable.ph_text_t,
        "warning" to R.drawable.ph_warning, "webcam" to R.drawable.ph_webcam, "wind" to R.drawable.ph_wind,
    )
}

/**
 * A plugin manifest (`plugin.json`). See docs/PLUGINS.md. Declarative manifests describe a request and how to map
 * its JSON into items; `native` manifests name an implementation built into the app.
 */
class Manifest(val json: JSONObject) {
    val format = json.optInt("format", 1)
    val id: String = json.optString("id")
    val version = json.optInt("version", 1)
    val name: String = json.optString("name", id)
    val description: String = json.optString("description")
    val author: String = json.optString("author")
    val source: String = json.optString("source")
    val category: Category = Category.entries.firstOrNull { it.name == json.optString("category").uppercase() } ?: Category.PLACES
    val iconName: String = json.optString("icon")
    val icon: Int get() = PluginIcons.byName[iconName] ?: category.icon
    val cost: Cost = Cost.entries.firstOrNull { it.label == json.optString("cost", "free") } ?: Cost.FREE
    val freshness: Freshness = Freshness.entries.firstOrNull { it.name == json.optString("freshness", "slow").uppercase() } ?: Freshness.SLOW
    val tags: List<String> = json.optJSONArray("tags").strings()
    val ambient = json.optBoolean("ambient", false)
    val radius: RadiusSpec? = json.optJSONObject("radius")?.let {
        RadiusSpec(it.optDouble("default"), it.optDouble("min", 10.0), it.optDouble("max", it.optDouble("default")))
    }
    val key: KeySpec? = json.optJSONObject("key")?.let { KeySpec(it.optString("label", "API key"), it.optString("signup")) }
    val dailyLimit: Int? = json.optInt("dailyLimit", 0).takeIf { it > 0 }
    val hosts: List<String> = json.optJSONArray("hosts").strings()
    val native: String? = json.optString("native").ifBlank { null }
    val request: JSONObject? = json.optJSONObject("request")
    val itemsPath: String = json.optString("items", "$")
    val item: JSONObject = json.optJSONObject("item") ?: JSONObject()
    val errorPath: String? = json.optString("error").ifBlank { null }

    /** Hosts Overpass requests may go to (the main instance and a mirror). */
    val effectiveHosts: List<String> get() = hosts + if (request?.has("overpass") == true) OVERPASS_HOSTS else emptyList()

    /** Problems that make the manifest unusable; empty when it's fine. */
    fun problems(): List<String> = buildList {
        if (format != 1) add("Unsupported format $format (this app reads format 1)")
        if (!Regex("^[a-z0-9_]{2,40}$").matches(id)) add("id must be 2-40 lowercase letters, digits or _")
        if (name.isBlank()) add("name is required")
        if (description.isBlank()) add("description is required")
        if (Category.entries.none { it.name == json.optString("category").uppercase() }) add("unknown category \"${json.optString("category")}\"")
        if (iconName.isNotBlank() && iconName !in PluginIcons.byName) add("unknown icon \"$iconName\"")
        if (radius != null && !(radius.minM <= radius.defaultM && radius.defaultM <= radius.maxM)) add("radius must satisfy min <= default <= max")
        if (native != null) return@buildList
        val req = request
        if (req == null) { add("request is required"); return@buildList }
        val urls = req.optJSONArray("urls").strings() + listOfNotNull(req.optString("url").ifBlank { null })
        if (urls.isEmpty() && !req.has("overpass")) add("request needs url, urls or overpass")
        for (u in urls) {
            if (!u.startsWith("https://")) add("request URLs must be https: $u")
            val host = runCatching { URI(u.substringBefore('{').let { if (it.count { c -> c == '/' } < 3) "$it/" else it }).host }.getOrNull()
            if (host == null) add("request URL must start with a literal host: $u")
            else if (host !in hosts) add("host $host is not listed in hosts")
        }
        if (!item.has("title")) add("item.title is required")
        if (item.has("lat") != item.has("lon")) add("item needs both lat and lon, or neither")
    }

    companion object {
        val OVERPASS_HOSTS = listOf("overpass-api.de", "overpass.private.coffee")

        fun parse(text: String): Manifest = Manifest(JSONObject(text))

        /** sha-256 of the manifest text with line endings normalised, as written in the plugin index. */
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.replace("\r\n", "\n").toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }
