package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LruCacheTest {
    @Test fun evictsLeastRecentlyUsed() {
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache["a"]            // "a" redevient la plus récente
        cache.put("c", 3)     // évince "b"
        assertEquals(1, cache["a"])
        assertNull(cache["b"])
        assertEquals(3, cache["c"])
    }

    @Test fun peekDoesNotRefresh() {
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.peek("a")
        cache.put("c", 3)     // "a" reste la plus ancienne
        assertNull(cache.peek("a"))
    }
}
