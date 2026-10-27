package com.seniorvisio.core

import android.content.Context

/**
 * Bascule entre « appels possibles » et « appels bloqués », à la demande d'un
 * soignant — le même bouton, au centre de l'écran de Jean, démarre la pause
 * et l'annule (voir MainActivity). Trois façons d'en sortir, toutes
 * équivalentes pour Jean : le soignant appuie une seconde fois avant de
 * partir, l'administrateur débloque depuis le PWA (voir IncomingCallService,
 * DeviceStatusReporter), ou rien n'est fait et les 30 minutes s'écoulent.
 */
object PauseAppels {

    private const val DURÉE_MINUTES = 30

    /**
     * Rappelé à chaque bascule (démarrage, annulation quelle qu'en soit la
     * cause) pendant que l'écran d'accueil est au premier plan — voir
     * MainActivity.onResume/onPause. Un seul abonné à la fois : cet écran est
     * le seul à afficher le bouton concerné.
     */
    private var onChange: (() -> Unit)? = null

    fun setOnChangeListener(listener: () -> Unit) {
        onChange = listener
    }

    fun clearOnChangeListener() {
        onChange = null
    }

    /** Appelée par le bouton de la tablette : démarre si rien n'est en cours, annule sinon. */
    fun basculer(context: Context) {
        val config = AdminConfig(context)
        if (config.isCareBreakActive()) {
            annulerActive(context, config, "appui répété sur le bouton de la tablette")
        } else {
            val maintenant = System.currentTimeMillis()
            config.careBreakStartedAtMs = maintenant
            config.careBreakUntilMs = maintenant + DURÉE_MINUTES * 60_000L
            CallTrace.record("PAUSE APPELS activée", "$DURÉE_MINUTES min, bouton de la tablette")
            AnnoncesVocales.jouer(context, AnnoncesVocales.Annonce.APPELS_INTERDITS)
            onChange?.invoke()
        }
    }

    /** Appelée par la commande à distance envoyée depuis le PWA administrateur. */
    fun annulerÀDistance(context: Context) {
        val config = AdminConfig(context)
        if (config.isCareBreakActive()) annulerActive(context, config, "débloqué depuis le PWA")
    }

    /**
     * Appelée périodiquement (signe de vie, toutes les cinq minutes) : constate
     * une échéance dépassée que personne n'a levée entre-temps.
     *
     * Le blocage réel des appels entrants (voir IncomingCallService) relit
     * directement l'horloge et s'arrête donc à l'heure, quoi qu'il arrive ici —
     * cette vérification ne sert qu'à journaliser la bascule et jouer
     * l'annonce de fin si rien d'autre ne l'a déjà fait, avec jusqu'à cinq
     * minutes de retard dans le pire cas (redémarrage du processus pendant la
     * pause). Un retard sur l'annonce, jamais sur le blocage lui-même.
     */
    fun vérifierExpirationNaturelle(context: Context) {
        val config = AdminConfig(context)
        val until = config.careBreakUntilMs
        if (until > 0L && System.currentTimeMillis() >= until) {
            annulerActive(context, config, "fin naturelle des 30 minutes", restantConnuMin = 0L)
        }
    }

    private fun annulerActive(
        context: Context,
        config: AdminConfig,
        motif: String,
        restantConnuMin: Long? = null,
    ) {
        val restantMin = restantConnuMin ?: ((config.careBreakUntilMs - System.currentTimeMillis()) / 60_000L)
        val débutaitÀ = config.careBreakStartedAtMs
        config.careBreakUntilMs = 0L
        config.careBreakStartedAtMs = 0L
        CallTrace.record("PAUSE APPELS levée", "$motif — il restait $restantMin min")
        // Journalisée seulement maintenant, l'intervalle entier déjà connu :
        // contrairement à l'éveil de l'écran, une pause n'a pas besoin de
        // rattrapage périodique (voir UsageStats.noteCareBreak).
        if (débutaitÀ > 0L) UsageStats.noteCareBreak(débutaitÀ, System.currentTimeMillis())
        AnnoncesVocales.jouer(context, AnnoncesVocales.Annonce.APPELS_AUTORISÉS)
        onChange?.invoke()
    }

    /** Ce que le bouton de la tablette et le panneau d'administration affichent. */
    fun décrire(context: Context): String {
        val config = AdminConfig(context)
        if (!config.isCareBreakActive()) return "appels possibles"
        val restantMin = (config.careBreakUntilMs - System.currentTimeMillis()) / 60_000L
        return "appels bloqués encore $restantMin min"
    }
}
