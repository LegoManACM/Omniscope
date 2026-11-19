package dev.freeapi.explorer.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.freeapi.explorer.App
import dev.freeapi.explorer.R
import dev.freeapi.explorer.core.Geo
import dev.freeapi.explorer.core.Item
import dev.freeapi.explorer.sensors.OrientationSource

/** The full-screen item page: the info card, expanded, plus every source link. */
@Composable
fun ItemPage(app: App, item: Item, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val fix by app.location.fix.collectAsState()
    val plugin = app.registry.plugin(item.pluginId)
    val c = plugin.color
    val enu = enuFor(item, fix)
    val images = item.images
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(Modifier.fillMaxSize().background(Color(0xFF0E0F11)).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { PhIcon(R.drawable.ph_arrow_left, Color.White) }
            PhIcon(plugin.icon, c, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("${plugin.name} · ${plugin.category.label}", color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding()) {
            if (images.isNotEmpty()) {
                val pager = rememberPagerState { images.size }
                Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1D20))) {
                    HorizontalPager(pager) { i -> NetImage(app, images[i], Modifier.fillMaxSize()) }
                    if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Color(0x99000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.height(14.dp))
            }
            Text(item.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
            item.subtitle?.let { Text(it, color = Color(0xFFBDBDBD), fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp)) }

            // Where it is, relative to you.
            val facts = buildList {
                if (item.hasPlace) add("Location" to fmtCoords(item.lat!!, item.lon!!))
                item.altM?.let { add("Altitude" to fmtAlt(it)) }
                item.heightM?.let { add("Height above ground" to "%.1f m".format(it)) }
                item.distanceFrom(fix)?.let { add("Distance" to Geo.fmtDistance(it)) }
                enu?.let { add("Direction" to "${it.bearingDeg.toInt()}° ${Geo.compassPoint(it.bearingDeg)}, %.1f° %s".format(
                    kotlin.math.abs(it.elevationDeg), if (it.elevationDeg >= 0) "up" else "down")) }
            }
            if (facts.isNotEmpty()) {
                SectionHeader("Where")
                facts.forEach { (k, v) -> FactRow(k, v, mono = k == "Location") }
            }
            if (item.fields.isNotEmpty()) {
                SectionHeader("Details")
                item.fields.forEach { (k, v) -> FactRow(k, v) }
            }
            if (item.links.isNotEmpty() || item.url != null) {
                SectionHeader("Sources")
                (item.links + listOfNotNull(item.url?.let { "Link" to it })).distinctBy { it.second }.forEach { (label, url) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open(url) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PhIcon(if (label.startsWith("OpenStreetMap")) R.drawable.ph_map_trifold else if (label == "Website") R.drawable.ph_globe else R.drawable.ph_link, c, 20.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(label, color = Color.White, fontSize = 15.sp)
                            Text(Uri.parse(url).host ?: url, color = Color(0xFF9E9E9E), fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF222428))
                }
            }
            if (enu != null) {
                SectionHeader("Compass")
                Text("Pointing the phone straight at this? Align the compass to it.", color = Color(0xFF9E9E9E), fontSize = 13.sp)
                OutlinedButton(onClick = {
                    val r = app.orientation.rotation.value ?: return@OutlinedButton
                    val f = fix ?: return@OutlinedButton
                    val current = OrientationSource.cameraMagneticHeading(r) + OrientationSource.declination(f) + app.headingOffset.value
                    app.setHeadingOffset((app.headingOffset.value + (enu.bearingDeg - current)).toFloat())
                    onClose()
                }, modifier = Modifier.padding(top = 6.dp)) {
                    PhIcon(R.drawable.ph_crosshair, Color.White, 18.dp); Spacer(Modifier.width(8.dp)); Text("Align compass here")
                }
            }
            Text("Source: ${plugin.source}", color = Color(0xFF6E6E6E), fontSize = 12.sp, modifier = Modifier.padding(vertical = 24.dp))
        }
    }
}

@Composable
private fun FactRow(k: String, v: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(k, color = Color(0xFF8A8A8A), fontSize = 14.sp, modifier = Modifier.width(120.dp))
        Text(v, color = Color(0xFFE6E6E6), fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}