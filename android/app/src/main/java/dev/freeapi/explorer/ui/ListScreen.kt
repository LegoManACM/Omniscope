package dev.freeapi.explorer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.freeapi.explorer.App
import dev.freeapi.explorer.core.Geo

@Composable
fun ListScreen(app: App, topInset: Dp) {
    val items by app.visibleItems.collectAsState()
    val fix by app.location.fix.collectAsState()
    val ambient = items.filter { app.registry.plugin(it.pluginId).ambient }
    val things = items.filter { app.registry.plugin(it.pluginId).let { p -> !p.ambient && !p.layer } }
        .map { it to it.distanceFrom(fix) }
        .sortedBy { it.second ?: Double.MAX_VALUE }

    LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = topInset + 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Text("Around you", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(fix?.let { "${fmtCoords(it.lat, it.lon)}  ·  ±${it.accuracyM.toInt()} m  ·  ALT ${fmtAlt(it.altM)}" } ?: "Waiting for a location fix…",
                color = Color(0xFF9E9E9E), fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        items(ambient) { item ->
            val p = app.registry.plugin(item.pluginId)
            Surface(onClick = { app.opened.value = item }, shape = RoundedCornerShape(14.dp), color = Color(0xFF16181B),
                border = BorderStroke(1.dp, p.color.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PluginBadge(p, 40.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        item.subtitle?.let { Text(it, color = Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        item { SectionHeader("${things.size} things nearby") }
        items(things) { (item, dist) ->
            val p = app.registry.plugin(item.pluginId)
            Row(Modifier.fillMaxWidth().clickable { app.opened.value = item }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                PluginBadge(p, 36.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(item.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.subtitle?.let { Text(it, color = Color(0xFF9E9E9E), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.width(8.dp))
                Text(Geo.fmtDistance(dist), color = Color(0xFFBDBDBD), fontSize = 12.sp)
            }
            HorizontalDivider(color = Color(0xFF1E2023))
        }
    }
}
