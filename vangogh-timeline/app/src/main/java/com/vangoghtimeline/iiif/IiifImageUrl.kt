package com.vangoghtimeline.iiif

/**
 * Règle IIIF Image API : une URL d'image est `{service}/{region}/{size}/{rotation}/{quality}.{format}`. Retirer ces quatre derniers
 * segments donne le **service d'image** (sa base), dont `{service}/info.json` décrit les tuiles.
 *
 * Utile quand un musée ne donne que l'URL d'une image (ex. `https://iiif.micr.io/PJEZO/full/max/0/default.jpg`) : on en tire
 * `https://iiif.micr.io/PJEZO`, ce qui permet vignettes à la bonne taille et zoom profond.
 */
object IiifImageUrl {
    private val pattern = Regex("""^(https?://.+)/[^/]+/[^/]+/!?\d+(?:\.\d+)?/(?:default|color|gray|bitonal|native)\.[A-Za-z]+$""")

    /** Base du service d'image, ou `null` si [url] n'a pas la forme d'une URL d'image IIIF. */
    fun serviceBaseOf(url: String): String? = pattern.matchEntire(url.substringBefore('?').trim())?.groupValues?.get(1)
}
