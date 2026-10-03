package com.vangoghtimeline.iiif

/** Lignes de détail d'une œuvre : les valeurs vides sont écartées, les longues coupées (une fiche tient à l'écran). */
internal fun detailsOf(vararg pairs: Pair<String, String?>): List<Pair<String, String>> =
    pairs.mapNotNull { (label, value) ->
        value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { label to (if (it.length > 400) it.take(400) + "…" else it) }
    }
