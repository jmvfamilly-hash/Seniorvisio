package com.vangoghtimeline.model

/**
 * Fusion des œuvres de plusieurs musées en une seule frise.
 *
 * Une même œuvre peut arriver par deux chemins (le Rijksmuseum directement, et Europeana qui l'agrège) : on garde la PREMIÈRE
 * rencontrée, dans l'ordre de priorité des listes données (source directe avant agrégateur), en comparant titre normalisé + année.
 * La liste rendue est triée par date.
 */
object ArtworkMerge {
    fun merge(lists: List<List<Artwork>>): List<Artwork> {
        val seen = HashSet<String>()
        val out = ArrayList<Artwork>()
        for (list in lists) for (a in list) {
            if (seen.add(keyOf(a))) out += a
        }
        return out.sortedWith(compareBy({ it.date.positionEpochDay }, { it.id }))
    }

    /** « The Bedroom (1889) » et « the bedroom. » sont la même œuvre : on ignore casse, accents de ponctuation et espaces. */
    fun keyOf(a: Artwork): String = a.title.lowercase().filter { it.isLetterOrDigit() } + "@" + a.date.year
}
