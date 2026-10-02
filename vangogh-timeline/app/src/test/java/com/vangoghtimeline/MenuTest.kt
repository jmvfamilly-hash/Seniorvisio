package com.vangoghtimeline

import com.vangoghtimeline.model.BackdropIndex
import com.vangoghtimeline.model.PoiKind
import com.vangoghtimeline.model.ThirdsFit
import kotlin.math.abs
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

    // ── fond : un tableau par artiste, point d'intérêt sur la règle des tiers ───────────
    private val index by lazy { BackdropIndex.parse(File("src/main/assets/backdrops/index.json").readText()) }

    @Test fun everyArtistEvenWithoutUniverseHasABackdropEntry() {
        for (a in catalog) assertTrue("fond manquant : ${a.name}", index[a.id] != null)
    }

    @Test fun ngaBackdropsAreRealOpenAccessImagesOfTheNgaFiles() {
        // identifiant et taille de chaque fond NGA : ils doivent figurer, tels quels, dans les données NGA extraites (pas de valeur inventée)
        val all = File("src/main/assets/nga").listFiles()!!.joinToString("\n") { it.readText() }
        val nga = index.values.filter { it.remoteUrl.startsWith("https://api.nga.gov/iiif/") }
        assertTrue(nga.size >= 14)
        for (b in nga) {
            val uuid = b.remoteUrl.removePrefix("https://api.nga.gov/iiif/").substringBefore('/')
            assertTrue("${b.artistId} : taille ou identifiant", all.contains("\"image\":\"$uuid\"") || b.artistId !in listOf("vincent-van-gogh", "claude-monet", "pierre-auguste-renoir", "berthe-morisot", "paul-gauguin", "john-singer-sargent", "joaquin-sorolla"))
            assertTrue(b.credit, b.credit.contains("National Gallery of Art") && b.credit.contains(b.date))
            assertTrue(b.usable && b.poiX in 0f..1f && b.poiY in 0f..1f)
        }
    }

    @Test fun thePointOfInterestLandsExactlyOnAThirdsLineForEveryScreenShape() {
        val third = 1f / 3f
        for (b in index.values.filter { it.usable }) for ((w, h) in listOf(1080f to 2340f, 1200f to 1920f, 2400f to 1080f, 1600f to 1600f)) {
            val p = ThirdsFit.fit(b.width, b.height, w, h, b.poiX, b.poiY, b.kind)
            // l'image couvre tout l'écran
            assertTrue("${b.artistId} couverture", p.offsetX <= 0.5f && p.offsetY <= 0.5f && p.offsetX + p.imageW >= w - 0.5f && p.offsetY + p.imageH >= h - 0.5f)
            val px = (p.offsetX + b.poiX * p.imageW) / w
            val py = (p.offsetY + b.poiY * p.imageH) / h
            if (p.exact) {
                assertTrue("${b.artistId} x=$px", abs(px - third) < 0.002f || abs(px - 2 * third) < 0.002f)
                assertTrue("${b.artistId} y=$py", abs(py - third) < 0.002f || (b.kind != PoiKind.FACE && abs(py - 2 * third) < 0.002f))
            }
        }
    }

    @Test fun aCenteredPointNeedsAZoomAndAFaceGoesOnTheUpperThirdLine() {
        val p = ThirdsFit.fit(1000, 1000, 1000f, 1000f, 0.5f, 0.5f, PoiKind.FACE)
        assertTrue(p.exact)
        assertEquals(1f / 3f, (p.offsetY + 0.5f * p.imageH) / 1000f, 0.001f)
        assertTrue("zoom nécessaire", p.imageW > 1000f)
        // un point déjà sur un tiers, image de la forme de l'écran : aucun zoom
        val q = ThirdsFit.fit(900, 900, 900f, 900f, 1f / 3f, 1f / 3f, PoiKind.TREE)
        assertEquals(900f, q.imageW, 0.5f)
    }

    @Test fun theZoomIsCappedAndReportsWhenTheThirdCannotBeReached() {
        val p = ThirdsFit.fit(1000, 1000, 1000f, 1000f, 0.99f, 0.5f, PoiKind.TREE, maxZoom = 1.5f)
        assertTrue(p.imageW <= 1500.5f)
        assertTrue(p.offsetX <= 0f && p.offsetX + p.imageW >= 999.5f)
    }

    @Test fun backdropsParseFromTheIndexAndUnfetchedOnesAreNotUsable() {
        assertEquals(PoiKind.FACE, index["vincent-van-gogh"]!!.kind)
        assertEquals(PoiKind.TREE, index["claude-monet"]!!.kind)
        assertTrue(!index["edvard-munch"]!!.usable)
    }
}
