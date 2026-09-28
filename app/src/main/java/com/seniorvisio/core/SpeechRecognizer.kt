package com.seniorvisio.core

/**
 * Ce que doit savoir faire un moteur de reconnaissance vocale pour être
 * utilisable ici : démarrer, avaler du son, s'arrêter. Rien d'autre.
 *
 * Plusieurs implémentations, choisies selon la source du son (voir
 * TranscriptionEngine) :
 *
 *  - AssemblyAiRealtimeTranscriber et GladiaStreamingTranscriber pour les
 *    appels — services distants, payants à la durée, nettement plus justes
 *    sur une voix quelconque que ce qu'un modèle embarqué peut faire ;
 *  - AndroidSpeechSession pour la pièce — le moteur déjà installé par
 *    Android, gratuit, sans rien à télécharger ni à charger en mémoire. Il
 *    n'écoute que le micro et ne passe donc pas par cette interface pour un
 *    appel (voir TranscriptionEngineChoice.ANDROID) — retenu pour la pièce
 *    précisément pour cette contrainte, qui l'empêche de peser sur un flux
 *    écouté des heures par jour.
 *
 * Un modèle hors-ligne embarqué (Vosk) a longtemps tenu la place de la pièce
 * ici. Retiré du projet : 1,4 Go téléchargés et chargés en mémoire ont fini
 * par mettre une tablette modeste à genoux — mémoire saturée, appel coupé en
 * plein milieu — pour un gain que la reconnaissance Android, gratuite et déjà
 * là, rendait inutile.
 */
interface SpeechRecognizer {

    /**
     * Lequel des moteurs est réellement derrière.
     *
     * Déclaré plutôt que déduit du type. La déduction par type marche tant
     * qu'aucune enveloppe ne s'intercale ; elle devient fausse dès qu'on
     * interpose une file d'attente (voir BufferedSpeechRecognizer) et
     * silencieusement fausse au moteur suivant, qui tomberait dans le
     * « sinon ». Les conséquences ne se voient pas tout de suite : du temps
     * gratuit compté comme du temps facturé, ou l'inverse.
     */
    val engine: TranscriptionEngineChoice

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
