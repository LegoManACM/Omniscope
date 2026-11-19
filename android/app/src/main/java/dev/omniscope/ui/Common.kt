package dev.omniscope.ui

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omniscope.App
import dev.omniscope.R
import dev.omniscope.core.Category
import dev.omniscope.core.Enu
import dev.omniscope.core.Geo
import dev.omniscope.core.Item
import dev.omniscope.core.Plugin
import dev.omniscope.sensors.Fix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone is held at roughly eye height above the ground you're standing on. */
const val EYE_HEIGHT_M = 1.5

val Category.composeColor: Color get() = Color(color)
val Plugin.color: Color get() = category.composeColor

/** Direction and distance from the user to an item, or null if it has neither a place nor a sky position. */
fun enuFor(item: Item, fix: Fix?): Enu? {
    item.sky?.let { return Geo.fromSky(it) }
    if (!item.hasPlace || fix == null) return null
    val myAlt = fix.altM ?: 0.0
    // No absolute altitude: assume the item stands on ground level with yours, at its height above that ground.
    val targetAlt = item.altM ?: (myAlt - EYE_HEIGHT_M + (item.heightM ?: EYE_HEIGHT_M))
    return Geo.enu(fix.lat, fix.lon, myAlt, item.lat!!, item.lon!!, targetAlt)
}

fun Item.distanceFrom(fix: Fix?): Double? =
    if (hasPlace && fix != null) Geo.distanceM(fix.lat, fix.lon, lat!!, lon!!) else null

fun fmtAlt(m: Double?): String = m?.let { "%,d m".format(it.toInt()) } ?: "?"
fun fmtCoords(lat: Double, lon: Double) = "%.5f, %.5f".format(lat, lon)

@Composable
fun PhIcon(@DrawableRes id: Int, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))

/** A plugin's icon on a soft disc of its category colour. */
@Composable
fun PluginBadge(plugin: Plugin, size: Dp = 36.dp, dim: Boolean = false) {
    val c = plugin.color
    Box(Modifier.size(size).clip(CircleShape).background(c.copy(alpha = if (dim) 0.08f else 0.18f)), contentAlignment = Alignment.Center) {
        PhIcon(plugin.icon, if (dim) c.copy(alpha = 0.35f) else c, size * 0.58f)
    }
}

@Composable
fun NetImage(app: App, url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val bmp by produceState<ImageBitmap?>(null, url) {
        value = runCatching {
            val bytes = app.http.bytes(url, 24 * 3_600_000L)
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }.getOrNull()
    }
    bmp?.let { Image(it, null, modifier, contentScale = contentScale) }
}

/**
 * The small info card (AR target, map selection): title, subtitle, a few fields, a thumbnail. Its border is the
 * category colour. [onOpen] opens the full item page.
 */
@Composable
fun ItemCard(app: App, item: Item, fix: Fix?, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val plugin = app.registry.plugin(item.pluginId)
    val c = plugin.color
    Surface(
        modifier = modifier.fillMaxWidth().let { if (onOpen != null) it.clickable { onOpen() } else it },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xE6121416),
        border = BorderStroke(1.5.dp, c.copy(alpha = 0.9f)),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhIcon(plugin.icon, c, 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(plugin.name.uppercase(), color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                    item.distanceFrom(fix)?.let { Text("  ·  ${Geo.fmtDistance(it)}", color = Color(0xFFAAAAAA), fontSize = 11.sp) }
                }
                Text(item.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
                item.subtitle?.let { Text(it, color = Color(0xFFBBBBBB), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                item.fields.take(3).forEach { (k, v) ->
                    Row(Modifier.padding(top = 3.dp)) {
                        Text(k, color = Color(0xFF8A8A8A), fontSize = 12.sp, modifier = Modifier.width(92.dp), maxLines = 1)
                        Text(v, color = Color(0xFFDDDDDD), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item.images.firstOrNull()?.let {
                Spacer(Modifier.width(12.dp))
                NetImage(app, it, Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(10.dp)))
            }
        }
    }
}

/** The funnel button at the bottom-left of AR and Map. */
@Composable
fun FilterButton(app: App, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hidden by app.hidden.collectAsState()
    Surface(onClick = onClick, modifier = modifier.size(52.dp), shape = RoundedCornerShape(14.dp), color = Color(0xE6121416),
        border = BorderStroke(1.dp, if (hidden.isEmpty()) Color(0x55FFFFFF) else Color(0xFFFFD54F))) {
        Box(contentAlignment = Alignment.Center) { PhIcon(R.drawable.ph_funnel, Color.White, 26.dp) }
    }
}

/** Which categories to show. Plugins themselves are switched on and off in Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(app: App, onManagePlugins: () -> Unit, onDismiss: () -> Unit) {
    val hidden by app.hidden.collectAsState()
    val enabled by app.registry.enabled.collectAsState()
    val maxDistance by app.maxDistance.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("Distance", style = MaterialTheme.typography.titleLarge)
            Text("Hide anything farther than this. Each plugin also has its own search radius in Settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            DistanceSlider(maxDistance, DISTANCE_STEPS.filter { it <= 250_000 }, noLimit = true) { app.setMaxDistance(it) }
            Spacer(Modifier.height(10.dp))
            Text("Show", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Category.entries.filter { it != Category.SCAN && it != Category.CONDITIONS }.forEach { c ->
                val plugins = app.registry.plugins.filter { it.category == c && it.id in enabled }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(c.composeColor.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        PhIcon(c.icon, c.composeColor, 20.dp)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(c.label, style = MaterialTheme.typography.bodyLarge)
                        Text(if (plugins.isEmpty()) "No plugins on" else plugins.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(c !in hidden, onCheckedChange = { app.setHidden(c, !it) })
                }
            }
            TextButton(onClick = onManagePlugins, modifier = Modifier.align(Alignment.End)) { Text("Manage plugins") }
        }
    }
}

/** Stepped distances for sliders: fine near you, coarse far away. */
val DISTANCE_STEPS = listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1_000.0, 2_500.0, 5_000.0, 10_000.0, 25_000.0, 50_000.0,
    100_000.0, 150_000.0, 250_000.0, 400_000.0, 1_000_000.0, 2_500_000.0, 5_000_000.0)

/**
 * A slider over [steps] (metres). With [noLimit], one more stop past the end means "no limit" (null).
 * [onChange] fires when the finger lifts, so a plugin isn't refetched at every step.
 */
@Composable
fun DistanceSlider(value: Double?, steps: List<Double>, noLimit: Boolean, onChange: (Double?) -> Unit) {
    val stops = steps.size + if (noLimit) 1 else 0
    fun indexOf(v: Double?) = if (v == null) stops - 1 else steps.indexOfFirst { it >= v - 0.5 }.let { if (it < 0) steps.lastIndex else it }
    var pos by androidx.compose.runtime.remember(value) { androidx.compose.runtime.mutableFloatStateOf(indexOf(value).toFloat()) }
    val i = pos.toInt().coerceIn(0, stops - 1)
    val current = if (noLimit && i == stops - 1) null else steps[i]
    Column {
        Text(current?.let { "Up to ${Geo.fmtDistance(it)}" } ?: "No limit", color = Color.White, fontSize = 15.sp)
        androidx.compose.material3.Slider(
            value = pos, onValueChange = { pos = it }, valueRange = 0f..(stops - 1).toFloat(), steps = (stops - 2).coerceAtLeast(0),
            onValueChangeFinished = { onChange(current) },
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier = modifier.padding(top = 18.dp, bottom = 8.dp), color = Color(0xFF9E9E9E),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)

val Spacing = Arrangement.spacedBy(8.dp)
