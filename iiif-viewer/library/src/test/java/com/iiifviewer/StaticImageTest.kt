package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticImageTest {
    /** Manifeste Presentation 2 d'une image SANS service IIIF (cas d'un musée dont le fichier est un simple JPEG). */
    private val v2NoService = """
    {"@type":"sc:Manifest","sequences":[{"canvases":[{"width":3000,"height":2000,"images":[{"resource":
      {"@id":"https://musee.example/img/pont.jpg","@type":"dctypes:Image","format":"image/jpeg"}}]}]}]}"""

    private val v3NoService = """
    {"type":"Manifest","items":[{"type":"Canvas","width":800,"height":600,"items":[{"type":"AnnotationPage","items":[
      {"type":"Annotation","body":{"id":"https://musee.example/img/pont.jpg","type":"Image","width":1600,"height":1200}}]}]}]}"""

    @Test fun manifestWithoutServiceHasNoServiceButAStaticImage() {
        assertNull(IiifManifestResolver.serviceIdOf(v2NoService))
        val img = IiifManifestResolver.staticImageOf(v2NoService)!!
        assertEquals("https://musee.example/img/pont.jpg", img.url)
        assertEquals(3000, img.width)
        assertEquals(2000, img.height)
    }

    @Test fun presentation3ImageSizeWinsOverCanvasSize() {
        val img = IiifManifestResolver.staticImageOf(v3NoService)!!
        assertEquals(1600, img.width)
        assertEquals(1200, img.height)
    }

    @Test fun anIiifImageUrlWithoutDeclaredServiceStillGivesItsService() {
        val m = """{"@type":"sc:Manifest","sequences":[{"canvases":[{"images":[{"resource":{"@id":"https://iiif.example/abc/full/full/0/default.jpg"}}]}]}]}"""
        assertEquals("https://iiif.example/abc", IiifManifestResolver.serviceIdOf(m))
    }

    @Test fun notAManifestHasNoStaticImage() {
        assertNull(IiifManifestResolver.staticImageOf("""{"width":10,"height":10}"""))
        assertNull(IiifManifestResolver.staticImageOf("pas du json"))
    }

    @Test fun tileUrlRoundTrips() {
        val info = IiifImageInfo(StaticImageUrl.baseUriOf("https://musee.example/img/pont.jpg"), 3000, 2000, 512, listOf(1, 2, 4))
        val tile = TileCalculator.allTiles(info, 2).first()
        val url = IiifUrls.tile(info, tile)
        assertTrue(StaticImageUrl.isStatic(url))
        val req = StaticImageUrl.parse(url)!!
        assertEquals("https://musee.example/img/pont.jpg", req.imageUrl)
        assertEquals(0, req.x); assertEquals(0, req.y)
        assertEquals(1024, req.width); assertEquals(1024, req.height)
        assertEquals(512, req.outputWidth)
        assertNull(StaticImageUrl.parse("https://h/i/0,0,1,1/1,/0/default.jpg"))
    }

    @Test fun sampleSizeKeepsAtLeastTheOutputWidth() {
        assertEquals(1, StaticImageUrl.sampleSizeFor(512, 512))
        assertEquals(2, StaticImageUrl.sampleSizeFor(1024, 512))
        assertEquals(4, StaticImageUrl.sampleSizeFor(2048, 512))
        assertEquals(2, StaticImageUrl.sampleSizeFor(1500, 512))   // 1500/4 = 375 < 512 : pas plus
    }
}
