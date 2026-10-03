package com.vangoghtimeline

import com.vangoghtimeline.iiif.ArticParser
import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.iiif.ImageReachability
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.ProbeLine
import com.vangoghtimeline.iiif.Reach
import com.vangoghtimeline.iiif.SourceState
import com.vangoghtimeline.iiif.SourceValidator
import com.vangoghtimeline.iiif.StoreDecision
import com.vangoghtimeline.iiif.StorePolicy
import com.vangoghtimeline.iiif.StoredSource
import com.vangoghtimeline.iiif.StoredUniverse
import com.vangoghtimeline.iiif.UniverseLoader
import com.vangoghtimeline.iiif.UniverseState
import com.vangoghtimeline.iiif.UniverseStore
import com.vangoghtimeline.iiif.defaultMuseumSources
import com.vangoghtimeline.model.AgeFormat
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class StoreTest {
    @Before fun reset() { Diag.clear() }

    private val day = 86_400_000L
    private val hour = 3_600_000L

    private fun stored(state: SourceState, fetchedAt: Long, attemptedAt: Long, n: Int = 1) = StoredSource(
        "AIC", state, "détail", n, fetchedAt, attemptedAt,
        List(n) { Artwork("a$it", "T$it", ArtworkDate.year(1888), iiif = IiifRef("m:$it", imageServiceId = "https://s/$it")) },
        listOf(ProbeLine("T0", "https://u", true, "ok")),
    )

    // ── Politique de fraîcheur ────────────────────────────────────────────────────
    @Test fun policyTable() {
        val now = 100 * day
        assertEquals(StoreDecision.FETCH, StorePolicy.decide(null, now))
        assertEquals(StoreDecision.FRESH, StorePolicy.decide(stored(SourceState.CONNECTED, now - 6 * day, now - 6 * day), now))
        assertEquals(StoreDecision.REFRESH, StorePolicy.decide(stored(SourceState.CONNECTED, now - 8 * day, now - 8 * day), now))
        assertEquals(StoreDecision.REFRESH, StorePolicy.decide(stored(SourceState.CONNECTED, now + day, now + day), now))      // horloge reculée
        // une copie hors ligne est retentée au bout d'une heure
        assertEquals(StoreDecision.FRESH, StorePolicy.decide(stored(SourceState.CACHED, now - 30 * day, now - 10 * 60_000), now))
        assertEquals(StoreDecision.REFRESH, StorePolicy.decide(stored(SourceState.CACHED, now - 30 * day, now - 2 * hour), now))
        // un échec aussi : pas avant une heure, puis on cherche à nouveau
        for (st in listOf(SourceState.REJECTED, SourceState.UNREACHABLE, SourceState.EMPTY, SourceState.UNAVAILABLE)) {
            assertEquals(st.name, StoreDecision.FRESH, StorePolicy.decide(stored(st, 0, now - 59 * 60_000, 0), now))
            assertEquals(st.name, StoreDecision.FETCH, StorePolicy.decide(stored(st, 0, now - 61 * 60_000, 0), now))
        }
        // un blocage temporaire (pare-feu du Met) est retenté au bout de 5 minutes, pas d'une heure
        assertEquals(StoreDecision.FRESH, StorePolicy.decide(stored(SourceState.LIMITED, 0, now - 4 * 60_000, 0), now))
        assertEquals(StoreDecision.FETCH, StorePolicy.decide(stored(SourceState.LIMITED, 0, now - 6 * 60_000, 0), now))
        // « Actualiser » : une source utilisable est rafraîchie (et reste affichée), une autre est cherchée
        assertEquals(StoreDecision.REFRESH, StorePolicy.decide(stored(SourceState.CONNECTED, now, now), now, force = true))
        assertEquals(StoreDecision.FETCH, StorePolicy.decide(stored(SourceState.EMPTY, 0, now, 0), now, force = true))
    }

    // ── Fichier ───────────────────────────────────────────────────────────────────
    private fun tmp(): File = kotlin.io.path.createTempDirectory("store").toFile()

    @Test fun storeRoundTripsAndRejectsOtherVersionsAndGarbage() {
        val dir = tmp()
        val u = StoredUniverse(UniverseStore.PARSER_VERSION, mapOf("aic" to stored(SourceState.CONNECTED, 5, 6, 3)))
        UniverseStore.write(dir, "vincent-van-gogh", u)
        val back = UniverseStore.read(dir, "vincent-van-gogh")!!
        val s = back.sources.getValue("aic")
        assertEquals(SourceState.CONNECTED, s.state); assertEquals(5L, s.fetchedAt); assertEquals(6L, s.attemptedAt); assertEquals(3, s.artworks.size)
        assertEquals("T2", s.artworks[2].title); assertEquals("ok", s.probes.single().detail); assertTrue(s.probes.single().ok)

        UniverseStore.write(dir, "ancien", StoredUniverse(UniverseStore.PARSER_VERSION - 1, u.sources))
        assertNull(UniverseStore.read(dir, "ancien"))                         // autre version des règles : ignoré
        UniverseStore.fileOf(dir, "casse").writeText("pas du json {")
        assertNull(UniverseStore.read(dir, "casse"))                          // fichier illisible : ignoré
        assertNull(UniverseStore.read(dir, "absent"))
        assertFalse(File(dir, "vincent-van-gogh.json.tmp").exists())          // écriture atomique : pas de reste
    }

    @Test fun ageIsFormattedInFrench() {
        assertEquals("à l'instant", AgeFormat.fr(10_000))
        assertEquals("il y a 5 min", AgeFormat.fr(5 * 60_000L))
        assertEquals("il y a 3 h", AgeFormat.fr(3 * hour))
        assertEquals("il y a 3 jours", AgeFormat.fr(3 * day))
    }

    // ── Chargeur avec magasin ─────────────────────────────────────────────────────
    private class CountingHttp(val pages: Map<String, String>) : ManifestSource {
        val asked = ArrayList<String>()
        var offline = false
        override suspend fun fetch(url: String): String { asked += url; if (offline) throw IOException("hors ligne"); return pages[url] ?: throw IOException("HTTP 403 sur $url") }
    }

    private val reachOk = ImageReachability { Reach(206, "image/jpeg") }
    private val aicJson = """{"data":[{"id":28560,"title":"The Bedroom","artist_title":"Vincent van Gogh","date_start":1889,"image_id":"abc","thumbnail":{"width":4,"height":3}}],"config":{"iiif_url":"https://www.artic.edu/iiif/2"}}"""
    private val artist: Artist by lazy {
        ArtistCatalog.parse(File("src/main/assets/artists_by_movement.json").readText()).first { it.id == "vincent-van-gogh" }.copy(sources = listOf(SourceSpec("aic")))
    }
    private fun pages() = mapOf(
        ArticParser.searchUrl(ArtworkQuery.of(artist)) to aicJson,
        "https://api.artic.edu/api/v1/artworks/28560/manifest.json" to
            """{"@type":"sc:Manifest","sequences":[{"canvases":[{"images":[{"resource":{"@id":"https://www.artic.edu/iiif/2/abc/full/843,/0/default.jpg","service":{"@id":"https://www.artic.edu/iiif/2/abc"}}}]}]}]}""",
        "https://www.artic.edu/iiif/2/abc/info.json" to """{"@id":"https://www.artic.edu/iiif/2/abc","width":4032,"height":3200}""",
    )

    private fun loader(http: CountingHttp, dir: File, clock: () -> Long) =
        UniverseLoader(defaultMuseumSources(http), SourceValidator(http, reachOk), dir, clock = clock)

    @Test fun aFreshStoreOpensWithoutAnyNetworkRequest() = runBlocking {
        val dir = tmp(); var t = 1_000 * day
        val http = CountingHttp(pages())
        loader(http, dir) { t }.load(artist) { }
        assertTrue(http.asked.isNotEmpty())
        http.asked.clear()
        t += 3 * day                                                          // 3 jours : encore frais
        val updates = ArrayList<UniverseState>()
        val state = loader(http, dir) { t }.load(artist) { updates += it }
        assertTrue("aucune requête attendue : ${http.asked}", http.asked.isEmpty())
        assertTrue(state.done && state.artworks.map { it.title } == listOf("The Bedroom"))
        assertEquals(1, updates.size)                                         // publié une seule fois, tout de suite
        assertTrue(updates[0].done && updates[0].artworks.size == 1)
        assertEquals(1_000 * day, state.updatedAtMs)                          // âge des données : date de la première obtention
        assertTrue(Diag.snapshot().any { it.category == "magasin" && it.message.contains("sources fraîches (sans réseau) : aic") })
    }

    @Test fun aStaleStoreShowsItsDataFirstThenRefreshesInTheBackground() = runBlocking {
        val dir = tmp(); var t = 1_000 * day
        val http = CountingHttp(pages())
        loader(http, dir) { t }.load(artist) { }
        http.asked.clear()
        t += 8 * day                                                          // périmé
        val updates = ArrayList<UniverseState>()
        val state = loader(http, dir) { t }.load(artist) { updates += it }
        assertTrue(updates.first().artworks.size == 1 && !updates.first().done)        // 1re publication : l'enregistré, « pas fini »
        assertTrue(http.asked.isNotEmpty())                                             // puis la mise à jour réseau
        assertTrue(updates.last().done)
        assertEquals(t, state.updatedAtMs)                                              // données rajeunies
    }

    @Test fun aStaleStoreWhenOfflineKeepsTheOldDataAsACopyWithItsOldDate() = runBlocking {
        val dir = tmp(); var t = 1_000 * day
        val http = CountingHttp(pages())
        loader(http, dir) { t }.load(artist) { }
        http.offline = true; t += 9 * day
        val state = loader(http, dir) { t }.load(artist) { }
        assertEquals(SourceState.CACHED, state.reports.single().state)
        assertEquals(1, state.artworks.size)
        assertEquals(1_000 * day, state.updatedAtMs)                                    // l'âge réel des données reste visible
        // une heure plus tard, une copie est retentée ; avant, non
        http.asked.clear(); http.offline = false
        loader(http, dir) { t + 10 * 60_000 }.load(artist) { }
        assertTrue("trop tôt pour retenter : ${http.asked}", http.asked.isEmpty())
        loader(http, dir) { t + 2 * hour }.load(artist) { }
        assertTrue(http.asked.isNotEmpty())
    }

    @Test fun aFailedSourceIsRetriedOnlyAfterAnHour() = runBlocking {
        val dir = tmp(); var t = 5 * day
        val http = CountingHttp(emptyMap())                                             // tout échoue
        val first = loader(http, dir) { t }.load(artist) { }
        assertEquals(SourceState.UNREACHABLE, first.reports.single().state)
        http.asked.clear(); t += 30 * 60_000
        loader(http, dir) { t }.load(artist) { }
        assertTrue("pas de nouvelle tentative avant 1 h : ${http.asked}", http.asked.isEmpty())
        t += 40 * 60_000
        loader(http, dir) { t }.load(artist) { }
        assertTrue(http.asked.isNotEmpty())
    }

    @Test fun forcingARefreshRefetchesFreshDataButKeepsItIfTheNetworkIsDown() = runBlocking {
        val dir = tmp(); val t = 10 * day
        val http = CountingHttp(pages())
        loader(http, dir) { t }.load(artist) { }
        http.asked.clear()
        loader(http, dir) { t + 1000 }.load(artist, force = true) { }
        assertTrue(http.asked.isNotEmpty())                                             // frais, mais « Actualiser » recherche quand même
        http.offline = true
        val state = loader(http, dir) { t + 2000 }.load(artist, force = true) { }
        assertEquals(SourceState.CACHED, state.reports.single().state)                  // et l'ancien univers n'est pas perdu
        assertEquals(1, state.artworks.size)
    }

    @Test fun aStoreFromAnotherRuleVersionIsIgnored() = runBlocking {
        val dir = tmp(); val t = 10 * day
        UniverseStore.write(dir, artist.id, StoredUniverse(UniverseStore.PARSER_VERSION - 1, mapOf("aic" to stored(SourceState.CONNECTED, t, t, 5))))
        val http = CountingHttp(pages())
        val state = loader(http, dir) { t }.load(artist) { }
        assertTrue(http.asked.isNotEmpty())                                             // les anciennes œuvres ne sont pas servies
        assertEquals(listOf("The Bedroom"), state.artworks.map { it.title })
    }
}
