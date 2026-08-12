package com.seniorvisio.core

/**
 * Contrat pour le moteur d'appel vidéo. Isole toute dépendance à un SDK
 * précis (WebRTC brut, Agora, Stream, Twilio...) pour pouvoir en changer
 * si l'un s'avère instable ou trop complexe à maintenir.
 */
interface CallEngine {

    /** Prépare une session entrante (sans démarrer le flux audio/vidéo). */
    fun prepareIncomingCall(callerId: String, onReady: () -> Unit, onError: (Throwable) -> Unit)

    /** Démarre effectivement le flux (déclenché par la présence détectée). */
    fun answer()

    /** Termine l'appel en cours. */
    fun hangUp()

    /** État courant, utile pour l'UI et les logs. */
    val state: CallState

    val engineName: String
}

enum class CallState {
    IDLE, RINGING_SILENT, RINGING_LOUD, CONNECTING, ACTIVE, ENDED, ERROR
}
