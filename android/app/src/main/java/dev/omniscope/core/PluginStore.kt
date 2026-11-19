package dev.omniscope.core

import android.content.Context
import dev.omniscope.plugins.DeclarativePlugin
import dev.omniscope.plugins.nativePlugins
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where an installed plugin came from. */
data class InstallInfo(val manifest: Manifest, val source: String, val commit: String?, val official: Boolean)

/** One plugin listed in a repository's plugins/index.json. */
data class CatalogEntry(
    val repo: String,
    val commit: String,
    val id: String,
    val name: String,
    val version: Int,
    val description: String,
    val category: Category,
    val icon: Int,
    val cost: Cost,
    val tags: List<String>,
    val author: String,
    val hosts: List<String>,
    val official: Boolean,
    val native: String?,
    val path: String,
    val sha256: String,
)

enum class EntryStatus { AVAILABLE, INSTALLED, UPDATE, UNSUPPORTED }

/**
 * Installed plugins and the catalogue they come from.
 *
 * - Official plugins ship inside the app (assets/plugins/official, copied from the repo's plugins/official at build
 *   time) and are installed on first run. Removing one remembers that, so it doesn't come back.
 * - Any GitHub repository with a `plugins/index.json` can be a source. Installs are pinned to the commit the index was
 *   read at and checked against the index's sha-256, so later pushes can't change an installed plugin silently.
 * - Native manifests name code built into the app; one this version doesn't have shows as "needs an app update".
 */
class PluginStore(private val context: Context, private val http: Http) {
    private val dir = File(context.filesDir, "plugins").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE)

    val installed = MutableStateFlow<List<Plugin>>(emptyList())
    val info = MutableStateFlow<Map<String, InstallInfo>>(emptyMap())
    val sources = MutableStateFlow(prefs.getString("sources", null)?.split('\n')?.filter { it.isNotBlank() } ?: listOf(OFFICIAL_REPO))

    private val removed: MutableSet<String> = prefs.getStringSet("removed", emptySet())!!.toMutableSet()

    /** Installs bundled official plugins that are new or newer, then loads everything installed. Call once at start. */
    fun load() {
        val bundled = context.assets.list("plugins/official").orEmpty()
        for (id in bundled) {
            val text = runCatching { context.assets.open("plugins/official/$id/plugin.json").bufferedReader().readText() }.getOrNull() ?: continue
            val m = runCatching { Manifest.parse(text) }.getOrNull() ?: continue
            // "preload": false official plugins are offered in Get plugins rather than installed for everyone.
            if (m.id in removed || m.problems().isNotEmpty() || !m.json.optBoolean("preload", true)) continue
            val current = readInstalled(m.id)
            if (current == null || current.manifest.version < m.version) write(m.id, text, "bundled", null, official = true)
        }
        refresh()
    }

    private fun refresh() {
        val all = dir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".meta.json") }.orEmpty()
            .mapNotNull { readInstalled(it.name.removeSuffix(".json")) }
            .sortedBy { it.manifest.name.lowercase() }
        info.value = all.associateBy { it.manifest.id }
        installed.value = all.mapNotNull { pluginFor(it.manifest) }
    }

    private fun pluginFor(m: Manifest): Plugin? = if (m.native != null) nativePlugins[m.native] else DeclarativePlugin(m)

    private fun readInstalled(id: String): InstallInfo? {
        val f = File(dir, "$id.json"); if (!f.exists()) return null
        val m = runCatching { Manifest.parse(f.readText()) }.getOrNull() ?: return null
        val meta = runCatching { JSONObject(File(dir, "$id.meta.json").readText()) }.getOrDefault(JSONObject())
        return InstallInfo(m, meta.optString("source", "bundled"), meta.optString("commit").ifBlank { null }, meta.optBoolean("official", false))
    }

    private fun write(id: String, text: String, source: String, commit: String?, official: Boolean) {
        File(dir, "$id.json").writeText(text)
        File(dir, "$id.meta.json").writeText(JSONObject().put("source", source).put("commit", commit ?: "").put("official", official).toString())
    }

    fun remove(id: String) {
        File(dir, "$id.json").delete(); File(dir, "$id.meta.json").delete()
        removed += id
        prefs.edit().putStringSet("removed", removed.toSet()).apply()
        refresh()
    }

    // --- catalogue ------------------------------------------------------------------------------------------------

    fun addSource(repo: String): Boolean {
        val r = repo.trim().removePrefix("https://github.com/").trim('/')
        if (!Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$").matches(r) || r in sources.value) return false
        sources.value = sources.value + r; saveSources(); return true
    }

    fun removeSource(repo: String) {
        if (repo == OFFICIAL_REPO) return
        sources.value = sources.value - repo; saveSources()
    }

    private fun saveSources() = prefs.edit().putString("sources", sources.value.joinToString("\n")).apply()

    /** The latest commit of [repo]'s default branch and its plugins/index.json (null if that commit has none). */
    private suspend fun readIndex(repo: String, shaTtlMs: Long): Pair<String, JSONObject?> {
        val sha = try {
            http.text("https://api.github.com/repos/$repo/commits/HEAD", shaTtlMs, mapOf("Accept" to "application/vnd.github.sha")).trim()
        } catch (e: HttpException) {
            throw Exception(when (e.code) {
                404, 422 -> "no such public repo on GitHub"
                403, 429 -> "GitHub's rate limit (60 checks an hour without an account); try again later"
                else -> "GitHub said ${e.code}"
            })
        }
        if (!Regex("^[0-9a-f]{40}$").matches(sha)) throw Exception("couldn't read the latest commit")
        val index = try {
            JSONObject(http.text("https://raw.githubusercontent.com/$repo/$sha/plugins/index.json", 30L * 24 * 3_600_000L))
        } catch (e: HttpException) {
            if (e.code == 404) null else throw e
        }
        return sha to index
    }

    /** Every source's index, newest commit on its default branch. Errors per source are returned alongside. */
    suspend fun catalog(force: Boolean = false): Pair<List<CatalogEntry>, Map<String, String>> {
        val entries = mutableListOf<CatalogEntry>()
        val errors = mutableMapOf<String, String>()
        for (repo in sources.value) {
            try {
                // The commit sha pins everything read from this repo for this listing. It's cached briefly (GitHub allows
                // 60 unauthenticated checks an hour); if the cached commit turns out to have no index, check again fresh.
                var read = readIndex(repo, if (force) 0 else 60_000L)
                if (read.second == null && !force) read = readIndex(repo, 0)
                val sha = read.first
                val index = read.second ?: throw Exception("this repo has no plugins/index.json on its main branch")
                val list = index.optJSONArray("plugins") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    entries += CatalogEntry(
                        repo = repo, commit = sha, id = e.optString("id"), name = e.optString("name"), version = e.optInt("version", 1),
                        description = e.optString("description"),
                        category = Category.entries.firstOrNull { it.name == e.optString("category").uppercase() } ?: Category.PLACES,
                        icon = PluginIcons.byName[e.optString("icon")] ?: Category.PLACES.icon,
                        cost = Cost.entries.firstOrNull { it.label == e.optString("cost") } ?: Cost.FREE,
                        tags = e.optJSONArray("tags").strings(), author = e.optString("author"), hosts = e.optJSONArray("hosts").strings(),
                        // Only the main repo can mark plugins official.
                        official = repo == OFFICIAL_REPO && e.optBoolean("official", false),
                        native = e.optString("native").ifBlank { null }, path = e.optString("path"), sha256 = e.optString("sha256"),
                    )
                }
            } catch (e: Exception) {
                errors[repo] = e.message ?: e.javaClass.simpleName
            }
        }
        return entries to errors
    }

    fun status(e: CatalogEntry): EntryStatus {
        if (e.native != null && e.native !in nativePlugins) return EntryStatus.UNSUPPORTED
        val have = info.value[e.id] ?: return EntryStatus.AVAILABLE
        return if (have.manifest.version < e.version) EntryStatus.UPDATE else EntryStatus.INSTALLED
    }

    /** Downloads, verifies and installs (or updates) a catalogue entry. Throws with a readable message on failure. */
    suspend fun install(e: CatalogEntry) {
        if (!Regex("^plugins/[A-Za-z0-9_./-]+\\.json$").matches(e.path) || e.path.contains("..")) throw Exception("Bad plugin path")
        val text = http.text("https://raw.githubusercontent.com/${e.repo}/${e.commit}/${e.path}", 30L * 24 * 3_600_000L)
        if (!Manifest.sha256(text).equals(e.sha256, ignoreCase = true)) throw Exception("Checksum mismatch: the manifest doesn't match the index")
        val m = Manifest.parse(text)
        if (m.id != e.id) throw Exception("Manifest id ${m.id} doesn't match the index (${e.id})")
        m.problems().takeIf { it.isNotEmpty() }?.let { throw Exception(it.joinToString("; ")) }
        if (m.native != null && m.native !in nativePlugins) throw Exception("Needs a newer version of the app")
        val existing = info.value[m.id]
        if (existing != null && existing.official && !e.official) throw Exception("An official plugin with this id is installed")
        write(m.id, text, e.repo, e.commit, e.official)
        removed -= m.id
        prefs.edit().putStringSet("removed", removed.toSet()).apply()
        refresh()
    }

    companion object {
        const val OFFICIAL_REPO = "LegoManACM/Omniscope"
    }
}
