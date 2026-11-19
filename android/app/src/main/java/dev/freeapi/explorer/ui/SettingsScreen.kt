package dev.freeapi.explorer.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.freeapi.explorer.App
import dev.freeapi.explorer.R
import dev.freeapi.explorer.core.Category
import dev.freeapi.explorer.core.Cost
import dev.freeapi.explorer.core.Detector
import dev.freeapi.explorer.core.Plugin
import dev.freeapi.explorer.mcp.McpService

@Composable
fun SettingsScreen(app: App, topInset: Dp) {
    val enabled by app.registry.enabled.collectAsState()
    val freeOnly by app.registry.freeOnly.collectAsState()
    val keys by app.registry.keys.collectAsState()
    var open by remember { mutableStateOf<Plugin?>(null) }
    val full = { _: Any? -> GridItemSpan(4) }

    LazyVerticalGrid(GridCells.Fixed(4), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = topInset + 10.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(span = { GridItemSpan(4) }) { Text("Settings", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }

        Category.entries.forEach { c ->
            val inCat = app.registry.plugins.filter { it.category == c }
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

        item(span = full) { SectionHeader("General") }
        item(span = full) {
            SettingRow("Free only", "Turns off every freemium and paid plugin.") { Switch(freeOnly, onCheckedChange = { app.registry.setFreeOnly(it) }) }
        }
        item(span = full) { CompassRow(app) }
        item(span = full) {
            SettingRow("Refresh everything", "Fetch every plugin again now.") { TextButton(onClick = { app.registry.refreshAll() }) { Text("Refresh") } }
        }
        item(span = full) { SectionHeader("Share") }
        item(span = full) { McpCard(app) }
        item(span = full) {
            Text("Icons: Phosphor (MIT). Map data © OpenStreetMap contributors (ODbL). Tiles: OpenFreeMap.",
                color = Color(0xFF6E6E6E), fontSize = 11.sp, modifier = Modifier.padding(top = 20.dp))
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
            lineHeight = 13.sp, modifier = Modifier.padding(top = 4.dp).height(28.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginSheet(app: App, p: Plugin, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled by app.registry.enabled.collectAsState()
    val states by app.registry.states.collectAsState()
    val keys by app.registry.keys.collectAsState()
    val st = states[p.id]
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PluginBadge(p, 48.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleLarge)
                    Text("${p.category.label} · ${p.cost.label}${if (p.key != null) " · needs a key" else ""}", color = p.color, fontSize = 13.sp)
                }
                Switch(p.id in enabled, onCheckedChange = { app.registry.setEnabled(p.id, it) })
            }
            Text(p.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            if (p.source.isNotBlank()) Text("Source: ${p.source}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
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
                Text("Default ${dev.freeapi.explorer.core.Geo.fmtDistance(spec.defaultM)}. The source supports ${dev.freeapi.explorer.core.Geo.fmtDistance(spec.minM)} " +
                    "to ${dev.freeapi.explorer.core.Geo.fmtDistance(spec.maxM)}.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            p.key?.let { spec ->
                var draft by remember(p.id) { mutableStateOf(keys[p.id].orEmpty()) }
                OutlinedTextField(draft, { draft = it }, label = { Text(spec.label) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                Row {
                    TextButton(onClick = { app.registry.setKey(p.id, draft) }) { Text("Save key") }
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spec.signupUrl))) }) { Text("Get a free key") }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Color(0xFF9E9E9E), fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun CompassRow(app: App) {
    val offset by app.headingOffset.collectAsState()
    SettingRow("Compass alignment", if (offset == 0f) "Not aligned. Use Align compass on an item's page (the Sun works well)."
        else "Corrected by %+.1f° on top of magnetic declination.".format(offset)) {
        if (offset != 0f) TextButton(onClick = { app.setHeadingOffset(0f) }) { Text("Reset") }
    }
}

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
                            "claude mcp add --transport http surroundings $url --header \"Authorization: Bearer $token\""))
                    }) { Text("Copy Claude command") }
                    TextButton(onClick = { token = app.newMcpToken() }) { Text("New token") }
                }
            }
        }
    }
}
