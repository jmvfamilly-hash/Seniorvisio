package com.vangoghtimeline

import com.vangoghtimeline.iiif.ArticParser
import com.vangoghtimeline.iiif.ClevelandParser
import com.vangoghtimeline.iiif.EuropeanaParser
import com.vangoghtimeline.iiif.ImageReachability
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.MetParser
import com.vangoghtimeline.iiif.Reach
import com.vangoghtimeline.iiif.RijksmuseumParser
import com.vangoghtimeline.iiif.SmkParser
import com.vangoghtimeline.iiif.SourceState
import com.vangoghtimeline.iiif.SourceValidator
import com.vangoghtimeline.iiif.UniverseLoader
import com.vangoghtimeline.iiif.WikipediaSummaryParser
import com.vangoghtimeline.iiif.defaultMuseumSources
import com.vangoghtimeline.model.Artist
import com.vangoghtimeline.model.ArtistCatalog
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.DatePrecision
import com.vangoghtimeline.model.Movement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/*
 * Catalogue, parseurs des musées ajoutés, validation d'accès et chargement d'un univers. Les réponses types suivent les formats
 * documentés des API ; elles n'ont PAS été comparées à des réponses réelles (voir le README : c'est le rôle du validateur à l'exécution).
 */
class UniverseTest {
    private fun catalog(): List<Artist> {
        val file = File("src/main/assets/artists_by_movement.json")
        return ArtistCatalog.parse(file.readText())
    }

    // ── Catalogue ─────────────────────────────────────────────────────────────────
    @Test fun catalogHasAllArtistsInThreeMovements() {
        val artists = catalog()
        assertEquals(20, artists.size)
        assertEquals(5, artists.count { it.movement == Movement.PRE_IMPRESSIONISM })
        assertEquals(9, artists.count { it.movement == Movement.IMPRESSIONISM })
        assertEquals(6, artists.count { it.movement == Movement.POST_IMPRESSIONISM })
    }

    @Test fun periodsAreParsedIncludingDecades() {
        assertEquals(1860 to 1926, ArtistCatalog.parsePeriod("1860s-1926"))
        assertEquals(1870 to 1919, ArtistCatalog.parsePeriod("1870s-1910s"))   // « 1910s » finit en 1919
        assertEquals(1840 to 1877, ArtistCatalog.parsePeriod("1840-1877"))
        assertEquals(null to null, ArtistCatalog.parsePeriod("inconnue"))
    }

    @Test fun onlyConfiguredArtistsHaveAUniverseTheOthersAreGrayed() {
        val byId = catalog().associateBy { it.id }
        assertTrue(byId.getValue("vincent-van-gogh").hasUniverse)
        assertTrue(byId.getValue("john-singer-sargent").hasUniverse)
        assertTrue(byId.getValue("joaquin-sorolla").hasUniverse)
        assertTrue(byId.getValue("pierre-auguste-renoir").hasUniverse)
        assertFalse(byId.getValue("gustave-courbet").hasUniverse)
        assertFalse(byId.getValue("claude-monet").hasUniverse)
    }

    @Test fun artistFieldsAndSources() {
        val vg = catalog().first { it.id == "vincent-van-gogh" }
        assertEquals("1853–1890", vg.lifespan)
        assertEquals("VG", vg.initials)
        assertEquals(1880 to 1890, vg.activeStart to vg.activeEnd)
        assertEquals(2, vg.locations.size)
        assertEquals(listOf("aic", "rijks", "cleveland", "met", "europeana"), vg.sources.map { it.sourceId })
        assertEquals("Gogh, Vincent van", vg.sources.first { it.sourceId == "rijks" }.term)
        assertEquals("JS", catalog().first { it.id == "john-singer-sargent" }.initials)
    }

    @Test fun everyConfiguredSourceIsImplemented() {
        val implemented = defaultMuseumSources(ManifestSource { error("réseau") }).keys
        for (a in catalog()) for (spec in a.sources) assertTrue("${a.name} → ${spec.sourceId}", spec.sourceId in implemented)
    }

    @Test fun queryMatchesCreatorsWithoutAccentsOrCase() {
        val q = ArtworkQuery.of(catalog().first { it.id == "joaquin-sorolla" })
        assertTrue(q.matchesCreator("Joaquín Sorolla y Bastida"))
        assertFalse(q.matchesCreator("SOROLLA"))                     // le nom de famille seul ne suffit plus : il faut un prénom ou une initiale
        assertTrue(q.matchesCreator("Sorolla y Bastida, Joaquín"))
        assertFalse(q.matchesCreator("John Singer Sargent"))
        assertTrue(1900 in q.years && 1850 !in q.years)
    }

    // ── Parseurs des musées ajoutés ───────────────────────────────────────────────
    private val sargent = ArtworkQuery("John Singer Sargent", "sargent", 1866..1926)

    @Test fun metSearchThenObject() {
        assertEquals(listOf(11, 22), MetParser.parseSearch("""{"total":2,"objectIDs":[11,22]}"""))
        assertTrue(MetParser.parseSearch("""{"total":0,"objectIDs":null}""").isEmpty())
        val ok = """{"objectID":436,"isPublicDomain":true,"title":"Madame X (Madame Pierre Gautreau)","artistDisplayName":"John Singer Sargent",
            "objectBeginDate":1883,"objectEndDate":1884,"primaryImage":"https://images.metmuseum.org/CRDImages/ad/original/DT1.jpg",
            "primaryImageSmall":"https://images.metmuseum.org/CRDImages/ad/web-large/DT1.jpg","medium":"Oil on canvas"}"""
        val art = MetParser.parseObject(ok, sargent)!!
        assertEquals("met-436", art.id)
        assertEquals(1883, art.date.year)                                       // (1883 + 1884) / 2
        assertEquals("static:https://images.metmuseum.org/CRDImages/ad/original/DT1.jpg", art.iiif.viewerUrl)
        assertEquals("https://images.metmuseum.org/CRDImages/ad/web-large/DT1.jpg", art.iiif.thumbnailUrlFor(300, 200))
        assertTrue(art.iiif.canOpenViewer)
        assertNull(MetParser.parseObject(ok.replace("true", "false"), sargent))                       // pas du domaine public
        assertNull(MetParser.parseObject(ok.replace("John Singer Sargent", "Autre"), sargent))      // autre artiste
        assertNull(MetParser.parseObject(ok.replace("https://images.metmuseum.org/CRDImages/ad/original/DT1.jpg", ""), sargent)) // pas d'image
    }

    @Test fun clevelandArtworks() {
        val text = """{"data":[
          {"id":1,"title":"Wheat Field","creation_date":"1889","creation_date_earliest":1889,"creation_date_latest":1889,
           "creators":[{"description":"Vincent van Gogh (Dutch, 1853–1890)"}],
           "images":{"web":{"url":"https://openaccess-cdn.clevelandart.org/1/1_web.jpg","width":"1054","height":"1280"},
                     "print":{"url":"https://openaccess-cdn.clevelandart.org/1/1_print.jpg","width":"3400","height":"4000"}}},
          {"id":2,"title":"Copie","creation_date":"1950","creators":[{"description":"Autre (French)"}],"images":{"web":{"url":"https://x/y.jpg"}}}]}"""
        val arts = ClevelandParser.parse(text, ArtworkQuery.VAN_GOGH)
        assertEquals(listOf("cleveland-1"), arts.map { it.id })
        assertEquals("static:https://openaccess-cdn.clevelandart.org/1/1_print.jpg", arts[0].iiif.viewerUrl)
        assertEquals("https://openaccess-cdn.clevelandart.org/1/1_web.jpg", arts[0].iiif.thumbnailUrl)
        assertEquals(3400f / 4000f, arts[0].iiif.aspectRatio, 1e-4f)
    }

    @Test fun smkArtworksWithNativeIiif() {
        val text = """{"items":[
          {"object_number":"KMS3911","artist":["Pierre-Auguste Renoir"],"titles":[{"title":"Portrait of a Lady"}],
           "production_date":[{"start":"1880-01-01T00:00:00","end":"1881-12-31T00:00:00"}],
           "image_iiif_id":"https://iiif.smk.dk/iiif/jp2/KMS3911.tif.jp2","image_width":2000,"image_height":2600},
          {"object_number":"KMS1","artist":["Autre"],"titles":[{"title":"X"}],"production_date":[{"start":"1880-01-01T00:00:00"}],"image_iiif_id":"https://i/x"},
          {"object_number":"KMS2","artist":["Pierre-Auguste Renoir"],"titles":[{"title":"Sans IIIF"}],"production_date":[{"start":"1880-01-01T00:00:00"}]}]}"""
        val arts = SmkParser.parse(text, ArtworkQuery("Pierre-Auguste Renoir", "Renoir", 1851..1920))
        assertEquals(1, arts.size)
        assertEquals("https://iiif.smk.dk/iiif/jp2/KMS3911.tif.jp2/info.json", arts[0].iiif.infoJsonUrl)
        assertEquals(1880, arts[0].date.year)
    }

    @Test fun wikipediaThumbnail() {
        assertEquals("https://upload.wikimedia.org/a/b.jpg", WikipediaSummaryParser.thumbnail("""{"title":"X","thumbnail":{"source":"https://upload.wikimedia.org/a/b.jpg","width":320}}"""))
        assertNull(WikipediaSummaryParser.thumbnail("""{"title":"X"}"""))
        assertNull(WikipediaSummaryParser.thumbnail("garbage"))
    }

    @Test fun searchUrlsAreBuiltPerArtist() {
        assertTrue(ArticParser.searchUrl(sargent).contains("q=John%20Singer%20Sargent"))
        assertTrue(EuropeanaParser.searchUrl(sargent).contains("John+Singer+Sargent"))
        assertTrue(RijksmuseumParser.searchUrl("Gogh, Vincent van").contains("creator=Gogh%2C%20Vincent%20van"))
        assertTrue(MetParser.searchUrl(sargent).contains("q=John+Singer+Sargent"))
    }

    // ── Validation d'accès, avant connexion ───────────────────────────────────────
    private class FakeHttp(val pages: Map<String, String>) : ManifestSource {
        val asked = ArrayList<String>()
        override suspend fun fetch(url: String): String { asked += url; return pages[url] ?: throw IOException("HTTP 403 sur $url") }
    }

    private val reachOk = ImageReachability { Reach(206, "image/jpeg") }
    private fun art(n: Int, viewer: String = "static:https://img/$n.jpg") =
        com.vangoghtimeline.model.Artwork("a$n", "Titre $n", com.vangoghtimeline.model.ArtworkDate.year(1880 + n),
            iiif = if (viewer.startsWith("static:")) com.vangoghtimeline.model.IiifRef("m:$n", imageUrl = viewer.removePrefix("static:"))
                   else com.vangoghtimeline.model.IiifRef(viewer))

    @Test fun staticImagesMustBeReachableImages() = runBlocking {
        val v = SourceValidator(FakeHttp(emptyMap()), reachOk)
        val (ok, probes) = v.validate((1..6).map { art(it) })
        assertTrue(ok)
        assertEquals(3, probes.size)                                   // échantillon de 3, première, médiane, dernière
        assertEquals(listOf("Titre 1", "Titre 3", "Titre 6"), probes.map { it.artworkTitle })
    }

    @Test fun anHtmlPageInsteadOfAnImageIsRejectedWithItsReason() = runBlocking {
        val v = SourceValidator(FakeHttp(emptyMap()), { Reach(200, "text/html; charset=utf-8") })
        val (ok, probes) = v.validate((1..4).map { art(it) })
        assertFalse(ok)
        assertTrue(probes.all { it.detail.contains("text/html") })
    }

    @Test fun http403OnImagesRejectsTheSource() = runBlocking {
        val v = SourceValidator(FakeHttp(emptyMap()), { Reach(403, null) })
        val (ok, probes) = v.validate(listOf(art(1)))
        assertFalse(ok)
        assertTrue(probes.single().detail.contains("HTTP 403"))
    }

    private val infoOk = """{"@id":"https://s/iiif/x","width":3000,"height":2000}"""
    private val manifestWithService = """{"@type":"sc:Manifest","sequences":[{"canvases":[{"images":[{"resource":{"@id":"https://s/iiif/x/full/full/0/default.jpg","service":{"@id":"https://s/iiif/x"}}}]}]}]}"""
    private val manifestStatic = """{"@type":"sc:Manifest","sequences":[{"canvases":[{"images":[{"resource":{"@id":"https://s/photo.jpg"}}]}]}]}"""

    @Test fun manifestIsFollowedToItsServiceInfoJson() = runBlocking {
        val http = FakeHttp(mapOf("https://m/1" to manifestWithService, "https://s/iiif/x/info.json" to infoOk))
        val line = SourceValidator(http, reachOk).probe(art(1, "https://m/1"))
        assertTrue(line.detail, line.ok)
        assertTrue(line.detail.contains("3000×2000"))
    }

    @Test fun manifestWithoutServiceFallsBackToAReachableImage() = runBlocking {
        val line = SourceValidator(FakeHttp(mapOf("https://m/1" to manifestStatic)), reachOk).probe(art(1, "https://m/1"))
        assertTrue(line.detail, line.ok)
        assertTrue(line.detail.contains("image ordinaire"))
    }

    @Test fun infoJsonWithoutSizeIsRejected() = runBlocking {
        val line = SourceValidator(FakeHttp(mapOf("https://s/info.json" to """{"id":"x"}""")), reachOk).probe(art(1, "https://s/info.json"))
        assertFalse(line.ok)
        assertTrue(line.detail.contains("largeur/hauteur"))
    }

    @Test fun unreadableManifestReportsTheHttpError() = runBlocking {
        val line = SourceValidator(FakeHttp(emptyMap()), reachOk).probe(art(1, "https://m/missing"))
        assertFalse(line.ok)
        assertTrue(line.detail.contains("HTTP 403"))
    }

    // ── Chargement d'un univers ───────────────────────────────────────────────────
    private val aicJson = """{"data":[{"id":28560,"title":"The Bedroom","artist_title":"Vincent van Gogh","date_start":1889,"image_id":"abc","thumbnail":{"width":4,"height":3}}],"config":{"iiif_url":"https://www.artic.edu/iiif/2"}}"""
    private val metSearch = """{"total":1,"objectIDs":[7]}"""
    private val metObject = """{"objectID":7,"isPublicDomain":true,"title":"Irises","artistDisplayName":"Vincent van Gogh","objectBeginDate":1890,"objectEndDate":1890,"primaryImage":"https://images.metmuseum.org/7.jpg","primaryImageSmall":"https://images.metmuseum.org/7s.jpg"}"""
    private fun tmp(): File = kotlin.io.path.createTempDirectory("uni").toFile()
    private fun vanGogh() = catalog().first { it.id == "vincent-van-gogh" }.copy(sources = listOf(
        com.vangoghtimeline.model.SourceSpec("aic"), com.vangoghtimeline.model.SourceSpec("met"), com.vangoghtimeline.model.SourceSpec("cleveland"),
    ))

    private fun pages() = mapOf(
        ArticParser.searchUrl(ArtworkQuery.of(vanGogh())) to aicJson,
        "https://api.artic.edu/api/v1/artworks/28560/manifest.json" to
            """{"@type":"sc:Manifest","sequences":[{"canvases":[{"images":[{"resource":{"@id":"https://www.artic.edu/iiif/2/abc/full/843,/0/default.jpg","service":{"@id":"https://www.artic.edu/iiif/2/abc"}}}]}]}]}""",
        "https://www.artic.edu/iiif/2/abc/info.json" to """{"@id":"https://www.artic.edu/iiif/2/abc","width":4032,"height":3200}""",
        MetParser.searchUrl(ArtworkQuery.of(vanGogh())) to metSearch,
        MetParser.objectUrl(7) to metObject,
    )

    @Test fun validatedSourcesAreConnectedFailingOnesAreRejectedOrUnreachable() = runBlocking {
        val http = FakeHttp(pages())
        // le Met répond mais ses images sont refusées (403) ; Cleveland ne répond pas du tout
        val loader = UniverseLoader(defaultMuseumSources(http), SourceValidator(http, { url -> if (url.contains("metmuseum")) Reach(403, null) else Reach(206, "image/jpeg") }), tmp())
        val updates = ArrayList<Int>()
        val state = loader.load(vanGogh()) { updates += it.artworks.size }
        val byId = state.reports.associateBy { it.sourceId }
        assertEquals(SourceState.CONNECTED, byId.getValue("aic").state)
        assertEquals(SourceState.REJECTED, byId.getValue("met").state)
        assertTrue(byId.getValue("met").detail, byId.getValue("met").detail.contains("HTTP 403"))
        assertEquals(SourceState.UNREACHABLE, byId.getValue("cleveland").state)
        assertEquals(listOf("The Bedroom"), state.artworks.map { it.title })      // rien venant d'une source refusée
        assertTrue(state.done)
        assertTrue(state.credit, state.credit == "Art Institute of Chicago (1)")
        assertTrue(updates.isNotEmpty())
    }

    @Test fun twoValidatedSourcesAreMergedAndTheLoaderOnlyAskedForWhatItNeeds() = runBlocking {
        val http = FakeHttp(pages())
        val loader = UniverseLoader(defaultMuseumSources(http), SourceValidator(http, reachOk), tmp())
        val state = loader.load(vanGogh()) { }
        assertEquals(listOf("The Bedroom", "Irises"), state.artworks.map { it.title })
        assertEquals(2, state.connectedCount)
        assertTrue(state.credit, state.credit.contains("Art Institute of Chicago (1)") && state.credit.contains("The Metropolitan Museum of Art (1)"))
    }

    @Test fun offlineFallsBackToTheCopyOfAPreviousValidatedConnection() = runBlocking {
        val dir = tmp()
        val http = FakeHttp(pages())
        UniverseLoader(defaultMuseumSources(http), SourceValidator(http, reachOk), dir).load(vanGogh()) { }
        val offline = FakeHttp(emptyMap())
        // 8 jours plus tard : les données enregistrées sont périmées, la mise à jour échoue (hors ligne) → on garde la copie
        val state = UniverseLoader(defaultMuseumSources(offline), SourceValidator(offline, reachOk), dir, clock = { System.currentTimeMillis() + 8L * 86_400_000 }).load(vanGogh()) { }
        assertEquals(setOf("The Bedroom", "Irises"), state.artworks.map { it.title }.toSet())
        assertEquals(SourceState.CACHED, state.reports.first { it.sourceId == "aic" }.state)
        assertTrue(state.credit.contains("hors ligne"))
    }

    @Test fun anUnknownSourceIsReportedUnavailableNotFatal() = runBlocking {
        val http = FakeHttp(pages())
        val artist = vanGogh().copy(sources = listOf(com.vangoghtimeline.model.SourceSpec("aic"), com.vangoghtimeline.model.SourceSpec("harvard")))
        val state = UniverseLoader(defaultMuseumSources(http), SourceValidator(http, reachOk), tmp()).load(artist) { }
        assertEquals(SourceState.UNAVAILABLE, state.reports.first { it.sourceId == "harvard" }.state)
        assertEquals(1, state.artworks.size)
    }

    @Test fun europeanaDuplicatesOfADirectlyConnectedMuseumAreDropped() = runBlocking {
        val q = ArtworkQuery.of(vanGogh())
        val europeana = """{"items":[
            {"id":"/9/a","title":["Irises"],"dcCreator":["Vincent van Gogh"],"year":["1890"],"edmPreview":["https://t/1"],"dataProvider":["The Metropolitan Museum of Art"]},
            {"id":"/9/b","title":["Autre dessin"],"dcCreator":["Vincent van Gogh"],"year":["1888"],"edmPreview":["https://t/2"],"dataProvider":["Kröller-Müller Museum"]}]}"""
        val http = FakeHttp(pages() + mapOf(EuropeanaParser.searchUrl(q) to europeana,
            "https://iiif.europeana.eu/presentation/9/b/manifest" to manifestWithService, "https://s/iiif/x/info.json" to infoOk,
            "https://iiif.europeana.eu/presentation/9/a/manifest" to manifestWithService))
        val artist = vanGogh().copy(sources = listOf(com.vangoghtimeline.model.SourceSpec("met"), com.vangoghtimeline.model.SourceSpec("europeana")))
        val state = UniverseLoader(defaultMuseumSources(http), SourceValidator(http, reachOk), tmp()).load(artist) { }
        assertEquals(setOf("Irises", "Autre dessin"), state.artworks.map { it.title }.toSet())
        assertEquals("met-7", state.artworks.first { it.title == "Irises" }.id)       // la notice directe du Met, pas celle d'Europeana
    }
}
