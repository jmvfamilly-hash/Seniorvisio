package com.vangoghtimeline.model

import kotlin.math.max

/**
 * Échelle de l'axe X : **1 pixel = [daysPerPixel] jours**. Travaille en jours entiers (voir [CivilCalendar]),
 * donc une œuvre datée au jour près tombe au pixel près.
 */
data class TimeScale(val originEpochDay: Long, val daysPerPixel: Float) {
    init { require(daysPerPixel > 0f) { "daysPerPixel doit être > 0" } }

    /** Abscisse (px, relative à l'origine) d'un jour. */
    fun xOf(epochDay: Long): Float = ((epochDay - originEpochDay) / daysPerPixel.toDouble()).toFloat()

    /** Jour (fractionnaire) situé à l'abscisse [x]. */
    fun dayAt(x: Float): Double = originEpochDay + x * daysPerPixel.toDouble()

    fun pixelsPerDay(): Float = 1f / daysPerPixel

    fun withDaysPerPixel(value: Float) = copy(daysPerPixel = value)
}

/** Dimensions d'une carte, en pixels. [gapX]/[gapY] : espace minimal entre deux cartes d'un même couloir / de deux couloirs. */
data class CardSpec(val width: Float, val height: Float, val gapX: Float, val gapY: Float)

/** Une œuvre placée, en coordonnées du CONTENU (l'origine est le coin haut-gauche de la frise complète). */
data class PlacedArtwork(
    val artwork: Artwork,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val lane: Int,
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/**
 * Résultat de la mise en page : toutes les œuvres placées, triées par abscisse croissante.
 *
 * @property leftInset abscisse du jour d'origine dans le contenu (la première carte, centrée sur sa date, ne déborde pas à gauche).
 */
class TimelinePlan(
    val scale: TimeScale,
    val items: List<PlacedArtwork>,
    val leftInset: Float,
    val contentWidth: Float,
    val contentHeight: Float,
    val laneCount: Int,
    private val cardWidth: Float,
) {
    /** Abscisse dans le contenu du jour [epochDay] (pour l'axe du temps). */
    fun contentXOf(epochDay: Long): Float = leftInset + scale.xOf(epochDay)

    /**
     * Œuvres qui touchent le rectangle [left, right] × [top, bottom] (coordonnées du contenu).
     *
     * Les cartes ont toutes la même largeur et [items] est trié par `x` : on trouve la première candidate par
     * recherche dichotomique (O(log n)) et on s'arrête dès que `x` dépasse le bord droit. Le filtre vertical ne parcourt
     * donc que la bande de temps visible.
     */
    fun visible(left: Float, top: Float, right: Float, bottom: Float): List<PlacedArtwork> {
        if (items.isEmpty()) return emptyList()
        var lo = 0
        var hi = items.size
        val minX = left - cardWidth // une carte d'abscisse x touche [left, …] si x + largeur > left
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].x <= minX) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<PlacedArtwork>()
        var i = lo
        while (i < items.size && items[i].x < right) {
            val p = items[i]
            if (p.bottom > top && p.y < bottom) out += p
            i++
        }
        return out
    }
}

/**
 * Mise en page de la frise.
 *
 * - **Axe X** : la date (au jour près) via [TimeScale]. La carte est centrée sur sa date.
 * - **Axe Y** : répartition en « couloirs » (lanes). Les œuvres sont parcourues par date croissante ; chacune va dans
 *   le premier couloir où sa carte ne chevauche pas la précédente (algorithme glouton d'emballage d'intervalles, optimal
 *   en nombre de couloirs pour des cartes de même largeur). Deux œuvres proches dans le temps ne se cachent donc jamais,
 *   et le nombre de couloirs s'adapte au zoom : zoomer sépare les œuvres, dézoomer les empile.
 */
object TimelineEngine {

    fun layout(
        artworks: List<Artwork>,
        daysPerPixel: Float,
        card: CardSpec,
        margin: Float = 48f,
        originEpochDay: Long? = null,
    ): TimelinePlan {
        val sorted = artworks.sortedWith(compareBy({ it.date.positionEpochDay }, { it.id }))
        val origin = originEpochDay ?: sorted.firstOrNull()?.date?.positionEpochDay ?: 0L
        val scale = TimeScale(origin, daysPerPixel)
        val leftInset = card.width / 2f + margin

        val laneEnds = ArrayList<Float>()                 // bord droit de la dernière carte de chaque couloir
        val placed = ArrayList<PlacedArtwork>(sorted.size)
        for (art in sorted) {
            val x = leftInset + scale.xOf(art.date.positionEpochDay) - card.width / 2f
            var lane = laneEnds.indexOfFirst { it + card.gapX <= x }
            if (lane < 0) { laneEnds += 0f; lane = laneEnds.lastIndex }
            laneEnds[lane] = x + card.width
            placed += PlacedArtwork(art, x, margin + lane * (card.height + card.gapY), card.width, card.height, lane)
        }
        val lanes = max(1, laneEnds.size)
        val contentW = (placed.lastOrNull()?.right ?: 0f) + margin
        val contentH = margin * 2 + lanes * card.height + (lanes - 1) * card.gapY
        return TimelinePlan(scale, placed, leftInset, contentW, contentH, lanes, card.width)
    }
}
