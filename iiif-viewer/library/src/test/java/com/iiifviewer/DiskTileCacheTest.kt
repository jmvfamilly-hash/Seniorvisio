package com.iiifviewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiskTileCacheTest {
    private fun dir(): File = kotlin.io.path.createTempDirectory("tiles").toFile()
    private var now = 1_000_000L
    private val clock: () -> Long = { now.also { now += 1_000 } }          // chaque appel avance d'une seconde : un ordre strict

    @Test fun roundTripAndMiss() {
        val c = DiskTileCache(dir(), 1_000_000, clock)
        assertNull(c.get("https://h/t1"))
        c.put("https://h/t1", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), c.get("https://h/t1"))
        assertEquals(1, c.hits); assertEquals(1, c.misses)
        assertEquals(3, c.bytes)
    }

    @Test fun theLeastRecentlyUsedEntriesGoFirst() {
        val c = DiskTileCache(dir(), maxBytes = 100, clock = clock)          // plafond 100 octets ; trim jusqu'à 90
        for (i in 1..4) c.put("u$i", ByteArray(30) { i.toByte() })           // 120 octets au total : dépasse dès le 4e
        // u1 (le plus ancien) est parti ; u2, u3, u4 restent (90 octets)
        assertNull(c.get("u1")); assertNotNull(c.get("u2")); assertNotNull(c.get("u3")); assertNotNull(c.get("u4"))
        assertTrue(c.bytes <= 100)
    }

    @Test fun aReadRefreshesAnEntryAndProtectsItFromEviction() {
        val c = DiskTileCache(dir(), maxBytes = 100, clock = clock)
        c.put("a", ByteArray(30)); c.put("b", ByteArray(30)); c.put("c", ByteArray(30))     // 90 octets
        assertNotNull(c.get("a"))                                            // « a » redevient le plus récent
        c.put("d", ByteArray(30))                                            // 120 : on retire le moins récent, c'est « b »
        assertNotNull(c.get("a")); assertNull(c.get("b")); assertNotNull(c.get("c")); assertNotNull(c.get("d"))
    }

    @Test fun textsExpireAndAreNotRejuvenatedByReading() {
        val c = DiskTileCache(dir(), 1_000_000, clock)
        c.putText("https://h/info.json", """{"width":10}""")
        assertEquals("""{"width":10}""", c.getText("https://h/info.json"))
        now += DiskTileCache.TEXT_TTL_MS                                     // 30 jours plus tard
        assertNull(c.getText("https://h/info.json"))
        assertEquals("""{"width":10}""", c.getText("https://h/info.json", maxAgeMs = Long.MAX_VALUE))   // toujours là sur disque
    }

    @Test fun textsAndBytesAreSeparateNamespaces() {
        val c = DiskTileCache(dir(), 1_000_000, clock)
        c.putText("https://h/x", "texte"); c.put("https://h/x", byteArrayOf(9))
        assertEquals("texte", c.getText("https://h/x")); assertArrayEquals(byteArrayOf(9), c.get("https://h/x"))
    }

    @Test fun anOversizedEntryIsNotKeptAndEmptyIsIgnored() {
        val c = DiskTileCache(dir(), Long.MAX_VALUE, clock)
        c.put("big", ByteArray(DiskTileCache.MAX_ENTRY_BYTES + 1)); c.put("empty", ByteArray(0))
        assertNull(c.get("big")); assertNull(c.get("empty")); assertEquals(0, c.bytes)
    }

    @Test fun removeDeletesBothKinds() {
        val c = DiskTileCache(dir(), 1_000_000, clock)
        c.put("u", byteArrayOf(1)); c.putText("u", "t")
        c.remove("u")
        assertNull(c.get("u")); assertNull(c.getText("u")); assertEquals(0, c.bytes)
    }

    @Test fun sizeIsRecoveredFromDiskOnRestartAndStatsAreReadable() {
        val d = dir()
        DiskTileCache(d, 1_000_000, clock).apply { put("a", ByteArray(500)); put("b", ByteArray(700)) }
        val reopened = DiskTileCache(d, 1_000_000, clock)
        assertEquals(1_200, reopened.bytes)
        assertArrayEquals(ByteArray(500), reopened.get("a"))
        assertTrue(reopened.stats(), reopened.stats().startsWith("2 fichiers") && reopened.stats().contains("100 % de réussites"))
        assertFalse(DiskTileCache(dir(), 10, clock).stats().contains("réussites"))
    }
}
