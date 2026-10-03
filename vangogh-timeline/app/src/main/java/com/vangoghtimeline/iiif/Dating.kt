package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkQuery

/**
 * Date d'une œuvre : l'[year] connue ; sans année, la moitié de la période d'activité de l'artiste, MARQUÉE estimée (comptée dans [tally]) ;
 * `null` (et comptée écartée) si l'année connue est hors des dates plausibles.
 */
internal fun dateOf(year: Int?, query: ArtworkQuery, tally: Tally?): ArtworkDate? {
    if (year == null) { tally?.let { it.estimated++ }; return query.estimatedDate() }
    if (year !in query.years) { tally?.drop("hors des dates plausibles"); return null }
    return ArtworkDate.year(year)
}
