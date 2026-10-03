package com.vangoghtimeline

import com.vangoghtimeline.iiif.ArtworkJson
import com.vangoghtimeline.iiif.ArticParser
import com.vangoghtimeline.iiif.ImageReachability
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.MetParser
import com.vangoghtimeline.iiif.MetSource
import com.vangoghtimeline.iiif.NgaParser
import com.vangoghtimeline.iiif.Reach
import com.vangoghtimeline.iiif.SourceState
import com.vangoghtimeline.iiif.SourceValidator
import com.vangoghtimeline.iiif.StoreDecision
import com.vangoghtimeline.iiif.StorePolicy
import com.vangoghtimeline.iiif.StoredSource
import com.vangoghtimeline.iiif.UniverseLoader
import com.vangoghtimeline.model.ArtistCatalog
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.SourceSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class MetProgressiveTest {
    private val catalog by lazy { ArtistCatalog.parse(File("src/main/assets/artists_by_movement.json").readText()) }
    private val sargentArtist by lazy { catalog.first { it.id == "john-singer-sargent" } }
    private val sargent by lazy { ArtworkQuery.of(sargentArtist) }
    private fun tmp(): File = kotlin.io.path.createTempDirectory("metp").toFile()
    private val reachOk = ImageReachability { Reach(206, "image/jpeg") }

    private fun notice(id: Int) = """{"objectID":$id,"isPublicDomain":true,"title":"T$id","artistDisplayName":"John Singer Sargent","objectBeginDate":1890,"objectEndDate":1890,
        "primaryImage":"https://i/$id.jpg","medium":"Oil on canvas","dimensions":"10 x 20 cm","creditLine":"Gift of X","accessionNumber":"A.$id","department":"Drawings","objectURL":"https://www.metmuseum.org/art/collection/search/$id"}"""

    private class MetHttp(val total: Int, val notices: (Int) -> String) : ManifestSource {
        val asked = ArrayList<String>()
        override suspend fun fetch(url: String): String {
            asked += url
            if (url.contains("/v1.1/search?hasImages")) {
                val offset = Regex("offset=(\\d+)").find(url)!!.groupValues[1].toInt()
                val ids = (offset + 1..minOf(offset + 100, total)).toList()
                return """{"total":$total,"objectIDs":${ids.joinToString(prefix = "[", postfix = "]")}}"""
            }
            val id = Regex("/objects/(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: throw IOException("HTTP 404 sur $url")
            return notices(id)
        }
    }

    @Test fun theMetReadsNoticesInSlicesAndResumesFromTheDiskCache() = runBlocking {
        val http = MetHttp(5) { notice(it) }
        val dir = File(tmp(), "n")
        val src = MetSource(http, maxRequests = 3, noticeCache = dir)
        val first = src.fetch(sargent, SourceSpec("met"))
        assertEquals(3, first.size)
        assertFalse(src.isComplete(sargent))                                        // 2 notices restent à lire
        val second = src.fetch(sargent, SourceSpec("met"))                          // 3 viennent du disque, 2 sont lues
        assertEquals(5, second.size)
        assertTrue(src.isComplete(sargent))
        assertEquals(5, http.asked.count { it.contains("/objects/") })              // chaque notice demandée UNE seule fois en tout
    }

    @Test fun theSearchFollowsSeveralPagesUpToTheLimit() = runBlocking {
        val http = MetHttp(250) { notice(it) }
        MetSource(http, maxObjects = 250, maxRequests = 0, noticeCache = null).fetch(sargent, SourceSpec("met"))
        val offsets = http.asked.filter { it.contains("/v1.1/search?hasImages") }.map { Regex("offset=(\\d+)").find(it)!!.groupValues[1].toInt() }
        assertEquals(listOf(0, 100, 200), offsets)
    }

    @Test fun aPartialSourceIsConnectedThenContinuedAutomaticallyUntilComplete() = runBlocking {
        val http = MetHttp(5) { notice(it) }
        val artist = sargentArtist.copy(sources = listOf(SourceSpec("met")))
        val states = ArrayList<SourceState>()
        val state = UniverseLoader(mapOf("met" to MetSource(http, maxRequests = 3, noticeCache = File(tmp(), "n"))), SourceValidator(http, reachOk), tmp(), limitedRetryDelayMs = 1)
            .load(artist) { states += it.reports.single().state }
        assertTrue(states.toString(), SourceState.PARTIAL in states)
        assertEquals(SourceState.CONNECTED, state.reports.single().state)           // la reprise automatique a lu le reste
        assertEquals(5, state.artworks.size)
    }

    @Test fun aPartialSourceIsRetriedAfterFiveMinutesNotAWeek() {
        val s = StoredSource("Met", SourceState.PARTIAL, "partielle", 3, 1_000, 1_000, emptyList(), emptyList())
        assertEquals(StoreDecision.FRESH, StorePolicy.decide(s, 1_000 + 60_000))
        assertEquals(StoreDecision.REFRESH, StorePolicy.decide(s, 1_000 + StorePolicy.PARTIAL_RETRY_MS + 1))
    }

    @Test fun theMetNoticeCarriesItsDetailsAndPage() {
        val a = MetParser.parseObject(notice(7), sargent)!!
        assertEquals(listOf("Technique", "Dimensions", "Type", "Département", "Crédit", "N° d'inventaire").filter { it != "Type" }, a.details.map { it.first }.filter { it != "Date" })
        assertEquals("10 x 20 cm", a.details.first { it.first == "Dimensions" }.second)
        assertEquals("https://www.metmuseum.org/art/collection/search/7", a.pageUrl)
    }

    @Test fun detailsAndPageSurviveTheLocalStore() {
        val a = MetParser.parseObject(notice(7), sargent)!!
        val back = ArtworkJson.decode(ArtworkJson.encode(listOf(a))).single()
        assertEquals(a.details, back.details)
        assertEquals(a.pageUrl, back.pageUrl)
    }

    @Test fun aicAndNgaAlsoGiveDetails() {
        val q = ArtworkQuery("Vincent van Gogh", "gogh", 1870..1890, 1885)
        val aic = ArticParser.parse("""{"data":[{"id":28560,"title":"The Bedroom","artist_title":"Vincent van Gogh","date_start":1889,"image_id":"a","medium_display":"Oil on canvas",
            "dimensions":"73.6 x 92.3 cm","credit_line":"Helen Birch Bartlett Memorial Collection","main_reference_number":"1926.417","classification_title":"painting"}],"config":{"iiif_url":"https://www.artic.edu/iiif/2"}}""", q).single()
        assertEquals("73.6 x 92.3 cm", aic.details.first { it.first == "Dimensions" }.second)
        assertEquals("1926.417", aic.details.first { it.first == "N° d'inventaire" }.second)
        assertEquals("https://www.artic.edu/artworks/28560", aic.pageUrl)
        val nga = File("src/main/assets/nga/vincent-van-gogh.json").readText()
        val first = NgaParser.parse(nga, q).first()
        assertTrue(first.details.any { it.first == "N° d'inventaire" } && first.details.any { it.first == "Dimensions" })
        assertTrue(first.pageUrl!!.startsWith("https://www.nga.gov/collection/art-object-page."))
    }
}
