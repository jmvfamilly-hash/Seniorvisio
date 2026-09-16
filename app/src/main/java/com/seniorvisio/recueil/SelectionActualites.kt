package com.seniorvisio.recueil

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Choisit les trente titres du jour parmi plusieurs fils d'information.
 *
 * ═══ AUCUN ÉTAT, POUR LA MÊME RAISON QUE LE CADENCEUR ═══
 *
 * On lui donne des listes de titres et une date, elle répond une liste. Rien
 * n'est retenu entre deux appels. C'est ce qui permet de la relire entièrement
 * d'un coup d'œil, et surtout de la VÉRIFIER en l'exécutant — ce qui a été
 * fait, comme pour CadenceurActualites, en la portant à l'identique et en
 * comparant les sorties sur des cas construits à la main.
 *
 * ═══ POURQUOI « LE JOUR » ET PAS « LES PLUS RÉCENTS » ═══
 *
 * L'administrateur a demandé un remplacement complet une fois par jour, et un
 * filtrage sur la date du jour. Les deux vont ensemble : sans le filtre, un fil
 * peu actif remonterait ses articles de la semaine dernière au même rang que
 * ceux de ce matin, et Jean lirait « hier » toute la journée sans que rien ne
 * le lui dise. Un titre d'actualité sans sa date est une affirmation au
 * présent ; il vaut mieux en montrer moins que d'en montrer de périmés.
 */
object SelectionActualites {

    /** Ce que la tablette affichera au plus dans une journée. */
    const val MAXIMUM = 30

    /**
     * @param parFlux une liste de titres PAR FIL, dans l'ordre où les fils sont
     *   configurés. L'ordre compte : c'est lui qui départage à égalité de date.
     * @param jour la date locale de la tablette — pas une date UTC. Un article
     *   paru à 23 h 30 à Paris est du 16 pour Jean, quelle que soit l'heure de
     *   Greenwich à ce moment-là.
     */
    fun choisir(
        parFlux: List<List<FluxRss.Titre>>,
        jour: LocalDate,
        zone: ZoneId,
        maximum: Int = MAXIMUM,
    ): List<FluxRss.Titre> {
        // ── 1. Ne garder que le jour demandé ──────────────────────────
        //
        // Un article sans date lisible est écarté : il ne peut pas prouver
        // qu'il est d'aujourd'hui. Le compte des écartés est rendu à part (voir
        // écartésFauteDeDate) pour qu'un fil entier qui disparaîtrait faute de
        // dates se voie dans le journal au lieu de s'évaporer.
        val retenus = parFlux.map { flux ->
            flux.filter { it.date != null && estDuJour(it.date, jour, zone) }
        }

        // ── 2. Regrouper par date, du plus récent au plus ancien ──────
        //
        // Les titres sont étiquetés de leur rang de fil AVANT le tri : c'est
        // cette étiquette qui permettra de répartir à égalité de date, une fois
        // les fils mélangés.
        val étiquetés = retenus.flatMapIndexed { rangDuFlux, flux ->
            flux.map { rangDuFlux to it }
        }
        val parDate = étiquetés.groupBy { it.second.date }
            .toSortedMap(compareByDescending { it })

        // ── 3. À égalité de date, répartir entre les fils ─────────────
        //
        // ═══ CE CAS N'EST PAS UN DÉTAIL THÉORIQUE ═══
        //
        // Beaucoup de fils ne datent leurs articles qu'à la minute, certains
        // seulement au jour. Les égalités ne sont donc pas des exceptions : ce
        // sont parfois TOUS les articles. Sans cette répartition, le premier
        // fil de la liste fournirait les trente titres et les autres ne
        // serviraient jamais à rien — un défaut qui ne se verrait qu'en
        // comparant les titres affichés aux fils configurés, c'est-à-dire
        // jamais.
        val choisis = mutableListOf<FluxRss.Titre>()
        for ((_, groupe) in parDate) {
            for (titre in répartir(groupe)) {
                choisis += titre
                if (choisis.size >= maximum) return choisis
            }
        }
        return choisis
    }

    /**
     * Combien de titres ont été écartés faute de date lisible, par fil.
     *
     * Séparé de [choisir] plutôt que rendu avec : la sélection répond une
     * liste de titres, et lui faire porter en plus un diagnostic aurait obligé
     * tous ses appelants à connaître une structure de retour composite pour une
     * information dont un seul se sert.
     */
    fun écartésFauteDeDate(parFlux: List<List<FluxRss.Titre>>): List<Int> =
        parFlux.map { flux -> flux.count { it.date == null } }

    /**
     * Un titre de chaque fil, à tour de rôle, jusqu'à épuisement.
     *
     * Les fils vides sont sautés sans décaler les autres : trois fils dont le
     * deuxième n'a rien donnent A, C, A, C… et non A, C, C, A.
     */
    private fun répartir(groupe: List<Pair<Int, FluxRss.Titre>>): List<FluxRss.Titre> {
        val paniers = groupe.groupBy({ it.first }, { it.second })
            .toSortedMap()
            .values
            .map { it.toMutableList() }
        val sortie = mutableListOf<FluxRss.Titre>()
        while (paniers.any { it.isNotEmpty() }) {
            for (panier in paniers) {
                if (panier.isNotEmpty()) sortie += panier.removeAt(0)
            }
        }
        return sortie
    }

    private fun estDuJour(date: Instant, jour: LocalDate, zone: ZoneId): Boolean =
        date.atZone(zone).toLocalDate() == jour
}
