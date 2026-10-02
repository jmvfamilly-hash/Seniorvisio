package com.vangoghtimeline.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Ce que le point d'intérêt désigne : un regard ou un visage, un arbre, ou (faute de mieux) le centre. */
enum class PoiKind { FACE, TREE, CENTER }

/**
 * Le fond du menu d'un artiste : UN tableau majeur, son image embarquée dans l'APK (`assets/backdrops/{artistId}.jpg`, téléchargée au build par
 * `tools/fetch_backdrops.py`) et son point d'intérêt, en fractions de l'image. [width] / [height] sont ceux de l'image embarquée (0 = image
 * pas encore connue : pas de fond, le menu retombe sur un dégradé).
 */
data class Backdrop(
    val artistId: String,
    val title: String,
    val date: String,
    val credit: String,
    val width: Int,
    val height: Int,
    val poiX: Float,
    val poiY: Float,
    val kind: PoiKind,
    /** Adresse de secours, utilisée seulement si l'image n'a pas pu être embarquée au build. */
    val remoteUrl: String,
) {
    val usable: Boolean get() = width > 0 && height > 0
    val assetUri: String get() = "file:///android_asset/backdrops/$artistId.jpg"
}

object BackdropIndex {
    fun parse(text: String): Map<String, Backdrop> {
        val arr: JsonArray = Json.parseToJsonElement(text).jsonObject["backdrops"]?.jsonArray ?: return emptyMap()
        return arr.map { it.jsonObject }.mapNotNull { o -> fromJson(o) }.associateBy { it.artistId }
    }

    private fun fromJson(o: JsonObject): Backdrop? {
        val id = o["artistId"]?.jsonPrimitive?.contentOrNull ?: return null
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        return Backdrop(
            artistId = id, title = str("title"), date = str("date"), credit = str("credit"),
            width = o["width"]?.jsonPrimitive?.intOrNull ?: 0, height = o["height"]?.jsonPrimitive?.intOrNull ?: 0,
            poiX = (o["poiX"]?.jsonPrimitive?.doubleOrNull ?: 0.5).toFloat().coerceIn(0f, 1f),
            poiY = (o["poiY"]?.jsonPrimitive?.doubleOrNull ?: 0.5).toFloat().coerceIn(0f, 1f),
            kind = when (str("kind")) { "face" -> PoiKind.FACE; "tree" -> PoiKind.TREE; else -> PoiKind.CENTER },
            remoteUrl = str("remoteUrl"),
        )
    }
}

/** Placement de l'image dans l'écran : taille affichée et position du coin haut gauche, en pixels. */
data class ThirdsPlacement(val imageW: Float, val imageH: Float, val offsetX: Float, val offsetY: Float, val exact: Boolean)

/**
 * Règle des tiers : met le point d'intérêt sur une ligne des tiers en largeur ET en hauteur (le regard sur la ligne du tiers supérieur, un arbre
 * sur la ligne la plus proche), l'image couvrant toujours tout l'écran. Si le point est trop près d'un bord pour que ce soit possible à la taille
 * qui couvre l'écran, l'image est agrandie juste assez, jusqu'à [maxZoom] fois la taille de couverture (au-delà : au plus près possible,
 * [ThirdsPlacement.exact] est faux).
 */
object ThirdsFit {
    fun fit(imgW: Int, imgH: Int, screenW: Float, screenH: Float, poiX: Float, poiY: Float, kind: PoiKind, maxZoom: Float = 2.2f): ThirdsPlacement {
        val third = 1f / 3f
        val tx = if (poiX < 0.5f) third else 2 * third
        val ty = if (kind == PoiKind.FACE) third else if (poiY < 0.5f) third else 2 * third
        val cover = maxOf(screenW / imgW, screenH / imgH)
        // échelle minimale pour que la cible soit atteignable sans laisser de vide (bord gauche <= 0 et bord droit >= écran, idem en hauteur)
        fun need(target: Float, p: Float, screen: Float, size: Int): Float {
            val a = if (p > 0f) target * screen / (p * size) else 0f
            val b = if (p < 1f) (1f - target) * screen / ((1f - p) * size) else 0f
            return maxOf(a, b)
        }
        val wanted = maxOf(cover, need(tx, poiX, screenW, imgW), need(ty, poiY, screenH, imgH))
        val s = wanted.coerceAtMost(cover * maxZoom)
        val w = imgW * s
        val h = imgH * s
        val ox = (tx * screenW - poiX * w).coerceIn(screenW - w, 0f)
        val oy = (ty * screenH - poiY * h).coerceIn(screenH - h, 0f)
        val exact = kotlin.math.abs(ox + poiX * w - tx * screenW) < 1f && kotlin.math.abs(oy + poiY * h - ty * screenH) < 1f
        return ThirdsPlacement(w, h, ox, oy, exact)
    }
}
