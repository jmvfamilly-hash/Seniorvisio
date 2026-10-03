package com.vangoghtimeline

import com.vangoghtimeline.iiif.ArticParser
import com.vangoghtimeline.iiif.ArtworkJson
import com.vangoghtimeline.iiif.EuropeanaParser
import com.vangoghtimeline.iiif.Tally
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkMerge
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.ArtistCatalog
import com.vangoghtimeline.model.formatFr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UndatedWorksTest {
    private val catalog by lazy { ArtistCatalog.parse(File("src/main/assets/artists_by_movement.json").readText()) }

    @Test fun anUndatedWorkIsPlacedAtTheMiddleOfTheActivePeriodAndSaysSo() {
        val vg = ArtworkQuery.of(catalog.first { it.id == "vincent-van-gogh" })
        assertEquals((vg.fallbackYear), (catalog.first { it.id == "vincent-van-gogh" }.let { (it.activeStart!! + it.activeEnd!!) / 2 }))
        val d = vg.estimatedDate()
        assertTrue(d.estimated)
        assertEquals("vers ${vg.fallbackYear} (date inconnue)", d.formatFr())
        assertEquals(ArtworkDate.year(1888).formatFr(), "1888")                         // une vraie date ne change pas
        // sans période d'activité connue : le milieu des dates plausibles
        assertEquals(1900, ArtworkQuery("X Y", "y", 1880..1920).fallbackYear)
        assertEquals(1885, ArtworkQuery("X Y", "y", 1870..1900, 1885).fallbackYear)
    }

    @Test fun anArticWorkWithoutDateIsKeptAndCounted() {
        val q = ArtworkQuery("Vincent van Gogh", "gogh", 1870..1890, 1885)
        val text = """{"data":[
            {"id":1,"title":"Datée","artist_title":"Vincent van Gogh","date_start":1888,"image_id":"a"},
            {"id":2,"title":"Sans date","artist_title":"Vincent van Gogh","date_start":null,"date_end":null,"image_id":"b"},
            {"id":3,"title":"Trop tôt","artist_title":"Vincent van Gogh","date_start":1500,"image_id":"c"}],"config":{"iiif_url":"https://www.artic.edu/iiif/2"}}"""
        val t = Tally()
        val arts = ArticParser.parse(text, q, t)
        assertEquals(listOf("Sans date" to 1885, "Datée" to 1888), arts.map { it.title to it.date.year }.sortedBy { it.second })
        assertTrue(arts.first { it.title == "Sans date" }.date.estimated)
        assertEquals("3 reçues, 2 retenues (dont 1 à date estimée) — écartées : 1 hors des dates plausibles", t.summary())
    }

    @Test fun europeanaUsesTheTimespanAsASecondChanceThenTheEstimate() {
        val q = ArtworkQuery("Berthe Morisot", "Morisot", 1851..1896, 1878)
        val items = """{"items":[
          {"id":"/1/a","title":["Avec année"],"dcCreator":["Morisot, Berthe"],"year":["1885"],"edmPreview":["https://t/1"]},
          {"id":"/1/b","title":["Période"],"dcCreator":["Morisot, Berthe"],"edmTimespanLabel":[{"def":"1880 - 1890"}],"edmPreview":["https://t/2"]},
          {"id":"/1/c","title":["Langues"],"dcCreator":["Morisot, Berthe"],"edmTimespanLabelLangAware":{"def":["1890s"]},"edmPreview":["https://t/3"]},
          {"id":"/1/d","title":["Rien"],"dcCreator":["Morisot, Berthe"],"edmTimespanLabel":[{"def":"19th century"}],"edmPreview":["https://t/4"]}]}"""
        val arts = EuropeanaParser.parse(items, q).associateBy { it.title }
        assertEquals(1885, arts.getValue("Avec année").date.year); assertFalse(arts.getValue("Avec année").date.estimated)
        assertEquals(1885, arts.getValue("Période").date.year);    assertFalse(arts.getValue("Période").date.estimated)   // (1880 + 1890) / 2
        assertEquals(1890, arts.getValue("Langues").date.year);    assertFalse(arts.getValue("Langues").date.estimated)
        assertEquals(1878, arts.getValue("Rien").date.year);       assertTrue(arts.getValue("Rien").date.estimated)        // pas de repli lisible : moitié de la période d'activité
    }

    private fun art(id: String, title: String, date: ArtworkDate) = Artwork(id, title, date, iiif = IiifRef("m:$id", imageUrl = "https://x/$id.jpg"))

    @Test fun anEstimatedWorkYieldsToTheSameTitleWithARealDate() {
        val estimatedFirst = listOf(art("a", "La Nuit", ArtworkDate.estimated(1885)), art("c", "Seule", ArtworkDate.estimated(1885)))
        val dated = listOf(art("b", "La nuit.", ArtworkDate.year(1889)))
        val merged = ArtworkMerge.merge(listOf(estimatedFirst, dated))
        assertEquals(listOf("b", "c"), merged.map { it.id }.sorted())                    // « La Nuit » (estimée) disparaît, « Seule » reste
    }

    @Test fun theEstimatedFlagSurvivesTheLocalStore() {
        val a = art("a", "T", ArtworkDate.estimated(1885)); val b = art("b", "U", ArtworkDate.year(1886))
        val back = ArtworkJson.decode(ArtworkJson.encode(listOf(a, b)))
        assertTrue(back[0].date.estimated); assertFalse(back[1].date.estimated)
        assertEquals(ArtworkDate.estimated(1885), back[0].date)
    }
}
