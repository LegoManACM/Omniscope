package dev.omniscope.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

data class PluginState(
    val items: List<Item> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0L,
    val at: Ctx? = null,
)

/** Holds the plugins, which are switched on, their keys and quotas, and their latest results. */
class Registry(context: Context, private val http: Http, private val scope: CoroutineScope, val plugins: List<Plugin>) {
    private val prefs = context.getSharedPreferences("registry", Context.MODE_PRIVATE)

    val enabled = MutableStateFlow(run {
        // Free plugins are on by default, including ones added in an app update after you'd saved your choices.
        val known = prefs.getStringSet("known", null)?.toSet() ?: emptySet()
        val saved = prefs.getStringSet("enabled", null)?.toSet()
        val newFree = plugins.filter { it.defaultOn && it.id !in known }.map { it.id }.toSet()
        val result = (saved ?: emptySet()) + newFree
        prefs.edit().putStringSet("known", plugins.map { it.id }.toSet()).putStringSet("enabled", result).apply()
        result
    })
    val freeOnly = MutableStateFlow(prefs.getBoolean("free_only", false))
    val keys = MutableStateFlow(plugins.mapNotNull { p -> p.key?.let { p.id to (prefs.getString("key_${p.id}", "") ?: "") } }.toMap())
    val states = MutableStateFlow<Map<String, PluginState>>(emptyMap())

    /** False while the app is in the background: nothing is fetched. */
    val running = MutableStateFlow(false)

    private val byId = plugins.associateBy { it.id }
    fun plugin(id: String): Plugin = byId.getValue(id)

    fun isActive(p: Plugin): Boolean =
        p.id in enabled.value && !(freeOnly.value && p.cost != Cost.FREE) && (p.key == null || !keys.value[p.id].isNullOrBlank())

    /** Items from every active plugin. */
    val items: StateFlow<List<Item>> = combine(states, enabled, freeOnly, keys) { s, _, _, _ ->
        plugins.filter { isActive(it) }.flatMap { s[it.id]?.items.orEmpty() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun setEnabled(id: String, on: Boolean) {
        enabled.value = if (on) enabled.value + id else enabled.value - id
        prefs.edit().putStringSet("enabled", enabled.value).apply()
        if (on) kick()
    }

    fun setFreeOnly(on: Boolean) {
        freeOnly.value = on
        prefs.edit().putBoolean("free_only", on).apply()
        kick()
    }

    fun setKey(id: String, key: String) {
        keys.value = keys.value + (id to key.trim())
        prefs.edit().putString("key_$id", key.trim()).apply()
        update(id) { PluginState() }
        kick()
    }

    /** Each plugin's search radius in metres, where the user changed it from the default. */
    val radii = MutableStateFlow(plugins.mapNotNull { p ->
        prefs.getFloat("radius_${p.id}", -1f).takeIf { it > 0 }?.let { p.id to it.toDouble() }
    }.toMap())

    fun radiusFor(p: Plugin): Double = p.radius?.let { spec -> (radii.value[p.id] ?: spec.defaultM).coerceIn(spec.minM, spec.maxM) } ?: 0.0

    fun setRadius(id: String, metres: Double) {
        radii.value = radii.value + (id to metres)
        prefs.edit().putFloat("radius_$id", metres.toFloat()).apply()
        update(id) { it.copy(updatedAt = 0L, error = null) } // refetch with the new radius
        kick()
    }

    fun usedToday(id: String): Int = prefs.getInt(quotaKey(id), 0)
    private fun quotaKey(id: String) = "quota_${id}_${LocalDate.now()}"
    private fun countCall(id: String) = prefs.edit().putInt(quotaKey(id), usedToday(id) + 1).apply()

    private val poke = Channel<Unit>(Channel.CONFLATED)
    fun kick() { poke.trySend(Unit) }

    fun refreshAll() {
        states.update { m -> m.mapValues { it.value.copy(updatedAt = 0L, error = null) } }
        kick()
    }

    fun start(where: StateFlow<Ctx?>) {
        scope.launch {
            while (true) {
                withTimeoutOrNull(5_000) { poke.receive() }
                if (!running.value) continue
                val ctx = where.value ?: continue
                for (p in plugins) if (p !is Detector && isActive(p)) maybeFetch(p, ctx)
            }
        }
    }

    private fun maybeFetch(p: Plugin, ctx: Ctx) {
        val st = states.value[p.id] ?: PluginState()
        if (st.loading) return
        val age = System.currentTimeMillis() - st.updatedAt
        val moved = st.at?.let { Geo.distanceM(it.lat, it.lon, ctx.lat, ctx.lon) } ?: Double.MAX_VALUE
        if (st.error != null) {
            if (age < 60_000) return
        } else if (age < p.freshness.ttlMs && moved < 300) return

        val limit = p.dailyLimit
        if (limit != null && usedToday(p.id) >= limit) {
            update(p.id) { it.copy(error = "Daily limit reached ($limit)", updatedAt = System.currentTimeMillis()) }
            return
        }
        update(p.id) { it.copy(loading = true) }
        scope.launch {
            val result = runCatching {
                if (p.cost != Cost.FREE) countCall(p.id)
                p.fetch(ctx.copy(timeMs = System.currentTimeMillis(), radiusM = radiusFor(p)), PluginEnv(http, keys.value[p.id]))
            }
            val now = System.currentTimeMillis()
            update(p.id) { old ->
                result.fold(
                    onSuccess = { PluginState(items = it, updatedAt = now, at = ctx) },
                    onFailure = { e -> old.copy(loading = false, error = e.message ?: e.javaClass.simpleName, updatedAt = now) },
                )
            }
        }
    }

    /**
     * Results for one plugin right now, for callers that can't wait for the refresh loop (the MCP server, with the
     * app closed): the cached state if still fresh for this place, else a fetch (which also updates the state).
     */
    suspend fun fetchFresh(p: Plugin, ctx: Ctx): PluginState {
        val st = states.value[p.id]
        val fresh = st != null && st.error == null && st.at != null &&
            System.currentTimeMillis() - st.updatedAt < p.freshness.ttlMs &&
            Geo.distanceM(st.at.lat, st.at.lon, ctx.lat, ctx.lon) < 300
        if (fresh) return st!!
        val limit = p.dailyLimit
        if (limit != null && usedToday(p.id) >= limit) return PluginState(error = "Daily limit reached ($limit)")
        return runCatching {
            if (p.cost != Cost.FREE) countCall(p.id)
            p.fetch(ctx.copy(timeMs = System.currentTimeMillis(), radiusM = radiusFor(p)), PluginEnv(http, keys.value[p.id]))
        }.fold(
            onSuccess = { PluginState(items = it, updatedAt = System.currentTimeMillis(), at = ctx).also { s -> update(p.id) { s } } },
            onFailure = { PluginState(error = it.message ?: it.javaClass.simpleName) },
        )
    }

    private fun update(id: String, f: (PluginState) -> PluginState) {
        states.update { it + (id to f(it[id] ?: PluginState())) }
    }
}
