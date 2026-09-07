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
     * Tout sur le moteur embarqué : gratuit, hors-ligne, et indépendant d'un
     * service en ligne qui pourrait tomber au mauvais moment. L'appelant peut
     * demander AssemblyAI pour son appel s'il trouve le texte insuffisant
     * (voir TranscriptionEngine.setCallEngineOverride) — une dépense décidée
     * cas par cas, par celui qui lit le texte, plutôt que subie en permanence.
     */
    AUTO("auto", "Automatique (tout sur le moteur embarqué)"),

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
