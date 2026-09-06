package com.seniorvisio.core

/**
 * Quel moteur de reconnaissance vocale utiliser pour une source donnée (voir
 * SpeechRecognizer). Réglable séparément pour la pièce et pour les appels, et
 * modifiable à distance en cours de route (voir DeviceStatusReporter) : c'est
 * la seule façon de comparer honnêtement deux moteurs, en les faisant écouter
 * la même voix dans la même pièce à quelques secondes d'intervalle.
 */
enum class TranscriptionEngineChoice(val remoteValue: String, val adminLabel: String) {
    /**
     * Le partage par défaut, et celui qui a du sens la plupart du temps : la
     * pièce sur le moteur embarqué (écoutée des heures par jour, elle doit
     * être gratuite), les appels sur AssemblyAI (ponctuels, et c'est là que la
     * justesse du texte se voit le plus).
     */
    AUTO("auto", "Automatique (pièce : embarqué, appels : AssemblyAI)"),

    ASSEMBLYAI("assemblyai", "AssemblyAI (en ligne, payant à la durée)"),

    VOSK("vosk", "Vosk (embarqué, gratuit, hors-ligne)");

    companion object {
        fun fromRemoteValue(value: String?): TranscriptionEngineChoice? =
            entries.firstOrNull { it.remoteValue == value }
    }
}

/**
 * Taille du modèle français embarqué. Le petit tient en 45 Mo et suffit à de
 * la commande vocale ; le grand est nettement plus juste sur une conversation
 * captée à distance par le micro d'une tablette, mais pèse 1,4 Go.
 *
 * Les deux coexistent sur la tablette une fois téléchargés : basculer de l'un
 * à l'autre pour comparer ne re-télécharge rien.
 */
enum class VoskModelSize(
    val remoteValue: String,
    val adminLabel: String,
    val directoryName: String,
    val url: String,
) {
    SMALL(
        remoteValue = "small",
        adminLabel = "Petit (45 Mo, rapide, approximatif)",
        directoryName = "vosk-model-fr-small",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
    ),
    LARGE(
        remoteValue = "large",
        adminLabel = "Grand (1,4 Go, nettement plus juste)",
        directoryName = "vosk-model-fr-large",
        url = "https://alphacephei.com/vosk/models/vosk-model-fr-0.22.zip",
    );

    companion object {
        fun fromRemoteValue(value: String?): VoskModelSize? =
            entries.firstOrNull { it.remoteValue == value }
    }
}
