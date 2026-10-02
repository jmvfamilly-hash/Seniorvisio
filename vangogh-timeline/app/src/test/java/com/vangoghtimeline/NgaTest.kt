package com.vangoghtimeline

import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.iiif.NgaParser
import com.vangoghtimeline.iiif.NgaSource
import com.vangoghtimeline.iiif.Tally
import com.vangoghtimeline.model.ArtistCatalog
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.RightsKind
import com.vangoghtimeline.model.SourceSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class NgaTest {
    @Before fun reset() { Diag.clear() }

    private val catalog by lazy { ArtistCatalog.parse(File("src/main/assets/artists_by_movement.json").readText()) }
    private fun query(id: String) = ArtworkQuery.of(catalog.first { it.id == id })
    private fun asset(id: String) = File("src/main/assets/nga/$id.json").readText()

    private fun work(id: Int, attribution: String, date: String = "1880", begin: Int? = 1880, end: Int? = 1880, open: Boolean = true, iiif: String = "https://api.nga.gov/iiif/u$id", maxPixels: String = "null") =
        """{"id":$id,"accession":"1.$id","title":"T$id","date":"$date","begin":${begin ?: "null"},"end":${end ?: "null"},"medium":"oil","classification":"Painting","credit":"Don X","attribution":"$attribution","image":"u$id","iiif":"$iiif","width":3000,"height":2000,"maxpixels":$maxPixels,"openaccess":$open}"""

    @Test fun keepsOnlyTheArtistsOwnWorksWithADate() {
        val q = query("pierre-auguste-renoir")
        val text = """{"works":[
            ${work(1, "Auguste Renoir")},
            ${work(2, "Julie Manet")},
            ${work(3, "French 20th Century, after Auguste Renoir")},
            ${work(4, "Auguste Renoir and Richard Guino")},
            ${work(5, "Auguste Renoir", date = "", begin = 1841, end = 1919)},
            ${work(6, "Auguste Renoir", begin = 1700, end = 1700, date = "1700")},
            ${work(7, "Attributed to Auguste Renoir")}
        ]}"""
        val t = Tally()
        assertEquals(listOf("nga-1"), NgaParser.parse(text, q, t).map { it.id })
        assertEquals("7 reçues, 1 retenues", t.summary().substringBefore(" — "))
        assertTrue(t.summary(), t.summary().contains("1 d'un autre artiste") && t.summary().contains("1 sans date précise") && t.summary().contains("1 hors des dates plausibles"))
    }

    @Test fun anOpenAccessImageIsPublicDomainAndARestrictedOneIsViewOnly() {
        val q = query("berthe-morisot")
        val arts = NgaParser.parse("""{"works":[${work(1, "Berthe Morisot")},${work(2, "Berthe Morisot", open = false, maxPixels = "900", begin = 1890, end = 1890, date = "1890")}]}""", q)
        assertEquals(RightsKind.PUBLIC_DOMAIN, arts.first { it.id == "nga-1" }.rights!!.kind)
        val restricted = arts.first { it.id == "nga-2" }.rights!!
        assertEquals(RightsKind.VIEW_ONLY, restricted.kind)
        assertTrue(restricted.label, restricted.label.contains("900 px"))
        assertTrue(arts[0].rights!!.attribution!!.contains("National Gallery of Art, Washington — Don X"))
    }

    @Test fun theWorkOpensInTheViewerThroughItsIiifService() {
        val a = NgaParser.parse("""{"works":[${work(1, "Berthe Morisot")}]}""", query("berthe-morisot")).single()
        assertEquals("https://api.nga.gov/iiif/u1/info.json", a.iiif.viewerUrl)
        assertEquals("https://api.nga.gov/iiif/u1/full/400,/0/default.jpg", a.iiif.thumbnailUrlFor(400, 1000))
        assertTrue(a.iiif.canOpenViewer)
    }

    @Test fun theRealExtractedFilesAreReadable() {
        val expected = mapOf("vincent-van-gogh" to 15..23, "berthe-morisot" to 12..28, "pierre-auguste-renoir" to 60..80, "john-singer-sargent" to 100..161, "joaquin-sorolla" to 1..1)
        for ((id, range) in expected) {
            val t = Tally()
            val arts = NgaParser.parse(asset(id), query(id), t)
            assertTrue("$id : ${arts.size} — ${t.summary()}", arts.size in range)
            assertTrue(arts.all { it.iiif.imageServiceId!!.startsWith("https://api.nga.gov/iiif/") && it.rights != null })
            assertTrue(arts.none { it.title.isBlank() })
        }
    }

    @Test fun theSourceReadsItsAssetAndIsEmptyWithoutOne() = runBlocking {
        val q = query("berthe-morisot")
        assertTrue(NgaSource { null }.fetch(q, SourceSpec("nga")).isEmpty())
        val arts = NgaSource { name -> if (name == "berthe-morisot") asset(name) else null }.fetch(q, SourceSpec("nga"))
        assertTrue(arts.isNotEmpty())
        assertTrue(Diag.snapshot().any { it.sourceId == "nga" && it.message.contains("analyse") })
    }

    @Test fun everyArtistWithAUniverseHasTheNgaSource() {
        assertTrue(catalog.filter { it.hasUniverse }.all { a -> a.sources.any { it.sourceId == "nga" } })
        assertFalse(catalog.filter { !it.hasUniverse }.any { it.sources.isNotEmpty() })
    }
}
