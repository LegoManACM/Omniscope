package dev.omniscope.core

import android.graphics.RectF
import androidx.annotation.DrawableRes
import dev.omniscope.R

enum class Cost(val label: String) { FREE("free"), FREEMIUM("freemium"), PAID("paid") }

/** How often a plugin refetches; also its cache TTL. */
enum class Freshness(val ttlMs: Long) {
    LIVE(15_000L),
    SLOW(10 * 60_000L),
    STATIC(6 * 3_600_000L),
}

/** Every plugin belongs to one category; the category owns the colour and the filter. */
enum class Category(val label: String, val color: Long, @DrawableRes val icon: Int) {
    CONDITIONS("Conditions", 0xFF64B5F6, R.drawable.ph_cloud_sun),
    PLACES("Places", 0xFFFF8A65, R.drawable.ph_storefront),
    HISTORY("History & culture", 0xFFB39DDB, R.drawable.ph_book_open),
    BUILDINGS("Buildings", 0xFF80DEEA, R.drawable.ph_buildings),
    NATURE("Nature", 0xFF81C784, R.drawable.ph_leaf),
    TERRAIN("Terrain", 0xFFBCAAA4, R.drawable.ph_mountains),
    SKY("Sky & space", 0xFFFFD54F, R.drawable.ph_planet),
    TRANSPORT("Aircraft & transport", 0xFF4DD0E1, R.drawable.ph_airplane_tilt),
    HAZARDS("Hazards", 0xFFFFB74D, R.drawable.ph_warning),
    SURVEILLANCE("Surveillance", 0xFFEF5350, R.drawable.ph_security_camera),
    MEDIA("Media", 0xFFF48FB1, R.drawable.ph_broadcast),
    SCAN("Scan", 0xFFCE93D8, R.drawable.ph_scan),
}

data class KeySpec(val label: String, val signupUrl: String)

/**
 * Where (and when) a plugin is asked about. Coordinates are already rounded to ~10 m. [radiusM] is the plugin's
 * search radius (the user's setting, or its default).
 */
data class Ctx(val lat: Double, val lon: Double, val altM: Double?, val timeMs: Long, val radiusM: Double = 0.0)

/** A plugin's adjustable search radius: its default and the range its API supports, in metres. */
data class RadiusSpec(val defaultM: Double, val minM: Double, val maxM: Double)

/** A direction in the sky, true-north azimuth and elevation in degrees. */
data class SkyPos(val azDeg: Double, val elDeg: Double)

/** A footprint ring [lat0, lon0, lat1, lon1, …] extruded from [minM] to [maxM] above the local ground. */
class Shape(val ring: DoubleArray, val minM: Double, val maxM: Double)

data class Item(
    val id: String,
    val pluginId: String,
    val title: String,
    val subtitle: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Height above sea level, when known (aircraft, peaks, the ISS). */
    val altM: Double? = null,
    /**
     * Height above the local ground, for things mounted on something (a camera on a pole). Used when [altM] is
     * null, assuming the ground there is level with yours. Null = eye level.
     */
    val heightM: Double? = null,
    /** For things with a direction but no place (Sun, Moon). */
    val sky: SkyPos? = null,
    /** The main link (opened by default). */
    val url: String? = null,
    val image: String? = null,
    val fields: List<Pair<String, String>> = emptyList(),
    /** A 3D solid (building footprint extruded between two heights), drawn as a wireframe in AR. */
    val shape: Shape? = null,
    /** Every source that has something to say about this item: label to URL. */
    val links: List<Pair<String, String>> = emptyList(),
    /** More images (a building's nearby photos, a species' photos). */
    val gallery: List<String> = emptyList(),
) {
    val hasPlace: Boolean get() = lat != null && lon != null
    val images: List<String> get() = (listOfNotNull(image) + gallery).distinct()
}

class PluginEnv(val http: Http, val key: String?)

interface Plugin {
    val id: String
    val name: String
    @get:DrawableRes val icon: Int
    val category: Category
    val description: String
    /** Where the data comes from, shown in Settings. */
    val source: String get() = ""
    val tags: List<String>
    val cost: Cost
    val freshness: Freshness
    val key: KeySpec? get() = null
    val dailyLimit: Int? get() = null
    /** Readings about "here" (weather, address): shown as cards, not as things out in the world. */
    val ambient: Boolean get() = false
    /** Geometry for AR (buildings): drawn, but not listed, mapped or sent over MCP. */
    val layer: Boolean get() = false
    /** Adjustable search radius; null when distance doesn't apply (weather, the sky, detectors). */
    val radius: RadiusSpec? get() = null
    /** Switched on when first installed. */
    val defaultOn: Boolean get() = cost == Cost.FREE

    suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item>
}

/** One camera frame for detectors: an upright NV21-free view of the image, plus its size. */
class Frame(
    /** Luminance (Y) plane, rotated upright, width × height bytes. */
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    /** The original frame for engines that take it directly (ML Kit). */
    val mediaImage: android.media.Image?,
    val rotationDegrees: Int,
)

/** Something a detector found in the camera frame. [box] is in upright frame coordinates. */
data class Detection(
    val detectorId: String,
    val kind: String,
    val label: String,
    val value: String? = null,
    val box: RectF? = null,
    val confidence: Float? = null,
)

/** A scan-mode plugin: runs on camera frames on the phone, never fetches. */
interface Detector : Plugin {
    override val category: Category get() = Category.SCAN
    override val freshness: Freshness get() = Freshness.STATIC
    override val tags: List<String> get() = listOf("scan")
    override suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item> = emptyList()

    /** Called on a background thread for each analysed frame. */
    suspend fun detect(frame: Frame): List<Detection>
    fun close() {}
}
