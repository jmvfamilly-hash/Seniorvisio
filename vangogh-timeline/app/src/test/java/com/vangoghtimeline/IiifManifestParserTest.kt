package com.vangoghtimeline

import com.vangoghtimeline.iiif.IiifManifestParser
import com.vangoghtimeline.iiif.ManifestRepository
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.DatePrecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IiifManifestParserTest {
    private fun manifest(
        label: String = """{"fr":["La Nuit étoilée"],"en":["The Starry Night"]}""",
        navDate: String? = "\"navDate\": \"1889-06-15T00:00:00Z\",",
        metadata: String = "[]",
    ) = """
    {
      "@context": "http://iiif.io/api/presentation/3/context.json",
      "id": "https://example.org/iiif/F612/manifest.json",
      "type": "Manifest",
      "label": $label,
      ${navDate ?: ""}
      "metadata": $metadata,
      "thumbnail": [{"id": "https://example.org/iiif/F612/full/200,/0/default.jpg", "type": "Image"}],
      "items": [{
        "id": "https://example.org/iiif/F612/canvas/1", "type": "Canvas", "width": 3000, "height": 2400,
        "items": [{ "type": "AnnotationPage", "items": [{
          "type": "Annotation", "motivation": "painting",
          "body": { "id": "https://example.org/iiif/F612/full/max/0/default.jpg", "type": "Image",
                    "service": [{ "id": "https://example.org/iiif/F612", "type": "ImageService3" }] }
        }]}]
      }]
    }""".trimIndent()

    @Test fun readsTitleDateAndImageService() {
        val a = IiifManifestParser.parse(manifest(), "https://example.org/iiif/F612/manifest.json")
        assertEquals("La Nuit étoilée", a.title)
        assertEquals(ArtworkDate.exact(1889, 6, 15), a.date)
        assertEquals("https://example.org/iiif/F612", a.iiif.imageServiceId)
        assertEquals("https://example.org/iiif/F612/info.json", a.iiif.infoJsonUrl)
        assertEquals(3000, a.iiif.canvasWidth)
        assertEquals(1.25f, a.iiif.aspectRatio, 1e-6f)
        assertEquals("https://example.org/iiif/F612/full/!320,288/0/default.jpg", a.iiif.thumbnailUrlFor(320, 288))
    }

    @Test fun languagePreferenceAndFallback() {
        val en = IiifManifestParser.parse(manifest(), "u", languages = listOf("en"))
        assertEquals("The Starry Night", en.title)
        val de = IiifManifestParser.parse(manifest(label = """{"nl":["Sterrennacht"]}"""), "u", languages = listOf("fr"))
        assertEquals("Sterrennacht", de.title)                 // première langue disponible
        val v2 = IiifManifestParser.parse(manifest(label = "\"Nuit étoilée\""), "u")
        assertEquals("Nuit étoilée", v2.title)
    }

    @Test fun metadataGivesPlaceMediumAndPrecision() {
        val md = """[
          {"label": {"fr": ["Lieu"]}, "value": {"fr": ["Saint-Rémy-de-Provence"]}},
          {"label": {"fr": ["Technique"]}, "value": {"fr": ["Huile sur toile"]}},
          {"label": {"fr": ["Précision de la date"]}, "value": {"fr": ["mois"]}}
        ]"""
        val a = IiifManifestParser.parse(manifest(metadata = md), "u")
        assertEquals("Saint-Rémy-de-Provence", a.place)
        assertEquals("Huile sur toile", a.medium)
        assertEquals(DatePrecision.MONTH, a.date.precision)
        assertEquals(ArtworkDate.month(1889, 6), a.date)
    }

    @Test fun fallsBackToDateMetadataWhenNoNavDate() {
        val md = """[{"label": {"en": ["Date"]}, "value": {"en": ["1888-10"]}}]"""
        val a = IiifManifestParser.parse(manifest(navDate = null, metadata = md), "u")
        assertEquals(ArtworkDate.month(1888, 10), a.date)
    }

    @Test fun rejectsManifestWithoutDate() {
        try {
            IiifManifestParser.parse(manifest(navDate = null), "u")
            fail("une œuvre sans date ne peut pas être placée sur la frise")
        } catch (e: IiifManifestParser.ParseException) {
            assertTrue(e.message!!.contains("date"))
        }
    }

    @Test fun rejectsInvalidJson() {
        try { IiifManifestParser.parse("{pas du json", "u"); fail() } catch (e: IiifManifestParser.ParseException) { /* attendu */ }
    }

    @Test fun thumbnailFallbackWithoutImageService() {
        val noService = manifest().replace("\"service\"", "\"x-service\"")
        val a = IiifManifestParser.parse(noService, "u")
        assertNull(a.iiif.imageServiceId)
        assertEquals("https://example.org/iiif/F612/full/200,/0/default.jpg", a.iiif.thumbnailUrlFor(320, 288))
    }

    @Test fun repositoryLoadsACollectionInDateOrderAndSkipsBadManifests() = runBlocking {
        val collection = """{"type":"Collection","items":[
            {"id":"m2","type":"Manifest"},{"id":"bad","type":"Manifest"},{"id":"m1","type":"Manifest"},{"id":"c","type":"Collection"}]}"""
        val pages = mapOf(
            "coll" to collection,
            "m1" to manifest(navDate = "\"navDate\": \"1888-01-01T00:00:00Z\","),
            "m2" to manifest(navDate = "\"navDate\": \"1890-07-01T00:00:00Z\","),
            "bad" to "{}",
        )
        val skipped = mutableListOf<String>()
        val repo = ManifestRepository({ url -> pages[url] ?: error("404 $url") }, parallelism = 2)
        val arts = repo.loadCollection("coll") { url, _ -> skipped += url }
        assertEquals(listOf(1888, 1890), arts.map { it.date.year })
        assertEquals(listOf("bad"), skipped)
    }
}
