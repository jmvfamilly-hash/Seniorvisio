package com.seniorvisio.oeuvres

/**
 * La taille du cadre où une œuvre s'affiche chez Jean, publiée vers le PWA.
 *
 * ═══ POURQUOI LA TABLETTE DOIT LE DIRE, ET NON LE PWA LE SUPPOSER ═══
 *
 * Les vues d'une exposition sont découpées dans le navigateur, avant d'être
 * téléversées (voir le panneau Exposition du PWA). Il faut donc une taille de
 * découpe, et la première idée — 1920 × 1080, la dalle — était fausse deux
 * fois.
 *
 * D'abord parce que la dalle n'est pas connue : celle de Jean et celle du banc
 * d'essai n'ont pas la même définition, et une constante aurait été juste sur
 * l'une et fausse sur l'autre, sans que rien ne le signale.
 *
 * Ensuite et surtout parce que l'œuvre n'occupe PAS l'écran. Elle occupe la
 * rangée comprise entre la bande d'information et les boutons de navigation —
 * exactement l'emplacement d'un titre d'actualité. Découper à la taille de
 * l'écran aurait donc produit des images deux à trois fois trop grandes, pour
 * les réduire ensuite à l'affichage : du poids téléversé, du disque occupé et
 * de la mémoire dépensée, tout cela pour rien.
 *
 * ═══ MESURÉ, ET NON CALCULÉ ═══
 *
 * La hauteur de cette rangée dépend des poids de la pile, des marges, de la
 * hauteur réelle du bandeau de date et de celle des boutons — laquelle dépend
 * de la police choisie par l'administrateur. La recalculer côté PWA
 * reviendrait à réécrire la mise en page dans un second langage, et à la voir
 * diverger au premier ajustement.
 *
 * L'écran d'accueil mesure donc sa propre rangée une fois disposée, et la
 * dépose ici. Le signe de vie la publie avec le reste.
 */
object CadreOeuvre {

    @Volatile private var largeur = 0
    @Volatile private var hauteur = 0

    /**
     * Appelé par l'écran d'accueil quand la rangée a été disposée.
     *
     * Les valeurs nulles sont écartées : une vue non encore mesurée rend zéro,
     * et publier un cadre de zéro pixel ferait découper des images vides — une
     * panne qui ne se verrait qu'à l'arrivée sur la tablette, très loin de sa
     * cause.
     */
    fun noter(largeurPx: Int, hauteurPx: Int) {
        if (largeurPx <= 0 || hauteurPx <= 0) return
        largeur = largeurPx
        hauteur = hauteurPx
    }

    /** Ce que le signe de vie publie, ou null tant que rien n'a été mesuré. */
    fun publiable(): Map<String, Any>? {
        val l = largeur
        val h = hauteur
        if (l <= 0 || h <= 0) return null
        return mapOf("largeur" to l, "hauteur" to h)
    }
}
