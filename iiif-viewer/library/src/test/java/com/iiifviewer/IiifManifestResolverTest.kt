package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IiifManifestResolverTest {
    private val v3 = """
    {"type":"Manifest","id":"https://h/m.json","items":[{"type":"Canvas","items":[{"type":"AnnotationPage","items":[
      {"type":"Annotation","body":{"id":"https://h/iiif/F612/full/max/0/default.jpg","type":"Image",
       "service":[{"id":"https://h/iiif/F612","type":"ImageService3"}]}}]}]}]}"""

    private val v2 = """
    {"@type":"sc:Manifest","sequences":[{"canvases":[{"images":[{"resource":
      {"@id":"https://h/iiif/F612/full/full/0/default.jpg","service":{"@id":"https://h/iiif/F612/","profile":"level1"}}}]}]}]}"""

    @Test fun presentation3() = assertEquals("https://h/iiif/F612", IiifManifestResolver.serviceIdOf(v3))

    @Test fun presentation2() = assertEquals("https://h/iiif/F612/", IiifManifestResolver.serviceIdOf(v2))

    @Test fun infoUrlStripsTrailingSlash() =
        assertEquals("https://h/iiif/F612/info.json", IiifManifestResolver.infoUrlFor("https://h/iiif/F612/"))

    @Test fun infoJsonIsNotAManifest() =
        assertNull(IiifManifestResolver.serviceIdOf("""{"@context":"…","id":"https://h/iiif/F612","width":3000,"height":2000}"""))

    @Test fun garbageIsNotAManifest() {
        assertNull(IiifManifestResolver.serviceIdOf("pas du json"))
        assertNull(IiifManifestResolver.serviceIdOf("""{"type":"Manifest","items":[]}"""))
    }
}
