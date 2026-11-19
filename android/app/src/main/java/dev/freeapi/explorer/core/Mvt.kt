package dev.freeapi.explorer.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * A minimal Mapbox Vector Tile (protobuf) decoder: enough to read OpenFreeMap's building footprints and heights
 * on the phone, with no extra libraries.
 */
object Mvt {
    class Feature(val type: Int, val tags: Map<String, Any>, val geometry: IntArray)
    class Layer(val name: String, val extent: Int, val features: List<Feature>)

    private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val c = b[pos++].toInt() and 0xff
                result = result or ((c and 0x7f).toLong() shl shift)
                if (c < 0x80) return result
                shift += 7
            }
        }
        fun sub(): Reader { val len = varint().toInt(); val r = Reader(b, pos, pos + len); pos += len; return r }
        fun string(): String { val len = varint().toInt(); val s = String(b, pos, len, Charsets.UTF_8); pos += len; return s }
        fun fixed32(): Int { var v = 0; for (i in 0 until 4) v = v or ((b[pos + i].toInt() and 0xff) shl (8 * i)); pos += 4; return v }
        fun fixed64(): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i)); pos += 8; return v }
        fun packed(): IntArray { val r = sub(); val out = ArrayList<Int>(); while (r.more()) out += r.varint().toInt(); return out.toIntArray() }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> throw IllegalStateException("Unsupported wire type $wire")
            }
        }
    }

    fun decode(bytes: ByteArray, only: Set<String>? = null): List<Layer> {
        val r = Reader(bytes, 0, bytes.size)
        val layers = mutableListOf<Layer>()
        while (r.more()) {
            val key = r.varint().toInt()
            if (key ushr 3 == 3 && key and 7 == 2) readLayer(r.sub(), only)?.let { layers += it } else r.skip(key and 7)
        }
        return layers
    }

    private fun readLayer(r: Reader, only: Set<String>?): Layer? {
        var name = ""; var extent = 4096
        val keys = mutableListOf<String>(); val values = mutableListOf<Any>(); val raw = mutableListOf<Reader>()
        while (r.more()) {
            val key = r.varint().toInt()
            when (key ushr 3) {
                1 -> name = r.string()
                2 -> raw += r.sub()
                3 -> keys += r.string()
                4 -> values += readValue(r.sub())
                5 -> extent = r.varint().toInt()
                else -> r.skip(key and 7)
            }
        }
        if (only != null && name !in only) return null
        val features = raw.map { f ->
            var type = 0; var tags = IntArray(0); var geom = IntArray(0)
            while (f.more()) {
                val key = f.varint().toInt()
                when (key ushr 3) {
                    2 -> tags = f.packed()
                    3 -> type = f.varint().toInt()
                    4 -> geom = f.packed()
                    else -> f.skip(key and 7)
                }
            }
            val map = HashMap<String, Any>()
            var i = 0
            while (i + 1 < tags.size) { keys.getOrNull(tags[i])?.let { k -> values.getOrNull(tags[i + 1])?.let { map[k] = it } }; i += 2 }
            Feature(type, map, geom)
        }
        return Layer(name, extent, features)
    }

    private fun readValue(r: Reader): Any {
        var v: Any = ""
        while (r.more()) {
            val key = r.varint().toInt()
            v = when (key ushr 3) {
                1 -> r.string()
                2 -> Float.fromBits(r.fixed32()).toDouble()
                3 -> Double.fromBits(r.fixed64())
                4, 5 -> r.varint().toDouble()
                6 -> r.varint().let { (it ushr 1) xor -(it and 1) }.toDouble()
                7 -> r.varint() != 0L
                else -> { r.skip(key and 7); v }
            }
        }
        return v
    }

    private fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)

    /** Polygon/line geometry as rings of tile coordinates [x0, y0, x1, y1, …]. */
    fun rings(geom: IntArray): List<IntArray> {
        val rings = mutableListOf<IntArray>()
        var cur = ArrayList<Int>()
        var x = 0; var y = 0; var i = 0
        while (i < geom.size) {
            val cmd = geom[i] and 7; val count = geom[i] ushr 3; i++
            when (cmd) {
                1 -> repeat(count) {
                    if (cur.isNotEmpty()) { rings += cur.toIntArray(); cur = ArrayList() }
                    x += zigzag(geom[i++]); y += zigzag(geom[i++]); cur += x; cur += y
                }
                2 -> repeat(count) { x += zigzag(geom[i++]); y += zigzag(geom[i++]); cur += x; cur += y }
                7 -> { if (cur.isNotEmpty()) rings += cur.toIntArray(); cur = ArrayList() }
            }
        }
        if (cur.isNotEmpty()) rings += cur.toIntArray()
        return rings
    }

    /** Surveyor's formula in tile coordinates: positive = exterior ring (MVT spec 4.3.4.4), negative = hole. */
    fun signedArea(ring: IntArray): Double {
        var a = 0.0
        val n = ring.size / 2
        for (k in 0 until n) {
            val j = (k + 1) % n
            a += ring[2 * k].toDouble() * ring[2 * j + 1] - ring[2 * j].toDouble() * ring[2 * k + 1]
        }
        return a / 2
    }

    fun tileLon(tx: Int, px: Double, z: Int) = (tx + px) / (1 shl z) * 360.0 - 180.0
    fun tileLat(ty: Int, py: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * (ty + py) / (1 shl z)))))
    fun tileX(lon: Double, z: Int) = ((lon + 180) / 360 * (1 shl z)).toInt()
    fun tileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat)
        return ((1 - kotlin.math.ln(kotlin.math.tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * (1 shl z)).toInt()
    }
}
