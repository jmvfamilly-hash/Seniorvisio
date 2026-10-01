package com.vangoghtimeline

import com.vangoghtimeline.iiif.ArtworkJson
import com.vangoghtimeline.iiif.CollectionLoader
import com.vangoghtimeline.iiif.EuropeanaParser
import com.vangoghtimeline.iiif.IiifImageUrl
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.ProductionDate
import com.vangoghtimeline.iiif.RijksmuseumParser
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkMerge
import com.vangoghtimeline.model.DatePrecision
import com.vangoghtimeline.model.IiifRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/*
 * Réponses types construites d'après les formats documentés (Linked Art / Rijksmuseum Data Services, Europeana Search API).
 * Elles n'ont PAS été comparées à une réponse réelle des services : voir le README.
 */
class MuseumsTest {
    private val rijksSearch = """
      {"@context":"https://linked.art/ns/v1/search.json","type":"OrderedCollectionPage",
       "next":{"id":"https://data.rijksmuseum.nl/search/collection?creator=x&pageToken=2","type":"OrderedCollectionPage"},
       "orderedItems":[{"id":"https://id.rijksmuseum.nl/200107928","type":"HumanMadeObject"},
                       {"id":"https://id.rijksmuseum.nl/200000001","type":"HumanMadeObject"}]}"""
    private val rijksObject = """
      {"id":"https://id.rijksmuseum.nl/200107928","type":"HumanMadeObject",
       "identified_by":[
         {"type":"Name","content":"Zelfportret","language":[{"id":"http://vocab.getty.edu/aat/300388256"}]},
         {"type":"Name","content":"Self-portrait","language":[{"id":"http://vocab.getty.edu/aat/300388277"}]},
         {"type":"Identifier","content":"SK-A-3262"}],
       "produced_by":{"type":"Production","timespan":{"type":"TimeSpan","begin_of_the_begin":"1887-01-01T00:00:00Z","end_of_the_end":"1887-12-31T23:59:59Z"}},
       "shows":[{"id":"https://id.rijksmuseum.nl/3000123","type":"VisualItem"}]}"""
    private val visualItem = """{"id":"https://id.rijksmuseum.nl/3000123","type":"VisualItem","digitally_shown_by":[{"id":"https://id.rijksmuseum.nl/5000123","type":"DigitalObject"}]}"""
    private val digitalObject = """{"id":"https://id.rijksmuseum.nl/5000123","type":"DigitalObject","access_point":{"id":"https://iiif.micr.io/PJEZO/full/max/0/default.jpg","type":"DigitalObject"}}"""

    @Test fun iiifImageUrlGivesItsService() {
        assertEquals("https://iiif.micr.io/PJEZO", IiifImageUrl.serviceBaseOf("https://iiif.micr.io/PJEZO/full/max/0/default.jpg"))
        assertEquals("https://h/iiif/2/a/b", IiifImageUrl.serviceBaseOf("https://h/iiif/2/a/b/0,0,512,512/256,/0/default.jpg?x=1"))
        assertNull(IiifImageUrl.serviceBaseOf("https://h/photo.jpg"))
    }

    @Test fun rijksSearchPage() {
        val page = RijksmuseumParser.parseSearchPage(rijksSearch)
        assertEquals(listOf("https://id.rijksmuseum.nl/200107928", "https://id.rijksmuseum.nl/200000001"), page.objectIds)
        assertTrue(page.next!!.endsWith("pageToken=2"))
        assertTrue(RijksmuseumParser.parseSearchPage("garbage").objectIds.isEmpty())
    }

    @Test fun rijksObjectPrefersEnglishTitleAndYearRange() {
        val o = RijksmuseumParser.parseObject(rijksObject)!!
        assertEquals("200107928", o.number)
        assertEquals("Self-portrait", o.title)
        assertEquals(DatePrecision.YEAR, o.date.precision)
        assertEquals(1887, o.date.year)
        assertEquals("https://id.rijksmuseum.nl/3000123", o.visualItemUrl)
    }

    @Test fun rijksImageChain() {
        assertEquals(listOf("https://id.rijksmuseum.nl/5000123"), RijksmuseumParser.parseVisualItem(visualItem))
        assertEquals("https://iiif.micr.io/PJEZO", RijksmuseumParser.parseDigitalObject(digitalObject))
        val art = RijksmuseumParser.toArtwork(RijksmuseumParser.parseObject(rijksObject)!!, "https://iiif.micr.io/PJEZO")
        assertEquals("https://iiif.micr.io/PJEZO/info.json", art.iiif.infoJsonUrl)
        assertEquals("https://iiif.micr.io/PJEZO/info.json", art.iiif.viewerUrl)   // « rijks:… » n'est pas un manifeste
        assertTrue(art.iiif.canOpenViewer)
    }

    @Test fun productionDateIsOnlyAsPreciseAsItsRange() {
        assertEquals(DatePrecision.DAY, ProductionDate.of("1888-10-01T00:00:00Z", "1888-10-01T23:59:59Z")!!.precision)
        assertEquals(DatePrecision.MONTH, ProductionDate.of("1888-10-01T00:00:00Z", "1888-10-31T23:59:59Z")!!.precision)
        assertEquals(DatePrecision.YEAR, ProductionDate.of("1888-01-01T00:00:00Z", "1888-12-31T23:59:59Z")!!.precision)
        assertEquals(1888, ProductionDate.of("1887-01-01T00:00:00Z", "1889-12-31T23:59:59Z")!!.year)   // année médiane
        assertNull(ProductionDate.of(null, null))
    }

    private val europeana = """
      {"success":true,"items":[
        {"id":"/2021672/resource_a","title":["The Sower"],"dcCreator":["Vincent van Gogh"],"year":["1888"],
         "edmPreview":["https://api.europeana.eu/thumbnail/v3/400/abc"],"dataProvider":["Kröller-Müller Museum"]},
        {"id":"/9200/rijks_b","title":["Self-portrait"],"dcCreator":["Vincent van Gogh"],"year":["1887"],"dataProvider":["Rijksmuseum"]},
        {"id":"/1/x","title":["Imitation"],"dcCreator":["Autre"],"year":["1888"]},
        {"id":"/1/y","title":["Trop tard"],"dcCreator":["Vincent van Gogh"],"year":["1950"]},
        {"id":"/1/z","dcCreator":["Vincent van Gogh"],"year":["1888"]}]}"""

    @Test fun europeanaItemsBecomeArtworksWithAManifest() {
        val arts = EuropeanaParser.parse(europeana)
        assertEquals(listOf("The Sower", "Self-portrait"), arts.map { it.title })
        val sower = arts.first()
        assertEquals("https://iiif.europeana.eu/presentation/2021672/resource_a/manifest", sower.iiif.manifestUrl)
        assertEquals("https://iiif.europeana.eu/presentation/2021672/resource_a/manifest", sower.iiif.viewerUrl)
        assertEquals("https://api.europeana.eu/thumbnail/v3/400/abc", sower.iiif.thumbnailUrlFor(300, 200))
        assertEquals("Europeana · Kröller-Müller Museum", sower.provider)
        assertEquals(DatePrecision.YEAR, sower.date.precision)
        assertTrue(EuropeanaParser.parse("garbage").isEmpty())
    }

    private fun art(id: String, title: String, year: Int, provider: String = "") =
        Artwork(id, title, ArtworkDate.year(year), iiif = IiifRef("x"), provider = provider)

    @Test fun mergeKeepsTheFirstOfDuplicatesAndSortsByDate() {
        val merged = ArtworkMerge.merge(listOf(
            listOf(art("a", "The Bedroom", 1889)),
            listOf(art("r", "Self-Portrait", 1887), art("r2", "the bedroom!", 1889)),
        ))
        assertEquals(listOf("r", "a"), merged.map { it.id })
    }

    @Test fun artworkJsonRoundTrips() {
        val list = listOf(
            Artwork("e", "Titre « é »", ArtworkDate.month(1888, 5), "Arles", "Huile", IiifRef("https://m", "https://s", "https://t", 40, 30), "Rijksmuseum"),
            art("f", "Sans image", 1880),
        )
        assertEquals(list, ArtworkJson.decode(ArtworkJson.encode(list)))
        assertTrue(ArtworkJson.decode("garbage").isEmpty())
    }

    // ── Chargement des trois musées ───────────────────────────────────────────────
    private class FakeSource(private val pages: Map<String, String>) : ManifestSource {
        override suspend fun fetch(url: String): String = pages[url] ?: throw IOException("HTTP 403 sur $url")
    }

    private val aicJson = """{"data":[{"id":28560,"title":"The Bedroom","artist_title":"Vincent van Gogh","date_start":1889,"image_id":"abc","thumbnail":{"width":4,"height":3}}],"config":{"iiif_url":"https://www.artic.edu/iiif/2"}}"""
    private fun tmp(): File = kotlin.io.path.createTempDirectory("vgt").toFile()

    @Test fun threeMuseumsAreMergedProgressivelyAndRijksDuplicatesFromEuropeanaDropped() = runBlocking {
        val source = FakeSource(mapOf(
            com.vangoghtimeline.iiif.ArticParser.SEARCH_URL to aicJson,
            RijksmuseumParser.SEARCH_URL to rijksSearch,
            "https://id.rijksmuseum.nl/200107928" to rijksObject,
            "https://id.rijksmuseum.nl/3000123" to visualItem,
            "https://id.rijksmuseum.nl/5000123" to digitalObject,
            // 200000001 et la page suivante de la recherche : 403 → ignorées sans faire échouer le reste
            EuropeanaParser.searchUrl() to europeana,
        ))
        val updates = ArrayList<Int>()
        val result = CollectionLoader(source, tmp()).load { updates += it.artworks.size }!!
        val titles = result.artworks.map { it.title }
        assertEquals(listOf("Self-portrait", "The Sower", "The Bedroom"), titles)       // Rijksmuseum direct gardé, sa notice Europeana écartée
        assertEquals("Rijksmuseum", result.artworks.first { it.title == "Self-portrait" }.provider)
        assertTrue("mises à jour progressives : $updates", updates.size >= 3 && updates == updates.sorted())
        assertTrue(result.credit, result.credit.contains("Art Institute of Chicago (1)") && result.credit.contains("Rijksmuseum (1)") && result.credit.contains("Europeana (1)"))
    }

    @Test fun oneMuseumFailingDoesNotRemoveTheOthers() = runBlocking {
        val source = FakeSource(mapOf(com.vangoghtimeline.iiif.ArticParser.SEARCH_URL to aicJson))
        val result = CollectionLoader(source, tmp()).load { }!!
        assertEquals(listOf("The Bedroom"), result.artworks.map { it.title })
        assertEquals("Art Institute of Chicago (1)", result.credit)
    }

    @Test fun offlineUsesLocalCopiesAndNothingAtAllRendersNull() = runBlocking {
        val dir = tmp()
        val online = FakeSource(mapOf(
            com.vangoghtimeline.iiif.ArticParser.SEARCH_URL to aicJson,
            RijksmuseumParser.SEARCH_URL to rijksSearch,
            "https://id.rijksmuseum.nl/200107928" to rijksObject,
            "https://id.rijksmuseum.nl/3000123" to visualItem,
            "https://id.rijksmuseum.nl/5000123" to digitalObject,
            EuropeanaParser.searchUrl() to europeana,
        ))
        CollectionLoader(online, dir).load { }
        val offline = CollectionLoader(FakeSource(emptyMap()), dir).load { }!!
        assertEquals(3, offline.artworks.size)
        assertTrue(offline.credit, offline.credit.contains("hors ligne"))
        assertNull(CollectionLoader(FakeSource(emptyMap()), tmp()).load { })
    }
}
