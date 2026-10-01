package com.iiifviewer

/** Interprétation de ce que l'utilisateur colle. Kotlin pur, testé sans Android. */
object IiifInput {

    /**
     * Rend l'URL de l'`info.json` à ouvrir, ou `null` si le texte n'est pas une URL http(s).
     * Tolère l'URL de base d'une image (`…/iiif/img01`, avec ou sans `/` final) : on ajoute `/info.json`.
     * Une URL qui finit déjà par `.json`, ou qui porte une query, est laissée telle quelle.
     */
    fun normalizeInfoUrl(raw: String): String? {
        val text = raw.trim()
        if (!Regex("^https?://[^\\s/]+\\S*$", RegexOption.IGNORE_CASE).matches(text)) return null
        if ('?' in text || text.endsWith(".json", ignoreCase = true)) return text
        return text.trimEnd('/') + "/info.json"
    }
}
