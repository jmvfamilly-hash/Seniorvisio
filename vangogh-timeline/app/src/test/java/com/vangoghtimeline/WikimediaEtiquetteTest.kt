package com.vangoghtimeline

import com.iiifviewer.HostEtiquette
import com.vangoghtimeline.iiif.WikimediaParser
import com.vangoghtimeline.iiif.WikimediaParser.standardWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikimediaEtiquetteTest {
    @Test fun thumbnailWidthsAreAlwaysStandardOnes() {
        assertEquals(3840, standardWidth(3840, 6000))
        assertEquals(1920, standardWidth(3840, 3000))      // fichier de 3000 px : la plus grande largeur standard qu'il permet
        assertEquals(1920, standardWidth(1920, null))      // taille inconnue (lot de licences en échec) : 1920
        assertEquals(500, standardWidth(500, 4000))
        assertEquals(330, standardWidth(500, 400))         // jamais plus large que le fichier
        assertEquals(20, standardWidth(500, 10))
        for (w in listOf(1, 99, 400, 3000, 5000, 6000)) assertTrue(standardWidth(3840, w) in WikimediaParser.STANDARD_WIDTHS)
    }

    @Test fun withoutKnownSizeTheViewerWidthIsTheSafeOne() {
        val item = WikimediaParser.Item("Q1", "Titre", 1890, "Fichier.jpg", null)
        val q = com.vangoghtimeline.model.ArtworkQuery("Joaquín Sorolla", "Sorolla", 1873..1924)
        val art = WikimediaParser.toArtwork(item, null, q)!!
        assertTrue(art.iiif.viewerUrl!!.endsWith("?width=1920"))
        assertTrue(art.iiif.thumbnailUrlFor(300, 200)!!.endsWith("?width=500"))
    }

    @Test fun wikimediaGetsAnIdentifyingUserAgentAndOthersKeepTheirs() {
        assertTrue(HostEtiquette.isWikimedia("https://commons.wikimedia.org/wiki/Special:FilePath/x.jpg?width=500"))
        assertTrue(HostEtiquette.isWikimedia("https://query.wikidata.org/sparql?format=json"))
        assertTrue(HostEtiquette.isWikimedia("https://en.wikipedia.org/w/api.php"))
        assertTrue(HostEtiquette.isWikimedia("https://upload.wikimedia.org/wikipedia/commons/a/ab/x.jpg"))
        assertFalse(HostEtiquette.isWikimedia("https://api.artic.edu/api/v1/artworks"))
        assertFalse(HostEtiquette.isWikimedia("https://notwikimedia.org.example.com/x"))
        assertEquals("Mozilla/5.0", HostEtiquette.userAgentFor("https://api.nga.gov/iiif/x/info.json", "Mozilla/5.0"))
        val ua = HostEtiquette.userAgentFor("https://commons.wikimedia.org/x", "Mozilla/5.0")
        assertTrue(ua, ua.startsWith("VanGoghTimeline/1.0") && ua.contains("https://") && !ua.contains("@") && !ua.contains("Mozilla"))
    }
}
