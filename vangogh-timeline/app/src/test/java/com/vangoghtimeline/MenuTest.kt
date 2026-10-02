package com.vangoghtimeline

import com.vangoghtimeline.model.ArtistBackdrops
import com.vangoghtimeline.model.ArtistCatalog
import com.vangoghtimeline.model.MenuRow
import com.vangoghtimeline.model.Movement
import com.vangoghtimeline.model.SortMode
import com.vangoghtimeline.model.menuRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MenuTest {
    private val catalog by lazy { ArtistCatalog.parse(File("src/main/assets/artists_by_movement.json").readText()) }

    private fun names(rows: List<MenuRow>) = rows.filterIsInstance<MenuRow.Item>().map { it.artist.name }

    @Test fun searchFiltersByNameCountryOrStyleWithoutCase() {
        assertEquals(listOf("Vincent van Gogh"), names(menuRows(catalog, "van gogh", SortMode.ALPHA)))
        assertEquals(catalog.size, names(menuRows(catalog, "   ", SortMode.ALPHA)).size)              // recherche vide : tout le monde
        val monets = names(menuRows(catalog, "MONET", SortMode.ALPHA))
        assertTrue(monets.toString(), "Claude Monet" in monets)
        assertTrue(names(menuRows(catalog, "zzzz-introuvable", SortMode.ALPHA)).isEmpty())
    }

    @Test fun alphabeticalSortIsFlatAndOrdered() {
        val rows = menuRows(catalog, "", SortMode.ALPHA)
        assertTrue(rows.all { it is MenuRow.Item })
        val n = names(rows).map { it.lowercase() }
        assertEquals(n.sorted(), n)
    }

    @Test fun periodSortGroupsLikeTheMockupMostRecentFirst() {
        val rows = menuRows(catalog, "", SortMode.PERIOD)
        val headers = rows.filterIsInstance<MenuRow.Header>().map { it.text }
        assertEquals(listOf(Movement.POST_IMPRESSIONISM, Movement.IMPRESSIONISM, Movement.PRE_IMPRESSIONISM).map { it.labelFr.uppercase() }, headers)
        // chaque artiste est sous le titre de son mouvement
        var current: Movement? = null
        for (r in rows) when (r) {
            is MenuRow.Header -> current = Movement.values().first { it.labelFr.uppercase() == r.text }
            is MenuRow.Item -> assertEquals(r.artist.name, current, r.artist.movement)
        }
        assertEquals(catalog.size, names(rows).size)
    }

    @Test fun countrySortGroupsUnderCountryTitles() {
        val rows = menuRows(catalog, "", SortMode.COUNTRY)
        assertTrue(rows.first() is MenuRow.Header)
        assertEquals(catalog.size, names(rows).size)
        val countries = rows.filterIsInstance<MenuRow.Header>().map { it.text }
        assertEquals(countries.sorted(), countries)
    }

    // ── fond : découpe IIIF du tableau ───────────────────────────────────────────
    @Test fun everyArtistWithAUniverseHasABackdrop() {
        for (a in catalog.filter { it.hasUniverse }) assertTrue("fond manquant : ${a.name}", ArtistBackdrops.of(a.id) != null)
    }

    @Test fun theBackdropRegionAlwaysStaysInsideTheImageWithTheScreenShape() {
        for (a in catalog.filter { it.hasUniverse }) {
            val b = ArtistBackdrops.of(a.id)!!
            for (aspect in listOf(0.46f, 0.56f, 1f, 1.78f, 2.17f)) {
                val (x, y, w, h) = b.region(aspect).toList()
                assertTrue("${a.name} x=$x", x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= b.width && y + h <= b.height)
                assertEquals("${a.name} forme $aspect", aspect, w.toFloat() / h, aspect * 0.02f)
            }
        }
    }

    @Test fun theBackdropUrlIsAnIiifRegionRequestOnTheNga() {
        val b = ArtistBackdrops.of("vincent-van-gogh")!!
        val url = b.url(0.46f, 1080)
        assertTrue(url, Regex("""^https://api\.nga\.gov/iiif/54ee6643-e0f9-4b92-a1d2-441e5108724d/\d+,\d+,\d+,\d+/1080,/0/default\.jpg$""").matches(url))
        assertTrue(b.credit.contains("National Gallery of Art") && b.credit.contains("1889"))
    }

    @Test fun theBackdropWorksAreRealOpenAccessImagesOfTheNgaFiles() {
        // identifiant et taille de chaque fond : ils doivent figurer, tels quels, dans les données NGA extraites (pas de valeur inventée)
        for (a in catalog.filter { it.hasUniverse }) {
            val b = ArtistBackdrops.of(a.id)!!
            val text = File("src/main/assets/nga/${a.id}.json").readText()
            val uuid = b.iiifBase.substringAfterLast('/')
            assertTrue("${a.name} : image $uuid absente des données NGA", text.contains("\"image\":\"$uuid\""))
            assertTrue("${a.name} : taille", text.contains("\"width\":${b.width},\"height\":${b.height}"))
            assertTrue("${a.name} : open access", Regex("\"image\":\"$uuid\".{0,200}?\"openaccess\":true").containsMatchIn(text))
        }
    }
}
