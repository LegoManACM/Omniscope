package dev.omniscope.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes a real OpenFreeMap z14 tile (downtown Spokane, OSM data) and checks the building geometry makes sense. */
class MvtTest {
    private val z = 14; private val tx = 2847; private val ty = 5718

    private fun tile(): ByteArray = javaClass.classLoader!!.getResourceAsStream("tile_14_2847_5718.pbf")!!.readBytes()

    @Test fun tileMath() {
        assertEquals(tx, Mvt.tileX(-117.426, z))
        assertEquals(ty, Mvt.tileY(47.658, z))
    }

    @Test fun decodesBuildingsWithHeights() {
        val layer = Mvt.decode(tile(), setOf("building")).single()
        assertEquals("building", layer.name)
        val polys = layer.features.filter { it.type == 3 }
        assertTrue("has building polygons", polys.isNotEmpty())
        val heights = polys.mapNotNull { it.tags["render_height"] as? Double }
        assertTrue("has heights", heights.isNotEmpty())
        assertTrue("downtown has tall buildings (max ${heights.max()})", heights.max() > 30)

        val ext = layer.extent.toDouble()
        val west = Mvt.tileLon(tx, 0.0, z); val east = Mvt.tileLon(tx, 1.0, z)
        val north = Mvt.tileLat(ty, 0.0, z); val south = Mvt.tileLat(ty, 1.0, z)
        var exteriors = 0; var holes = 0
        for (f in polys) for (ring in Mvt.rings(f.geometry)) {
            if (Mvt.signedArea(ring) > 0) exteriors++ else holes++
            for (k in 0 until ring.size / 2) {
                val lon = Mvt.tileLon(tx, ring[2 * k] / ext, z); val lat = Mvt.tileLat(ty, ring[2 * k + 1] / ext, z)
                // Tiles carry a small buffer beyond their edge.
                assertTrue(lon in west - 0.01..east + 0.01 && lat in south - 0.01..north + 0.01)
            }
        }
        println("building features=${polys.size} exterior rings=$exteriors holes=$holes max height=${heights.max()} m")
        assertTrue("many individual footprints", exteriors > 50)
        assertTrue("exteriors outnumber holes", exteriors > holes)
    }
}
