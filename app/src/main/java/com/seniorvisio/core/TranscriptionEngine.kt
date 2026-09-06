package com.seniorvisio.core

import android.content.Context
import android.util.Log

/**
 * Le moteur de transcription : une source de son entre, du texte étiqueté par
 * sa source sort. Rien d'autre.
 *
 * C'est la pièce du milieu de la chaîne — source sonore, moteur, couche
 * d'affichage — et la seule qui parle à AssemblyAI. Les deux endroits qui
 * captent du son (le service qui écoute la pièce, le moteur d'appel WebRTC)
 * avaient jusqu'ici chacun leur copie de cette logique : création paresseuse
 * de la session, clé API, remontée d'erreur, arrêt. Les deux ont divergé au
 * moins une fois — le correctif sur la taille des blocs audio n'a été appliqué
 * qu'à l'une d'elles avant d'être remonté d'un cran. Une seule copie
 * maintenant.
 *
 * Une seule session AssemblyAI à la fois, jamais deux. Question de coût
 * — le service est facturé à la durée de connexion — mais surtout de sens :
 * deux textes qui arrivent en même temps de deux sources différentes
 * demanderaient à Jean de choisir lequel lire, ce qui est exactement ce qu'on
 * ne veut pas lui demander. Changer de source ferme la session en cours ; la
 * zone qui perd sa source s'efface alors d'elle-même, comme après un silence.
 */
class TranscriptionEngine(
    private val context: Context,
    /** Texte transcrit, accompagné de la source dont il provient. */
    private val onText: (source: TranscriptionSource, text: String, isFinal: Boolean) -> Unit,
    /**
     * Messages destinés à être montrés à celui qui teste (voir
     * CallSignalingClient.reportCaptionDebug) : arrivée effective du son,
     * clé API manquante, connexion perdue. Sans accès au journal système de la
     * tablette, c'est le seul moyen de savoir où ça bloque.
     */
    private val onDiagnostic: (String) -> Unit = {},
) {

    private var transcriber: AssemblyAiRealtimeTranscriber? = null
    @Volatile private var activeSource: TranscriptionSource? = null

    /** Sources dont l'arrivée de son a déjà été signalée, pour ne le dire qu'une fois chacune. */
    private val reportedSources = mutableSetOf<TranscriptionSource>()

    /**
     * Choisit la source à transcrire, ou `null` pour ne rien transcrire du
     * tout. Le son des autres sources continue d'arriver mais est ignoré (voir
     * [feed]) : c'est volontaire, ça évite de brancher et débrancher des sinks
     * audio en pleine conversation pour un simple changement d'avis.
     */
    fun setActiveSource(source: TranscriptionSource?) {
        if (activeSource == source) return
        activeSource = source
        // La session en cours écoutait autre chose : on la ferme, la suivante
        // s'ouvrira au premier bloc de la nouvelle source.
        stopSession()
    }

    fun activeSource(): TranscriptionSource? = activeSource

    /**
     * Bloc de son brut (PCM 16 bits) venant de [source]. Ignoré si ce n'est
     * pas la source active — les appelants peuvent donc alimenter le moteur
     * en continu depuis toutes leurs sources sans se soucier de laquelle
     * compte à cet instant.
     */
    fun feed(source: TranscriptionSource, pcm16: ByteArray, sampleRate: Int, channels: Int) {
        if (reportedSources.add(source)) {
            onDiagnostic("son ${label(source)} reçu (${sampleRate}Hz, ${channels}ch)")
        }
        if (source != activeSource) return

        val apiKey = AdminConfig(context).assemblyAiApiKey
        if (apiKey.isBlank()) {
            onDiagnostic("clé API AssemblyAI absente")
            return
        }
        val instance = transcriber ?: AssemblyAiRealtimeTranscriber(apiKey).also { created ->
            transcriber = created
            created.start(
                onText = { text, isFinal ->
                    // La source peut avoir changé pendant que ce texte
                    // arrivait : on l'étiquette avec celle qui l'a réellement
                    // produit, pas avec celle qui est active maintenant.
                    onText(source, text, isFinal)
                },
                onError = { message ->
                    Log.w(TAG, "AssemblyAI temps réel (${label(source)}) : $message")
                    onDiagnostic("AssemblyAI : $message")
                },
            )
        }
        instance.sendAudio(pcm16, sampleRate, channels)
    }

    /** Ferme tout : plus aucune source active, plus aucune session ouverte. */
    fun stop() {
        activeSource = null
        stopSession()
        reportedSources.clear()
    }

    private fun stopSession() {
        transcriber?.stop()
        transcriber = null
    }

    private fun label(source: TranscriptionSource) = when (source) {
        TranscriptionSource.ROOM -> "micro tablette"
        TranscriptionSource.CALL -> "appel"
    }

    companion object {
        private const val TAG = "TranscriptionEngine"
    }
}
