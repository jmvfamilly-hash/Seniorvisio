package com.vangoghtimeline.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * File de préchargement ordonnée : [submit] reçoit la liste COMPLÈTE de ce qu'on veut, du plus urgent au moins urgent ; au plus
 * [parallel] chargements tournent à la fois, toujours le plus urgent d'abord. Une nouvelle soumission REMPLACE la précédente :
 * ce qui n'y figure plus est annulé s'il tourne, oublié s'il attend (on a défilé ailleurs, inutile de le charger).
 *
 * Un élément réussi n'est pas rechargé ; un échec est retenté à une soumission ultérieure, [maxAttempts] fois au plus
 * (un serveur qui répond 403 ne doit pas être martelé à chaque image de défilement).
 *
 * @param scope DOIT être mono-thread (Main) : l'état mutable n'y est touché que depuis ce thread.
 */
class PriorityPrefetcher<T : Any>(
    private val scope: CoroutineScope,
    private val parallel: Int,
    private val keyOf: (T) -> String,
    private val maxAttempts: Int = 2,
    private val load: suspend (T) -> Unit,
) {
    private val pending = ArrayList<T>()
    private val running = HashMap<String, Job>()
    private val done = HashSet<String>()
    private val attempts = HashMap<String, Int>()

    val runningCount: Int get() = running.size

    fun submit(ordered: List<T>) {
        val wanted = ordered.mapTo(HashSet()) { keyOf(it) }
        val stale = running.keys.filter { it !in wanted }
        for (k in stale) running.remove(k)?.cancel()
        pending.clear()
        for (item in ordered) {
            val k = keyOf(item)
            if (k !in done && k !in running) pending += item
        }
        pump()
    }

    /** Annule tout (fin de la frise). */
    fun clear() {
        pending.clear()
        running.values.forEach { it.cancel() }
        running.clear()
    }

    private fun pump() {
        while (running.size < parallel && pending.isNotEmpty()) {
            val item = pending.removeAt(0)
            val k = keyOf(item)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    load(item)
                    markDone(k)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val n = (attempts[k] ?: 0) + 1
                    attempts[k] = n
                    if (n >= maxAttempts) markDone(k)
                } finally {
                    // identité : une tâche annulée puis relancée sous la même clé ne doit pas effacer la nouvelle
                    if (running[k] === coroutineContext[Job]) running.remove(k)
                    pump()
                }
            }
            running[k] = job
            job.start()
        }
    }

    private fun markDone(k: String) {
        if (done.size > 2000) done.clear()
        done += k
    }
}
