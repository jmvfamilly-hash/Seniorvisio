package com.vangoghtimeline

import com.vangoghtimeline.iiif.ArtworkJson
import com.vangoghtimeline.iiif.EuropeanaParser
import com.vangoghtimeline.iiif.IiifImageUrl
import com.vangoghtimeline.iiif.ProductionDate
import com.vangoghtimeline.iiif.RijksmuseumParser
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkMerge
import com.vangoghtimeline.model.DatePrecision
import com.vangoghtimeline.model.IiifRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
