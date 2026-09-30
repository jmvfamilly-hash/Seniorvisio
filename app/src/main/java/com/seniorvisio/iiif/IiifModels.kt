package com.seniorvisio.iiif

/*
 * Couche 1 — Modèle du domaine.
 * Kotlin pur (aucun import Android/Compose) : compatible Compose Multiplatform (commonMain).
 */

/**
 * Sous-ensemble de l'`info.json` IIIF Image API 3.0 nécessaire au rendu en tuiles.
 *
 * @property baseUri URI de l'image, sans slash final (ex. `https://host/iiif/3/img01`).
 * @property scaleFactors Facteurs de réduction IIIF, triés croissants (ex. [1, 2, 4, 8, 16]).
 *   Un facteur `f` signifie qu'un pixel de tuile couvre `f × f` pixels de l'image source :
 *   1 = pleine résolution, 16 = la plus grossière.
 */
data class IiifImageInfo(
    val baseUri: String,
    val width: Int,
    val height: Int,
    val tileSize: Int = 256,
    val scaleFactors: List<Int> = listOf(1, 2, 4, 8, 16),
) {
    init {
        require(width > 0 && height > 0) { "Dimensions invalides: ${width}x$height" }
        require(tileSize > 0) { "tileSize doit être > 0" }
        require(scaleFactors.isNotEmpty() && scaleFactors.all { it > 0 }) { "scaleFactors invalides" }
        require(scaleFactors == scaleFactors.sorted()) { "scaleFactors doit être trié croissant" }
    }
}

/**
 * État de la caméra. Transformation image → écran :
 *
 *     screen = image * scale + translation
 *
 * - [scale] : pixels écran par pixel image (zoom). 1.0 = 100 %, 0.25 = image affichée 4× plus petite.
 * - [translationX]/[translationY] : position, en pixels écran, de l'origine (0,0) de l'image.
 */
data class ViewportState(
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
)

/** Rectangle en coordonnées image (pixels de l'image pleine résolution). Bornes: [left, right) × [top, bottom). */
data class IiifRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
    /** Segment `region` IIIF : `x,y,w,h`. */
    fun toIiifParam(): String = "$x,$y,$width,$height"
}

/**
 * Une tuile de la pyramide.
 *
 * @property scaleFactor niveau de zoom IIIF (1 = pleine résolution).
 * @property column colonne dans la grille de ce niveau.
 * @property row ligne dans la grille de ce niveau.
 * @property region zone couverte, en coordonnées de l'image pleine résolution
 *   (tronquée aux bords de l'image pour les tuiles de dernière ligne/colonne).
 */
data class Tile(
    val scaleFactor: Int,
    val column: Int,
    val row: Int,
    val region: IiifRegion,
) {
    /** Identifiant unique, stable : sert de clé de cache LRU et de clé de Job. */
    val id: String get() = "$scaleFactor/$column/$row"

    /** Largeur en pixels de la tuile décodée (`w,` du paramètre `size`), arrondie au supérieur. */
    val outputWidth: Int get() = ceilDiv(region.width, scaleFactor)
    val outputHeight: Int get() = ceilDiv(region.height, scaleFactor)

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
}
