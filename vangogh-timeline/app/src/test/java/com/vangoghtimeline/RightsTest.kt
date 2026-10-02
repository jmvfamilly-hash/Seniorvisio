package com.vangoghtimeline

import com.vangoghtimeline.iiif.ArticParser
import com.vangoghtimeline.iiif.ArtworkJson
import com.vangoghtimeline.iiif.ClevelandParser
import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.iiif.EuropeanaParser
import com.vangoghtimeline.iiif.HispanicSocietyProbe
import com.vangoghtimeline.iiif.ImageReachability
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.Reach
import com.vangoghtimeline.iiif.SmkParser
import com.vangoghtimeline.iiif.SourceState
import com.vangoghtimeline.iiif.SourceValidator
import com.vangoghtimeline.iiif.UniverseLoader
import com.vangoghtimeline.iiif.WikimediaParser
import com.vangoghtimeline.iiif.defaultMuseumSources
import com.vangoghtimeline.model.Artist
import com.vangoghtimeline.model.ArtistCatalog
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.RightsCatalog
import com.vangoghtimeline.model.RightsInfo
import com.vangoghtimeline.model.RightsKind
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

class RightsTest {
    @Before fun reset() { Diag.clear() }

    private val catalog by lazy { ArtistCatalog.parse(File("src/main/assets/artists_by_movement.json").readText()) }
    private fun artist(id: String): Artist = catalog.first { it.id == id }

    // ── Correspondances de licences ───────────────────────────────────────────────
    @Test fun europeanaRightsUrlsAreMapped() {
        val pd = RightsCatalog.fromRightsUrl("http://creativecommons.org/publicdomain/mark/1.0/")
        assertEquals(RightsKind.PUBLIC_DOMAIN, pd.kind); assertTrue(pd.label.contains("Public Domain Mark"))
        assertEquals(RightsKind.PUBLIC_DOMAIN, RightsCatalog.fromRightsUrl("https://creativecommons.org/publicdomain/zero/1.0/").kind)

        val by = RightsCatalog.fromRightsUrl("http://creativecommons.org/licenses/by-nc-sa/4.0/", "Musée X")
        assertEquals(RightsKind.OPEN_LICENSE, by.kind); assertEquals("CC BY-NC-SA 4.0", by.label); assertEquals("Musée X", by.attribution)
        assertTrue(by.conditions, by.conditions.contains("citer l'auteur") && by.conditions.contains("pas d'usage commercial") && by.conditions.contains("mêmes conditions"))

        assertEquals(RightsKind.VIEW_ONLY, RightsCatalog.fromRightsUrl("http://rightsstatements.org/vocab/InC/1.0/").kind)
        val edu = RightsCatalog.fromRightsUrl("http://rightsstatements.org/vocab/InC-EDU/1.0/")
        assertEquals(RightsKind.VIEW_ONLY, edu.kind); assertTrue(edu.conditions.contains("éducatif"))
        assertEquals(RightsKind.PUBLIC_DOMAIN, RightsCatalog.fromRightsUrl("http://rightsstatements.org/vocab/NoC-OKLR/1.0/").kind)
        assertEquals(RightsKind.UNKNOWN, RightsCatalog.fromRightsUrl("http://rightsstatements.org/vocab/CNE/1.0/").kind)
        assertEquals(RightsKind.UNKNOWN, RightsCatalog.fromRightsUrl(null).kind)
        assertEquals(RightsKind.UNKNOWN, RightsCatalog.fromRightsUrl("https://exemple.org/droits").kind)
    }

    @Test fun commonsLicensesAreMappedAndHtmlIsStripped() {
        assertEquals(RightsKind.PUBLIC_DOMAIN, RightsCatalog.fromCommons("PD-Art", null, null).kind)
        assertEquals(RightsKind.PUBLIC_DOMAIN, RightsCatalog.fromCommons("Public domain", null, null).kind)
        assertEquals(RightsKind.PUBLIC_DOMAIN, RightsCatalog.fromCommons("CC0", null, null).kind)
        val sa = RightsCatalog.fromCommons("CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0", """<a href="//x">Jean &amp; Marie</a>""")
        assertEquals(RightsKind.OPEN_LICENSE, sa.kind); assertEquals("Jean & Marie", sa.attribution); assertTrue(sa.conditions.contains("mêmes conditions"))
        assertEquals(RightsKind.VIEW_ONLY, RightsCatalog.fromCommons("Fair use", null, null).kind)
        assertEquals(RightsKind.UNKNOWN, RightsCatalog.fromCommons(null, null, null).kind)
    }

    @Test fun everyKindHasConditionsAndTheSummaryCountsThem() {
        for (k in RightsKind.values()) assertTrue(RightsCatalog.conditionsFor(k).isNotBlank())
        fun a(n: Int, r: RightsInfo?) = Artwork("a$n", "T$n", ArtworkDate.year(1888), iiif = IiifRef("m"), rights = r)
        val list = listOf(a(1, RightsCatalog.publicDomain("PD")), a(2, RightsCatalog.publicDomain("PD")), a(3, RightsCatalog.viewOnly("©")), a(4, null))
        assertEquals("2 domaine public · 1 consultation privée · 1 droits non précisés", RightsCatalog.summary(list))
    }

    @Test fun rightsSurviveTheLocalJsonStore() {
        val r = RightsInfo(RightsKind.OPEN_LICENSE, "CC BY 4.0", "https://x", "Auteur « é »", "Conditions.")
        val list = listOf(Artwork("a", "T", ArtworkDate.year(1888), iiif = IiifRef("m"), rights = r), Artwork("b", "U", ArtworkDate.year(1889), iiif = IiifRef("m")))
        val back = ArtworkJson.decode(ArtworkJson.encode(list))
        assertEquals(r, back[0].rights); assertNull(back[1].rights)
    }

    // ── Les sources renseignent les droits et ne filtrent plus sur le domaine public ─
    private val vg = ArtworkQuery.VAN_GOGH

    @Test fun aicProtectedWorksAreKeptWithTheirRights() {
        val json = """{"data":[
          {"id":1,"title":"PD","artist_title":"Vincent van Gogh","date_start":1888,"image_id":"a","is_public_domain":true},
          {"id":2,"title":"Protégée","artist_title":"Vincent van Gogh","date_start":1889,"image_id":"b","is_public_domain":false,"copyright_notice":"© Ayants droit"}],
          "config":{"iiif_url":"https://www.artic.edu/iiif/2"}}"""
        val arts = ArticParser.parse(json, vg)
        assertEquals(RightsKind.PUBLIC_DOMAIN, arts[0].rights!!.kind)
        assertEquals(RightsKind.VIEW_ONLY, arts[1].rights!!.kind); assertTrue(arts[1].rights!!.label.contains("Ayants droit"))
        assertFalse(ArticParser.searchUrl(vg).contains("is_public_domain%5D=true"))
    }

    @Test fun clevelandAndSmkRights() {
        val cleveland = """{"data":[{"id":1,"title":"A","creation_date_earliest":1889,"creation_date_latest":1889,"share_license_status":"CC0",
            "creators":[{"description":"Vincent van Gogh (Dutch)"}],"images":{"print":{"url":"https://c/1.jpg"}}},
            {"id":2,"title":"B","creation_date_earliest":1889,"creation_date_latest":1889,"share_license_status":"Copyrighted","copyright":"© X",
            "creators":[{"description":"Vincent van Gogh (Dutch)"}],"images":{"web":{"url":"https://c/2.jpg"}}}]}"""
        val c = ClevelandParser.parse(cleveland, vg)
        assertEquals(listOf(RightsKind.PUBLIC_DOMAIN, RightsKind.VIEW_ONLY), c.map { it.rights!!.kind })
        assertFalse(ClevelandParser.searchUrl(vg).contains("cc0=1"))
        val smk = """{"items":[{"object_number":"K1","artist":["Vincent van Gogh"],"titles":[{"title":"T"}],"production_date":[{"start":"1888-01-01T00:00:00"}],
            "image_iiif_id":"https://i/x","public_domain":false,"rights":"© SMK"}]}"""
        val s = SmkParser.parse(smk, vg)
        assertEquals(RightsKind.VIEW_ONLY, s.single().rights!!.kind); assertTrue(s.single().rights!!.label.contains("© SMK"))
        assertFalse(SmkParser.searchUrl(vg).contains("public_domain"))
    }

    @Test fun europeanaKeepsNonOpenItemsWithTheirLicenseAndHonoursNoDistributePreview() {
        val items = """{"items":[
          {"id":"/1/a","title":["Ouverte"],"dcCreator":["Vincent van Gogh"],"year":["1888"],"edmPreview":["https://t/1"],"dataProvider":["Musée A"],"rights":["http://creativecommons.org/publicdomain/mark/1.0/"]},
          {"id":"/1/b","title":["Réservée"],"dcCreator":["Vincent van Gogh"],"year":["1889"],"edmPreview":["https://t/2"],"dataProvider":["Musée B"],"rights":["http://rightsstatements.org/vocab/InC/1.0/"],"previewNoDistribute":true}]}"""
        val arts = EuropeanaParser.parse(items, vg)
        assertEquals(listOf("Ouverte", "Réservée"), arts.map { it.title })
        assertEquals(RightsKind.PUBLIC_DOMAIN, arts[0].rights!!.kind); assertEquals("Musée A, via Europeana", arts[0].rights!!.attribution)
        assertEquals("https://t/1", arts[0].iiif.thumbnailUrl)
        assertEquals(RightsKind.VIEW_ONLY, arts[1].rights!!.kind)
        assertNull(arts[1].iiif.thumbnailUrl)                                                       // aperçu non redistribuable : pas de vignette
        assertTrue(arts[1].rights!!.conditions.contains("vignette masquée"))
        assertTrue(EuropeanaParser.searchVariants(vg).none { it.contains("reusability") })
        assertTrue(EuropeanaParser.dataProviderUrl("Museo Sorolla").contains("DATA_PROVIDER%3A%22Museo+Sorolla%22"))
    }

    // ── Wikimedia ─────────────────────────────────────────────────────────────────
    private val sparql = """{"results":{"bindings":[
      {"item":{"value":"http://www.wikidata.org/entity/Q111"},"itemLabel":{"value":"Paseo a orillas del mar"},"inception":{"value":"1909-01-01T00:00:00Z"},
       "image":{"value":"http://commons.wikimedia.org/wiki/Special:FilePath/Joaqu%C3%ADn%20Sorolla%20-%20Paseo.jpg"},"collectionLabel":{"value":"Museo Sorolla"}},
      {"item":{"value":"http://www.wikidata.org/entity/Q111"},"itemLabel":{"value":"Paseo a orillas del mar"},"image":{"value":"http://commons.wikimedia.org/wiki/Special:FilePath/Autre.jpg"}},
      {"item":{"value":"http://www.wikidata.org/entity/Q222"},"itemLabel":{"value":"Q222"},"image":{"value":"http://commons.wikimedia.org/wiki/Special:FilePath/X.jpg"}},
      {"item":{"value":"http://www.wikidata.org/entity/Q333"},"itemLabel":{"value":"Sans date"},"image":{"value":"http://commons.wikimedia.org/wiki/Special:FilePath/Y.jpg"}}]}}"""
    private val imageInfo = """{"query":{"pages":{"1":{"title":"File:Joaquín Sorolla - Paseo.jpg","imageinfo":[{"width":6000,"height":4000,"extmetadata":
      {"LicenseShortName":{"value":"PD-Art"},"LicenseUrl":{"value":"https://creativecommons.org/publicdomain/mark/1.0/"},"Artist":{"value":"<a href='x'>Joaquín Sorolla</a>"}}}]}}}}"""

    @Test fun wikimediaParsing() {
        assertEquals("Q297838", WikimediaParser.parseQid("""{"query":{"pages":{"4":{"pageprops":{"wikibase_item":"Q297838"}}}}}"""))
        assertNull(WikimediaParser.parseQid("""{"query":{"pages":{"-1":{"missing":""}}}}"""))
        assertTrue(WikimediaParser.sparqlUrl("Q297838").startsWith("https://query.wikidata.org/sparql?format=json&query="))
        assertTrue(WikimediaParser.sparql("Q297838").contains("wdt:P170 wd:Q297838") && WikimediaParser.sparql("Q297838").contains("wdt:P18"))
        assertEquals("Joaquín Sorolla - Paseo.jpg", WikimediaParser.fileNameOf("http://commons.wikimedia.org/wiki/Special:FilePath/Joaqu%C3%ADn%20Sorolla%20-%20Paseo.jpg"))
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Joaqu%C3%ADn%20Sorolla%20-%20Paseo.jpg?width=3000", WikimediaParser.filePathUrl("Joaquín Sorolla - Paseo.jpg", 3000))

        val tally = com.vangoghtimeline.iiif.Tally()
        val items = WikimediaParser.parseSparql(sparql, tally)
        assertEquals(listOf("Q111", "Q333"), items.map { it.qid })                       // doublon et libellé « Q222 » écartés
        assertEquals(1909, items[0].year); assertEquals("Museo Sorolla", items[0].collection); assertNull(items[1].year)
        assertTrue(tally.summary(), tally.summary().contains("doublons") && tally.summary().contains("sans titre"))
        val info = WikimediaParser.parseImageInfo(imageInfo)
        assertEquals(6000, info.getValue("Joaquín Sorolla - Paseo.jpg").width)
        assertEquals(RightsKind.PUBLIC_DOMAIN, info.getValue("Joaquín Sorolla - Paseo.jpg").rights.kind)
        assertEquals("Joaquín Sorolla", info.getValue("Joaquín Sorolla - Paseo.jpg").rights.attribution)
    }

    @Test fun wikimediaArtworkCarriesImagesLicenseAndProvider() {
        val q = ArtworkQuery.of(artist("joaquin-sorolla"))
        val item = WikimediaParser.parseSparql(sparql)[0]
        val art = WikimediaParser.toArtwork(item, WikimediaParser.parseImageInfo(imageInfo)["Joaquín Sorolla - Paseo.jpg"], q)!!
        assertEquals("wikimedia-Q111", art.id); assertEquals(1909, art.date.year); assertEquals("Wikimedia · Museo Sorolla", art.provider)
        assertTrue(art.iiif.viewerUrl!!.startsWith("static:https://commons.wikimedia.org/wiki/Special:FilePath/") && art.iiif.viewerUrl!!.endsWith("?width=3000"))
        assertTrue(art.iiif.thumbnailUrlFor(300, 200)!!.endsWith("?width=400"))
        assertEquals(6000f / 4000f, art.iiif.aspectRatio, 1e-4f)
        assertEquals(RightsKind.PUBLIC_DOMAIN, art.rights!!.kind)
        // licence non lue : droits « non précisés », jamais présumés
        assertEquals(RightsKind.UNKNOWN, WikimediaParser.toArtwork(item, null, q)!!.rights!!.kind)
        assertNull(WikimediaParser.toArtwork(WikimediaParser.parseSparql(sparql)[1], null, q))                       // sans année : placée nulle part
    }

    private class FakeHttp(val pages: Map<String, String>) : ManifestSource {
        val asked = ArrayList<String>()
        override suspend fun fetch(url: String): String { asked += url; return pages[url] ?: throw IOException("HTTP 403 sur $url") }
    }
    private val reachOk = ImageReachability { Reach(206, "image/jpeg") }
    private fun tmp(): File = kotlin.io.path.createTempDirectory("rights").toFile()

    @Test fun theWikimediaSourceRunsEndToEndThroughTheValidator() = runBlocking {
        val sorolla = artist("joaquin-sorolla").copy(sources = listOf(SourceSpec("wikimedia", "Joaqu%C3%ADn_Sorolla")))
        val item = WikimediaParser.parseSparql(sparql)[0]
        val http = FakeHttp(mapOf(
            WikimediaParser.qidUrl("Joaqu%C3%ADn_Sorolla") to """{"query":{"pages":{"4":{"pageprops":{"wikibase_item":"Q297838"}}}}}""",
            WikimediaParser.sparqlUrl("Q297838") to sparql,
            WikimediaParser.imageInfoUrl(listOf(item.file, "Y.jpg")) to imageInfo,
        ))
        // le lot de licences demande aussi le fichier sans année ; seule la clé de l'URL compte, on la reconstruit comme la source
        val itemsAll = WikimediaParser.parseSparql(sparql)
        val http2 = FakeHttp(http.pages + (WikimediaParser.imageInfoUrl(itemsAll.map { it.file }) to imageInfo))
        val state = UniverseLoader(defaultMuseumSources(http2), SourceValidator(http2, reachOk), tmp()).load(sorolla) { }
        assertEquals(SourceState.CONNECTED, state.reports.single().state)
        assertEquals(listOf("Paseo a orillas del mar"), state.artworks.map { it.title })
        assertEquals("1 domaine public", state.rightsSummary)
        assertTrue(state.credit, state.credit.contains("Wikimedia"))
    }

    // ── Hispanic Society : source de reconnaissance ───────────────────────────────
    @Test fun theHispanicSocietyProbeLogsTheShapeAndAddsNothing() = runBlocking {
        val html = "<html><a href=\"/objects/101/titre\">A</a><a href=\"/objects/102\">B</a><a href=\"/objects/101\">A</a> iiif manifest</html>"
        val http = FakeHttp(mapOf("https://hispanicsociety.emuseum.com/search/Sorolla/objects" to html))
        val q = ArtworkQuery.of(artist("joaquin-sorolla"))
        assertTrue(HispanicSocietyProbe(http).fetch(q, SourceSpec("hispanic", "Sorolla")).isEmpty())
        val log = Diag.snapshot().filter { it.category == "reconnaissance" }
        assertTrue(log.any { it.message.contains("2 liens d'objets distincts") && it.message.contains("2 mentions iiif/manifest") })
        assertTrue(log.any { it.message.startsWith("inaccessible") })                       // les autres adresses sont consignées en échec

        val sorolla = artist("joaquin-sorolla").copy(sources = listOf(SourceSpec("hispanic", "Sorolla")))
        val state = UniverseLoader(defaultMuseumSources(http), SourceValidator(http, reachOk), tmp()).load(sorolla) { }
        assertEquals(SourceState.UNAVAILABLE, state.reports.single().state)
        assertTrue(state.reports.single().detail.contains("reconnaissance"))
        assertTrue(state.artworks.isEmpty())
    }

    // ── Catalogue : toutes les sources pour les quatre artistes ───────────────────
    @Test fun everyArtistWithAUniverseGetsEverySourceInPriorityOrder() {
        val expected = listOf("aic", "rijks", "cleveland", "met", "smk", "europeana", "wikimedia", "hispanic")
        for (id in listOf("vincent-van-gogh", "john-singer-sargent", "joaquin-sorolla", "pierre-auguste-renoir")) {
            assertEquals(id, expected, artist(id).sources.map { it.sourceId })
        }
        assertEquals("Museo Sorolla", artist("joaquin-sorolla").sources.first { it.sourceId == "europeana" }.term)
        assertEquals("Sorolla y Bastida, Joaquín", artist("joaquin-sorolla").sources.first { it.sourceId == "rijks" }.term)
        assertTrue(catalog.filter { it.hasUniverse }.map { it.id }.toSet() == setOf("vincent-van-gogh", "john-singer-sargent", "joaquin-sorolla", "pierre-auguste-renoir"))
    }
}
