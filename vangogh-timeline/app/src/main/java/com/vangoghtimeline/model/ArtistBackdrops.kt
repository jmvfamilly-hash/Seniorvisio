package com.vangoghtimeline.model

/**
 * Un point d'intérêt d'un tableau majeur de l'artiste, servi en plein écran derrière le menu : une DÉCOUPE de l'image (région IIIF) d'une œuvre
 * en domaine public / open access du National Gallery of Art (images CC0, service `api.nga.gov/iiif`, vérifié sur les rapports).
 *
 * @property focusX @property focusY centre du point d'intérêt, en fraction de l'image (0 à 1)
 * @property zoom 1 = la plus grande découpe de la forme de l'écran qui tient dans l'image ; 2 = deux fois plus près
 */
data class Backdrop(
    val artistId: String,
    val titleFr: String,
    val date: String,
    val iiifBase: String,
    val width: Int,
    val height: Int,
    val focusX: Float,
    val focusY: Float,
    val zoom: Float,
) {
    /** Légende de crédit (les images open access du NGA sont libres de droits). */
    val credit: String get() = "$titleFr, $date — National Gallery of Art, Washington (CC0)"

    /** Région `x, y, w, h` en pixels de l'image, de la forme [aspect] (largeur / hauteur de l'écran), toujours DANS l'image. */
    fun region(aspect: Float): IntArray {
        val a = aspect.coerceIn(0.2f, 5f)
        val fullW = minOf(width.toFloat(), height * a)
        val fullH = fullW / a
        val w = (fullW / zoom).coerceAtMost(width.toFloat())
        val h = (fullH / zoom).coerceAtMost(height.toFloat())
        val x = (focusX * width - w / 2f).coerceIn(0f, width - w)
        val y = (focusY * height - h / 2f).coerceIn(0f, height - h)
        return intArrayOf(x.toInt(), y.toInt(), w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
    }

    /** Adresse IIIF de la découpe, à la largeur [outWidth] (forme « largeur seule » acceptée par tous les serveurs IIIF). */
    fun url(aspect: Float, outWidth: Int): String {
        val r = region(aspect)
        return "$iiifBase/${r[0]},${r[1]},${r[2]},${r[3]}/$outWidth,/0/default.jpg"
    }
}

/**
 * Le fond du menu pour chaque artiste qui a un univers : un tableau majeur et son point d'intérêt. Les œuvres viennent des données ouvertes du
 * NGA (identifiants, tailles réelles) ; les centres et zooms sont des choix éditoriaux, à affiner à l'œil.
 */
object ArtistBackdrops {
    private const val NGA = "https://api.nga.gov/iiif/"

    private val all: Map<String, Backdrop> = listOf(
        // visage de l'autoportrait de 1889
        Backdrop("vincent-van-gogh", "Autoportrait", "1889", NGA + "54ee6643-e0f9-4b92-a1d2-441e5108724d", 21687, 28273, 0.50f, 0.36f, 2.4f),
        Backdrop("claude-monet", "Le Pont japonais", "1899", NGA + "0b9cefb5-1ee4-401a-8154-8d4039191a28", 29700, 23942, 0.50f, 0.52f, 1.5f),
        Backdrop("pierre-auguste-renoir", "La Fillette à l'arrosoir", "1876", NGA + "bcc517e0-84c3-4e21-8c08-c9a0181ced29", 15915, 22072, 0.50f, 0.40f, 1.8f),
        Backdrop("berthe-morisot", "Les Sœurs", "1869", NGA + "798b462e-a54f-4750-bc2a-e96172576fcb", 30906, 19591, 0.45f, 0.45f, 1.7f),
        Backdrop("paul-gauguin", "Danse des petites Bretonnes, Pont-Aven", "1888", NGA + "732faae9-f8fe-459b-b86e-1c6039024179", 35659, 27973, 0.50f, 0.50f, 1.6f),
        Backdrop("john-singer-sargent", "Ellen Peabody Endicott", "1901", NGA + "cea770c4-f095-4a1f-b970-028fde1fbb2e", 27585, 39142, 0.50f, 0.30f, 2.0f),
        Backdrop("joaquin-sorolla", "Isabelita et Thor", "1893", NGA + "c0c1efc0-45c3-4bc0-95bc-3b5c311f13fb", 32885, 46864, 0.50f, 0.30f, 2.0f),
    ).associateBy { it.artistId }

    fun of(artistId: String): Backdrop? = all[artistId]
}
