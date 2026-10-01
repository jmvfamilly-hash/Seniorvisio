package com.vangoghtimeline

import com.vangoghtimeline.model.PriorityPrefetcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityPrefetcherTest {
    @Test fun loadsInOrderWithBoundedParallelism() = runBlocking {
        val started = ArrayList<String>()
        val gates = HashMap<String, CompletableDeferred<Unit>>()
        val p = PriorityPrefetcher<String>(this, parallel = 2, keyOf = { it }) { k ->
            started += k
            gates.getOrPut(k) { CompletableDeferred() }.await()
        }
        p.submit(listOf("a", "b", "c", "d"))
        yield(); yield()
        assertEquals(listOf("a", "b"), started)         // 2 voies seulement, dans l'ordre
        gates.getOrPut("a") { CompletableDeferred() }.complete(Unit)
        yield(); yield()
        assertEquals(listOf("a", "b", "c"), started)    // une voie libérée : le suivant démarre
        p.clear()
    }

    @Test fun newSubmissionCancelsWhatIsNoLongerWanted() = runBlocking {
        val cancelled = ArrayList<String>()
        val p = PriorityPrefetcher<String>(this, parallel = 2, keyOf = { it }) { k ->
            try { CompletableDeferred<Unit>().await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled += k; throw e }
        }
        p.submit(listOf("a", "b"))
        yield(); yield()
        p.submit(listOf("b", "z"))                      // « a » n'est plus voulu
        yield(); yield()
        assertEquals(listOf("a"), cancelled)
        assertEquals(2, p.runningCount)                 // « b » continue, « z » a pris la voie de « a »
        p.clear()
    }

    @Test fun doneItemsAreNotReloaded() = runBlocking {
        val loads = ArrayList<String>()
        val p = PriorityPrefetcher<String>(this, parallel = 2, keyOf = { it }) { loads += it }
        p.submit(listOf("a", "b"))
        yield(); yield(); yield()
        p.submit(listOf("a", "b", "c"))
        yield(); yield(); yield()
        assertEquals(listOf("a", "b", "c"), loads)
    }

    @Test fun failuresAreRetriedAFewTimesOnly() = runBlocking {
        var calls = 0
        val p = PriorityPrefetcher<String>(this, parallel = 1, keyOf = { it }, maxAttempts = 2) { calls++; error("403") }
        repeat(5) { p.submit(listOf("a")); yield(); yield() }
        assertTrue("essais bornés, obtenu $calls", calls == 2)
    }
}
