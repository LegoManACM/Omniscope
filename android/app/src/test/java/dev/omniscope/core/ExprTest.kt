package dev.omniscope.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExprTest {
    private val node = JSONObject("""
        {"name":"Mt Spokane","ele":"1791 m","flight":"UAL12  ","alt_baro":"ground","alt_geom":35000,
         "tags":{"addr:street":"Main St","wikipedia":"en:Mount Spokane"},
         "photos":[{"url":"a/square.jpg"},{"url":"b/square.jpg"}],
         "pages":{"12":{"title":"File:Bridge.jpg"},"34":{"title":"File:Tower.png"}},
         "time":1700000000000,"kind":"water_tower","empty":"","mag":4}
    """.trimIndent())

    @Test fun paths() {
        assertEquals("Mt Spokane", Expr.string("$.name", node))
        assertEquals("Main St", Expr.string("$.tags['addr:street']", node))
        assertEquals("b/square.jpg", Expr.string("$.photos[-1].url", node))
        assertEquals(listOf("File:Bridge.jpg", "File:Tower.png"), Expr.list("$.pages.*.title", node))
        assertNull(Expr.string("$.missing.deep", node))
    }

    @Test fun fallbacksAndPipes() {
        assertEquals("UAL12", Expr.string("$.flight | trim || $.name", node))
        assertEquals("Mt Spokane", Expr.string("$.empty || $.name", node))
        assertEquals(1791.0, Expr.number("$.ele | num", node)!!, 0.0)
        assertNull(Expr.number("$.alt_baro | num", node)) // "ground" isn't a number
        assertEquals(10668.0, Expr.number("$.alt_baro | num | mul:0.3048 || $.alt_geom | mul:0.3048", node)!!, 0.01)
        assertEquals("1,791 m", Expr.string("$.ele | num | thousands | suffix: m", node))
        assertEquals("water tower", Expr.string("$.kind | replace:'_=> '", node))
        assertEquals("historic water_tower", Expr.string("$.kind | prefix:'historic '", node))
        assertEquals("Bridge", Expr.string("$.pages.12.title | removeprefix:File: | noext", node))
        assertEquals("2023-11-14", Expr.string("$.time | date", node))
        assertEquals("1987-05-01", Expr.string("'1987-05-01T10:00:00' | date", node))
        assertEquals("2026-01-12", Expr.string("'1/12/2026' | date", node))
        assertEquals(-117.5, Expr.number("'Point(-117.5 47.25)' | wktlon", node)!!, 0.0)
        assertEquals(47.25, Expr.number("'Point(-117.5 47.25)' | wktlat", node)!!, 0.0)
        val parcel = JSONObject("""{"geometry":{"rings":[[[-117.0,47.0],[-116.0,47.0],[-116.0,48.0],[-117.0,48.0],[-117.0,47.0]]]},"o":"COWLES REAL ESTATE CO"}""")
        assertEquals(47.5, Expr.number("$.geometry.rings | ringlat", parcel)!!, 1e-9)
        assertEquals(-116.5, Expr.number("$.geometry.rings | ringlon", parcel)!!, 1e-9)
        assertEquals("Cowles Real Estate Co", Expr.string("$.o | titlecase", parcel))
        assertEquals(5, Expr.string("'2026-11-19T15:04:00+00:00' | localtime", node)!!.length)
        assertEquals("4.0", Expr.string("$.mag | round1", node))
        assertEquals("https://en.wikipedia.org/wiki/Mount_Spokane", Expr.string("$.tags.wikipedia | wikiurl", node))
        assertEquals(listOf("a/medium.jpg", "b/medium.jpg"), Expr.list("$.photos[*].url | replace:square=>medium", node))
        assertEquals("fallback", Expr.string("$.nope || 'fallback'", node))
    }

    @Test fun templates() {
        assertEquals("Magnitude 4.0 · Mt Spokane", Expr.string("Magnitude {$.mag | round1} · {$.name}", node))
        assertEquals("Mt Spokane", Expr.string("{$.missing} · {$.name}", node)) // empty parts drop out
        assertNull(Expr.string("https://x.org/{$.missing}", node, strict = true)) // links need every part
        assertEquals("https://x.org/Mt Spokane", Expr.string("https://x.org/{$.name}", node, strict = true))
    }

    @Test fun requestTemplates() {
        val ctx = Ctx(47.6, -117.4, null, 0L, radiusM = 2500.0)
        assertEquals("lat=47.60000&r=2500&km=3&km3=2.500&nm=1", Expr.request("lat={lat}&r={radius_m}&km={radius_km}&km3={radius_km:3}&nm={radius_nm}", ctx, null))
        assertEquals("k=a%2Bb", Expr.request("k={key}", ctx, "a+b"))
        assertEquals("47.57738,47.62262", Expr.request("{south},{north}", ctx, null))
    }
}
