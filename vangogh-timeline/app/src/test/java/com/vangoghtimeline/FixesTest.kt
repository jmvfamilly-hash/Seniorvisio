package com.vangoghtimeline

import com.vangoghtimeline.iiif.ClevelandParser
import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.iiif.DiagLevel
import com.vangoghtimeline.iiif.EuropeanaParser
import com.vangoghtimeline.iiif.ImageReachability
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.MetParser
import com.vangoghtimeline.iiif.MetSource
import com.vangoghtimeline.iiif.MuseumSource
import com.vangoghtimeline.iiif.RateLimiter
import com.vangoghtimeline.iiif.Reach
import com.vangoghtimeline.iiif.RetryingSource
import com.vangoghtimeline.iiif.SourceState
import com.vangoghtimeline.iiif.SourceValidator
import com.vangoghtimeline.iiif.Tally
import com.vangoghtimeline.iiif.TemporaryBlock
import com.vangoghtimeline.iiif.UniverseLoader
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

class FixesTest {
    @Before fun reset() { Diag.clear(); Diag.context = null }

    private val catalog: List<Artist> by lazy { ArtistCatalog.parse(File("src/main/assets/artists_by_movement.json").readText()) }
    private fun q(id: String) = ArtworkQuery.of(catalog.first { it.id == id })

    // ── C. créateur : nom de famille ET prénom/initiale ───────────────────────────
    @Test fun creatorMatchingRejectsNamesakes() {
        val sargent = q("john-singer-sargent")
        for (ok in listOf("Sargent, John Singer", "John Singer Sargent (American, 1856–1925)", "J. S. Sargent", "John S. Sargent")) assertTrue(ok, sargent.matchesCreator(ok))
        for (no in listOf("E. Sargent", "O.H.Sarg.", "Sargent", "Charles Sprague Sargent")) assertFalse(no, sargent.matchesCreator(no))

        val vg = q("vincent-van-gogh")
        assertTrue(vg.matchesCreator("Gogh, Vincent van")); assertTrue(vg.matchesCreator("V. van Gogh")); assertTrue(vg.matchesCreator("Vincent van Gogh"))
        assertFalse(vg.matchesCreator("Theo van Gogh")); assertFalse(vg.matchesCreator("Van Gogh"))

        val renoir = q("pierre-auguste-renoir")
        assertTrue(renoir.matchesCreator("Renoir, Pierre-Auguste")); assertTrue(renoir.matchesCreator("Auguste Renoir"))
        assertFalse(renoir.matchesCreator("Jean Renoir")); assertFalse(renoir.matchesCreator("Claude Renoir"))
    }

    @Test fun europeanaStrictModeNeedsADeclaredCreator() {
        val items = """{"items":[
          {"id":"/1/a","title":["Sans créateur"],"year":["1890"],"edmPreview":["https://t/1"]},
          {"id":"/1/b","title":["Champignons"],"dcCreator":["E. Sargent"],"year":["1892"],"edmPreview":["https://t/2"]},
          {"id":"/1/c","title":["Portrait"],"dcCreator":["Sargent, John Singer"],"year":["1890"],"edmPreview":["https://t/3"]}]}"""
        val sargent = q("john-singer-sargent")
        val lenient = Tally(); val strict = Tally()
        assertEquals(listOf("Sans créateur", "Portrait"), EuropeanaParser.parse(items, sargent, lenient).map { it.title })
        assertEquals(listOf("Portrait"), EuropeanaParser.parse(items, sargent, strict, strictCreator = true).map { it.title })
        assertTrue(strict.summary(), strict.summary().contains("1 sans créateur déclaré") && strict.summary().contains("1 d'un autre créateur"))
        // seule la recherche par nom de famille est stricte (sans accent dans le nom, la 2e variante fait doublon avec la 1re et disparaît)
        assertEquals(listOf(false, true), EuropeanaParser.variants(sargent).map { it.second })
        assertEquals(listOf(false, false, true), EuropeanaParser.variants(q("joaquin-sorolla")).map { it.second })
    }

    // ── D. raisons des rejets ─────────────────────────────────────────────────────
    @Test fun metNoticesAreCountedByReasonAndTheTallyIsLogged() {
        val sargent = q("john-singer-sargent")
        fun notice(id: Int, pd: Boolean, image: String, artist: String) =
            """{"objectID":$id,"isPublicDomain":$pd,"title":"T$id","artistDisplayName":"$artist","objectBeginDate":1890,"objectEndDate":1890,"primaryImage":"$image"}"""
        val t = Tally()
        assertEquals("met-1", MetParser.parseObject(notice(1, false, "https://i/1.jpg", "John Singer Sargent"), sargent, t)!!.id)   // hors domaine public avec image : consultation privée
        assertEquals(null, MetParser.parseObject(notice(2, true, "", "John Singer Sargent"), sargent, t))
        assertEquals(null, MetParser.parseObject(notice(3, true, "https://i/3.jpg", "Autre"), sargent, t))
        assertEquals("met-4", MetParser.parseObject(notice(4, true, "https://i/4.jpg", "John Singer Sargent"), sargent, t)!!.id)
        assertEquals("4 reçues, 2 retenues — écartées : 1 sans image, 1 d'un autre artiste", t.summary())
        t.log("met", "john-singer-sargent")
        assertTrue(Diag.snapshot().single().let { it.level == DiagLevel.INFO && it.message.contains("analyse : 4 reçues, 2 retenues") })
    }

    @Test fun anEmptyResultLogsTheShapeOfTheResponse() {
        val t = Tally()
        assertTrue(ClevelandParser.parse("""{"foo":[{"a":1,"b":2}]}""", q("vincent-van-gogh"), t).isEmpty())
        t.log("cleveland", "vincent-van-gogh")
        val e = Diag.snapshot().single()
        assertEquals(DiagLevel.WARN, e.level)
        assertTrue(e.message, e.message.contains("clés [foo]") && e.message.contains("premier élément de « foo » : [a, b]"))
    }

    // ── A. cadence et nouvel essai ────────────────────────────────────────────────
    @Test fun temporaryBlockRecognition() {
        assertTrue(TemporaryBlock.matches("HTTP 429 sur https://x"))
        assertTrue(TemporaryBlock.matches("HTTP 503 sur https://x"))
        assertTrue(TemporaryBlock.matches("HTTP 403 sur https://x — type : text/html · corps : « <html>… _Incapsula_Resource »"))
        assertFalse(TemporaryBlock.matches("HTTP 403 sur https://x — type : application/json"))
        assertFalse(TemporaryBlock.matches("HTTP 404 sur https://x"))
        assertFalse(TemporaryBlock.matches(null))
    }

    @Test fun theRateLimiterSpacesRequestStarts() = runBlocking {
        var t = 1_000L
        val waits = ArrayList<Long>()
        val limiter = RateLimiter(parallel = 4, minGapMs = 150, sleep = { waits += it }, now = { t })
        repeat(3) { limiter.run { } }                    // trois départs au même instant : 0, 150, 300 ms d'attente
        assertEquals(listOf(150L, 300L), waits)
        t += 10_000
        waits.clear()
        limiter.run { }                                  // longtemps après : plus d'attente
        assertTrue(waits.isEmpty())
    }

    private class FlakyHttp(val failures: Int, val message: String) : ManifestSource {
        var calls = 0
        override suspend fun fetch(url: String): String { calls++; if (calls <= failures) throw IOException(message); return "ok" }
    }

    @Test fun aTemporaryBlockIsRetriedWithBackoffAndEveryWaitIsLogged() = runBlocking {
        val sleeps = ArrayList<Long>()
        val inner = FlakyHttp(2, "HTTP 403 sur https://collectionapi.metmuseum.org/x — type : text/html · corps : « Incapsula »")
        val src = RetryingSource(inner, RateLimiter(2, 0, sleep = { }), "metmuseum.org", listOf(2_000, 5_000), sleep = { sleeps += it })
        assertEquals("ok", src.fetch("https://collectionapi.metmuseum.org/x"))
        assertEquals(listOf(2_000L, 5_000L), sleeps)
        assertEquals(3, inner.calls)
        assertEquals(2, Diag.snapshot().count { it.category == "réseau" && it.message.contains("blocage temporaire") })
    }

    @Test fun otherFailuresAndOtherHostsAreNotRetried() = runBlocking {
        val sleeps = ArrayList<Long>()
        val json403 = FlakyHttp(1, "HTTP 403 sur https://collectionapi.metmuseum.org/x — type : application/json")
        val a = RetryingSource(json403, RateLimiter(2, 0, sleep = { }), "metmuseum.org", sleep = { sleeps += it })
        try { a.fetch("https://collectionapi.metmuseum.org/x"); throw AssertionError("aurait dû échouer") } catch (e: IOException) { }
        assertEquals(1, json403.calls); assertTrue(sleeps.isEmpty())
        val other = FlakyHttp(0, "")
        assertEquals("ok", RetryingSource(other, RateLimiter(2, 0, sleep = { }), "metmuseum.org").fetch("https://api.artic.edu/x"))
    }

    private fun tmp(): File = kotlin.io.path.createTempDirectory("fix").toFile()
    private val reachOk = ImageReachability { Reach(206, "image/jpeg") }

    private class FailOnceSource(val works: List<Artwork>, val message: String) : MuseumSource {
        var calls = 0
        override val id = "fixed"; override val name = "Fixe"; override val europeanaKeyword = "fixe"
        override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> { calls++; if (calls == 1) throw IOException(message); return works }
    }

    @Test fun aBlockedSourceIsLimitedThenRetriedAutomatically() = runBlocking {
        val works = (1..3).map { n -> Artwork("f$n", "Titre $n", ArtworkDate.year(1880 + n), iiif = IiifRef("m:$n", imageUrl = "https://img/$n.jpg")) }
        val src = FailOnceSource(works, "HTTP 403 sur https://collectionapi.metmuseum.org/x — type : text/html · corps : « Incapsula »")
        val artist = catalog.first { it.id == "vincent-van-gogh" }.copy(sources = listOf(SourceSpec("fixed")))
        val updates = ArrayList<SourceState>()
        val state = UniverseLoader(mapOf("fixed" to src), SourceValidator(object : ManifestSource { override suspend fun fetch(url: String) = "" }, reachOk), tmp(), limitedRetryDelayMs = 1)
            .load(artist) { updates += it.reports.single().state }
        assertEquals(listOf(SourceState.LIMITED, SourceState.LIMITED, SourceState.CONNECTED), updates)   // publiée LIMITED, puis rattrapée
        assertEquals(SourceState.CONNECTED, state.reports.single().state)
        assertEquals(3, state.artworks.size)
        assertEquals(2, src.calls)
        val log = Diag.snapshot()
        assertTrue(log.any { it.level == DiagLevel.ERROR && it.message.contains("BLOQUÉE TEMPORAIREMENT") })
        assertTrue(log.any { it.message.contains("nouvel essai automatique") })
    }

    @Test fun metNoticesAreCachedOnDiskSoTheyAreNotRequestedTwice() = runBlocking {
        val sargent = q("john-singer-sargent")
        val v = MetParser.searchVariants(sargent)[0]
        val notice = """{"objectID":7,"isPublicDomain":true,"title":"Portrait","artistDisplayName":"John Singer Sargent","objectBeginDate":1890,"objectEndDate":1890,"primaryImage":"https://i/7.jpg"}"""
        val asked = ArrayList<String>()
        val http = object : ManifestSource {
            override suspend fun fetch(url: String): String { asked += url; return when (url) { v -> """{"total":1,"objectIDs":[7]}"""; MetParser.objectUrl(7) -> notice; else -> throw IOException("HTTP 403 sur $url") } }
        }
        val dir = File(tmp(), "notices")
        val first = MetSource(http, noticeCache = dir).fetch(sargent, SourceSpec("met"))
        assertEquals(listOf("met-7"), first.map { it.id })
        asked.clear()
        val second = MetSource(http, noticeCache = dir).fetch(sargent, SourceSpec("met"))
        assertEquals(listOf("met-7"), second.map { it.id })
        assertFalse(asked.contains(MetParser.objectUrl(7)))            // la notice vient du disque
        assertTrue(asked.contains(v))                                  // la recherche, elle, est refaite
    }
}
