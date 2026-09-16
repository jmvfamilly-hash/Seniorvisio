package com.seniorvisio.core

import java.text.Normalizer

/**
 * Reconnaît les trois mots que Jean peut dire à sa tablette.
 *
 * ═══ AUCUN MOTEUR DE PLUS ═══
 *
 * Rien n'écoute ici. Cet objet reçoit le texte que la transcription de la
 * pièce produit déjà en permanence (voir RoomPresenceService) et y cherche
 * trois mots. Ouvrir une seconde capture audio pour trois mots aurait doublé
 * la consommation du micro, et surtout créé un second usage concurrent du
 * microphone — exactement le défaut qui rendait les sous-titres peu fiables
 * avant qu'on ne les ramène à une capture unique.
 *
 * ═══ UN MOT SEUL, ET SEULEMENT SEUL ═══
 *
 * Une commande n'est reconnue que si la phrase transcrite ne contient QUE ça,
 * aux formules de politesse près. « Suivant » déclenche ; « je me demande ce
 * qui vient ensuite, le suivant peut-être » ne déclenche pas.
 *
 * C'est une décision, pas une limite technique. Ces trois mots sont courants
 * dans une conversation ordinaire, et cette tablette écoute une pièce où des
 * gens parlent des heures par jour. Une détection permissive ferait défiler
 * les titres pendant les visites et éteindrait l'écran au milieu d'une phrase
 * — sans que Jean puisse relier l'effet à sa cause, ni personne deviner
 * pourquoi. Le prix est qu'il faut dire le mot nettement ; c'est le bon prix.
 *
 * ═══ JAMAIS LE SEUL CHEMIN ═══
 *
 * Les trois commandes doublent des boutons présents à l'écran. Les consignes
 * d'accessibilité retenues pour ce projet l'imposent pour le glissement du
 * doigt, et la raison vaut identiquement ici : une fonction qui n'existerait
 * qu'à la voix serait invisible, et perdue le jour où la reconnaissance
 * bronche — ce qui arrive.
 */
object CommandesVocales {

    enum class Commande { SUIVANT, PRECEDENT, SOMMEIL }

    /**
     * Les formulations acceptées.
     *
     * Plusieurs par commande, parce que personne ne dit exactement le mot
     * prévu : on dit « la suivante » devant une photo et « suivant » devant un
     * titre. Écrites sans accent ni ponctuation, comme le texte normalisé
     * auquel elles sont comparées.
     */
    private val FORMULATIONS = mapOf(
        Commande.SUIVANT to setOf(
            "suivant", "suivante", "la suivante", "le suivant", "apres", "suite",
        ),
        Commande.PRECEDENT to setOf(
            "precedent", "precedente", "la precedente", "le precedent",
            "avant", "retour", "revenir",
        ),
        Commande.SOMMEIL to setOf(
            "sommeil", "dodo", "bonne nuit", "eteins", "eteindre", "extinction",
        ),
    )

    /**
     * Ce qu'on retire avant de comparer : les mots qui entourent une commande
     * sans la changer. « Tablette, suivant s'il te plaît » vaut « suivant ».
     */
    private val POLITESSES = listOf(
        "s il te plait", "s il vous plait", "stp", "svp", "merci",
        "tablette", "senior visio", "seniorvisio", "voila", "alors",
    )

    /**
     * La commande contenue dans cette phrase, ou null.
     *
     * @param texte une phrase transcrite, telle que le moteur la rend.
     */
    fun détecter(texte: String?): Commande? {
        val nettoyé = normaliser(texte ?: return null)
        if (nettoyé.isEmpty()) return null
        // Les politesses sont retirées AVANT la comparaison, et un seul passage
        // suffit : deux formules dans la même phrase — « tablette, suivant
        // s'il te plaît » — se retirent toutes les deux ici.
        var réduit = nettoyé
        for (mot in POLITESSES) réduit = réduit.replace(mot, " ")
        réduit = réduit.replace(Regex("\\s+"), " ").trim()
        if (réduit.isEmpty()) return null

        for ((commande, formulations) in FORMULATIONS) {
            if (réduit in formulations) return commande
        }
        return null
    }

    /**
     * Minuscules, sans accent, sans ponctuation.
     *
     * Les accents sautent parce que les moteurs de reconnaissance ne les
     * rendent pas tous de la même façon : « précédent » arrive tantôt accentué,
     * tantôt non, selon le moteur réglé par l'administrateur. Comparer des
     * formes accentuées ferait dépendre les commandes du choix du moteur, ce
     * qui se manifesterait par « ça ne marche plus » après un réglage sans
     * rapport apparent.
     */
    private fun normaliser(texte: String): String =
        Normalizer.normalize(texte.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
