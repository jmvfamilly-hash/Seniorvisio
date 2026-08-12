package com.seniorvisio.core

/**
 * Contrôle le cycle "alerte 30s + option de blocage" côté Jean.
 * Remplace l'ancienne approche par détection de présence caméra :
 * plus simple, plus prévisible, et suffisant pour le besoin actuel
 * (appel volontaire déclenché par un proche, pas de veille permanente
 * à surveiller par caméra).
 *
 * Reste une interface pour pouvoir, plus tard, réintroduire une logique
 * différente (ex: présence, double confirmation) sans toucher au service.
 */
interface CallAlertController {

    /**
     * Démarre le compte à rebours. `onTimeoutConnect` est appelé si Jean
     * n'a rien fait avant la fin du délai (connexion auto). `onBlocked`
     * est appelé si Jean appuie sur "Bloquer".
     */
    fun startCountdown(
        callerName: String,
        durationSeconds: Int,
        onTick: (remainingSeconds: Int) -> Unit,
        onTimeoutConnect: () -> Unit,
        onBlocked: () -> Unit
    )

    /** Annule un compte à rebours en cours (ex: appelant raccroche avant la fin). */
    fun cancel()
}
