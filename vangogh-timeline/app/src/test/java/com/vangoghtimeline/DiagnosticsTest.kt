package com.vangoghtimeline

import com.vangoghtimeline.iiif.ArticParser
import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.iiif.DiagLevel
import com.vangoghtimeline.iiif.DiagnosticsReport
import com.vangoghtimeline.iiif.ImageReachability
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.MetParser
import com.vangoghtimeline.iiif.MuseumSource
import com.vangoghtimeline.iiif.Reach
import com.vangoghtimeline.iiif.SourceState
import com.vangoghtimeline.iiif.SourceValidator
import com.vangoghtimeline.iiif.UniverseLoader
import com.vangoghtimeline.iiif.defaultMuseumSources
import com.vangoghtimeline.model.Artist
import com.vangoghtimeline.model.ArtistCatalog
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.SourceSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class DiagnosticsTest {
    @Before fun reset() {
        Diag.clear(); Diag.context = null; Diag.clock = { 1_700_000_000_000L }
    }

    @Test fun identicalEventsAreMergedWithACount() {
        repeat(37) { Diag.warn("tuile", "HTTP 403", "https://h/t$it", key = "tuile|h|403") }
        val events = Diag.snapshot()
        assertEquals(1, events.size)
        assertEquals(37, events[0].count)
        assertEquals("https://h/t0", events[0].url)          // le premier exemple d'URL est gardé
    }

    @Test fun differentLevelsOrKeysAreKeptApart() {
        Diag.warn("a", "m"); Diag.error("a", "m"); Diag.warn("a", "autre")
        assertEquals(3, Diag.snapshot().size)
    }

    @Test fun theLogIsBoundedAndKeepsTheMostRecent() {
        repeat(Diag.MAX_EVENTS + 100) { Diag.error("source", "échec $it") }
        val events = Diag.snapshot()
        assertEquals(Diag.MAX_EVENTS, events.size)
        assertEquals("échec ${Diag.MAX_EVENTS + 99}", events.last().message)
        assertFalse(events.any { it.message == "échec 0" })
    }

    @Test fun longMessagesAreTruncatedAndContextIsAttributed() {
        Diag.context = "vincent-van-gogh"
        Diag.error("visionneuse", "x".repeat(5000))
        val e = Diag.snapshot().single()
        assertTrue(e.message.length <= Diag.MAX_MESSAGE + 1)
        assertEquals("vincent-van-gogh", e.artistId)
    }

    @Test fun hostOfGroupsByServer() {
        assertEquals("api.artic.edu", Diag.hostOf("https://api.artic.edu/api/v1/x?y=1"))
        assertEquals("h", Diag.hostOf("http://h"))
    }

    private fun artist(id: String, name: String, vararg sources: String) = Artist(
        id, name, "", "", null, null, "", "", "", emptyList(), com.vangoghtimeline.model.Movement.IMPRESSIONISM,
        sources = sources.map { SourceSpec(it) },
    )

    @Test fun reportListsEverythingAcrossArtistsServicesAndNavigation() {
        Diag.warn("source", "recherche en échec : HTTP 410 sur https://m/search", "https://m/search", "met", "vincent-van-gogh")
        Diag.warn("vignette", "HTTP 403", "https://img/1", key = "v|img|403"); Diag.warn("vignette", "HTTP 403", "https://img/2", key = "v|img|403")
        Diag.error("visionneuse", "ouverture impossible de « Pont » : No value for width", "https://m/pont", artistId = "john-singer-sargent")
        val report = DiagnosticsReport.build(
            "Version rev99\nAppareil : Test",
            listOf(
                DiagnosticsReport.ArtistSection("claude-monet", "Claude Monet", emptyList(), null),
                DiagnosticsReport.ArtistSection("john-singer-sargent", "John Singer Sargent", listOf("aic", "met"), null),
            ),
            Diag.snapshot(), lastCrash = "Plantage du 2026… NullPointerException",
        )
        for (needle in listOf("Version rev99", "grisé : aucune source configurée", "non préparé (sources : aic, met)", "ALERTE source/met [vincent-van-gogh]",
            "HTTP 410 sur https://m/search", "(×2", "ERREUR visionneuse [john-singer-sargent]", "No value for width", "Dernier plantage", "NullPointerException")) {
            assertTrue("manque « $needle » dans :\n$report", report.contains(needle))
        }
        assertTrue(report.contains("1 erreurs, 3 alertes"))     // 1 erreur ; 1 + 2 (×2 regroupé) alertes
    }

    // ── Les échecs rattrapés par un repli sont consignés ──────────────────────────
    private class FakeHttp(val pages: Map<String, String>) : ManifestSource {
        override suspend fun fetch(url: String): String = pages[url] ?: throw IOException("HTTP 403 sur $url")
    }

    private val reachOk = ImageReachability { Reach(206, "image/jpeg") }
    private fun tmp(): File = kotlin.io.path.createTempDirectory("diag").toFile()
    private val vg: Artist get() = ArtistCatalog.parse(File("src/main/assets/artists_by_movement.json").readText()).first { it.id == "vincent-van-gogh" }

    private val aicJson = """{"data":[{"id":28560,"title":"The Bedroom","artist_title":"Vincent van Gogh","date_start":1889,"image_id":"abc","thumbnail":{"width":4,"height":3}}],"config":{"iiif_url":"https://www.artic.edu/iiif/2"}}"""
    private fun aicPages(q: ArtworkQuery) = mapOf(
        ArticParser.searchUrl(q) to aicJson,
        "https://api.artic.edu/api/v1/artworks/28560/manifest.json" to
            """{"@type":"sc:Manifest","sequences":[{"canvases":[{"images":[{"resource":{"@id":"https://www.artic.edu/iiif/2/abc/full/843,/0/default.jpg","service":{"@id":"https://www.artic.edu/iiif/2/abc"}}}]}]}]}""",
        "https://www.artic.edu/iiif/2/abc/info.json" to """{"@id":"https://www.artic.edu/iiif/2/abc","width":4032,"height":3200}""",
    )

    @Test fun aSourceRescuedByTheSimpleUserAgentFallbackIsStillLogged() = runBlocking {
        val artist = vg.copy(sources = listOf(SourceSpec("aic")))
        val pages = FakeHttp(aicPages(ArtworkQuery.of(artist)))
        val loader = UniverseLoader(
            defaultMuseumSources(FakeHttp(emptyMap())), SourceValidator(pages, reachOk), tmp(),
            fallbackSources = defaultMuseumSources(pages),
        )
        val state = loader.load(artist) { }
        assertEquals(SourceState.CONNECTED, state.reports.single().state)
        val msgs = Diag.snapshot().filter { it.level == DiagLevel.WARN }.map { it.message }
        assertTrue(msgs.toString(), msgs.any { it.contains("recherche en échec") })
        assertTrue(msgs.toString(), msgs.any { it.contains("repli (User-Agent sobre) RÉUSSI") })
    }

    @Test fun theOfflineCopyFallbackIsLoggedAsAWarning() = runBlocking {
        val artist = vg.copy(sources = listOf(SourceSpec("aic")))
        val dir = tmp()
        val pages = FakeHttp(aicPages(ArtworkQuery.of(artist)))
        UniverseLoader(defaultMuseumSources(pages), SourceValidator(pages, reachOk), dir).load(artist) { }
        Diag.clear()
        val offline = FakeHttp(emptyMap())
        val state = UniverseLoader(defaultMuseumSources(offline), SourceValidator(offline, reachOk), dir).load(artist) { }
        assertEquals(SourceState.CACHED, state.reports.single().state)
        assertTrue(Diag.snapshot().any { it.level == DiagLevel.WARN && it.message.contains("copie hors ligne utilisée") })
    }

    private class FixedSource(val works: List<Artwork>) : MuseumSource {
        override val id = "fixed"
        override val name = "Fixe"
        override val europeanaKeyword = "fixe"
        override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec) = works
    }

    @Test fun failingSamplesAreLoggedEvenWhenTheSourceIsConnected() = runBlocking {
        val works = (1..3).map { n -> Artwork("f$n", "Titre $n", ArtworkDate.year(1880 + n), iiif = IiifRef("m:$n", imageUrl = "https://img/$n.jpg")) }
        val reach = ImageReachability { url -> if (url.endsWith("/2.jpg")) Reach(403, null) else Reach(206, "image/jpeg") }
        val http = FakeHttp(emptyMap())
        val artist = vg.copy(sources = listOf(SourceSpec("fixed")))
        val state = UniverseLoader(mapOf("fixed" to FixedSource(works)), SourceValidator(http, reach, sampleSize = 3), tmp()).load(artist) { }
        assertEquals(SourceState.CONNECTED, state.reports.single().state)      // 2 sur 3 : connectée…
        val failed = Diag.snapshot().filter { it.category == "validation" }
        assertEquals(1, failed.size)                                           // …mais l'échantillon en échec est consigné
        assertTrue(failed[0].message.contains("HTTP 403"))
        assertEquals("fixed", failed[0].sourceId)
        assertEquals(artist.id, failed[0].artistId)
    }

    @Test fun metSearchVariantsAreTriedInOrderAndEveryFailureIsLogged() = runBlocking {
        val artist = vg.copy(sources = listOf(SourceSpec("met")))
        val q = ArtworkQuery.of(artist)
        val variants = MetParser.searchVariants(q)
        assertEquals(3, variants.size)
        val metObject = """{"objectID":7,"isPublicDomain":true,"title":"Irises","artistDisplayName":"Vincent van Gogh","objectBeginDate":1890,"objectEndDate":1890,"primaryImage":"https://images.metmuseum.org/7.jpg"}"""
        // la variante 1 répond 410, la 2 aussi, la 3 réussit
        val http = FakeHttp(mapOf(variants[2] to """{"total":1,"objectIDs":[7]}""", MetParser.objectUrl(7) to metObject))
        val state = UniverseLoader(defaultMuseumSources(http), SourceValidator(http, reachOk), tmp()).load(artist) { }
        assertEquals(SourceState.CONNECTED, state.reports.single().state)
        assertEquals(listOf("Irises"), state.artworks.map { it.title })
        val msgs = Diag.snapshot().map { it.message }
        assertTrue(msgs.toString(), msgs.any { it.contains("variante 1/3 en échec") })
        assertTrue(msgs.toString(), msgs.any { it.contains("variante 2/3 en échec") })
        assertTrue(msgs.toString(), msgs.any { it.contains("variante 3/3 utilisée") })
    }

    @Test fun whenEveryMetVariantFailsTheSourceIsUnreachableWithAllFailuresLogged() = runBlocking {
        val artist = vg.copy(sources = listOf(SourceSpec("met")))
        val http = FakeHttp(emptyMap())
        val state = UniverseLoader(defaultMuseumSources(http), SourceValidator(http, reachOk), tmp()).load(artist) { }
        assertEquals(SourceState.UNREACHABLE, state.reports.single().state)
        assertEquals(3, Diag.snapshot().count { it.message.contains("variante") && it.level == DiagLevel.WARN })
        assertTrue(Diag.snapshot().any { it.level == DiagLevel.ERROR && it.message.contains("injoignable") })
    }
}
