package com.vangoghtimeline

import com.vangoghtimeline.iiif.ArticParser
import com.vangoghtimeline.iiif.ArticRepository
import com.vangoghtimeline.iiif.ArtworkSource
import com.vangoghtimeline.model.ArtworkDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArticParserTest {
    // Réponse type de l'API de l'Art Institute of Chicago (champs demandés par SEARCH_URL).
    private val response = """
    {
      "pagination": {"total": 4},
      "data": [
        {"id": 28560, "title": "The Bedroom", "artist_title": "Vincent van Gogh", "date_start": 1889, "date_end": 1889,
         "place_of_origin": "Saint-Rémy-de-Provence, France", "medium_display": "Oil on canvas",
         "image_id": "25c31d8d-21a4-9ea1-1d73-6a2eca4dda7e", "thumbnail": {"width": 4032, "height": 3200, "alt_text": "x"}},
        {"id": 80607, "title": "Self-Portrait", "artist_title": "Vincent van Gogh", "date_start": 1887, "date_end": 1888,
         "place_of_origin": "Paris", "medium_display": "Oil on artist's board", "image_id": "abc", "thumbnail": {"width": 2000, "height": 2500}},
        {"id": 1, "title": "Après Van Gogh", "artist_title": "Autre Artiste", "date_start": 1950, "image_id": "zzz"},
        {"id": 2, "title": "Sans image", "artist_title": "Vincent van Gogh", "date_start": 1888, "image_id": null}
      ],
      "config": {"iiif_url": "https://www.artic.edu/iiif/2", "website_url": "http://www.artic.edu"}
    }"""

    @Test fun keepsOnlyVanGoghWorksWithAnImage() {
        val arts = ArticParser.parse(response)
        assertEquals(listOf("artic-80607", "artic-28560"), arts.map { it.id })     // triées par date : 1887 puis 1889
    }

    @Test fun buildsIiifImageService() {
        val bedroom = ArticParser.parse(response).first { it.title == "The Bedroom" }
        assertEquals("https://www.artic.edu/iiif/2/25c31d8d-21a4-9ea1-1d73-6a2eca4dda7e", bedroom.iiif.imageServiceId)
        assertEquals("https://www.artic.edu/iiif/2/25c31d8d-21a4-9ea1-1d73-6a2eca4dda7e/info.json", bedroom.iiif.infoJsonUrl)
        assertEquals("https://api.artic.edu/api/v1/artworks/28560/manifest.json", bedroom.iiif.manifestUrl)
        assertTrue(bedroom.iiif.canOpenViewer)
        assertEquals(4032f / 3200f, bedroom.iiif.aspectRatio, 1e-4f)
    }

    @Test fun datesAreYearPrecision() {
        val bedroom = ArticParser.parse(response).first { it.title == "The Bedroom" }
        assertEquals(ArtworkDate.year(1889), bedroom.date)
    }

    @Test fun placeKeepsTheCityOnly() {
        val arts = ArticParser.parse(response)
        assertEquals("Saint-Rémy-de-Provence", arts.first { it.title == "The Bedroom" }.place)
        assertEquals("Paris", arts.first { it.title == "Self-Portrait" }.place)
    }

    @Test fun thumbnailsUseWidthOnlyForm() {
        val bedroom = ArticParser.parse(response).first { it.title == "The Bedroom" }
        assertEquals("https://www.artic.edu/iiif/2/25c31d8d-21a4-9ea1-1d73-6a2eca4dda7e/full/400,/0/default.jpg", bedroom.iiif.thumbnailUrlFor(400, 1000))
    }

    @Test fun garbageGivesAnEmptyList() {
        assertTrue(ArticParser.parse("pas du json").isEmpty())
        assertTrue(ArticParser.parse("""{"data": "x"}""").isEmpty())
    }

    @Test fun repositoryFallsBackToTheCacheWhenOffline() = runBlocking {
        val cache = File.createTempFile("artic", ".json").apply { deleteOnExit() }
        val online = ArticRepository({ response }, cache).load()
        assertTrue(online is ArtworkSource.Online)
        // le réseau tombe : la copie enregistrée prend le relais
        val offline = ArticRepository({ error("pas de réseau") }, cache).load()
        assertTrue(offline is ArtworkSource.Cached)
        assertEquals(2, offline!!.artworks.size)
        // ni réseau ni copie : null (l'appelant prend le jeu de secours)
        cache.delete()
        assertNull(ArticRepository({ error("pas de réseau") }, cache).load())
    }

    @Test fun sampleArtworksCannotOpenTheViewer() {
        assertTrue(SampleArtworks.all.none { it.iiif.canOpenViewer })
    }
}
