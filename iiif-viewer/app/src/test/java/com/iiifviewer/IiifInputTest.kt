package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IiifInputTest {
    @Test fun baseUriGetsInfoJson() {
        assertEquals("https://h/iiif/img/info.json", IiifInput.normalizeInfoUrl("  https://h/iiif/img  "))
        assertEquals("https://h/iiif/img/info.json", IiifInput.normalizeInfoUrl("https://h/iiif/img/"))
    }

    @Test fun jsonUrlsAreKept() {
        assertEquals("https://h/iiif/img/info.json", IiifInput.normalizeInfoUrl("https://h/iiif/img/info.json"))
        assertEquals("https://h/x.json", IiifInput.normalizeInfoUrl("https://h/x.json"))
        assertEquals("https://h/img?token=1", IiifInput.normalizeInfoUrl("https://h/img?token=1"))
    }

    @Test fun nonUrlsAreRejected() {
        assertNull(IiifInput.normalizeInfoUrl(""))
        assertNull(IiifInput.normalizeInfoUrl("ftp://h/img"))
        assertNull(IiifInput.normalizeInfoUrl("pas une url"))
        assertNull(IiifInput.normalizeInfoUrl("https://h/a b"))
        assertNull(IiifInput.normalizeInfoUrl("https://"))
    }
}
