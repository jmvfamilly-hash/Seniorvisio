package com.seniorvisio.core

/**
 * Quel moteur de reconnaissance vocale utiliser pour une source donnée (voir
 * SpeechRecognizer). Réglable séparément pour la pièce et pour les appels, et
 * modifiable à distance en cours de route (voir DeviceStatusReporter) : c'est
 * la seule façon de comparer honnêtement deux moteurs, en les faisant écouter
 * la même voix dans la même pièce à quelques secondes d'intervalle.
 */
enum class TranscriptionEngineChoice(
    val remoteValue: String,
    val adminLabel: String,
    /**
     * Facturé au temps de connexion, et non aux mots.
     *
     * Déclaré ici plutôt que testé moteur par moteur : la coupure des sessions
     * au silence, qui est ce qui empêche la facture d'enfler pendant qu'une
     * pièce est vide, ne visait qu'AssemblyAI nommément. Un second service
     * payant y aurait échappé en silence — et le seul symptôme aurait été une
     * facture, un mois plus tard.
     */
    val billedByDuration: Boolean = false,
) {
    /**
     * Pour les appels : Gladia d'abord, secours AssemblyAI si Gladia est
     * indisponible ou à plafond (voir TranscriptionEngine.createRecognizerFor)
     * — pour le moment, le temps de comparer les deux services. L'ordre
     * n'est pas gravé : il se change ici, à un seul endroit, quand la
     * comparaison sera tranchée. L'administrateur reste libre de forcer
     * l'un ou l'autre sans passer par ce défaut.
     *
     * Sans objet pour la pièce, qui n'écoute plus qu'avec la reconnaissance
     * d'Android (voir ANDROID, AdminConfig.roomEngine) : un service facturé à
     * la durée n'a rien à faire sur un flux permanent, écouté des heures par
     * jour.
     */
    AUTO("auto", "Automatique (Gladia, secours AssemblyAI)"),

    ASSEMBLYAI("assemblyai", "AssemblyAI (en ligne, payant à la durée)", billedByDuration = true),

    /**
     * Alternative à AssemblyAI, pas son remplaçant (voir
     * GladiaStreamingTranscriber). Les deux se règlent séparément pour la
     * pièce et pour les appels : c'est la seule façon de les comparer
     * honnêtement, sur la même voix dans la même pièce à quelques minutes
     * d'intervalle. Même nature de coût, donc même prudence — un service
     * facturé à la durée n'a rien à faire sur une pièce écoutée toute la
     * journée sans qu'on l'ait décidé.
     */
    GLADIA("gladia", "Gladia (en ligne, payant à la durée)", billedByDuration = true),

    /**
     * La reconnaissance vocale d'Android lui-même (voir AndroidSpeechSession).
     * Gratuite, déjà installée, sans rien à télécharger ni à charger en
     * mémoire — c'est ce qui en fait le seul moteur retenu pour la pièce
     * (voir AdminConfig.roomEngine), depuis le retrait de Vosk : un modèle
     * hors-ligne de 1,4 Go a fini par mettre une tablette à genoux (mémoire
     * saturée, appel coupé en plein milieu) sur un usage qui n'a jamais eu
     * besoin d'un modèle aussi lourd.
     *
     * Contrainte qui n'est pas la nôtre : son API n'écoute que le micro, on ne
     * peut pas lui donner un flux audio. Elle ne vaut donc QUE pour la pièce —
     * un appel arrive par WebRTC, jamais par le micro. Choisie pour un appel,
     * le moteur le signale et retombe sur Gladia (voir
     * TranscriptionEngine.createRecognizerFor).
     */
    ANDROID("android", "Reconnaissance Android (pièce seulement)");

    companion object {
        fun fromRemoteValue(value: String?): TranscriptionEngineChoice? =
            entries.firstOrNull { it.remoteValue == value }
    }
}
