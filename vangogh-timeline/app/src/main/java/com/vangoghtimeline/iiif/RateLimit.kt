package com.vangoghtimeline.iiif

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Blocage temporaire ou limite de débit : `429`, `503`, ou `403` avec une PAGE HTML (le pare-feu anti-robot « Incapsula » du Met répond
 * ainsi quand on le sollicite trop). Différent d'un refus définitif : on attend et on retente.
 */
object TemporaryBlock {
    fun matches(message: String?): Boolean {
        if (message == null) return false
        if (message.contains("HTTP 429") || message.contains("HTTP 503")) return true
        return message.contains("HTTP 403") && (message.contains("text/html", ignoreCase = true) || message.contains("Incapsula", ignoreCase = true))
    }
}

/**
 * Cadence les requêtes vers un même serveur : au plus [parallel] à la fois, et au moins [minGapMs] entre deux départs (pour tous les
 * appelants qui partagent l'instance : plusieurs artistes chargés en même temps ne s'additionnent donc pas).
 */
class RateLimiter(
    parallel: Int = 2,
    private val minGapMs: Long = 150,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val gate = Semaphore(parallel)
    private val slotLock = Mutex()
    private var nextSlot = 0L

    suspend fun <T> run(block: suspend () -> T): T = gate.withPermit {
        val wait = slotLock.withLock {
            val t = now()
            val start = maxOf(t, nextSlot)
            nextSlot = start + minGapMs
            start - t
        }
        if (wait > 0) sleep(wait)
        block()
    }
}

/**
 * Enveloppe d'un [ManifestSource] pour UN serveur ([hostContains]) : requêtes cadencées par [limiter], et, si le serveur répond par un
 * blocage temporaire ([TemporaryBlock]), nouveaux essais après [retryDelaysMs] (2 s puis 5 s). Chaque attente est consignée dans [Diag].
 * Les autres serveurs passent sans modification.
 */
class RetryingSource(
    private val inner: ManifestSource,
    private val limiter: RateLimiter,
    private val hostContains: String,
    private val retryDelaysMs: List<Long> = listOf(2_000, 5_000),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : ManifestSource {
    override suspend fun fetch(url: String): String {
        if (!url.contains(hostContains)) return inner.fetch(url)
        var attempt = 0
        while (true) {
            try {
                return limiter.run { inner.fetch(url) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!TemporaryBlock.matches(e.message) || attempt >= retryDelaysMs.size) throw e
                val wait = retryDelaysMs[attempt++]
                Diag.warn(
                    "réseau", "blocage temporaire ou limite de débit (${e.message?.take(90)}) → nouvel essai $attempt/${retryDelaysMs.size} dans $wait ms",
                    url, key = "ratelimit|${Diag.hostOf(url)}|$attempt",
                )
                sleep(wait)
            }
        }
    }
}
