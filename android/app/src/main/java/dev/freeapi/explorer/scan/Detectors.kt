package dev.freeapi.explorer.scan

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.freeapi.explorer.R
import dev.freeapi.explorer.core.Cost
import dev.freeapi.explorer.core.Detection
import dev.freeapi.explorer.core.Detector
import dev.freeapi.explorer.core.Frame

fun allDetectors(): List<Detector> = listOf(MlKitBarcodes, ZxingBarcodes, MlKitText, MlKitLabels)

/** Builds an upright [Frame] from a CameraX analysis image: tight Y plane rotated by the frame's rotation. */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
fun ImageProxy.toFrame(): Frame {
    val plane = planes[0]
    val w = width; val h = height
    val src = plane.buffer.also { it.rewind() }
    val rowStride = plane.rowStride
    val y = ByteArray(w * h)
    val row = ByteArray(rowStride)
    for (r in 0 until h) {
        src.position(r * rowStride)
        src.get(row, 0, minOf(rowStride, src.remaining()))
        System.arraycopy(row, 0, y, r * w, w)
    }
    val rot = imageInfo.rotationDegrees
    val (out, ow, oh) = when (rot) {
        90 -> Triple(ByteArray(w * h).also { o -> for (r in 0 until h) for (c in 0 until w) o[c * h + (h - 1 - r)] = y[r * w + c] }, h, w)
        180 -> Triple(ByteArray(w * h).also { o -> for (i in 0 until w * h) o[w * h - 1 - i] = y[i] }, w, h)
        270 -> Triple(ByteArray(w * h).also { o -> for (r in 0 until h) for (c in 0 until w) o[(w - 1 - c) * h + r] = y[r * w + c] }, h, w)
        else -> Triple(y, w, h)
    }
    return Frame(out, ow, oh, image, rot)
}

private fun Frame.input(): InputImage? = mediaImage?.let { InputImage.fromMediaImage(it, rotationDegrees) }

object MlKitBarcodes : Detector {
    override val id = "scan_mlkit_barcodes"; override val name = "Barcodes (ML Kit)"; override val icon = R.drawable.ph_barcode
    override val source = "Google ML Kit, on the phone"
    override val description = "QR codes and barcodes of every common format. Fast and accurate; closed source but free and offline."
    override val cost = Cost.FREE
    private val client by lazy { BarcodeScanning.getClient() }

    override suspend fun detect(frame: Frame): List<Detection> {
        val img = frame.input() ?: return emptyList()
        return Tasks.await(client.process(img)).mapNotNull { b ->
            val value = b.rawValue ?: return@mapNotNull null
            Detection(id, "barcode", formatName(b.format), value, b.boundingBox?.let { RectF(it) })
        }
    }

    private fun formatName(f: Int) = when (f) {
        Barcode.FORMAT_QR_CODE -> "QR code"; Barcode.FORMAT_EAN_13 -> "EAN-13"; Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"; Barcode.FORMAT_UPC_E -> "UPC-E"; Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"; Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"; Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"; else -> "Barcode"
    }

    override fun close() = client.close()
}

object ZxingBarcodes : Detector {
    override val id = "scan_zxing_barcodes"; override val name = "Barcodes (ZXing)"; override val icon = R.drawable.ph_barcode
    override val source = "ZXing, open source (Apache 2.0)"
    override val description = "Open-source QR and barcode reader. Finds one code per frame."
    override val cost = Cost.FREE
    override val defaultOn = false // ML Kit reads the same codes; turn this on to compare or to stay fully open source
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to BarcodeFormat.entries.toList()))
    }

    override suspend fun detect(frame: Frame): List<Detection> {
        val source = PlanarYUVLuminanceSource(frame.luma, frame.width, frame.height, 0, 0, frame.width, frame.height, false)
        return try {
            val r = synchronized(reader) { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }
            val pts = r.resultPoints.orEmpty().filterNotNull()
            val box = if (pts.isEmpty()) null else RectF(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
            listOf(Detection(id, "barcode", r.barcodeFormat.name.replace('_', ' '), r.text, box))
        } catch (e: NotFoundException) {
            emptyList()
        } finally {
            reader.reset()
        }
    }
}

object MlKitText : Detector {
    override val id = "scan_mlkit_text"; override val name = "Text (ML Kit)"; override val icon = R.drawable.ph_text_t
    override val source = "Google ML Kit, on the phone"
    override val description = "Reads Latin-script text: signs, labels, plaques."
    override val cost = Cost.FREE
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun detect(frame: Frame): List<Detection> {
        val img = frame.input() ?: return emptyList()
        return Tasks.await(client.process(img)).textBlocks.filter { it.text.length >= 3 }.map { b ->
            Detection(id, "text", "Text", b.text, b.boundingBox?.let { RectF(it) })
        }
    }

    override fun close() = client.close()
}

object MlKitLabels : Detector {
    override val id = "scan_mlkit_labels"; override val name = "Objects (ML Kit)"; override val icon = R.drawable.ph_tag
    override val source = "Google ML Kit, on the phone"
    override val description = "Names what's in view (400+ everyday objects and scenes). Whole-frame labels, no boxes."
    override val cost = Cost.FREE
    private val client by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build()) }

    override suspend fun detect(frame: Frame): List<Detection> {
        val img = frame.input() ?: return emptyList()
        return Tasks.await(client.process(img)).take(5).map { l -> Detection(id, "label", l.text, confidence = l.confidence) }
    }

    override fun close() = client.close()
}
