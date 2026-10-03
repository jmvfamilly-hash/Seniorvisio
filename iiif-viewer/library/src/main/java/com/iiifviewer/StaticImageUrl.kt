package com.iiifviewer

/**
 * Image « simple » (JPEG/PNG sans service IIIF) servie au visualiseur comme si c'était une image IIIF : l'`info.json` est fabriqué
 * (voir [HttpIiifSources]) et chaque tuile est demandée par une URL IIIF-like préfixée `static:` :
 *
 *     static:{url de l'image}/{x},{y},{w},{h}/{largeur},/0/default.jpg
 *
 * qui n'est jamais envoyée au réseau : [HttpIiifSources] la reconnaît, télécharge l'image UNE fois, et en découpe la région.
 * Pur Kotlin : testé sur la JVM.
 */
object StaticImageUrl {
    private const val PREFIX = "static:"
    private val tile = Regex("""^static:(.+)/(\d+),(\d+),(\d+),(\d+)/(\d+),/0/default\.jpg$""")

    /** Une tuile demandée : la région de l'image d'origine et la largeur de sortie. */
    class Request(val imageUrl: String, val x: Int, val y: Int, val width: Int, val height: Int, val outputWidth: Int)

    fun baseUriOf(imageUrl: String): String = PREFIX + imageUrl
    fun isStatic(url: String): Boolean = url.startsWith(PREFIX)

    /** Adresse à donner au visualiseur pour ouvrir directement une image ordinaire : `static:{url}`. */
    fun viewerUrlFor(imageUrl: String): String = PREFIX + imageUrl

    /** L'URL de l'image si [viewerUrl] est de la forme `static:{url}` (http/https), sinon `null`. */
    fun imageUrlOf(viewerUrl: String): String? = viewerUrl.removePrefix(PREFIX).takeIf { viewerUrl.startsWith(PREFIX) && it.startsWith("http") }

    fun parse(url: String): Request? {
        val m = tile.matchEntire(url) ?: return null
        val g = m.groupValues
        return Request(g[1], g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
    }

    /** Plus grand facteur de sous-échantillonnage (puissance de 2) qui garde au moins [outputWidth] pixels de large. */
    fun sampleSizeFor(regionWidth: Int, outputWidth: Int): Int {
        var s = 1
        while (outputWidth > 0 && regionWidth / (s * 2) >= outputWidth) s *= 2
        return s
    }
}
