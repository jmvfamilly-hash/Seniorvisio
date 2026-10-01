package com.vangoghtimeline

import com.vangoghtimeline.demo.DemoTile
import com.vangoghtimeline.demo.DemoTileUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoTileUrlTest {
    @Test fun recognisesDemoAddresses() {
        assertTrue(DemoTileUrl.isDemo("demo:vangogh/s27"))
        assertFalse(DemoTileUrl.isDemo("https://example.org/iiif/x/info.json"))
    }

    @Test fun baseAndIdIgnoreManifestAndInfoSuffixes() {
        assertEquals("demo:vangogh/s27", DemoTileUrl.base("demo:vangogh/s27"))
        assertEquals("demo:vangogh/s27", DemoTileUrl.base("demo:vangogh/s27/manifest.json"))
        assertEquals("demo:vangogh/s27", DemoTileUrl.base("demo:vangogh/s27/info.json"))
        assertEquals("s27", DemoTileUrl.idOf("demo:vangogh/s27/info.json"))
    }

    @Test fun parsesAnImageApiTileUrl() {
        assertEquals(
            DemoTile("s27", 512, 256, 1024, 768, 512),
            DemoTileUrl.parseTile("demo:vangogh/s27/512,256,1024,768/512,/0/default.jpg"),
        )
        assertNull(DemoTileUrl.parseTile("demo:vangogh/s27/info.json"))
        assertNull(DemoTileUrl.parseTile("https://example.org/0,0,10,10/10,/0/default.jpg"))
    }
}
