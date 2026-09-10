package com.seniorvisio.core

/**
 * Lequel des deux moteurs répond à la question « est-ce Jean qui parle ».
 *
 * Deux moteurs et non un seul, exactement pour la raison qui a fait garder
 * AssemblyAI à côté de Gladia : c'est la seule façon de les comparer
 * honnêtement, sur la même voix dans la même pièce, à quelques minutes
 * d'intervalle. Un comparatif fait ailleurs ne dit rien de la voix de Jean,
 * de celles de ses proches, ni de l'acoustique de cette chambre-là.
 *
 * ═══ Ce que le choix entraîne, et qu'il ne faut pas manquer ═══
 *
 * Une signature apprise par l'un est **illisible** par l'autre : l'un range une
 * moyenne de coefficients acoustiques, l'autre un profil neuronal opaque. Elles
 * sont donc stockées séparément (voir AdminConfig.jeanVoiceSignature), ce qui a
 * une conséquence heureuse : basculer d'un moteur à l'autre ne détruit pas
 * l'apprentissage du premier, et revenir en arrière ne demande pas de refaire
 * parler Jean.
 *
 * Les seuils aussi sont séparés, et c'est plus subtil : les deux rendent bien
 * un nombre entre 0 et 1, mais l'un sort d'un réseau de neurones et l'autre
 * d'une moyenne pondérée entre hauteur de voix et timbre. Le même nombre n'y
 * veut pas dire la même chose. Un curseur unique aurait donc appliqué au second
 * moteur une exigence réglée pour le premier, ce dont le seul symptôme aurait
 * été une reconnaissance devenue absurde après un simple changement de moteur.
 */
enum class SpeakerEngineChoice(
    /** Valeur échangée avec le PWA. Ne jamais la changer : elle est stockée. */
    val remoteValue: String,
    val adminLabel: String,
    /** Ce moteur exige-t-il une clé d'accès pour démarrer ? */
    val requiresAccessKey: Boolean,
    /**
     * Ressemblance exigée par défaut, en pourcentage. Propre à chaque moteur,
     * puisque leurs scores ne sont pas comparables.
     */
    val defaultThresholdPercent: Int,
) {

    /**
     * Le comparateur écrit dans l'application : timbre et hauteur de voix (voir
     * VoiceFeatures). Gratuit, sans clé, sans réseau, quelques kilo-octets.
     * Très sûr contre une voix nettement différente de celle de Jean, faible
     * contre une voix masculine proche — mesuré à 0,70 pour un fils contre 0,85
     * pour Jean lui-même.
     *
     * Seuil par défaut volontairement haut : le coût d'une erreur n'est pas
     * symétrique. Afficher en clair une phrase de Jean ne coûte que de la
     * place ; atténuer celle d'un proche lui retire ce qu'il a besoin de lire.
     */
    EMBEDDED("embedded", "Intégré (timbre et hauteur)", requiresAccessKey = false, defaultThresholdPercent = 72),

    /**
     * Picovoice Eagle : reconnaissance de locuteur neuronale, exécutée sur
     * l'appareil. Environ six mégaoctets de modèle et de code natif, et une clé
     * d'accès à obtenir auprès de Picovoice.
     *
     * Rien ne sort de la tablette, ce qui n'est pas un détail pour un
     * microphone qui écoute une chambre du matin au soir : le seul échange avec
     * l'extérieur est la validation de la clé.
     *
     * Seuil par défaut plus bas que celui du moteur intégré, et ce n'est pas
     * une exigence moindre : c'est un autre barème. Il est fait pour être
     * réglé sur la mesure publiée au diagnostic, comme l'autre.
     */
    PICOVOICE("picovoice", "Picovoice Eagle (sur l'appareil)", requiresAccessKey = true, defaultThresholdPercent = 50),
    ;

    companion object {
        fun fromRemoteValue(value: String?): SpeakerEngineChoice? =
            entries.firstOrNull { it.remoteValue == value }
    }
}
