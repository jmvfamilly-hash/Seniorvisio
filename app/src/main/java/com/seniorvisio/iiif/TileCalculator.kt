package com.seniorvisio.iiif

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Couche 2 — Moteur de culling. Fonctions pures, sans état, testables sans UI.
 */
object TileCalculator {

    /**
     * Niveau IIIF à charger pour un zoom donné.
     *
     * Le zoom [scale] est en pixels écran / pixel image ; à ce zoom, un pixel écran
     * couvre `1 / scale` pixels image. Une tuile de facteur `f` fournit un pixel pour
     * `f` pixels image, elle est donc nette tant que `f <= 1 / scale`.
     * On prend le plus grand facteur qui respecte cette condition (le moins de tuiles
     * possible sans flou) ; si même `f = 1` est plus grossier que l'écran (zoom > 100 %),
     * on retombe sur le plus petit facteur (pleine résolution, upscalée par le GPU).
     *
     * Note : c'est le facteur IIIF « adjacent » du zoom, choisi du côté net (≤ 1/scale)
     * — choisir le côté supérieur produirait des tuiles floues.
     */
    fun idealScaleFactor(scale: Float, info: IiifImageInfo): Int {
        require(scale > 0f) { "scale doit être > 0" }
        val targetDownsample = 1f / scale
        return info.scaleFactors.lastOrNull { it <= targetDownsample } ?: info.scaleFactors.first()
    }

    /**
     * Tuiles strictement nécessaires pour remplir l'écran au niveau idéal.
     */
    fun calculateVisibleTiles(
        viewport: ViewportState,
        info: IiifImageInfo,
        screenWidth: Int,
        screenHeight: Int,
    ): List<Tile> =
        calculateTilesAtLevel(viewport, info, screenWidth, screenHeight, idealScaleFactor(viewport.scale, info))

    /**
     * Tuiles d'un niveau [scaleFactor] imposé qui intersectent l'écran.
     * Réutilisable pour le niveau N-1 (repli en cache) du rendu progressif.
     */
    fun calculateTilesAtLevel(
        viewport: ViewportState,
        info: IiifImageInfo,
        screenWidth: Int,
        screenHeight: Int,
        scaleFactor: Int,
    ): List<Tile> {
        if (screenWidth <= 0 || screenHeight <= 0 || viewport.scale <= 0f) return emptyList()

        // ── Projection écran → image ──────────────────────────────────────────────
        // Modèle direct (image → écran) :     s = p * scale + t
        // Modèle inverse (écran → image) :    p = (s - t) / scale
        // Sans rotation, l'image d'un rectangle écran est un rectangle image ; on projette
        // néanmoins les 4 coins pour que le code reste valable si une rotation s'ajoute :
        // la bounding box est alors le min/max des 4 coins projetés.
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        val corners = arrayOf(
            0f to 0f,
            screenWidth.toFloat() to 0f,
            screenWidth.toFloat() to screenHeight.toFloat(),
            0f to screenHeight.toFloat(),
        )
        corners.forEachIndexed { i, (sx, sy) ->
            xs[i] = (sx - viewport.translationX) / viewport.scale
            ys[i] = (sy - viewport.translationY) / viewport.scale
        }
        // Bounding box dans l'espace image, bornée à l'image (on ne charge rien hors image).
        val left = max(xs.min(), 0f)
        val top = max(ys.min(), 0f)
        val right = min(xs.max(), info.width.toFloat())
        val bottom = min(ys.max(), info.height.toFloat())
        if (right <= left || bottom <= top) return emptyList() // image entièrement hors écran

        // ── Intersection avec la grille ───────────────────────────────────────────
        // À l'échelle f, une tuile couvre span = tileSize * f pixels image :
        //   colonne c → x ∈ [c * span, (c+1) * span)
        // Colonnes touchées : de floor(left / span) à ceil(right / span) - 1
        // (borne droite exclusive : right pile sur une frontière n'ajoute pas de colonne).
        val span = info.tileSize * scaleFactor
        val columns = ceilDiv(info.width, span)
        val rows = ceilDiv(info.height, span)
        val colMin = floor(left / span).toInt().coerceIn(0, columns - 1)
        val colMax = (ceil(right / span).toInt() - 1).coerceIn(0, columns - 1)
        val rowMin = floor(top / span).toInt().coerceIn(0, rows - 1)
        val rowMax = (ceil(bottom / span).toInt() - 1).coerceIn(0, rows - 1)

        val tiles = ArrayList<Tile>((colMax - colMin + 1) * (rowMax - rowMin + 1))
        for (row in rowMin..rowMax) {
            for (col in colMin..colMax) {
                val x = col * span
                val y = row * span
                tiles += Tile(
                    scaleFactor = scaleFactor,
                    column = col,
                    row = row,
                    // Les tuiles de bord sont tronquées à l'image (exigence IIIF : région ⊂ image).
                    region = IiifRegion(x, y, min(span, info.width - x), min(span, info.height - y)),
                )
            }
        }
        return tiles
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
}
