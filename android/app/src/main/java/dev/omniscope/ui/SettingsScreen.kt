package dev.omniscope.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omniscope.App
import dev.omniscope.R
import dev.omniscope.core.Category
import dev.omniscope.core.Cost
import dev.omniscope.core.Detector
import dev.omniscope.core.Geo
import dev.omniscope.core.Plugin
import dev.omniscope.core.PluginStore
import dev.omniscope.mcp.McpService

private enum class Page(val title: String, @DrawableRes val icon: Int, val subtitle: String) {
    MANAGE("Manage plugins", R.drawable.ph_sliders_horizontal, "What's installed: switch on and off, radius, keys, remove"),
    GET("Get plugins", R.drawable.ph_globe, "Browse and install plugins from GitHub"),
    DISPLAY("Display & AR", R.drawable.ph_crosshair, "Screen, building outlines, compass alignment"),
    DATA("Data & privacy", R.drawable.ph_key, "Free only, refresh, cache"),
    SHARING("Sharing", R.drawable.ph_plug, "MCP server for Claude, daimon and AOA"),
    ABOUT("About", R.drawable.ph_info, "Version, source code, licences"),
}

@Composable
fun SettingsScreen(app: App, topInset: Dp) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    val current = page
    if (current == null) {
        SettingsHome(app, topInset) { page = it }
    } else {
        BackHandler { page = null }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = topInset + 4.dp, start = 4.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = null }) { PhIcon(R.drawable.ph_arrow_left, Color.White) }
                Text(current.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            when (current) {
                Page.MANAGE -> ManagePlugins(app) { page = Page.GET }
                Page.GET -> GetPluginsScreen(app)
                Page.DISPLAY -> DisplaySettings(app)
                Page.DATA -> DataSettings(app)
                Page.SHARING -> Column(Modifier.padding(14.dp)) { McpCard(app) }
                Page.ABOUT -> About(app)
            }
        }
    }
}

@Composable
private fun SettingsHome(app: App, topInset: Dp, open: (Page) -> Unit) {
    val installed by app.store.installed.collectAsState()
    val enabled by app.registry.enabled.collectAsState()
    LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = topInset + 10.dp, bottom = 24.dp)) {
        item { Text("Settings", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp)) }
        Page.entries.forEach { p ->
            item {
                val sub = if (p == Page.MANAGE) "${installed.size} installed, ${installed.count { it.id in enabled }} on" else p.subtitle
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open(p) }.padding(vertical = 14.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF1E2126)), contentAlignment = Alignment.Center) {
                        PhIcon(p.icon, Color(0xFF8C9EFF), 22.dp)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(p.title, color = Color.White, fontSize = 16.sp)
                        Text(sub, color = Color(0xFF9E9E9E), fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = Color(0xFF1E2023))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Manage plugins

@Composable
private fun ManagePlugins(app: App, onGetMore: () -> Unit) {
    val installed by app.store.installed.collectAsState()
    val enabled by app.registry.enabled.collectAsState()
    val freeOnly by app.registry.freeOnly.collectAsState()
    val keys by app.registry.keys.collectAsState()
    var open by remember { mutableStateOf<Plugin?>(null) }

    LazyVerticalGrid(GridCells.Fixed(4), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Category.entries.forEach { c ->
            val inCat = installed.filter { it.category == c }
            if (inCat.isEmpty()) return@forEach
            item(key = "h_${c.name}", span = { GridItemSpan(4) }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    PhIcon(c.icon, c.composeColor, 16.dp); Spacer(Modifier.width(6.dp))
                    Text(c.label.uppercase(), color = Color(0xFF9E9E9E), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                }
            }
            items(inCat, key = { it.id }) { p ->
                val on = p.id in enabled && !(freeOnly && p.cost != Cost.FREE)
                val missingKey = p.key != null && keys[p.id].isNullOrBlank()
                PluginTile(p, on && !missingKey, missingKey, p.cost != Cost.FREE) { open = p }
            }
        }
        item(span = { GridItemSpan(4) }) {
            OutlinedButton(onClick = onGetMore, modifier = Modifier.padding(top = 16.dp)) {
                PhIcon(R.drawable.ph_globe, Color.White, 18.dp); Spacer(Modifier.width(8.dp)); Text("Get more plugins")
            }
        }
    }
    open?.let { p -> PluginSheet(app, p) { open = null } }
}

@Composable
private fun PluginTile(p: Plugin, on: Boolean, missingKey: Boolean, metered: Boolean, onClick: () -> Unit) {
    val c = p.color
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
            .background(if (on) c.copy(alpha = 0.16f) else Color(0xFF16181B))
            .border(1.dp, if (on) c.copy(alpha = 0.7f) else Color(0xFF2A2D31), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            PhIcon(p.icon, if (on) c else c.copy(alpha = 0.3f), 30.dp)
            if (missingKey || metered) {
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).clip(CircleShape).background(Color(0xFF2A2D31)), contentAlignment = Alignment.Center) {
                    PhIcon(R.drawable.ph_key, if (missingKey) Color(0xFFFFB74D) else Color(0xFFBDBDBD), 11.dp)
                }
            }
        }
        Text(p.name, color = if (on) Color.White else Color(0xFF8A8A8A), fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis, lineHeight = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(28.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginSheet(app: App, p: Plugin, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled by app.registry.enabled.collectAsState()
    val states by app.registry.states.collectAsState()
    val keys by app.registry.keys.collectAsState()
    val info by app.store.info.collectAsState()
    val meta = info[p.id]
    val st = states[p.id]
    var confirmRemove by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PluginBadge(p, 48.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (meta?.official == true) OfficialBadge()
                    }
                    Text("${p.category.label} · ${p.cost.label}${if (p.key != null) " · needs a key" else ""}", color = p.color, fontSize = 13.sp)
                }
                Switch(p.id in enabled, onCheckedChange = { app.registry.setEnabled(p.id, it) })
            }
            Text(p.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            if (p.source.isNotBlank()) Text("Data: ${p.source}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            meta?.let { m ->
                Text("Version ${m.manifest.version}" + (if (m.manifest.author.isNotBlank()) " by ${m.manifest.author}" else "") +
                    " · " + (if (m.source == "bundled") "came with the app" else "from ${m.source}") +
                    (if (m.manifest.native != null) " · built into the app" else ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if (m.manifest.effectiveHosts.isNotEmpty()) Text("Talks to: ${m.manifest.effectiveHosts.joinToString(", ")}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            val status = when {
                p is Detector -> "Runs on camera frames in scan mode (long-press the AR view)."
                st == null -> "Not fetched yet."
                st.loading -> "Loading…"
                st.error != null -> "Error: ${st.error}"
                else -> "${st.items.size} items, updated ${(System.currentTimeMillis() - st.updatedAt) / 1000} s ago. Refreshes ${p.freshness.name.lowercase()}."
            }
            Text(status, color = if (st?.error != null) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            p.dailyLimit?.let { Text("Used today: ${app.registry.usedToday(p.id)} of $it", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            p.radius?.let { spec ->
                val radii by app.registry.radii.collectAsState()
                Text("Search radius", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                val steps = (listOf(spec.minM) + DISTANCE_STEPS.filter { it > spec.minM && it < spec.maxM } + listOf(spec.maxM)).distinct()
                DistanceSlider(radii[p.id] ?: spec.defaultM, steps, noLimit = false) { m -> if (m != null) app.registry.setRadius(p.id, m) }
                Text("Default ${Geo.fmtDistance(spec.defaultM)}. The source supports ${Geo.fmtDistance(spec.minM)} to ${Geo.fmtDistance(spec.maxM)}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            p.key?.let { spec ->
                var draft by remember(p.id) { mutableStateOf(keys[p.id].orEmpty()) }
                OutlinedTextField(draft, { draft = it }, label = { Text(spec.label) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                Row {
                    TextButton(onClick = { app.registry.setKey(p.id, draft) }) { Text("Save key") }
                    if (spec.signupUrl.isNotBlank()) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spec.signupUrl))) }) { Text("Get a free key") }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (!confirmRemove) {
                TextButton(onClick = { confirmRemove = true }) { Text("Remove plugin", color = Color(0xFFEF9A9A)) }
            } else {
                Text(if (meta?.official == true) "Remove ${p.name}? You can get it back from Get plugins." else "Remove ${p.name}?",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { app.registry.setEnabled(p.id, false); app.store.remove(p.id); onDismiss() }) { Text("Remove", color = Color(0xFFEF5350)) }
                    TextButton(onClick = { confirmRemove = false }) { Text("Keep") }
                }
            }
        }
    }
}

@Composable
fun OfficialBadge() {
    Row(Modifier.padding(start = 8.dp).background(Color(0x332E48DD), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("OFFICIAL", color = Color(0xFF8C9EFF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Display, data, about

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Color(0xFF9E9E9E), fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun DisplaySettings(app: App) {
    val keepOn by app.keepScreenOn.collectAsState()
    val outlines by app.buildingOutlines.collectAsState()
    val offset by app.headingOffset.collectAsState()
    Column(Modifier.padding(horizontal = 16.dp)) {
        SettingRow("Keep screen on", "While the AR or Nav tab is showing.") { Switch(keepOn, onCheckedChange = { app.setKeepScreenOn(it) }) }
        SettingRow("Building outlines", "Faint outlines of buildings within 150 m in AR. The building you aim at is always highlighted.") {
            Switch(outlines, onCheckedChange = { app.setBuildingOutlines(it) })
        }
        SettingRow("Compass alignment", if (offset == 0f) "Not aligned. Use Align compass on an item's page (the Sun works well)."
            else "Corrected by %+.1f° on top of magnetic declination.".format(offset)) {
            if (offset != 0f) TextButton(onClick = { app.setHeadingOffset(0f) }) { Text("Reset") }
        }
    }
}

@Composable
private fun DataSettings(app: App) {
    val freeOnly by app.registry.freeOnly.collectAsState()
    var cacheBytes by remember { mutableStateOf(cacheSize(app)) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        SettingRow("Free only", "Turns off every freemium and paid plugin.") { Switch(freeOnly, onCheckedChange = { app.registry.setFreeOnly(it) }) }
        SettingRow("Refresh everything", "Fetch every plugin again now.") { TextButton(onClick = { app.registry.refreshAll() }) { Text("Refresh") } }
        SettingRow("Cached data", "%.1f MB of API responses, map tiles and images, so things load fast and work offline."
            .format(cacheBytes / 1_048_576.0)) {
            TextButton(onClick = { java.io.File(app.cacheDir, "http").deleteRecursively(); cacheBytes = cacheSize(app) }) { Text("Clear") }
        }
        Text("Your location only goes to the plugins you have switched on, and only to the hosts each one lists. API keys stay on this phone.",
            color = Color(0xFF9E9E9E), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

private fun cacheSize(app: App): Long = java.io.File(app.cacheDir, "http").walkTopDown().filter { it.isFile }.sumOf { it.length() }

@Composable
private fun About(app: App) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Omniscope $version", color = Color.White, fontSize = 18.sp)
        Text("Everything around you, from free data. Open source; plugins are JSON files anyone can contribute.",
            color = Color(0xFF9E9E9E), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${PluginStore.OFFICIAL_REPO}"))) },
            modifier = Modifier.padding(top = 8.dp)) { Text("Source code on GitHub") }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${PluginStore.OFFICIAL_REPO}/blob/main/docs/PLUGINS.md"))) }) {
            Text("Write a plugin")
        }
        Text("Icons: Phosphor (MIT). Map data © OpenStreetMap contributors (ODbL). Tiles: OpenFreeMap. Scanning: Google ML Kit, ZXing (Apache 2.0).",
            color = Color(0xFF6E6E6E), fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Sharing

@Composable
private fun McpCard(app: App) {
    val context = LocalContext.current
    val enabled by app.mcpEnabled.collectAsState()
    val running by app.mcpRunning.collectAsState()
    val error by app.mcpError.collectAsState()
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(app.mcpToken) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF16181B)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhIcon(R.drawable.ph_plug, Color(0xFF8C9EFF), 22.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("MCP server", color = Color.White, fontSize = 16.sp)
                    Text(when {
                        error != null -> "Error: $error"
                        running -> "Running: Claude, daimon or AOA can read your surroundings"
                        enabled -> "Starting…"
                        else -> "Off"
                    }, color = Color(0xFF9E9E9E), fontSize = 12.sp)
                }
                Switch(enabled, onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    app.setMcpEnabled(on)
                })
            }
            if (enabled) {
                val urls = remember(running) { McpService.urls() }
                Text("Reach it over Tailscale or the same Wi-Fi at:", color = Color(0xFF9E9E9E), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                urls.forEach { u ->
                    Text(u + if (McpService.isTailscale(u.substringAfter("//").substringBefore(':'))) "  (Tailscale)" else "",
                        color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Text("Token: " + if (showToken) token else "…" + token.takeLast(4), color = Color.White, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                Row {
                    TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") }
                    TextButton(onClick = {
                        val url = urls.firstOrNull() ?: "http://PHONE_IP:8787/mcp"
                        clipboard.setPrimaryClip(ClipData.newPlainText("MCP command",
                            "claude mcp add --transport http omniscope $url --header \"Authorization: Bearer $token\""))
                    }) { Text("Copy Claude command") }
                    TextButton(onClick = { token = app.newMcpToken() }) { Text("New token") }
                }
            }
        }
    }
}
