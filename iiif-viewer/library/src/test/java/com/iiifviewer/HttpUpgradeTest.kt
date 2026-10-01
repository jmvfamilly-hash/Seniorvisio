package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpUpgradeTest {
    @Test fun httpIsUpgradedToHttps() {
        assertEquals("https://nationalmuseumse.iiifhosting.com/iiif/x/info.json", HttpUpgrade.secure("http://nationalmuseumse.iiifhosting.com/iiif/x/info.json"))
        assertEquals("https://h/a?b=c#d", HttpUpgrade.secure("http://h/a?b=c#d"))
        assertEquals("https://h", HttpUpgrade.secure("HTTP://h"))
        assertTrue(HttpUpgrade.needsUpgrade("http://h/x"))
    }

    @Test fun httpsAndOtherSchemesAreUntouched() {
        assertEquals("https://h/x", HttpUpgrade.secure("https://h/x"))
        assertEquals("static:https://h/x.jpg", HttpUpgrade.secure("static:https://h/x.jpg"))
        assertFalse(HttpUpgrade.needsUpgrade("https://h/x"))
    }

    @Test fun localAddressesAreKept() {
        for (u in listOf("http://localhost:8080/x", "http://127.0.0.1/x", "http://192.168.1.5/x", "http://10.0.0.2/x", "http://172.20.1.1/x", "http://musee.local/x")) {
            assertEquals(u, HttpUpgrade.secure(u))
        }
        assertEquals("https://172.15.0.1/x", HttpUpgrade.secure("http://172.15.0.1/x"))   // hors de la plage privée 172.16-31
    }
}
