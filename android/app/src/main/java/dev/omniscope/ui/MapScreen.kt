package dev.omniscope.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.omniscope.App
import dev.omniscope.R
import dev.omniscope.core.Category
import dev.omniscope.core.Geo
import dev.omniscope.core.Item
import dev.omniscope.plugins.AlprPlugin
import dev.omniscope.sensors.OrientationSource
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/** OpenFreeMap: free vector tiles, no key, no usage limits. Positron is the light style from the sketch. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

private fun hex(c: Color) = "#%06X".format(0xFFFFFF and c.toArgb())

/** A circular sector polygon (lon/lat ring) for view cones. */
private fun sector(lat: Double, lon: Double, facingDeg: Double, halfDeg: Double, radiusM: Double): Polygon {
    val kE = 111_320.0 * cos(Math.toRadians(lat)); val kN = 110_540.0
    val pts = mutableListOf(Point.fromLngLat(lon, lat))
    var a = facingDeg - halfDeg
    while (a <= facingDeg + halfDeg + 0.01) {
        val r = Math.toRadians(a)
        pts += Point.fromLngLat(lon + radiusM * sin(r) / kE, lat + radiusM * cos(r) / kN)
        a += 5
    }
    pts += Point.fromLngLat(lon, lat)
    return Polygon.fromLngLats(listOf(pts))
}

@Composable
fun MapScreen(app: App, topInset: Dp, onFilter: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val items by app.visibleItems.collectAsState()
    val fix by app.location.fix.collectAsState()
    val rot by app.orientation.rotation.collectAsState()
    val offset by app.headingOffset.collectAsState()
    val focused by app.focused.collectAsState()
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centred by remember { mutableStateOf(false) }

    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                s.addSource(GeoJsonSource("cams-cone"))
                s.addLayer(FillLayer("cams-cone", "cams-cone").withProperties(fillColor(hex(Category.SURVEILLANCE.composeColor)), fillOpacity(0.22f)))
                s.addSource(GeoJsonSource("me-cone"))
                s.addLayer(FillLayer("me-cone", "me-cone").withProperties(fillColor("#FFD54F"), fillOpacity(0.45f)))
                s.addSource(GeoJsonSource("items"))
                s.addLayer(CircleLayer("items-glow", "items").withProperties(circleRadius(13f), circleColor(get("color")), circleOpacity(0.22f)))
                s.addLayer(CircleLayer("items-dot", "items").withProperties(
                    circleRadius(6.5f), circleColor(get("color")), circleStrokeColor("#FFFFFF"), circleStrokeWidth(1.5f)))
                s.addSource(GeoJsonSource("me"))
                s.addLayer(CircleLayer("me-dot", "me").withProperties(
                    circleRadius(8f), circleColor("#2E48DD"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
                style = s
            }
            m.addOnMapClickListener { pt ->
                val hit = m.queryRenderedFeatures(m.projection.toScreenLocation(pt), "items-dot", "items-glow").firstOrNull()
                val key = hit?.getStringProperty("key")
                app.focused.value = key?.let { k -> app.visibleItems.value.firstOrNull { it.id == k } }
                true
            }
            map = m
        }
    }

    // Dots and camera cones.
    LaunchedEffect(style, items) {
        val s = style ?: return@LaunchedEffect
        val placed = items.filter { it.hasPlace && it.shape == null && !app.registry.plugin(it.pluginId).ambient }
        s.getSourceAs<GeoJsonSource>("items")?.setGeoJson(FeatureCollection.fromFeatures(placed.map { item ->
            Feature.fromGeometry(Point.fromLngLat(item.lon!!, item.lat!!)).apply {
                addStringProperty("color", hex(app.registry.plugin(item.pluginId).color)); addStringProperty("key", item.id)
            }
        }))
        val cones = placed.filter { it.pluginId == AlprPlugin.id }.mapNotNull { cam ->
            AlprPlugin.facingOf(cam)?.let { Feature.fromGeometry(sector(cam.lat!!, cam.lon!!, it, AlprPlugin.VIEW_HALF_ANGLE_DEG, AlprPlugin.VIEW_RANGE_M)) }
        }
        s.getSourceAs<GeoJsonSource>("cams-cone")?.setGeoJson(FeatureCollection.fromFeatures(cones))
    }

    // You, and the way you're facing (throttled to whole degrees).
    val heading = rot?.let { r -> fix?.let { f -> ((OrientationSource.cameraMagneticHeading(r) + OrientationSource.declination(f) + offset + 360) % 360).toInt() } }
    LaunchedEffect(style, fix, heading) {
        val s = style ?: return@LaunchedEffect
        val f = fix ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("me")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(f.lon, f.lat)))
        s.getSourceAs<GeoJsonSource>("me-cone")?.setGeoJson(FeatureCollection.fromFeatures(
            listOfNotNull(heading?.let { Feature.fromGeometry(sector(f.lat, f.lon, it.toDouble(), 28.0, 45.0)) })))
        if (!centred) {
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.lat, f.lon), 16.0))
            centred = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Recentre.
        Surface(onClick = { fix?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lon), 16.0)) } },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = topInset + 12.dp, end = 12.dp).size(46.dp),
            shape = CircleShape, color = Color.White, shadowElevation = 3.dp) {
            Box(contentAlignment = Alignment.Center) { PhIcon(R.drawable.ph_navigation_arrow, Color(0xFF2E48DD), 22.dp) }
        }

        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterButton(app, onFilter)
            Box(Modifier.weight(1f)) {
                val f = focused
                if (f != null) ItemCard(app, f, fix) { app.opened.value = f }
                else NearbyCard(app, items, fix)
            }
        }
    }
}

/** Alerts first (inside a camera's view), then the nearest few things. */
@Composable
private fun NearbyCard(app: App, items: List<Item>, fix: dev.omniscope.sensors.Fix?) {
    val placed = items.filter { it.hasPlace && it.shape == null && !app.registry.plugin(it.pluginId).ambient }
    val watching = if (fix == null) emptyList() else placed.filter { it.pluginId == AlprPlugin.id && AlprPlugin.sees(it, fix.lat, fix.lon) }
    val nearest = placed.filter { it !in watching }.map { it to (it.distanceFrom(fix) ?: Double.MAX_VALUE) }.sortedBy { it.second }.take(4)
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xF2FFFFFF), border = BorderStroke(1.5.dp, Color(0xFF2E48DD)), shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Nearby", color = Color(0xFF2E48DD), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            watching.forEach { cam ->
                Row(Modifier.fillMaxWidth().clickable { app.opened.value = cam }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhIcon(R.drawable.ph_warning, Category.SURVEILLANCE.composeColor, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("In camera view: ${cam.title}", color = Color(0xFFC62828), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (nearest.isEmpty() && watching.isEmpty()) Text(if (fix == null) "Waiting for location…" else "Nothing loaded yet", color = Color(0xFF666666), fontSize = 13.sp)
            nearest.forEach { (item, d) ->
                val p = app.registry.plugin(item.pluginId)
                Row(Modifier.fillMaxWidth().clickable { app.opened.value = item }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(p.color))
                    Spacer(Modifier.width(10.dp))
                    Text(Geo.fmtDistance(d), color = Color(0xFF555555), fontSize = 13.sp, modifier = Modifier.width(62.dp))
                    Text(item.title, color = Color(0xFF1F1B1B), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
