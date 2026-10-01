package com.iiifviewer

import kotlin.math.hypot
import kotlin.math.min

/**
 * Quelles tuiles précharger pour que l'ouverture d'une image soit instantanée : celles de la **vue d'arrivée** (image entière
 * à l'écran), c'est-à-dire le niveau le plus grossier (filet de sécurité) puis le niveau net de cette vue.
 *
 * Pur Kotlin : testé sur la JVM.
 */
object PrewarmPlanner {

    /** Viewport « image entière, centrée » : celui où [IiifZoomViewer] s'ouvre à `initialZoom = 1`. */
    fun fitViewport(info: IiifImageInfo, screenWidth: Int, screenHeight: Int): ViewportState {
        val s = min(screenWidth.toFloat() / info.width, screenHeight.toFloat() / info.height)
        return ViewportState(s, (screenWidth - info.width * s) / 2f, (screenHeight - info.height * s) / 2f)
    }

    /**
     * Tuiles à demander, DANS L'ORDRE : d'abord tout le niveau le plus grossier (quelques tuiles : de quoi afficher une image
     * floue tout de suite), puis le niveau net de la vue d'arrivée en partant du centre de l'écran.
     *
     * Le niveau net est celui de [TileCalculator.idealScaleFactor] ; s'il dépasse [budgetBytes] en mémoire (grande image, grand
     * écran), on prend un niveau plus grossier : mieux vaut une vue d'arrivée un peu douce que des dizaines de Mo gardés pour
     * une vignette que l'utilisateur n'ouvrira peut-être pas.
     */
    fun plan(info: IiifImageInfo, screenWidth: Int, screenHeight: Int, budgetBytes: Long): List<Tile> {
        if (screenWidth <= 0 || screenHeight <= 0) return emptyList()
        val vp = fitViewport(info, screenWidth, screenHeight)
        val base = info.scaleFactors.last()
        var level = TileCalculator.idealScaleFactor(vp.scale, info)
        var arrival = TileCalculator.calculateTilesAtLevel(vp, info, screenWidth, screenHeight, level)
        while (level < base && bytes(arrival) > budgetBytes) {
            level = info.scaleFactors.first { it > level }
            arrival = TileCalculator.calculateTilesAtLevel(vp, info, screenWidth, screenHeight, level)
        }
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        fun distance(t: Tile): Float {
            val r = t.region
            return hypot((r.x + r.width / 2f) * vp.scale + vp.translationX - cx, (r.y + r.height / 2f) * vp.scale + vp.translationY - cy)
        }
        return (TileCalculator.allTiles(info, base) + arrival.sortedBy(::distance)).distinctBy { it.id }
    }

    private fun bytes(tiles: List<Tile>): Long = tiles.sumOf { it.outputWidth.toLong() * it.outputHeight * 4 }
}
