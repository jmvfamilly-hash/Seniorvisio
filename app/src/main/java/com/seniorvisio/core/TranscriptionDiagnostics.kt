package com.seniorvisio.core

import android.os.SystemClock

/**
 * Les derniers messages de diagnostic de la transcription, gardés en mémoire
 * et republiés avec le signe de vie (voir DeviceStatusReporter).
 *
 * Ces messages existaient déjà, mais ne partaient que dans le document d'un
 * appel en cours : hors appel — c'est-à-dire l'essentiel du temps, la tablette
 * passant ses journées à écouter la pièce sans que personne appelle — ils
 * n'allaient nulle part. Régler le moteur de la pièce depuis le PWA puis
 * chercher pourquoi rien ne s'écrit revenait donc à regarder un écran vide,
 * sans le moindre indice.
 *
 * Les cinq derniers, avec le temps écoulé : le message qui compte est rarement
 * le dernier. « son micro tablette reçu », puis « modèle embarqué
 * indisponible », puis plus rien pendant vingt minutes se lit d'un coup d'œil
 * et raconte toute l'histoire ; le seul dernier message n'en dirait rien.
 */
object TranscriptionDiagnostics {

    private data class Entry(val message: String, val atMs: Long)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(message: String) {
        // Un message identique au précédent est ignoré : les moteurs répètent
        // volontiers la même cause à chaque tentative, et cinq lignes
        // identiques ne valent pas mieux qu'une.
        if (entries.lastOrNull()?.message == message) return
        entries.addLast(Entry(message, SystemClock.elapsedRealtime()))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun describe(): String {
        if (entries.isEmpty()) return "aucun message"
        val now = SystemClock.elapsedRealtime()
        return entries.joinToString(" | ") { entry ->
            val ago = (now - entry.atMs) / 1000
            val when_ = if (ago < 60) "il y a ${ago}s" else "il y a ${ago / 60}min"
            "${entry.message} ($when_)"
        }
    }

    private const val MAX_ENTRIES = 5
}
