package com.seniorvisio.core

/**
 * Ce que doit savoir faire un moteur de reconnaissance vocale pour être
 * utilisable ici : démarrer, avaler du son, s'arrêter. Rien d'autre.
 *
 * Deux implémentations, choisies selon la source du son (voir
 * TranscriptionEngine) :
 *
 *  - AssemblyAiRealtimeTranscriber pour les appels — service distant, payant
 *    à la durée, mais nettement plus juste sur une voix quelconque ;
 *  - VoskSpeechRecognizer pour la pièce — embarqué, gratuit, hors-ligne, au
 *    prix d'une transcription plus approximative.
 *
 * Ce partage n'est pas un compromis mou mais une réponse à deux problèmes
 * différents. La pièce est écoutée potentiellement des heures par jour :
 * facturée à la durée, elle coûterait une centaine d'euros par mois. Les
 * appels, eux, sont ponctuels et c'est là que la justesse du texte se voit le
 * plus — c'est ce que Jean lit à la place d'entendre.
 */
interface SpeechRecognizer {

    /**
     * Ouvre une session. `onText` reçoit le texte au fil de l'eau, `isFinal`
     * distinguant une version encore révisable d'une phrase close. `onError`
     * remonte ce qui empêche la transcription de fonctionner, pour affichage
     * (voir CallSignalingClient.reportCaptionDebug et l'écran admin) — sans
     * accès au journal système de la tablette, c'est le seul moyen de savoir
     * qu'il se passe quelque chose.
     */
    fun start(onText: (text: String, isFinal: Boolean) -> Unit, onError: (String) -> Unit)

    /** Bloc de son brut (PCM 16 bits signé, petit-boutiste, entrelacé si plusieurs canaux). */
    fun accept(pcm16: ByteArray, sampleRate: Int, channels: Int)

    fun stop()
}
