package dev.omniscope.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.omniscope.App
import dev.omniscope.R
import dev.omniscope.core.Category
import dev.omniscope.core.Enu
import dev.omniscope.core.Geo
import dev.omniscope.core.Item
import dev.omniscope.sensors.Fix
import dev.omniscope.sensors.OrientationSource
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

// ------------------------------------------------------------------------------------------------------------------
// Camera

/** Field of view of the back camera along the sensor's long and short sides, in degrees. */
private data class Fov(val longDeg: Double, val shortDeg: Double)

private fun backCameraFov(context: android.content.Context): Fov {
    val cm = context.getSystemService(CameraManager::class.java)
    for (id in cm.cameraIdList) {
        val ch = cm.getCameraCharacteristics(id)
        if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
        fun fov(mm: Float) = Math.toDegrees(2 * atan(mm / (2.0 * focal)))
        return Fov(fov(max(size.width, size.height)), fov(min(size.width, size.height)))
    }
    return Fov(66.0, 51.0)
}

/** Camera preview, plus frame analysis for scan mode while [analyzer] is set. Both use 4:3 so boxes line up. */
@Composable
private fun CameraPreview(analyzer: ImageAnalysis.Analyzer?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(owner, analyzer) {
        val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get().also { p ->
                val preview = Preview.Builder().setResolutionSelector(ratio).build().also { it.setSurfaceProvider(view.surfaceProvider) }
                p.unbindAll()
                runCatching {
                    if (analyzer != null) {
                        val analysis = ImageAnalysis.Builder().setResolutionSelector(ratio)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            .also { it.setAnalyzer(executor, analyzer) }
                        p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } else {
                        p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    }
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
}

// ------------------------------------------------------------------------------------------------------------------
// Projection

/**
 * World to screen. [r] is the rotation matrix (world = R · device, world = East, magnetic North, Up), [offsetDeg]
 * is declination plus the user's alignment, so true-north directions are first turned into the sensor's frame.
 */
private class Proj(val r: FloatArray, offsetDeg: Double, val f: Double, val cx: Float, val cy: Float) {
    val cosT = cos(Math.toRadians(offsetDeg)); val sinT = sin(Math.toRadians(offsetDeg))

    fun project(e: Double, n: Double, u: Double): Offset? {
        val e2 = e * cosT - n * sinT; val n2 = n * cosT + e * sinT
        val dx = r[0] * e2 + r[3] * n2 + r[6] * u
        val dy = r[1] * e2 + r[4] * n2 + r[7] * u
        val dz = r[2] * e2 + r[5] * n2 + r[8] * u
        if (dz > -0.02) return null
        return Offset((cx + f * dx / -dz).toFloat(), (cy - f * dy / -dz).toFloat())
    }

    /** The camera's view direction as a true-north ENU unit vector. */
    fun forward(): DoubleArray {
        val em = -r[2].toDouble(); val nm = -r[5].toDouble(); val u = -r[8].toDouble()
        return doubleArrayOf(em * cosT + nm * sinT, nm * cosT - em * sinT, u)
    }

    fun headingDeg(): Double { val fw = forward(); return (Math.toDegrees(kotlin.math.atan2(fw[0], fw[1])) + 360) % 360 }
    fun pitchDeg(): Double = Math.toDegrees(kotlin.math.asin(forward()[2].coerceIn(-1.0, 1.0)))
}

private fun focal(size: IntSize, fov: Fov): Double {
    val w = size.width.toDouble(); val h = size.height.toDouble()
    // PreviewView FILL_CENTER on a portrait 4:3 stream: the long side fits the height unless the view is squatter.
    return if (h / w >= 4.0 / 3.0) (h / 2) / tan(Math.toRadians(fov.longDeg / 2)) else (w / 2) / tan(Math.toRadians(fov.shortDeg / 2))
}

// ------------------------------------------------------------------------------------------------------------------
// Scene

private class Point(val item: Item, val enu: Enu, val color: Int, val distM: Double?)

/** A building in local metres around you: footprint [e0, n0, …] and bottom/top heights relative to eye level. */
private class Wire(val item: Item, val en: FloatArray, val bottom: Float, val top: Float, val distM: Float, val tint: Int?, val containsYou: Boolean)

private fun buildPoints(app: App, items: List<Item>, fix: Fix?): List<Point> =
    items.filter { it.shape == null && !app.registry.plugin(it.pluginId).ambient }
        .mapNotNull { item -> enuFor(item, fix)?.let { Point(item, it, app.registry.plugin(item.pluginId).color.toArgb(), item.distanceFrom(fix)) } }
        .sortedBy { it.distM ?: Double.MAX_VALUE }
        .take(200)

private fun buildWires(app: App, items: List<Item>, fix: Fix?): List<Wire> {
    if (fix == null) return emptyList()
    val kE = 111_320.0 * cos(Math.toRadians(fix.lat)); val kN = 110_540.0
    fun e(lon: Double) = ((lon - fix.lon) * kE).toFloat()
    fun n(lat: Double) = ((lat - fix.lat) * kN).toFloat()
    // A building with a place or camera inside takes that category's colour.
    val markers = items.filter { it.hasPlace && it.shape == null && it.altM == null }
        .map { Triple(e(it.lon!!), n(it.lat!!), app.registry.plugin(it.pluginId).color.toArgb()) }
    return items.mapNotNull { item ->
        val s = item.shape ?: return@mapNotNull null
        val dist = Geo.distanceM(fix.lat, fix.lon, item.lat!!, item.lon!!).toFloat()
        if (dist > 320f) return@mapNotNull null
        val pts = s.ring.size / 2
        val en = FloatArray(pts * 2)
        for (k in 0 until pts) { en[2 * k] = e(s.ring[2 * k + 1]); en[2 * k + 1] = n(s.ring[2 * k]) }
        Wire(item, en, (s.minM - EYE_HEIGHT_M).toFloat(), (s.maxM - EYE_HEIGHT_M).toFloat(), dist,
            markers.firstOrNull { (px, py, _) -> inside(en, px, py) }?.third, inside(en, 0f, 0f))
    }.sortedByDescending { it.distM }
}

private fun inside(poly: FloatArray, x: Float, y: Float): Boolean {
    var c = false
    val n = poly.size / 2
    var j = n - 1
    for (i in 0 until n) {
        val xi = poly[2 * i]; val yi = poly[2 * i + 1]; val xj = poly[2 * j]; val yj = poly[2 * j + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
        j = i
    }
    return c
}

/** Distance along a horizontal ray from the origin to the polygon's nearest edge, or null if it misses. */
private fun rayHit(poly: FloatArray, de: Double, dn: Double): Double? {
    var best: Double? = null
    val n = poly.size / 2
    for (k in 0 until n) {
        val ax = poly[2 * k].toDouble(); val ay = poly[2 * k + 1].toDouble()
        val j = (k + 1) % n
        val ex = poly[2 * j] - ax; val ey = poly[2 * j + 1] - ay
        val denom = de * ey - dn * ex
        if (kotlin.math.abs(denom) < 1e-9) continue
        val t = (ax * ey - ay * ex) / denom
        val s = (ax * dn - ay * de) / denom
        if (t >= 0 && s in 0.0..1.0 && (best == null || t < best)) best = t
    }
    return best
}

/** What the reticle is on: a point within a few degrees, otherwise the first building the view ray hits. */
private fun pick(proj: Proj, points: List<Point>, wires: List<Wire>, current: Item?): Item? {
    val fw = proj.forward()
    var best: Point? = null; var bestAng = Double.MAX_VALUE
    for (p in points) {
        val dot = (p.enu.e * fw[0] + p.enu.n * fw[1] + p.enu.u * fw[2]).coerceIn(-1.0, 1.0)
        val ang = Math.toDegrees(acos(dot))
        val limit = if (p.item.id == current?.id) 7.0 else 4.0 // hysteresis: harder to lose than to gain
        if (ang < limit && ang < bestAng) { best = p; bestAng = ang }
    }
    if (best != null) return best.item
    val h = hypot(fw[0], fw[1]); if (h < 0.05) return null
    val de = fw[0] / h; val dn = fw[1] / h; val slope = fw[2] / h
    var hit: Wire? = null; var bestT = 320.0
    for (w in wires) {
        if (w.containsYou) continue
        val t = rayHit(w.en, de, dn) ?: continue
        val z = t * slope
        if (t < bestT && z >= w.bottom - 0.5 && z <= w.top + 0.5) { bestT = t; hit = w }
    }
    return hit?.item
}

/** Reusable buffers for projecting building wireframes each frame without allocating. */
private class WireScratch {
    var lines = FloatArray(1024)
    private var cam = FloatArray(256)

    fun project(w: Wire, p: Proj): Int {
        val r = p.r
        val pts = w.en.size / 2
        if (cam.size < pts * 6) cam = FloatArray(pts * 6)
        for (k in 0 until pts) {
            val e = w.en[2 * k].toDouble(); val n = w.en[2 * k + 1].toDouble()
            val e2 = e * p.cosT - n * p.sinT; val n2 = n * p.cosT + e * p.sinT
            val bx = r[0] * e2 + r[3] * n2; val by = r[1] * e2 + r[4] * n2; val bz = r[2] * e2 + r[5] * n2
            val o = k * 6 // bottom vertex at o, top vertex at o + 3
            cam[o] = (bx + r[6] * w.bottom).toFloat(); cam[o + 1] = (by + r[7] * w.bottom).toFloat(); cam[o + 2] = (bz + r[8] * w.bottom).toFloat()
            cam[o + 3] = (bx + r[6] * w.top).toFloat(); cam[o + 4] = (by + r[7] * w.top).toFloat(); cam[o + 5] = (bz + r[8] * w.top).toFloat()
        }
        var count = 0
        fun seg(a: Int, b: Int) {
            var ax = cam[a]; var ay = cam[a + 1]; var az = cam[a + 2]
            var bx = cam[b]; var by = cam[b + 1]; var bz = cam[b + 2]
            val near = -0.2f
            if (az > near && bz > near) return
            // Clip the part behind the camera so lines through it don't flip.
            if (az > near) { val t = (near - az) / (bz - az); ax += t * (bx - ax); ay += t * (by - ay); az = near }
            if (bz > near) { val t = (near - bz) / (az - bz); bx += t * (ax - bx); by += t * (ay - by); bz = near }
            if (lines.size < count + 4) lines = lines.copyOf(lines.size * 2)
            lines[count++] = (p.cx + p.f * ax / -az).toFloat(); lines[count++] = (p.cy - p.f * ay / -az).toFloat()
            lines[count++] = (p.cx + p.f * bx / -bz).toFloat(); lines[count++] = (p.cy - p.f * by / -bz).toFloat()
        }
        for (k in 0 until pts) {
            val next = (k + 1) % pts
            seg(k * 6, next * 6)          // footprint
            seg(k * 6 + 3, next * 6 + 3)  // roof
            seg(k * 6, k * 6 + 3)         // corner
        }
        return count
    }

    /** Translucent faces (walls and roof) for the targeted building; faces crossing behind the camera are skipped. */
    fun fill(w: Wire, p: Proj, canvas: android.graphics.Canvas, paint: Paint) {
        val pts = w.en.size / 2
        fun pt(k: Int, top: Boolean) = p.project(w.en[2 * k].toDouble(), w.en[2 * k + 1].toDouble(), (if (top) w.top else w.bottom).toDouble())
        val roof = Path(); var ok = true
        for (k in 0 until pts) { val q = pt(k, true) ?: run { ok = false; null } ?: break; if (k == 0) roof.moveTo(q.x, q.y) else roof.lineTo(q.x, q.y) }
        if (ok) { roof.close(); canvas.drawPath(roof, paint) }
        for (k in 0 until pts) {
            val j = (k + 1) % pts
            val a = pt(k, false) ?: continue; val b = pt(j, false) ?: continue; val c = pt(j, true) ?: continue; val d = pt(k, true) ?: continue
            val wall = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }
            canvas.drawPath(wall, paint)
        }
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Screen

@Composable
fun ArScreen(app: App, topInset: Dp, onFilter: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val fix by app.location.fix.collectAsState()
    val rot by app.orientation.rotation.collectAsState()
    val headingAcc by app.orientation.headingAccuracyDeg.collectAsState()
    val calibration by app.orientation.calibration.collectAsState()
    val items by app.visibleItems.collectAsState()
    val offset by app.headingOffset.collectAsState()
    val scanMode by app.scanMode.collectAsState()
    val detections by app.scan.detections.collectAsState()
    val frameSize by app.scan.frameSize.collectAsState()
    val products by app.scan.products.collectAsState()
    val fov = remember { backCameraFov(context) }

    val declination = remember(fix?.let { Geo.round4(it.lat * 10) }, fix?.let { Geo.round4(it.lon * 10) }) {
        fix?.let { OrientationSource.declination(it) } ?: 0f
    }
    val totalOffset = (declination + offset).toDouble()
    val points = remember(items, fix) { buildPoints(app, items, fix) }
    val wires = remember(items, fix) { buildWires(app, items, fix) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var target by remember { mutableStateOf<Item?>(null) }
    val scratch = remember { WireScratch() }

    // Targeting runs ~15 times a second, with a short dwell so the reticle doesn't flicker between neighbours.
    LaunchedEffect(points, wires, totalOffset, viewSize) {
        var candidate: String? = null; var since = 0L; var lostSince = 0L
        while (true) {
            val r = app.orientation.rotation.value
            if (r != null && viewSize.width > 0) {
                val proj = Proj(r, totalOffset, focal(viewSize, fov), viewSize.width / 2f, viewSize.height / 2f)
                val picked = pick(proj, points, wires, target)
                val now = System.currentTimeMillis()
                when {
                    picked?.id == target?.id -> { candidate = null; lostSince = 0L }
                    picked == null -> { if (lostSince == 0L) lostSince = now else if (now - lostSince > 450) { target = null; lostSince = 0L } }
                    picked.id != candidate -> { candidate = picked.id; since = now }
                    now - since > 160 -> { target = picked; candidate = null; lostSince = 0L }
                }
            }
            delay(66)
        }
    }

    val paints = remember {
        object {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = Typeface.MONOSPACE; setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt()) }
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt()) }
            val tape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt()) }
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            val boxText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
        }
    }
    val scanColor = Category.SCAN.composeColor.toArgb()
    val boxes = remember { mutableListOf<Pair<RectF, dev.omniscope.core.Detection>>() }

    val glow = rememberInfiniteTransition(label = "scan glow")
    val glowAlpha by glow.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "alpha")

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
        CameraPreview(if (scanMode) app.scan else null, Modifier.fillMaxSize())

        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    app.scanMode.value = !app.scanMode.value
                    if (!app.scanMode.value) app.scan.clear()
                },
                onTap = { p ->
                    val hit = boxes.firstOrNull { it.first.contains(p.x, p.y) }?.second
                    when {
                        hit != null -> app.opened.value = app.scan.itemFor(hit)
                        target != null -> app.opened.value = target
                    }
                },
            )
        }) {
            val r = rot ?: return@Canvas
            val nc = drawContext.canvas.nativeCanvas
            val w = size.width; val h = size.height
            val proj = Proj(r, totalOffset, focal(IntSize(w.toInt(), h.toInt()), fov), w / 2, h / 2)
            val cx = w / 2; val cy = h / 2
            val dp = density

            // Buildings: faint outlines when close, the targeted one filled.
            for (wire in wires) {
                val isTarget = wire.item.id == target?.id
                if (!isTarget && wire.distM > 150f) continue
                val count = scratch.project(wire, proj)
                if (count == 0) continue
                if (isTarget) {
                    val c = Category.BUILDINGS.composeColor.toArgb()
                    paints.fill.color = c; paints.fill.alpha = 60
                    scratch.fill(wire, proj, nc, paints.fill)
                    paints.stroke.color = c; paints.stroke.alpha = 235; paints.stroke.strokeWidth = 3.5f * dp
                } else {
                    val fade = (1f - wire.distM / 160f).coerceIn(0.1f, 1f)
                    paints.stroke.color = wire.tint ?: 0xFFB2EBF2.toInt(); paints.stroke.strokeWidth = 1f * dp
                    paints.stroke.alpha = ((if (wire.tint != null) 120 else 70) * fade).toInt()
                }
                nc.drawLines(scratch.lines, 0, count, paints.stroke)
            }

            // Points: soft glowing dots, smaller with distance.
            for (p in points.asReversed()) {
                val s = proj.project(p.enu.e, p.enu.n, p.enu.u) ?: continue
                if (s.x < -40 || s.x > w + 40 || s.y < -40 || s.y > h + 40) continue
                val d = p.distM ?: 5_000.0
                val rad = ((15 - 2.6 * log10(max(d, 10.0))).coerceIn(5.5, 12.0) * dp).toFloat()
                paints.dot.color = p.color
                paints.dot.alpha = 40; nc.drawCircle(s.x, s.y, rad * 2.3f, paints.dot)
                paints.dot.alpha = 90; nc.drawCircle(s.x, s.y, rad * 1.55f, paints.dot)
                paints.dot.alpha = 235; nc.drawCircle(s.x, s.y, rad, paints.dot)
                paints.dot.color = 0xFF101010.toInt(); paints.dot.alpha = 150; nc.drawCircle(s.x, s.y, rad * 0.32f, paints.dot)
                if (p.item.id == target?.id) {
                    paints.stroke.color = android.graphics.Color.WHITE; paints.stroke.alpha = 230; paints.stroke.strokeWidth = 2f * dp
                    nc.drawCircle(s.x, s.y, rad * 2.1f, paints.stroke)
                }
            }

            // Scan mode detections.
            boxes.clear()
            if (scanMode && frameSize.first > 0) {
                val (fw, fh) = frameSize
                val scale = max(w / fw, h / fh)
                val ox = (w - fw * scale) / 2; val oy = (h - fh * scale) / 2
                paints.box.color = scanColor; paints.box.strokeWidth = 2.5f * dp
                paints.boxText.textSize = 13f * dp
                for (d in detections) {
                    val b = d.box ?: continue
                    val rect = RectF(ox + b.left * scale, oy + b.top * scale, ox + b.right * scale, oy + b.bottom * scale)
                    nc.drawRoundRect(rect, 8 * dp, 8 * dp, paints.box)
                    val label = products[d.value]?.title ?: (if (d.kind == "text") d.value?.lines()?.firstOrNull() else d.value) ?: d.label
                    val tw = paints.boxText.measureText(label.take(40)) + 16 * dp
                    paints.fill.color = scanColor; paints.fill.alpha = 220
                    nc.drawRoundRect(RectF(rect.left, rect.top - 24 * dp, rect.left + tw, rect.top - 2 * dp), 6 * dp, 6 * dp, paints.fill)
                    nc.drawText(label.take(40), rect.left + 8 * dp, rect.top - 8 * dp, paints.boxText)
                    boxes += RectF(rect.left, rect.top - 24 * dp, max(rect.right, rect.left + tw), rect.bottom) to d
                }
            }

            // Compass tape: ±45° around your heading, with HDG and your ALT underneath.
            val heading = proj.headingDeg()
            val tapeY = topInset.toPx() + 30 * dp
            val pxPerDeg = w / 100f
            paints.stroke.color = android.graphics.Color.WHITE; paints.stroke.alpha = 220; paints.stroke.strokeWidth = 1.6f * dp
            val arc = Path().apply { moveTo(10 * dp, tapeY + 10 * dp); quadTo(cx, tapeY - 8 * dp, w - 10 * dp, tapeY + 10 * dp) }
            nc.drawPath(arc, paints.stroke)
            fun tapeYAt(x: Float): Float { val t = (x / w).coerceIn(0f, 1f); return (1 - t) * (1 - t) * (tapeY + 10 * dp) + 2 * (1 - t) * t * (tapeY - 8 * dp) + t * t * (tapeY + 10 * dp) }
            paints.tape.textSize = 15f * dp
            for (az in 0 until 360 step 15) {
                val dAz = (((az - heading) + 540) % 360) - 180
                if (kotlin.math.abs(dAz) > 50) continue
                val x = (cx + dAz * pxPerDeg).toFloat(); val y = tapeYAt(x)
                val label = when (az) { 0 -> "N"; 45 -> "NE"; 90 -> "E"; 135 -> "SE"; 180 -> "S"; 225 -> "SW"; 270 -> "W"; 315 -> "NW"; else -> null }
                if (label != null) {
                    paints.tape.color = if (az == 0) 0xFFFF8A80.toInt() else android.graphics.Color.WHITE
                    nc.drawText(label, x, y - 6 * dp, paints.tape)
                } else nc.drawLine(x, y - 5 * dp, x, y + 2 * dp, paints.stroke)
            }
            nc.drawLine(cx, tapeY - 2 * dp, cx, tapeY + 8 * dp, paints.stroke)
            paints.hud.textSize = 13f * dp; paints.hud.textAlign = Paint.Align.RIGHT
            nc.drawText("HDG %03d°".format(heading.toInt()), cx - 14 * dp, tapeY + 30 * dp, paints.hud)
            paints.hud.textAlign = Paint.Align.LEFT
            nc.drawText("ALT ${fix?.altM?.let { fmtAlt(it) } ?: "--"}", cx + 14 * dp, tapeY + 30 * dp, paints.hud)

            // Reticle: two arcs and a level line.
            val rr = 24 * dp
            paints.stroke.color = android.graphics.Color.WHITE; paints.stroke.alpha = 230; paints.stroke.strokeWidth = 2f * dp
            nc.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), 200f, 140f, false, paints.stroke)
            nc.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), 20f, 140f, false, paints.stroke)
            nc.drawLine(cx - 52 * dp, cy, cx - 6 * dp, cy, paints.stroke)
            nc.drawLine(cx + 6 * dp, cy, cx + 52 * dp, cy, paints.stroke)

            // Target readouts around the reticle: coordinates above, ALT left, DIST right, name below.
            val t = target
            if (t != null) {
                val plugin = app.registry.plugin(t.pluginId)
                val enu = enuFor(t, fix)
                val alt = t.altM ?: t.shape?.maxM?.let { (fix?.altM ?: 0.0) - EYE_HEIGHT_M + it }
                    ?: if (t.hasPlace) (fix?.altM?.let { it - EYE_HEIGHT_M + (t.heightM ?: EYE_HEIGHT_M) }) else null
                paints.hud.textSize = 12f * dp
                // Readouts sit on small dark plates so dots behind them don't make them unreadable.
                fun readout(text: String, x: Float, baseline: Float, align: Paint.Align) {
                    paints.hud.textAlign = align
                    val tw = paints.hud.measureText(text)
                    val left = when (align) { Paint.Align.RIGHT -> x - tw; Paint.Align.CENTER -> x - tw / 2; else -> x }
                    paints.fill.color = 0xFF101214.toInt(); paints.fill.alpha = 190
                    nc.drawRoundRect(RectF(left - 6 * dp, baseline - 13 * dp, left + tw + 6 * dp, baseline + 5 * dp), 6 * dp, 6 * dp, paints.fill)
                    nc.drawText(text, x, baseline, paints.hud)
                }
                val top = when {
                    t.hasPlace -> fmtCoords(t.lat!!, t.lon!!)
                    enu != null -> "AZ %.1f°  EL %.1f°".format(enu.bearingDeg, enu.elevationDeg)
                    else -> ""
                }
                readout(top, cx, cy - rr - 12 * dp, Paint.Align.CENTER)
                readout(if (alt != null) "ALT ${fmtAlt(alt)}" else "EL %.0f°".format(enu?.elevationDeg ?: 0.0), cx - 58 * dp, cy + 4 * dp, Paint.Align.RIGHT)
                readout("DIST ${t.distanceFrom(fix)?.let { Geo.fmtDistance(it) } ?: "--"}", cx + 58 * dp, cy + 4 * dp, Paint.Align.LEFT)
                paints.stroke.color = plugin.color.toArgb(); paints.stroke.strokeWidth = 2f * dp
                nc.drawLine(cx, cy + rr, cx, cy + rr + 22 * dp, paints.stroke)
                paints.title.color = plugin.color.toArgb(); paints.title.textSize = 17f * dp
                nc.drawText(t.title.take(34), cx, cy + rr + 42 * dp, paints.title)
            }
        }

        // Scan mode: a subtle glow around the edge of the screen.
        if (scanMode) {
            val c = Category.SCAN.composeColor
            Canvas(Modifier.fillMaxSize()) {
                val edge = 26.dp.toPx(); val a = 0.42f * glowAlpha
                drawRect(Brush.verticalGradient(listOf(c.copy(alpha = a), Color.Transparent), 0f, edge), size = size.copy(height = edge))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = a)), size.height - edge, size.height),
                    topLeft = Offset(0f, size.height - edge), size = size.copy(height = edge))
                drawRect(Brush.horizontalGradient(listOf(c.copy(alpha = a), Color.Transparent), 0f, edge), size = size.copy(width = edge))
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c.copy(alpha = a)), size.width - edge, size.width),
                    topLeft = Offset(size.width - edge, 0f), size = size.copy(width = edge))
            }
        }

        // Status under the compass tape: scanning, labels, compass calibration.
        Column(Modifier.align(Alignment.TopCenter).padding(top = topInset + 86.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val poorCompass = calibration <= SensorManager.SENSOR_STATUS_ACCURACY_LOW || (headingAcc ?: 0f) > 25f
            if (poorCompass && !scanMode) Pill(R.drawable.ph_compass, "Compass needs calibrating: move the phone in a figure-8", Color(0xFFFFB74D))
            if (scanMode) {
                val labels = detections.filter { it.kind == "label" }
                Pill(R.drawable.ph_scan, if (labels.isEmpty()) "Scanning. Long-press to stop" else labels.joinToString("  ·  ") { it.label },
                    Category.SCAN.composeColor)
            }
        }

        // Bottom: filter button and the target's card.
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterButton(app, onFilter)
            Box(Modifier.weight(1f)) {
                androidx.compose.animation.AnimatedVisibility(visible = target != null, enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }) {
                    var shown by remember { mutableStateOf<Item?>(null) }
                    target?.let { shown = it }
                    shown?.let { ItemCard(app, it, fix) { app.opened.value = it } }
                }
            }
        }
    }
}

@Composable
private fun Pill(icon: Int, text: String, color: Color) {
    Row(Modifier.padding(top = 4.dp).background(Color(0xB3121416), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        PhIcon(icon, color, 16.dp); Spacer(Modifier.width(6.dp)); Text(text, color = Color.White, fontSize = 12.sp)
    }
}
