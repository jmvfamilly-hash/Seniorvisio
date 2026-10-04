package com.vangoghtimeline

import com.vangoghtimeline.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MuseumMapTest {
    private fun art(id: String, provider: String, vararg details: Pair<String, String>) =
        Artwork(id, "t", ArtworkDate.year(1888), iiif = IiifRef(""), provider = provider, details = details.toList())

    @Test fun directSourcesAndCollectionsAreLocated() {
        assertEquals("aic", MuseumMap.locate(art("a", "Art Institute of Chicago"))?.id)
        assertEquals("nga", MuseumMap.locate(art("b", "National Gallery of Art"))?.id)            // pas Londres
        assertEquals("rijks", MuseumMap.locate(art("c", "Rijksmuseum"))?.id)
        assertEquals("met", MuseumMap.locate(art("d", "The Met"))?.id)
        assertEquals("orsay", MuseumMap.locate(art("e", "Wikimedia", "Collection" to "musée d'Orsay"))?.id)        // accents ignorés
        assertEquals("ngl", MuseumMap.locate(art("f", "Wikimedia", "Collection" to "National Gallery, London"))?.id)
        assertEquals("sorolla", MuseumMap.locate(art("g", "Europeana", "Fournisseur" to "Museo Sorolla"))?.id)
        assertEquals("kroller", MuseumMap.locate(art("h", "Wikimedia", "Collection" to "Kröller-Müller Museum"))?.id)
    }

    @Test fun unknownPlacesAreNotGuessed() {
        assertNull(MuseumMap.locate(art("x", "Wikimedia", "Collection" to "private collection")))
        assertNull(MuseumMap.locate(art("y", "")))
        // un autre détail (technique…) ne sert pas à localiser
        assertNull(MuseumMap.locate(art("z", "Wikimedia", "Technique" to "Orsay blue oil")))
    }

    @Test fun clustersGroupByPlaceLargestFirstAndKeepUnknown() {
        val list = listOf(art("1", "Rijksmuseum"), art("2", "Art Institute of Chicago"), art("3", "Rijksmuseum"), art("4", "Wikimedia"))
        val (clusters, unknown) = MuseumMap.clusters(list)
        assertEquals(listOf("rijks", "aic"), clusters.map { it.place.id })
        assertEquals(2, clusters.first().artworks.size)
        assertEquals(listOf("4"), unknown.map { it.id })
    }

    @Test fun mercatorProjectionIsOrderedAndCentered() {
        val (x0, y0) = MuseumMap.project(0.0, 0.0)
        assertEquals(0.5, x0, 1e-9); assertEquals(0.5, y0, 1e-9)
        val paris = MuseumMap.project(48.86, 2.33); val chicago = MuseumMap.project(41.88, -87.62)
        assertTrue(chicago.first < paris.first)          // Chicago à l'ouest de Paris
        assertTrue(paris.second < chicago.second)        // Paris au nord de Chicago
        assertTrue(MuseumMap.project(-33.9, 151.2).second > 0.5)   // Sydney dans l'hémisphère sud
    }

    @Test fun placeIdsAreUniqueAndCoordinatesValid() {
        assertEquals(MuseumMap.places.size, MuseumMap.places.map { it.id }.toSet().size)
        assertTrue(MuseumMap.places.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && it.keys.isNotEmpty() })
    }

    @Test fun landAssetIsReadable() {
        val text = File("src/main/assets/map/land.json").readText()
        assertTrue(text.contains("\"rings\""))
    }
}
