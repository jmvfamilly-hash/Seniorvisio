package com.vangoghtimeline.model

/** Le parcours de l'artiste, lieu par lieu, tel que décrit dans le catalogue : « 1886–1888 · Paris — … ». */
fun careerLines(a: Artist): List<String> =
    a.locations.map { l -> listOf(l.period, l.location).filter { it.isNotBlank() }.joinToString(" · ") + if (l.workType.isNotBlank()) " — ${l.workType}" else "" }
