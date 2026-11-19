package dev.omniscope.plugins

import dev.omniscope.core.Category
import dev.omniscope.core.Cost
import dev.omniscope.core.Ctx
import dev.omniscope.core.Expr
import dev.omniscope.core.Geo
import dev.omniscope.core.Freshness
import dev.omniscope.core.Item
import dev.omniscope.core.KeySpec
import dev.omniscope.core.Manifest
import dev.omniscope.core.Plugin
import dev.omniscope.core.PluginEnv
import dev.omniscope.core.RadiusSpec
import dev.omniscope.core.enc
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URI

/**
 * A plugin defined entirely by a JSON manifest: build the request from your location, fetch JSON, and map each
 * result into an [Item] with [Expr] expressions. It can only contact the hosts its manifest declares.
 */
class DeclarativePlugin(val manifest: Manifest) : Plugin {
    override val id = manifest.id
    override val name = manifest.name
    override val icon = manifest.icon
    override val category: Category = manifest.category
    override val description = manifest.description
    override val source = manifest.source
    override val tags = manifest.tags
    override val cost: Cost = manifest.cost
    override val freshness: Freshness = manifest.freshness
    override val key: KeySpec? = manifest.key
    override val dailyLimit = manifest.dailyLimit
    override val ambient = manifest.ambient
    override val radius: RadiusSpec? = manifest.radius

    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> {
        val req = manifest.request ?: throw Exception("No request in manifest")
        val headers = req.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { k -> Expr.request(h.getString(k), ctx, env.key) } }.orEmpty()
        val urls = buildList {
            req.optJSONArray("urls")?.let { a -> for (i in 0 until a.length()) add(Expr.request(a.getString(i), ctx, env.key)) }
            req.optString("url").takeIf { it.isNotBlank() }?.let { add(Expr.request(it, ctx, env.key)) }
            req.optString("overpass").takeIf { it.isNotBlank() }?.let { q ->
                val query = Expr.request(q, ctx, env.key)
                Manifest.OVERPASS_HOSTS.forEach { h -> add("https://$h/api/interpreter?data=${enc(query)}") }
            }
        }
        var last: Exception? = null
        for (url in urls) {
            val host = URI(url).host
            if (host !in manifest.effectiveHosts) throw Exception("Blocked: $host isn't in this plugin's hosts")
            try {
                return nearestFirst(parse(env.http.text(url, freshness.ttlMs, headers), ctx), ctx)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("No request URL")
    }

    /**
     * Keeps the [Manifest] `limit` (default 300) nearest results, so an API that returns a whole country (tide stations,
     * UK crime) doesn't flood the app. Results without a place keep their order, first.
     */
    private fun nearestFirst(items: List<Item>, ctx: Ctx): List<Item> {
        val limit = manifest.json.optInt("limit", 300).coerceIn(1, 2000)
        if (items.size <= limit) return items
        return items.sortedBy { if (it.hasPlace) Geo.distanceM(ctx.lat, ctx.lon, it.lat!!, it.lon!!) else -1.0 }.take(limit)
    }

    /** Maps a response body to items (public for tests). `{@lat}`/`{@lon}` in item expressions are the user's position. */
    fun parse(body: String, ctx: Ctx? = null): List<Item> {
        val here = ctx?.let { mapOf("{@lat}" to "%.5f".format(java.util.Locale.US, it.lat), "{@lon}" to "%.5f".format(java.util.Locale.US, it.lon)) }.orEmpty()
        fun spec(key: String): String = here.entries.fold(m0(key)) { acc, (k, v) -> acc.replace(k, v) }
        val root = JSONTokener(body).nextValue()
        manifest.errorPath?.let { p -> Expr.string(p, root)?.let { throw Exception(it) } }
        val m = manifest.item
        val wantsPlace = m.has("lat")
        fun pairsOf(key: String, node: Any?, strict: Boolean) = pairs(m.optJSONArray(key), node, strict, here)
        return Expr.list(manifest.itemsPath, root).mapIndexedNotNull { index, node ->
            val title = Expr.string(spec("title"), node) ?: return@mapIndexedNotNull null
            val lat = Expr.number(spec("lat"), node)
            val lon = Expr.number(spec("lon"), node)
            if (wantsPlace && (lat == null || lon == null)) return@mapIndexedNotNull null
            Item(
                id = "$id:" + (Expr.string(spec("id"), node) ?: index.toString()),
                pluginId = id,
                title = title,
                subtitle = Expr.string(spec("subtitle"), node),
                lat = lat, lon = lon,
                altM = Expr.number(spec("altM"), node),
                heightM = Expr.number(spec("heightM"), node),
                image = Expr.string(spec("image"), node, strict = true),
                gallery = Expr.list(spec("gallery"), node).mapNotNull { Expr.format(it) },
                url = Expr.string(spec("url"), node, strict = true),
                fields = pairsOf("fields", node, strict = false),
                links = pairsOf("links", node, strict = true),
            )
        }
    }

    /** `[["Label", "expr"], …]` to (label, value) pairs, dropping empty values. */
    private fun m0(key: String): String = manifest.item.optString(key)

    private fun pairs(arr: JSONArray?, node: Any?, strict: Boolean, here: Map<String, String>): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val pair = arr.optJSONArray(i) ?: return@mapNotNull null
            val label = pair.optString(0).ifBlank { return@mapNotNull null }
            Expr.string(here.entries.fold(pair.optString(1)) { acc, (k, v) -> acc.replace(k, v) }, node, strict)?.let { label to it }
        }
    }
}
