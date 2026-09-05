package com.seniorvisio.core

/**
 * Les trois zones empilées sur l'écran de Jean (voir HomeZonesController),
 * de haut en bas par défaut. Leur ordre est réglable par l'admin (voir
 * AdminConfig.zoneOrder) : le bon ordre dépend de la position de la tablette
 * et de la façon dont Jean la regarde (assis, couché, tablette en hauteur),
 * impossible à figer une fois pour toutes depuis ici.
 *
 * Le libellé sert au choix de l'ordre dans le panneau admin — Jean, lui, ne
 * voit jamais ces noms : les zones s'identifient par leur contenu.
 */
enum class HomeZone(val adminLabel: String) {
    /** Date du jour, moment de la journée et météo — le fond de commerce de l'écran, toujours affiché. */
    INFO("Date et météo"),

    /** Ce qui se dit dans la pièce, capté par le micro de la tablette. */
    ROOM("Paroles dans la pièce"),

    /** Ce que dit la personne au bout de l'appel, distant ou dans la même pièce. */
    CALL("Appel");

    companion object {
        /**
         * Ordre appliqué tant que l'admin n'a rien changé : l'information
         * permanente en haut (elle est toujours là, donc elle ne fait jamais
         * sauter le reste en apparaissant), puis la pièce, puis l'appel — le
         * plus bas étant le plus proche du regard quand la tablette est posée
         * sur une table devant Jean.
         */
        val DEFAULT_ORDER = listOf(INFO, ROOM, CALL)

        /**
         * Les six ordres possibles, proposés tels quels dans le panneau admin
         * plutôt que trois listes à combiner : aucun réglage incohérent n'est
         * atteignable (deux zones au même endroit, une zone nulle part), donc
         * rien à valider ni à rattraper côté saisie.
         */
        fun allOrders(): List<List<HomeZone>> = listOf(
            listOf(INFO, ROOM, CALL),
            listOf(INFO, CALL, ROOM),
            listOf(ROOM, INFO, CALL),
            listOf(ROOM, CALL, INFO),
            listOf(CALL, INFO, ROOM),
            listOf(CALL, ROOM, INFO),
        )
    }
}
