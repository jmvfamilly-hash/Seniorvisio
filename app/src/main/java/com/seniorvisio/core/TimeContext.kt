package com.seniorvisio.core

/**
 * Repère du moment de la journée, partagé entre l'écran d'accueil
 * (MainActivity) et l'écran d'appel entrant (IncomingCallActivity) — les deux
 * affichent le même pictogramme + mot, jamais l'heure exacte : "14:35" demande
 * de déchiffrer deux nombres, alors que ce qui compte au quotidien, c'est de
 * savoir où on en est dans la journée.
 *
 * Bornes rondes plutôt que calées sur le lever/coucher du soleil réel : pas de
 * dépendance à la position géographique ni à la saison, pour un repère simple
 * et prévisible d'un jour à l'autre.
 */
object TimeContext {

    enum class Moment(val icon: String, val label: String) {
        MATIN("🌅", "Matin"),
        APRES_MIDI("🌤️", "Après-midi"),
        SOIR("🌇", "Soir"),
        NUIT("🌙", "Nuit"),
    }

    fun momentOfDay(hour: Int): Moment = when (hour) {
        in 5..11 -> Moment.MATIN
        in 12..17 -> Moment.APRES_MIDI
        in 18..21 -> Moment.SOIR
        else -> Moment.NUIT
    }
}
