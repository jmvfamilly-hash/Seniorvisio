package com.vangoghtimeline.model

/** « il y a 3 jours » : l'âge d'une donnée, en français, pour le panneau d'un artiste. */
object AgeFormat {
    fun fr(ageMs: Long): String = when {
        ageMs < 0 -> "à l'instant"
        ageMs < 60_000 -> "à l'instant"
        ageMs < 3_600_000 -> "il y a ${ageMs / 60_000} min"
        ageMs < 48 * 3_600_000L -> "il y a ${ageMs / 3_600_000} h"
        else -> "il y a ${ageMs / 86_400_000} jours"
    }
}
