package com.vangoghtimeline

import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.iiif.DiagLevel
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.NetworkTolerance
import com.vangoghtimeline.iiif.Reach
import com.vangoghtimeline.iiif.SmkParser
import com.vangoghtimeline.iiif.SmkSource
import com.vangoghtimeline.iiif.SourceValidator
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

class NetworkToleranceTest {
    @Before fun reset() { Diag.clear() }

    private class FakeHttp(val pages: Map<String, String>) : ManifestSource {
        override suspend fun fetch(url: String): String = pages[url] ?: throw java.io.IOException("HTTP 404 sur $url")
    }

    private fun art(n: Int) = Artwork("a$n", "Titre $n", ArtworkDate.year(1880 + n), iiif = IiifRef("m:$n", imageUrl = "https://x/$n.jpg"))
    private val six = (1..6).map { art(it) }

    @Test fun networkFailuresAreRecognised() {
        assertTrue(NetworkTolerance.isTransient("HTTP 429 sur https://x"))
        assertTrue(NetworkTolerance.isTransient("Unable to resolve host \"api.europeana.eu\""))
        assertTrue(NetworkTolerance.isTransient("délai dépassé (45 s)"))
        assertFalse(NetworkTolerance.isTransient("HTTP 404 sur https://x"))
        assertFalse(NetworkTolerance.isTransient("type « text/html » au lieu d'une image"))
    }

    @Test fun aRateLimitedProbeIsRetriedOnceAndThenDoesNotCountAgainstTheSource() = runBlocking {
        val calls = HashMap<String, Int>()
        val v = SourceValidator(FakeHttp(emptyMap()), { url -> calls[url] = (calls[url] ?: 0) + 1; if (url.endsWith("/3.jpg") || url.endsWith("/6.jpg")) Reach(429, null) else Reach(206, "image/jpeg") }, sleep = { })
        val verdict = v.decide(six)                         // échantillon : 1, 3, 6 ; 3 et 6 limités (429)
        assertTrue(verdict.ok)                              // un seul échantillon concluant, et il passe
        assertFalse(verdict.inconclusive)
        assertEquals(2, calls["https://x/3.jpg"])           // un nouvel essai
        assertTrue(verdict.lines.count { it.transient } == 2)
    }

    @Test fun aSourceWhoseSamplesAllFailOnTheNetworkIsInconclusiveNotRejected() = runBlocking {
        val v = SourceValidator(FakeHttp(emptyMap()), { Reach(429, null) }, sleep = { })
        val verdict = v.decide(six)
        assertFalse(verdict.ok)
        assertTrue(verdict.inconclusive)
    }

    @Test fun aRealRefusalStillRejects() = runBlocking {
        val v = SourceValidator(FakeHttp(emptyMap()), { Reach(404, null) }, sleep = { })
        val verdict = v.decide(six)
        assertFalse(verdict.ok)
        assertFalse(verdict.inconclusive)
    }

    @Test fun cancellationNoiseIsNotJournalled() {
        Diag.warn("tuile", "Canceled")
        Diag.warn("tuile", "Socket closed")
        Diag.warn("tuile", "StandaloneCoroutine was cancelled")
        Diag.warn("tuile", "HTTP 404 sur https://x")
        Diag.warn("préchauffage", "essai 1 : Canceled")                 // messages réels : préfixés par « essai N : »
        Diag.warn("tuile", "essai 1 (abandon) : Socket is closed")
        Diag.warn("tuile", "essai 1 : Canceled by user")                // une vraie cause qui contient le mot : gardée
        Diag.error("tuile", "Canceled")                      // une erreur n'est jamais écartée
        assertEquals(listOf(DiagLevel.WARN, DiagLevel.WARN, DiagLevel.ERROR), Diag.snapshot().map { it.level })
        assertEquals(5, Diag.ignoredCancellations)
    }

    @Test fun smkTriesNameVariantsUntilOneGivesWorks() = runBlocking {
        val q = ArtworkQuery("Pierre-Auguste Renoir", "Renoir", 1851..1920)
        val keys = SmkParser.searchKeys(q)
        assertEquals(listOf("Pierre-Auguste Renoir", "Renoir"), keys)
        val item = """{"items":[{"object_number":"KMS1","artist":["Auguste Renoir"],"titles":[{"title":"Portrait"}],"image_iiif_id":"https://iip.smk.dk/iiif/jp2/KMS1.tif.jp2",
            "production_date":[{"start":"1880-01-01","end":"1880-12-31"}],"image_width":100,"image_height":100,"public_domain":true}]}"""
        val http = FakeHttp(mapOf(SmkParser.searchUrl(q, keys[0]) to """{"items":[],"found":0}""", SmkParser.searchUrl(q, keys[1]) to item))
        val arts = SmkSource(http).fetch(q, SourceSpec("smk"))
        assertEquals(listOf("Portrait"), arts.map { it.title })
        assertTrue(Diag.snapshot().any { it.message.contains("variante 2/2") })
    }
}
