package dev.omniscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omniscope.App
import dev.omniscope.R
import dev.omniscope.core.CatalogEntry
import dev.omniscope.core.Category
import dev.omniscope.core.Cost
import dev.omniscope.core.EntryStatus
import dev.omniscope.core.PluginStore
import kotlinx.coroutines.launch

/** Search and install plugins from the official repo and any other GitHub sources you've added. */
@Composable
fun GetPluginsScreen(app: App) {
    val scope = rememberCoroutineScope()
    val info by app.store.info.collectAsState()
    val sources by app.store.sources.collectAsState()
    var entries by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<Category?>(null) }
    val busy = remember { mutableStateMapOf<String, String>() } // id -> "Installing…" or an error

    LaunchedEffect(reload, sources) {
        loading = true
        val (e, err) = app.store.catalog(force = reload > 0)
        entries = e; errors = err; loading = false
    }

    val q = query.trim().lowercase()
    val shown = entries.filter { e ->
        (category == null || e.category == category) &&
            (q.isEmpty() || listOf(e.name, e.description, e.author, e.id).any { it.lowercase().contains(q) } || e.tags.any { it.lowercase().contains(q) })
    }.sortedWith(compareBy({ !it.official }, { it.name.lowercase() }))

    LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp)) {
        item {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search plugins") }, singleLine = true,
                leadingIcon = { PhIcon(R.drawable.ph_binoculars, Color(0xFF9E9E9E), 20.dp) }, modifier = Modifier.fillMaxWidth())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                item { FilterChip(category == null, { category = null }, label = { Text("All") }) }
                items(Category.entries.filter { c -> entries.any { it.category == c } }) { c ->
                    FilterChip(category == c, { category = if (category == c) null else c }, label = { Text(c.label) },
                        leadingIcon = { PhIcon(c.icon, c.composeColor, 16.dp) })
                }
            }
        }
        if (loading) item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                Text("Reading plugin catalogues from GitHub…", color = Color(0xFF9E9E9E))
            }
        }
        errors.forEach { (repo, msg) ->
            item { Text("Couldn't read $repo: $msg", color = Color(0xFFFFB74D), fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        if (!loading && shown.isEmpty() && errors.isEmpty()) item { Text("No plugins match.", color = Color(0xFF9E9E9E), modifier = Modifier.padding(16.dp)) }
        items(shown, key = { "${it.repo}/${it.id}" }) { e ->
            val status = app.store.status(e).let { s -> if (s == EntryStatus.INSTALLED && info[e.id] == null) EntryStatus.AVAILABLE else s }
            CatalogRow(e, status, busy[e.id]) {
                busy[e.id] = "Installing…"
                scope.launch {
                    try {
                        app.store.install(e)
                        app.registry.setEnabled(e.id, true)
                        busy.remove(e.id)
                    } catch (ex: Exception) {
                        busy[e.id] = "Failed: ${ex.message}"
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF1E2023))
        }
        item { Sources(app, sources) { reload++ } }
    }
}

@Composable
private fun CatalogRow(e: CatalogEntry, status: EntryStatus, busy: String?, onInstall: () -> Unit) {
    val c = e.category.composeColor
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { PhIcon(e.icon, c, 24.dp) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (e.official) OfficialBadge()
            }
            Text(listOfNotNull(e.category.label, e.cost.label.takeIf { e.cost != Cost.FREE }, e.author.ifBlank { null }?.let { "by $it" },
                "v${e.version}").joinToString(" · "), color = c, fontSize = 12.sp)
            Text(e.description, color = Color(0xFFBDBDBD), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (e.hosts.isNotEmpty()) Text("Talks to: ${e.hosts.joinToString(", ")}", color = Color(0xFF7E7E7E), fontSize = 11.sp)
            if (e.repo != PluginStore.OFFICIAL_REPO) Text("From ${e.repo}", color = Color(0xFF7E7E7E), fontSize = 11.sp)
            busy?.let { Text(it, color = if (it.startsWith("Failed")) Color(0xFFFFB74D) else Color(0xFF9E9E9E), fontSize = 12.sp) }
        }
        when (status) {
            EntryStatus.AVAILABLE -> Button(onClick = onInstall, enabled = busy == null || busy.startsWith("Failed")) { Text("Install") }
            EntryStatus.UPDATE -> Button(onClick = onInstall, enabled = busy == null || busy.startsWith("Failed")) { Text("Update") }
            EntryStatus.INSTALLED -> Text("Installed", color = Color(0xFF81C784), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            EntryStatus.UNSUPPORTED -> Text("Needs app\nupdate", color = Color(0xFF9E9E9E), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** The GitHub repos plugins come from. The official one can't be removed. */
@Composable
private fun Sources(app: App, sources: List<String>, onReload: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 20.dp)) {
        SectionHeader("Sources")
        sources.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PhIcon(R.drawable.ph_globe, Color(0xFF9E9E9E), 18.dp)
                Text("github.com/$s", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                if (s == PluginStore.OFFICIAL_REPO) OfficialBadge() else TextButton(onClick = { app.store.removeSource(s) }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(draft, { draft = it; error = null }, placeholder = { Text("owner/repo") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { if (app.store.addSource(draft)) draft = "" else error = "Use owner/repo, e.g. someone/omniscope-plugins" }) { Text("Add") }
        }
        error?.let { Text(it, color = Color(0xFFFFB74D), fontSize = 12.sp) }
        Text("A source is any GitHub repo with a plugins/index.json. Plugins from other sources are never marked official; check what " +
            "each one talks to before installing.", color = Color(0xFF7E7E7E), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        TextButton(onClick = onReload) { Text("Check for updates") }
    }
}
