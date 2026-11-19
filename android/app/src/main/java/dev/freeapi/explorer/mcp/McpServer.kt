package dev.freeapi.explorer.mcp

import dev.freeapi.explorer.App
import dev.freeapi.explorer.core.Ctx
import dev.freeapi.explorer.core.Geo
import dev.freeapi.explorer.core.Item
import dev.freeapi.explorer.core.Plugin
import dev.freeapi.explorer.core.PluginState
import dev.freeapi.explorer.sensors.OrientationSource
import dev.freeapi.explorer.ui.enuFor
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs

private class RpcError(val code: Int, message: String) : Exception(message)

/**
 * A minimal MCP server (Streamable HTTP transport, JSON responses only, no SSE) on `POST /mcp`, behind a bearer
 * token. Tools read the same plugin registry the app's views use.
 */
class McpServer(private val app: App, port: Int, private val token: String) : NanoHTTPD(port) {

    override fun serve(s: IHTTPSession): Response {
        if (s.uri != "/mcp") return plain(Response.Status.NOT_FOUND, "Not found. The MCP endpoint is /mcp.")
        val auth = s.headers["authorization"].orEmpty()
        if (!constantTimeEquals(auth, "Bearer $token")) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "unauthorized"))
        when (s.method) {
            Method.GET -> return plain(Response.Status.METHOD_NOT_ALLOWED, "This server doesn't offer an SSE stream.")
            Method.DELETE -> return plain(Response.Status.OK, "")
            Method.POST -> {}
            else -> return plain(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        }
        val msg = try {
            JSONObject(readBody(s))
        } catch (e: Exception) {
            return json(Response.Status.BAD_REQUEST, rpcError(JSONObject.NULL, -32700, "Parse error"))
        }
        if (!msg.has("id") || msg.isNull("id")) {
            return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "") // a notification
        }
        val id = msg.get("id")
        val response = try {
            JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("result", handle(msg.optString("method"), msg.optJSONObject("params") ?: JSONObject()))
        } catch (e: RpcError) {
            rpcError(id, e.code, e.message ?: "error")
        } catch (e: Exception) {
            rpcError(id, -32603, e.message ?: e.javaClass.simpleName)
        }
        return json(Response.Status.OK, response)
    }

    private fun handle(method: String, params: JSONObject): Any = when (method) {
        "initialize" -> {
            val asked = params.optString("protocolVersion")
            JSONObject()
                .put("protocolVersion", if (asked in SUPPORTED) asked else SUPPORTED.first())
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "free-api-explorer").put("version", "0.1"))
                .put("instructions", "Live context about the phone owner's physical surroundings: where they are, " +
                    "weather and air, and things nearby from free data sources (places, aircraft overhead, wildlife, " +
                    "history, cameras…). Distances are metres, bearings are degrees from true north.")
        }
        "ping" -> JSONObject()
        "tools/list" -> JSONObject().put("tools", TOOLS)
        "tools/call" -> callTool(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject())
        else -> throw RpcError(-32601, "Method not found: $method")
    }

    private fun callTool(name: String, args: JSONObject): JSONObject {
        val result: Any = try {
            runBlocking(Dispatchers.IO) {
                when (name) {
                    "where_am_i" -> whereAmI()
                    "nearby" -> nearby(args)
                    "looking_at" -> lookingAt(args)
                    "list_plugins" -> listPlugins()
                    else -> throw RpcError(-32602, "Unknown tool: $name")
                }
            }
        } catch (e: RpcError) {
            throw e
        } catch (e: Exception) {
            return toolResult(e.message ?: e.javaClass.simpleName, isError = true)
        }
        return toolResult(if (result is JSONObject) result.toString(2) else result.toString())
    }

    // --- tools --------------------------------------------------------------------------------------------------

    private fun currentCtx(): Ctx = app.ctx.value ?: throw Exception("No location fix yet. Is location on, and has the phone had a GPS fix?")

    private suspend fun results(plugins: List<Plugin>, ctx: Ctx): List<Pair<Plugin, PluginState>> = coroutineScope {
        plugins.map { p -> async { p to (withTimeoutOrNull(25_000) { app.registry.fetchFresh(p, ctx) } ?: PluginState(error = "timed out")) } }.awaitAll()
    }

    private fun activePlugins() = app.registry.plugins.filter { app.registry.isActive(it) && !it.layer && it !is dev.freeapi.explorer.core.Detector }

    private suspend fun whereAmI(): JSONObject {
        val ctx = currentCtx()
        val fix = app.location.fix.value
        val here = JSONArray()
        for ((p, st) in results(activePlugins().filter { it.ambient }, ctx)) {
            st.items.forEach { here.put(itemJson(it, p, fix, includeGeometry = false)) }
            st.error?.let { here.put(JSONObject().put("plugin", p.id).put("error", it)) }
        }
        return JSONObject()
            .put("location", JSONObject().put("lat", fix?.lat).put("lon", fix?.lon).put("accuracy_m", fix?.accuracyM)
                .put("altitude_m", fix?.altM).put("age_s", fix?.let { (System.currentTimeMillis() - it.timeMs) / 1000 }))
            .put("here", here)
    }

    private suspend fun nearby(args: JSONObject): JSONObject {
        val ctx = currentCtx()
        val fix = app.location.fix.value
        val wantPlugins = args.optJSONArray("plugins")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val wantTags = args.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val radius = args.optDouble("radius_m", 5_000.0)
        val limit = args.optInt("limit", 40).coerceIn(1, 200)
        val plugins = activePlugins().filter { p ->
            !p.ambient && (wantPlugins == null || p.id in wantPlugins) && (wantTags == null || p.tags.any { it in wantTags })
        }
        val errors = JSONObject()
        val all = mutableListOf<Triple<Item, Plugin, Double>>()
        for ((p, st) in results(plugins, ctx)) {
            st.error?.let { errors.put(p.id, it) }
            for (item in st.items) {
                val d = if (item.hasPlace && fix != null) Geo.distanceM(fix.lat, fix.lon, item.lat!!, item.lon!!) else null
                // Sky things (Sun, Moon) have no distance; aircraft and the ISS count by ground distance.
                if (d == null && item.sky == null) continue
                if (d != null && d > radius && (item.altM ?: 0.0) < 100_000) continue
                all += Triple(item, p, d ?: Double.MAX_VALUE)
            }
        }
        val items = JSONArray()
        all.sortedBy { it.third }.take(limit).forEach { (item, p, _) -> items.put(itemJson(item, p, fix, includeGeometry = true)) }
        return JSONObject().put("count", items.length()).put("total_matching", all.size).put("radius_m", radius)
            .put("items", items).also { if (errors.length() > 0) it.put("errors", errors) }
    }

    private suspend fun lookingAt(args: JSONObject): JSONObject {
        val ctx = currentCtx()
        val fix = app.location.fix.value ?: throw Exception("No location fix yet.")
        // The rotation sensor only runs while the app is open; sample it briefly if needed.
        var r = app.orientation.rotation.value
        if (r == null) {
            withContext(Dispatchers.Main) { app.orientation.start() }
            repeat(20) { if (app.orientation.rotation.value == null) delay(100) }
            r = app.orientation.rotation.value
            if (!app.registry.running.value) withContext(Dispatchers.Main) { app.orientation.stop() }
        }
        r ?: throw Exception("No orientation reading available.")
        val heading = (OrientationSource.cameraMagneticHeading(r) + OrientationSource.declination(fix) + app.headingOffset.value + 360) % 360
        val width = args.optDouble("field_deg", 30.0)
        val items = JSONArray()
        val found = mutableListOf<Triple<Item, Plugin, Double>>()
        for ((p, st) in results(activePlugins().filter { !it.ambient }, ctx)) for (item in st.items) {
            val enu = enuFor(item, fix) ?: continue
            val off = abs(((enu.bearingDeg - heading + 540) % 360) - 180)
            if (off <= width / 2) found += Triple(item, p, enu.distM)
        }
        found.sortedBy { it.third }.take(30).forEach { (item, p, _) -> items.put(itemJson(item, p, fix, includeGeometry = true)) }
        return JSONObject().put("camera_heading_deg", heading.toInt()).put("camera_heading", Geo.compassPoint(heading))
            .put("note", "Heading is where the back camera points; accuracy depends on compass calibration.")
            .put("items", items)
    }

    private fun listPlugins(): JSONObject {
        val arr = JSONArray()
        for (p in app.registry.plugins) {
            val st = app.registry.states.value[p.id]
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("description", p.description)
                .put("tags", JSONArray(p.tags)).put("cost", p.cost.label).put("active", app.registry.isActive(p))
                .put("needs_key", p.key != null).put("kind", if (p.ambient) "about here" else "things nearby")
                .put("items_cached", st?.items?.size ?: 0).put("last_error", st?.error))
        }
        return JSONObject().put("plugins", arr)
    }

    private fun itemJson(item: Item, p: Plugin, fix: dev.freeapi.explorer.sensors.Fix?, includeGeometry: Boolean): JSONObject {
        val o = JSONObject().put("plugin", p.id).put("title", item.title)
        item.subtitle?.let { o.put("subtitle", it) }
        if (includeGeometry) {
            enuFor(item, fix)?.let { enu ->
                if (enu.distM.isFinite()) o.put("distance_m", enu.distM.toInt())
                o.put("bearing_deg", enu.bearingDeg.toInt()).put("compass", Geo.compassPoint(enu.bearingDeg))
                    .put("elevation_deg", "%.1f".format(enu.elevationDeg).toDouble())
            }
            if (item.hasPlace) o.put("lat", item.lat).put("lon", item.lon)
            item.altM?.let { o.put("altitude_m", it.toInt()) }
        }
        item.url?.let { o.put("url", it) }
        if (item.fields.isNotEmpty()) o.put("details", JSONObject().also { d -> item.fields.forEach { (k, v) -> d.put(k, v) } })
        return o
    }

    // --- plumbing -----------------------------------------------------------------------------------------------

    private fun readBody(s: IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun toolResult(text: String, isError: Boolean = false) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    private fun rpcError(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun json(status: Response.Status, body: JSONObject) = newFixedLengthResponse(status, "application/json", body.toString())
    private fun plain(status: Response.Status, body: String) = newFixedLengthResponse(status, "text/plain", body)

    private fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val PORT = 8787
        private val SUPPORTED = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        private fun schema(props: JSONObject = JSONObject()) = JSONObject().put("type", "object").put("properties", props)
        private fun strArray(desc: String) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", desc)

        private val TOOLS = JSONArray()
            .put(JSONObject().put("name", "where_am_i")
                .put("description", "The phone owner's current location plus readings about it: address, weather, air quality.")
                .put("inputSchema", schema()))
            .put(JSONObject().put("name", "nearby")
                .put("description", "Things around the phone owner from every active data plugin, nearest first: places, " +
                    "Wikipedia articles, aircraft overhead, the ISS, peaks, wildlife sightings, photos, earthquakes, " +
                    "licence-plate cameras, radio stations, webcams, Sun and Moon. Each has distance, bearing and elevation angle.")
                .put("inputSchema", schema(JSONObject()
                    .put("plugins", strArray("Only these plugin ids (see list_plugins)."))
                    .put("tags", strArray("Only plugins with any of these tags, e.g. sky, nature, history, surveillance."))
                    .put("radius_m", JSONObject().put("type", "number").put("description", "Max ground distance, default 5000."))
                    .put("limit", JSONObject().put("type", "integer").put("description", "Max items, default 40.")))))
            .put(JSONObject().put("name", "looking_at")
                .put("description", "What lies in the direction the phone's back camera is pointing right now (compass heading), nearest first.")
                .put("inputSchema", schema(JSONObject()
                    .put("field_deg", JSONObject().put("type", "number").put("description", "Width of the view cone in degrees, default 30.")))))
            .put(JSONObject().put("name", "list_plugins")
                .put("description", "The data plugins: what each provides, tags, cost (free/freemium/paid), whether it's on, and last error.")
                .put("inputSchema", schema()))
    }
}
