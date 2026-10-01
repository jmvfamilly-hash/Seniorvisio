package com.vangoghtimeline.demo

/** Une tuile demandée au « serveur IIIF » de démonstration : région de l'image et largeur de sortie. */
data class DemoTile(val id: String, val x: Int, val y: Int, val width: Int, val height: Int, val outWidth: Int)

/**
 * Adresses du serveur IIIF **hors ligne** de démonstration : `demo:vangogh/<id>` (manifeste ou service d'image).
 * Les tuiles suivent exactement l'Image API : `demo:vangogh/<id>/x,y,w,h/<largeur>,/0/default.jpg`.
 * Pur Kotlin : testé sur la JVM.
 */
object DemoTileUrl {
    private const val PREFIX = "demo:vangogh/"
    private val TILE = Regex("""^demo:vangogh/([^/]+)/(\d+),(\d+),(\d+),(\d+)/(\d+),/0/default\.jpg$""")

    fun isDemo(url: String) = url.startsWith(PREFIX)

    /** `demo:vangogh/s27`, `…/manifest.json` ou `…/info.json` → `demo:vangogh/s27`. */
    fun base(url: String): String = url.removeSuffix("/info.json").removeSuffix("/manifest.json").trimEnd('/')

    fun idOf(url: String): String = base(url).removePrefix(PREFIX)

    fun parseTile(url: String): DemoTile? {
        val m = TILE.matchEntire(url) ?: return null
        val (id, x, y, w, h, ow) = m.destructured
        return DemoTile(id, x.toInt(), y.toInt(), w.toInt(), h.toInt(), ow.toInt())
    }
}
