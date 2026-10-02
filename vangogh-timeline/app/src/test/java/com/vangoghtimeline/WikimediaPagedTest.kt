package com.vangoghtimeline

import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.iiif.GettyProbe
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.MfaProbe
import com.vangoghtimeline.iiif.VanGoghMuseumProbe
import com.vangoghtimeline.iiif.WikimediaParser
import com.vangoghtimeline.iiif.WikimediaSource
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.RightsCatalog
import com.vangoghtimeline.model.RightsKind
import com.vangoghtimeline.model.SourceSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URLDecoder

class WikimediaPagedTest {
    @Before fun reset() { Diag.clear() }

    private val q = ArtworkQuery("Claude Monet", "Monet", 1850..1930, 1885)
    private val spec = SourceSpec("wikimedia", "Claude_Monet")
    private fun tmp(): File = kotlin.io.path.createTempDirectory("wikip").toFile()

    private fun row(n: Int, year: Int = 1890) =
        """{"item":{"value":"http://www.wikidata.org/entity/Q$n"},"itemLabel":{"value":"Oeuvre $n"},"inception":{"value":"$year-01-01T00:00:00Z"},
            "image":{"value":"http://commons.wikimedia.org/wiki/Special:FilePath/Fichier%20$n.jpg"},"collectionLabel":{"value":"Musée d'Orsay"}}"""
    private fun sparqlPage(rows: List<String>) = """{"results":{"bindings":[${rows.joinToString(",")}]}}"""

    /** Wikipédia → Q, deux pages SPARQL (300 + 20 œuvres, dont un doublon de la première page), lots de licences. */
    private class FakeWiki(val clock: LongArray = LongArray(1), val tickPerBatchMs: Long = 0, val pages: Map<Int, String>) : ManifestSource {
        val asked = ArrayList<String>()
        var batches = 0
        override suspend fun fetch(url: String): String {
            asked += url
            return when {
                url.contains("wikibase_item") -> """{"query":{"pages":{"4":{"pageprops":{"wikibase_item":"Q296"}}}}}"""
                url.startsWith("https://query.wikidata.org/sparql") -> {
                    val offset = Regex("OFFSET (\\d+)").find(URLDecoder.decode(url, "UTF-8"))!!.groupValues[1].toInt()
                    pages[offset] ?: throw IOException("HTTP 404 sur $url")
                }
                url.contains("prop=imageinfo") -> {
                    batches++
                    clock[0] += tickPerBatchMs * 1_000_000
                    val titles = URLDecoder.decode(url.substringAfter("&titles="), "UTF-8").split("|")
                    val body = titles.mapIndexed { i, t ->
                        """"$i":{"title":"$t","imageinfo":[{"width":4000,"height":3000,"extmetadata":{"LicenseShortName":{"value":"PD-Art"},"LicenseUrl":{"value":"https://creativecommons.org/publicdomain/mark/1.0/"}}}]}"""
                    }.joinToString(",")
                    """{"query":{"pages":{$body}}}"""
                }
                else -> throw IOException("HTTP 404 sur $url")
            }
        }
    }

    private fun twoPages(): Map<Int, String> = mapOf(
        0 to sparqlPage((1..300).map { row(it) }),
        300 to sparqlPage((301..319).map { row(it) } + row(1)),         // 20 lignes dont un doublon d'une œuvre de la page 1
    )

    @Test fun theSparqlQueryCarriesAnOffsetAndAStableOrder() {
        val q0 = WikimediaParser.sparql("Q296")
        assertTrue(q0.contains("ORDER BY ?item LIMIT 300 OFFSET 0"))
        assertTrue(WikimediaParser.sparql("Q296", 600).endsWith("ORDER BY ?item LIMIT 300 OFFSET 600"))
        assertEquals(WikimediaParser.sparqlUrl("Q296"), WikimediaParser.sparqlUrl("Q296", 0))
    }

    @Test fun allPagesAreFollowedAndDuplicatesAcrossPagesAreMerged() = runBlocking {
        val http = FakeWiki(pages = twoPages())
        val src = WikimediaSource(http)
        val arts = src.fetch(q, spec)
        assertEquals(319, arts.size)                                                   // 300 + 19 nouvelles (le doublon de la page 2 est écarté)
        val offsets = http.asked.filter { it.startsWith("https://query.wikidata.org") }.map { Regex("OFFSET (\\d+)").find(URLDecoder.decode(it, "UTF-8"))!!.groupValues[1].toInt() }
        assertEquals(listOf(0, 300), offsets)
        assertEquals(11, http.batches)                                                  // 319 fichiers / lots de 30
        assertTrue(src.isComplete(q))
        assertTrue(arts.all { it.rights!!.kind == RightsKind.PUBLIC_DOMAIN })
    }

    @Test fun aSlowPassStopsAtItsDeadlineAndTheNextOneResumesFromTheDiskCache() = runBlocking {
        val clock = LongArray(1)
        val http = FakeWiki(clock = clock, tickPerBatchMs = 10_000, pages = twoPages())      // chaque lot « dure » 10 s ; délai de 25 s
        val dir = tmp()
        val src = WikimediaSource(http, cacheDir = dir, softDeadlineMs = 25_000, clockNanos = { clock[0] })
        val first = src.fetch(q, spec)
        assertEquals(319, first.size)                                                   // tout est affiché, licences « non lues » pour les lots en attente
        assertFalse(src.isComplete(q))
        assertTrue(first.any { it.rights!!.kind == RightsKind.UNKNOWN } && first.any { it.rights!!.kind == RightsKind.PUBLIC_DOMAIN })
        val afterFirst = http.batches
        assertEquals(3, afterFirst)                                                     // 3 lots avant le délai (0, 10, 20 s)

        http.asked.clear()
        val second = src.fetch(q, spec)
        assertTrue("les pages SPARQL viennent du disque", http.asked.none { it.startsWith("https://query.wikidata.org") })
        assertEquals(319, second.size)
        assertEquals(3 + 3, http.batches)                                               // 3 lots de plus (délai encore atteint)
        // jusqu'à ce que tout soit lu : chaque lot n'est demandé qu'une fois
        var rounds = 0
        while (!src.isComplete(q) && rounds++ < 10) src.fetch(q, spec)
        assertTrue(src.isComplete(q))
        assertEquals(11, http.batches)
        assertTrue(src.fetch(q, spec).all { it.rights!!.kind == RightsKind.PUBLIC_DOMAIN })
    }

    @Test fun worksOutsideThePlausibleDatesGetNoLicenseLookup() = runBlocking {
        val http = FakeWiki(pages = mapOf(0 to sparqlPage(listOf(row(1), row(2, year = 1500), row(3)))))
        val arts = WikimediaSource(http).fetch(q, spec)
        assertEquals(listOf("Oeuvre 1", "Oeuvre 3"), arts.map { it.title })
        val titles = http.asked.single { it.contains("prop=imageinfo") }
        assertFalse(URLDecoder.decode(titles, "UTF-8").contains("Fichier 2.jpg"))
    }

    @Test fun theInfoCacheRoundTripsIncludingMissingFiles() {
        val info = WikimediaParser.FileInfo(4000, 3000, RightsCatalog.fromCommons("CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/", "Auteur"))
        val back = WikimediaParser.decodeInfoCache(WikimediaParser.encodeInfoCache(mapOf("a.jpg" to info, "b.jpg" to null)))
        assertEquals(4000, back.getValue("a.jpg")!!.width)
        assertEquals(info.rights.kind, back.getValue("a.jpg")!!.rights.kind)
        assertTrue(back.containsKey("b.jpg") && back["b.jpg"] == null)
    }

    @Test fun theInternationalProbesLogTheShapeOfWhatTheyFindAndAddNothing() = runBlocking {
        val http = object : ManifestSource {
            override suspend fun fetch(url: String): String =
                if (url.startsWith("https://data.getty.edu/museum/collection/sparql")) """{"head":{"vars":["s"]},"results":{"bindings":[]}}"""
                else if (url == "https://collections.mfa.org/") "<html><head><title>MFA Collections</title></head><form action=\"/search\"><input name=\"q\"></form> open access IIIF</html>"
                else throw IOException("HTTP 403 sur $url")
        }
        val vg = ArtworkQuery("Vincent van Gogh", "gogh", 1863..1891)
        assertTrue(GettyProbe(http).fetch(vg, SourceSpec("getty")).isEmpty())
        assertTrue(MfaProbe(http).fetch(vg, SourceSpec("mfa")).isEmpty())
        assertTrue(VanGoghMuseumProbe(http).fetch(vg, SourceSpec("vgm")).isEmpty())
        val log = Diag.snapshot().filter { it.category == "reconnaissance" }
        assertTrue(log.toString(), log.any { it.sourceId == "getty" && it.message.contains("JSON") && it.message.contains("head") })
        assertTrue(log.toString(), log.any { it.sourceId == "mfa" && it.message.contains("titre « MFA Collections »") && it.message.contains("IIIF") })
        assertTrue(log.any { it.sourceId == "vgm" && it.message.startsWith("inaccessible") })
        assertTrue(log.any { it.url!!.contains("q=Vincent%20van%20Gogh") })              // l'artiste est dans l'adresse cherchée
    }
}
