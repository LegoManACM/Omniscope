package dev.freeapi.explorer.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import dev.freeapi.explorer.App
import dev.freeapi.explorer.core.Detection
import dev.freeapi.explorer.core.Detector
import dev.freeapi.explorer.core.Item
import dev.freeapi.explorer.core.obj
import dev.freeapi.explorer.core.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Scan mode: runs every active detector plugin on each camera frame (on the analysis thread), merges what they find,
 * and turns detections into items. Product barcodes are looked up in Open Food Facts (free, keyless).
 */
class ScanSession(private val app: App) : ImageAnalysis.Analyzer {
    val detections = MutableStateFlow<List<Detection>>(emptyList())
    /** Upright frame size, for mapping detection boxes onto the preview. */
    val frameSize = MutableStateFlow(0 to 0)
    /** Barcode value to looked-up product (null while unknown or not a product). */
    val products = MutableStateFlow<Map<String, Item?>>(emptyMap())

    override fun analyze(proxy: ImageProxy) {
        try {
            val detectors = app.registry.plugins.filterIsInstance<Detector>().filter { app.registry.isActive(it) }
            if (detectors.isEmpty()) { detections.value = emptyList(); return }
            val frame = proxy.toFrame()
            val found = runBlocking { detectors.flatMap { d -> runCatching { d.detect(frame) }.getOrDefault(emptyList()) } }
            frameSize.value = frame.width to frame.height
            detections.value = merge(found)
            found.filter { it.kind == "barcode" && it.value != null && isProductCode(it.value) }.forEach { lookup(it.value!!) }
        } finally {
            proxy.close()
        }
    }

    fun clear() { detections.value = emptyList() }

    /** Two engines reading the same code: keep one, preferring the one with a box. */
    private fun merge(all: List<Detection>): List<Detection> {
        val barcodes = all.filter { it.kind == "barcode" }.groupBy { it.value }.map { (_, ds) -> ds.firstOrNull { it.box != null } ?: ds.first() }
        return barcodes + all.filter { it.kind != "barcode" }
    }

    private fun isProductCode(v: String) = v.length in 8..14 && v.all { it.isDigit() }

    private fun lookup(code: String) {
        if (code in products.value) return
        products.update { it + (code to null) }
        app.scope.launch {
            val item = runCatching {
                val j = JSONObject(app.http.text("https://world.openfoodfacts.org/api/v2/product/$code.json" +
                    "?fields=product_name,brands,image_front_url,quantity,nutriscore_grade,categories,ingredients_text_en", 7L * 24 * 3_600_000L))
                val p = j.obj("product") ?: return@runCatching null
                val name = p.str("product_name") ?: return@runCatching null
                Item("scan:product:$code", MlKitBarcodes.id, name, listOfNotNull(p.str("brands"), p.str("quantity")).joinToString(" · ").ifBlank { null },
                    image = p.str("image_front_url"),
                    links = listOf("Open Food Facts" to "https://world.openfoodfacts.org/product/$code"),
                    fields = listOfNotNull("Barcode" to code, p.str("nutriscore_grade")?.let { "Nutri-Score" to it.uppercase() },
                        p.str("categories")?.let { "Categories" to it }, p.str("ingredients_text_en")?.let { "Ingredients" to it }))
            }.getOrNull()
            products.update { it + (code to item) }
        }
    }

    /** The item a detection opens. */
    fun itemFor(d: Detection): Item {
        val value = d.value.orEmpty()
        products.value[value]?.let { return it }
        return when (d.kind) {
            "barcode" -> Item("scan:${d.detectorId}:$value", d.detectorId, if (value.length <= 40) value else value.take(40) + "…", d.label,
                links = listOfNotNull(
                    value.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { "Open link" to it },
                    value.takeIf { isProductCode(it) }?.let { "Open Food Facts" to "https://world.openfoodfacts.org/product/$it" },
                ),
                fields = listOf("Format" to d.label, "Value" to value))
            "text" -> Item("scan:text:${value.hashCode()}", d.detectorId, value.lines().first().take(60), "Text in view",
                links = listOf("Search the web" to "https://duckduckgo.com/?q=${dev.freeapi.explorer.core.enc(value.take(200))}"),
                fields = listOf("Text" to value))
            else -> Item("scan:label:${d.label}", d.detectorId, d.label, d.confidence?.let { "%.0f%% confident".format(it * 100) },
                links = listOf("Wikipedia" to "https://en.wikipedia.org/wiki/Special:Search?search=${dev.freeapi.explorer.core.enc(d.label)}"))
        }
    }
}
