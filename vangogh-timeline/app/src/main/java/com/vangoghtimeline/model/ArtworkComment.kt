package com.vangoghtimeline.model

/**
 * Le commentaire d'une œuvre affiché au niveau 3 de la frise : le texte descriptif du musée s'il en fournit un (Description, Inscriptions…),
 * sinon une phrase faite des seuls faits connus (technique, dimensions, collection, crédit). Rien n'est inventé : sans aucun fait, une chaîne vide.
 */
fun commentOf(a: Artwork): String {
    val named = listOf("Description", "Inscriptions", "Inscription", "Commentaire")
    a.details.firstOrNull { it.first in named }?.let { return it.second }
    val facts = buildList {
        (a.details.firstOrNull { it.first == "Technique" }?.second ?: a.medium)?.let { add(it) }
        a.details.firstOrNull { it.first == "Dimensions" }?.let { add(it.second) }
        a.details.firstOrNull { it.first == "Crédit" || it.first == "Credit" }?.let { add(it.second) }
    }
    return facts.joinToString(". ").let { if (it.isEmpty()) it else it.trimEnd('.') + "." }
}
