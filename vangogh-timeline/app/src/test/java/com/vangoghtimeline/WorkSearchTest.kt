package com.vangoghtimeline

import com.vangoghtimeline.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkSearchTest {
    private val catalog by lazy { ArtistCatalog.parse(File("src/main/assets/artists_by_movement.json").readText()) }
    private fun artist(id: String) = catalog.first { it.id == id }
    private fun work(id: String, title: String, year: Int, medium: String? = null, place: String? = null, provider: String = "") =
        Artwork(id, title, ArtworkDate.year(year), place = place, medium = medium, iiif = IiifRef(""), provider = provider)

    private val vg = artist("vincent-van-gogh")
    private val sargent = artist("john-singer-sargent")
    private val works = mapOf(
        vg to listOf(work("a", "Portrait of Père Tanguy", 1887, "Oil on canvas", "Paris"), work("b", "Wheat Field with Cypresses", 1889, "Oil on canvas", "Saint-Rémy"), work("c", "Study of a hand", 1885, "Black chalk")),
        sargent to listOf(work("d", "Portrait of Madame X", 1884, "Oil on canvas", provider = "The Met"), work("e", "Nature morte aux pommes", 1889, "Watercolor")),
    )

    @Test fun allWordsMustMatchAcrossArtistTitleYearPlaceMediumAndTags() {
        assertEquals(setOf("a", "d"), WorkSearch.search("portrait", works).map { it.artwork.id }.toSet())
        assertEquals(listOf("a"), WorkSearch.search("van gogh portrait", works).map { it.artwork.id })
        assertEquals(setOf("b", "e"), WorkSearch.search("1889", works).map { it.artwork.id }.toSet())
        assertEquals(listOf("b"), WorkSearch.search("saint remy", works).map { it.artwork.id })            // sans accents
        assertEquals(listOf("e"), WorkSearch.search("SARGENT aquarelle", works).map { it.artwork.id })      // technique déduite, casse ignorée
        assertEquals(listOf("c"), WorkSearch.search("dessin", works).map { it.artwork.id })
        assertEquals(listOf("d"), WorkSearch.search("met huile", works).map { it.artwork.id })
        assertTrue(WorkSearch.search("   ", works).isEmpty())
        assertTrue(WorkSearch.search("zzzz", works).isEmpty())
    }

    @Test fun hitsCarryTheirArtist() {
        val hit = WorkSearch.search("madame", works).single()
        assertEquals("john-singer-sargent", hit.artist.id)
    }

    @Test fun historyKeepsMostRecentFirstWithoutDuplicatesAndCaps() {
        var h = emptyList<String>()
        h = SearchHistory.add(h, "Portrait huile")
        h = SearchHistory.add(h, "paysage 1888")
        h = SearchHistory.add(h, "  portrait   HUILE ")
        assertEquals(listOf("portrait HUILE", "paysage 1888"), h)
        assertEquals(h, SearchHistory.add(h, "  "))
        assertEquals(listOf("paysage 1888"), SearchHistory.remove(h, "portrait HUILE"))
        for (i in 1..40) h = SearchHistory.add(h, "recherche $i")
        assertEquals(SearchHistory.MAX, h.size)
        assertEquals("recherche 40", h.first())
        assertEquals(h, SearchHistory.decode(SearchHistory.encode(h)))
        assertTrue(SearchHistory.decode("pas du json").isEmpty() && SearchHistory.decode(null).isEmpty())
    }

    @Test fun chronoTourOrdersByDateAndStopsAtTheEnds() {
        val ordered = ChronoTour.order(works.values.flatten()).map { it.id }
        assertEquals(listOf("d", "c", "a", "b", "e"), ordered)          // 1884, 1885, 1887, 1889 (b avant e : identifiant)
    }

    @Test fun chronoTourSteps() {
        assertEquals(2, ChronoTour.next(-1, 5, 2))
        assertEquals(3, ChronoTour.next(2, 5, 2))
        assertEquals(4, ChronoTour.next(4, 5, 0))
        assertEquals(0, ChronoTour.previous(0, 5, 3))
        assertEquals(1, ChronoTour.previous(2, 5, 3))
        assertEquals(-1, ChronoTour.next(-1, 0, 0))
    }
}
