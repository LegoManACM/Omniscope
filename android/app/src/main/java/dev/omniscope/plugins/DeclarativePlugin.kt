package dev.omniscope.plugins

import dev.omniscope.core.Category
import dev.omniscope.core.Cost
import dev.omniscope.core.Ctx
import dev.omniscope.core.Expr
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
                return parse(env.http.text(url, freshness.ttlMs, headers))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("No request URL")
    }

    /** Maps a response body to items (public for tests). */
    fun parse(body: String): List<Item> {
        val root = JSONTokener(body).nextValue()
        manifest.errorPath?.let { p -> Expr.string(p, root)?.let { throw Exception(it) } }
        val m = manifest.item
        val wantsPlace = m.has("lat")
        return Expr.list(manifest.itemsPath, root).mapIndexedNotNull { index, node ->
            val title = Expr.string(m.optString("title"), node) ?: return@mapIndexedNotNull null
            val lat = Expr.number(m.optString("lat"), node)
            val lon = Expr.number(m.optString("lon"), node)
            if (wantsPlace && (lat == null || lon == null)) return@mapIndexedNotNull null
            Item(
                id = "$id:" + (Expr.string(m.optString("id"), node) ?: index.toString()),
                pluginId = id,
                title = title,
                subtitle = Expr.string(m.optString("subtitle"), node),
                lat = lat, lon = lon,
                altM = Expr.number(m.optString("altM"), node),
                heightM = Expr.number(m.optString("heightM"), node),
                image = Expr.string(m.optString("image"), node, strict = true),
                gallery = Expr.list(m.optString("gallery"), node).mapNotNull { Expr.format(it) },
                url = Expr.string(m.optString("url"), node, strict = true),
                fields = pairs(m.optJSONArray("fields"), node, strict = false),
                links = pairs(m.optJSONArray("links"), node, strict = true),
            )
        }
    }

    /** `[["Label", "expr"], …]` to (label, value) pairs, dropping empty values. */
    private fun pairs(arr: JSONArray?, node: Any?, strict: Boolean): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val pair = arr.optJSONArray(i) ?: return@mapNotNull null
            val label = pair.optString(0).ifBlank { return@mapNotNull null }
            Expr.string(pair.optString(1), node, strict)?.let { label to it }
        }
    }
}
