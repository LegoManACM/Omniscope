package dev.omniscope

import android.app.Application
import android.content.Context
import dev.omniscope.core.Ctx
import dev.omniscope.core.Geo
import dev.omniscope.core.Http
import dev.omniscope.core.Item
import dev.omniscope.core.Registry
import dev.omniscope.core.PluginStore
import dev.omniscope.sensors.LocationSource
import dev.omniscope.sensors.OrientationSource
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class App : Application() {
    val scope = MainScope()
    lateinit var http: Http
    lateinit var location: LocationSource
    lateinit var orientation: OrientationSource
    lateinit var registry: Registry
    lateinit var store: PluginStore
    lateinit var ctx: StateFlow<Ctx?>

    /** The item shown full screen (the item page), from any view. */
    val opened = MutableStateFlow<Item?>(null)
    /** The item whose small card is showing on the map. */
    val focused = MutableStateFlow<Item?>(null)

    lateinit var scan: dev.omniscope.scan.ScanSession
    /** AR scan mode (long-press toggles it). */
    val scanMode = MutableStateFlow(false)

    private val prefs by lazy { getSharedPreferences("app", Context.MODE_PRIVATE) }

    /** Categories hidden by the filter button (they still fetch; they just aren't shown). */
    val hidden by lazy {
        MutableStateFlow(prefs.getStringSet("hidden_categories", emptySet())!!.mapNotNull { n ->
            dev.omniscope.core.Category.entries.firstOrNull { it.name == n }
        }.toSet())
    }

    fun setHidden(c: dev.omniscope.core.Category, hide: Boolean) {
        hidden.value = if (hide) hidden.value + c else hidden.value - c
        prefs.edit().putStringSet("hidden_categories", hidden.value.map { it.name }.toSet()).apply()
    }

    /** The filter's distance limit in metres; null = no limit. */
    val maxDistance by lazy { MutableStateFlow(prefs.getFloat("max_distance", -1f).takeIf { it > 0 }?.toDouble()) }

    // --- display settings ---------------------------------------------------------------------------------------
    /** Keep the screen on while AR or Nav is showing. */
    val keepScreenOn by lazy { MutableStateFlow(prefs.getBoolean("keep_screen_on", true)) }
    /** Faint outlines of nearby buildings in AR (the targeted building is always highlighted). */
    val buildingOutlines by lazy { MutableStateFlow(prefs.getBoolean("building_outlines", true)) }

    fun setKeepScreenOn(on: Boolean) { keepScreenOn.value = on; prefs.edit().putBoolean("keep_screen_on", on).apply() }
    fun setBuildingOutlines(on: Boolean) { buildingOutlines.value = on; prefs.edit().putBoolean("building_outlines", on).apply() }

    fun setMaxDistance(m: Double?) {
        maxDistance.value = m
        prefs.edit().putFloat("max_distance", m?.toFloat() ?: -1f).apply()
    }

    /**
     * Active items the views show: not in a hidden category, within the plugin's own radius (some APIs can't search
     * as small as you ask, so it's applied here too), and within the filter's distance limit. Things without a
     * ground distance that means anything (Sun, Moon, the ISS, "about here" readings) are always kept.
     */
    val visibleItems: StateFlow<List<Item>> by lazy {
        kotlinx.coroutines.flow.combine(registry.items, hidden, maxDistance, registry.radii, location.fix) { items, h, limit, _, fix ->
            items.filter { item ->
                val p = registry.plugin(item.pluginId)
                if (p.category in h) return@filter false
                if (p.ambient || !item.hasPlace || (item.altM ?: 0.0) >= 100_000 || fix == null) return@filter true
                val d = Geo.distanceM(fix.lat, fix.lon, item.lat!!, item.lon!!)
                (p.radius == null || d <= registry.radiusFor(p)) && (limit == null || d <= limit)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }
    /** Extra heading correction from tap-to-align, degrees, added on top of declination. */
    val headingOffset by lazy { MutableStateFlow(prefs.getFloat("heading_offset", 0f)) }

    fun setHeadingOffset(deg: Float) {
        val norm = (((deg + 180f) % 360f) + 360f) % 360f - 180f
        headingOffset.value = norm
        prefs.edit().putFloat("heading_offset", norm).apply()
    }

    // --- MCP server -------------------------------------------------------------------------------------------
    val mcpRunning = MutableStateFlow(false)
    val mcpError = MutableStateFlow<String?>(null)
    val mcpEnabled by lazy { MutableStateFlow(prefs.getBoolean("mcp_enabled", false)) }

    /** Bearer token clients must send. Made once, kept until regenerated. */
    val mcpToken: String
        get() = prefs.getString("mcp_token", null) ?: newMcpToken()

    fun newMcpToken(): String {
        val t = java.security.SecureRandom().let { r -> ByteArray(24).also { r.nextBytes(it) } }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("mcp_token", t).apply()
        if (mcpRunning.value) { stopMcp(); startMcp() }
        return t
    }

    fun setMcpEnabled(on: Boolean) {
        mcpEnabled.value = on
        prefs.edit().putBoolean("mcp_enabled", on).apply()
        if (on) startMcp() else stopMcp()
    }

    /** Called while the app is in the foreground (a location foreground service can't start from the background). */
    fun startMcp() {
        mcpError.value = null
        startForegroundService(android.content.Intent(this, dev.omniscope.mcp.McpService::class.java))
    }

    private fun stopMcp() = stopService(android.content.Intent(this, dev.omniscope.mcp.McpService::class.java))

    override fun onCreate() {
        super.onCreate()
        http = Http(cacheDir)
        location = LocationSource(this)
        orientation = OrientationSource(this)
        store = PluginStore(this, http).also { it.load() }
        registry = Registry(this, http, scope, store.installed)
        scan = dev.omniscope.scan.ScanSession(this)
        ctx = location.fix.map { f -> f?.let { Ctx(Geo.round4(it.lat), Geo.round4(it.lon), it.altM, it.timeMs) } }
            .stateIn(scope, SharingStarted.Eagerly, null)
        registry.start(ctx)
    }

    fun resume() {
        if (mcpEnabled.value && !mcpRunning.value) runCatching { startMcp() }
        location.acquire("ui")
        orientation.start()
        registry.running.value = true
        registry.kick()
    }

    fun pause() {
        location.release("ui")
        orientation.stop()
        registry.running.value = false
    }
}
