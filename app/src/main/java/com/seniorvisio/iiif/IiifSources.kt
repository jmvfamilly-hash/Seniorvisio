package com.seniorvisio.iiif

import androidx.compose.ui.graphics.ImageBitmap

/*
 * Frontières avec la plateforme (réseau + décodage). Le reste du visualiseur est
 * du Kotlin commun : en KMP, ces deux interfaces sont implémentées une fois par cible
 * (Ktor + decodeToImageBitmap, HttpURLConnection + BitmapFactory, NSURLSession...).
 */

/** Télécharge et décode une tuile. Doit être annulable : l'annulation de la coroutine doit couper la requête. */
fun interface TileImageSource {
    suspend fun load(url: String): ImageBitmap
}

/** Lit et interprète l'`info.json` IIIF. */
fun interface IiifInfoSource {
    suspend fun loadInfo(infoUrl: String): IiifImageInfo
}

interface IiifSources : TileImageSource, IiifInfoSource

/** Construction d'URL IIIF Image API 3.0 : `{baseUri}/{region}/{size}/0/default.jpg`. */
object IiifUrls {
    /** `size` = `w,` : largeur imposée, hauteur déduite en gardant le ratio (forme valide en v3). */
    fun tile(info: IiifImageInfo, tile: Tile): String =
        "${info.baseUri}/${tile.region.toIiifParam()}/${tile.outputWidth},/0/default.jpg"
}
