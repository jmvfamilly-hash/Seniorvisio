package com.vangoghtimeline.model

import kotlin.math.abs
import kotlin.math.floor

/** Une carte visible, vue du centre de l'écran : [offsetX] = écart horizontal entre son centre et celui de l'écran (frise « à plat »). */
data class FocusCandidate(val id: String, val offsetX: Float, val y: Float)

/**
 * Quelles cartes sont au **sommet du rouleau** : celles qui font face à l'utilisateur, au centre de l'écran. Ce sont elles dont
 * on préchauffe l'image entière : l'utilisateur est sur le point de s'y arrêter, et un toucher est probable.
 *
 * Hystérésis : une carte ENTRE quand son centre est à moins de [enterFraction] largeur de carte du centre, et ne SORT que
 * au-delà de [leaveFraction] : pendant un défilement lent, une carte à la limite ne charge/libère pas en boucle. Au plus
 * [maxActive] à la fois (la mémoire d'une image préchauffée se compte en Mo) ; les cartes déjà actives sont gardées en priorité.
 *
 * Pur Kotlin : testé sur la JVM.
 */
class RollerTopPolicy(
    private val enterFraction: Float = 0.5f,
    private val leaveFraction: Float = 0.9f,
    private val maxActive: Int = 3,
) {
    init { require(enterFraction in 0f..leaveFraction && maxActive > 0) { "paramètres invalides" } }

    private val active = LinkedHashSet<String>()

    /** [active] : ce qui doit être chaud ; [started] et [released] : ce qui vient d'y entrer / d'en sortir. */
    class Change(val active: List<String>, val started: List<String>, val released: List<String>)

    fun update(candidates: List<FocusCandidate>, cardWidth: Float): Change {
        val byId = candidates.associateBy { it.id }
        val keep = active.mapNotNull { byId[it] }
            .filter { abs(it.offsetX) <= leaveFraction * cardWidth }
            .sortedBy { abs(it.offsetX) }
        val keepIds = keep.mapTo(HashSet()) { it.id }
        val entering = candidates
            .filter { it.id !in keepIds && abs(it.offsetX) <= enterFraction * cardWidth }
            .sortedWith(compareBy({ abs(it.offsetX) }, { it.y }))
        val next = (keep + entering).take(maxActive).map { it.id }
        val started = next.filter { it !in active }
        val released = active.filter { it !in next }
        active.clear(); active.addAll(next)
        return Change(next, started, released)
    }

    /** Oublie tout (ex. changement de frise) : la prochaine mise à jour repart de zéro. */
    fun reset() = active.clear()
}

/**
 * Ordre de chargement des vignettes de ce qui sera visible au prochain défilement : d'abord celles qui font face à l'utilisateur
 * (les plus proches du centre, par tranches d'une largeur de carte), et dans une tranche, de haut en bas.
 */
object NextScrollOrder {
    fun order(items: List<PlacedArtwork>, centerX: Float): List<PlacedArtwork> =
        items.sortedWith(
            compareBy<PlacedArtwork>(
                { floor(abs(it.x + it.width / 2f - centerX) / it.width) },
                { it.y },
                { abs(it.x + it.width / 2f - centerX) },
            ),
        )

    /**
     * Zone de la frise (abscisses du contenu) à préparer : large dans le sens du défilement ([direction] > 0 : vers la droite,
     * < 0 : vers la gauche, 0 : à l'arrêt, des deux côtés), étroite derrière.
     */
    fun zone(scrollX: Float, viewportWidth: Float, direction: Int): Pair<Float, Float> {
        val ahead = 1.8f * viewportWidth
        val behind = 1.0f * viewportWidth
        val left = scrollX - if (direction < 0) ahead else behind
        val right = scrollX + viewportWidth + if (direction > 0) ahead else behind
        return left to right
    }
}
