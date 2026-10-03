package com.vangoghtimeline

import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.iiif.ImageReachability
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.MetParser
import com.vangoghtimeline.iiif.MetSource
import com.vangoghtimeline.iiif.MuseumSource
import com.vangoghtimeline.iiif.RateLimiter
import com.vangoghtimeline.iiif.Reach
import com.vangoghtimeline.iiif.SourceState
import com.vangoghtimeline.iiif.SourceValidator
import com.vangoghtimeline.iiif.UniverseLoader
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.ArtistCatalog
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

class MetPacingTest {
    @Before fun reset() { Diag.clear() }
    private val catalog by lazy { ArtistCatalog.parse(File("src/main/assets/artists_by_movement.json").readText()) }
    private val artist by lazy { catalog.first { it.id == "john-singer-sargent" } }
    private val q by lazy { ArtworkQuery.of(artist) }
    private fun tmp(): File = kotlin.io.path.createTempDirectory("pace").toFile()
    private val reachOk = ImageReachability { Reach(206, "image/jpeg") }

    @Test fun everyFirewallBlockSlowsTheRequestsDownAndSuccessesSpeedThemUpAgain() = runBlocking {
        val waits = ArrayList<Long>()
        val limiter = RateLimiter(1, 100, sleep = { waits += it }, now = { 0L })
        assertEquals(100L, limiter.currentGapMs())
        limiter.markBlocked(); assertEquals(200L, limiter.currentGapMs())
        limiter.markBlocked(); assertEquals(400L, limiter.currentGapMs())
        repeat(5) { limiter.markBlocked() }; assertEquals(2_000L, limiter.currentGapMs())          // plafond
        repeat(30) { limiter.markOk() }; assertEquals(1_000L, limiter.currentGapMs())                  // 30 réussites : on remonte d'un cran
        repeat(300) { limiter.markOk() }; assertEquals(100L, limiter.currentGapMs())                   // jamais en dessous de l'écart de base
        limiter.markBlocked()
        repeat(3) { limiter.run { } }                                                                  // trois départs au même instant : 0, 200, 400 ms d'attente
        assertEquals(listOf(200L, 400L), waits)
    }

    private fun notice(id: Int) = """{"objectID":$id,"isPublicDomain":true,"title":"T$id","artistDisplayName":"John Singer Sargent","objectBeginDate":1890,"objectEndDate":1890,"primaryImage":"https://i/$id.jpg"}"""

    private class BlockingHttp(val search: String, val okIds: Set<Int>, val notice: (Int) -> String) : ManifestSource {
        val noticeCalls = ArrayList<Int>()
        override suspend fun fetch(url: String): String {
            if (url.contains("/v1.1/search?hasImages")) return search
            val id = Regex("/objects/(\\d+)").find(url)!!.groupValues[1].toInt()
            noticeCalls += id
            if (id !in okIds) throw IOException("HTTP 403 sur $url — type : text/html · corps : « Incapsula »")
            return notice(id)
        }
    }

    @Test fun aBlockMidWayKeepsWhatWasReadAndMarksTheSourcePartial() = runBlocking {
        val http = BlockingHttp("""{"total":5,"objectIDs":[1,2,3,4,5]}""", setOf(1, 2)) { notice(it) }
        val src = MetSource(http, parallelism = 1)
        val arts = src.fetch(q, SourceSpec("met"))
        assertEquals(listOf("met-1", "met-2"), arts.map { it.id })                       // les deux premières notices sont gardées
        assertFalse(src.isComplete(q))
        assertEquals(listOf(1, 2, 3), http.noticeCalls)                                  // la 3e bloque : les suivantes ne sont PAS demandées
    }

    @Test fun theLoaderShowsThePartialMetThenCompletesItOnTheNextPass() = runBlocking {
        val http = BlockingHttp("""{"total":4,"objectIDs":[1,2,3,4]}""", setOf(1, 2)) { notice(it) }
        val dir = File(tmp(), "n")
        val src = MetSource(http, parallelism = 1, noticeCache = dir)
        val states = ArrayList<SourceState>()
        val loader = UniverseLoader(mapOf("met" to src), SourceValidator(http, reachOk), tmp(), limitedRetryDelayMs = -1)
        val first = loader.load(artist.copy(sources = listOf(SourceSpec("met")))) { states += it.reports.single().state }
        assertEquals(SourceState.PARTIAL, first.reports.single().state)
        assertEquals(2, first.artworks.size)                                             // visibles tout de suite, pas « bloquée sans rien »
    }

    // ── validation : œuvre à image introuvable ───────────────────────────────────
    private class Fixed(val works: List<Artwork>) : MuseumSource {
        override val id = "fixed"; override val name = "Fixe"; override val europeanaKeyword = "fixe"
        override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec) = works
    }

    @Test fun aWorkWhoseImageIsDefinitivelyMissingIsRemovedButTheSourceStaysConnected() = runBlocking {
        val works = (1..2).map { n -> Artwork("f$n", "Titre $n", ArtworkDate.year(1880 + n), iiif = IiifRef("m:$n", imageUrl = "https://img/$n.jpg")) }
        val reach = ImageReachability { url -> if (url.endsWith("/2.jpg")) Reach(404, null) else Reach(206, "image/jpeg") }
        val http = object : ManifestSource { override suspend fun fetch(url: String) = "" }
        val state = UniverseLoader(mapOf("fixed" to Fixed(works)), SourceValidator(http, reach, sleep = { }), tmp(), limitedRetryDelayMs = -1)
            .load(artist.copy(sources = listOf(SourceSpec("fixed")))) { }
        assertEquals(SourceState.CONNECTED, state.reports.single().state)
        assertEquals(listOf("f1"), state.artworks.map { it.id })                         // l'œuvre à image 404 n'entre pas dans la frise
        assertEquals(1, state.reports.single().count)
        assertTrue(Diag.snapshot().any { it.message.contains("image introuvable retirée") })
    }

    @Test fun aNetworkFailureOnAnImageNeverRemovesTheWork() = runBlocking {
        val works = (1..3).map { n -> Artwork("f$n", "Titre $n", ArtworkDate.year(1880 + n), iiif = IiifRef("m:$n", imageUrl = "https://img/$n.jpg")) }
        val reach = ImageReachability { url -> if (url.endsWith("/3.jpg")) Reach(429, null) else Reach(206, "image/jpeg") }
        val http = object : ManifestSource { override suspend fun fetch(url: String) = "" }
        val state = UniverseLoader(mapOf("fixed" to Fixed(works)), SourceValidator(http, reach, sleep = { }), tmp(), limitedRetryDelayMs = -1)
            .load(artist.copy(sources = listOf(SourceSpec("fixed")))) { }
        assertEquals(3, state.artworks.size)                                             // un 429 ne prouve rien contre l'image
    }
}
