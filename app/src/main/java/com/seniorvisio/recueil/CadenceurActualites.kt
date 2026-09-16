package com.seniorvisio.recueil

import com.seniorvisio.core.AdminConfig
import java.time.LocalDateTime

/**
 * Décide quel titre est « celui de maintenant ».
 *
 * ═══ LE CRÉNEAU EST CALCULÉ, PAS FIXÉ ═══
 *
 * La journée éveillée — vingt-quatre heures moins la fenêtre de nuit déjà
 * réglée dans le panneau d'administration — est divisée par le nombre de
 * titres installés. Trente titres et quinze heures de veille donnent des
 * créneaux de trente minutes ; seize titres, des créneaux de cinquante-six.
 *
 * Fixer une durée aurait produit l'un ou l'autre des deux défauts : des titres
 * jamais vus parce que la journée finit avant eux, ou un écran qui répète le
 * dernier pendant des heures. Diviser fait que le fil, quel qu'il soit, occupe
 * exactement la journée.
 *
 * ═══ AUCUN ÉTAT ═══
 *
 * Cette classe ne retient rien : on lui donne une heure et un nombre de
 * titres, elle répond un rang. Deux conséquences qui valent la peine — elle se
 * raisonne entièrement à la lecture, et la tablette qui redémarre à midi
 * retrouve le titre de midi sans avoir à savoir ce qui s'est passé avant.
 */
object CadenceurActualites {

    /**
     * Le rang du titre à montrer, et l'instant où il cédera la place.
     *
     * @param finDuCreneau null pendant la nuit : il n'y a alors rien à montrer
     *   et rien à programmer avant le retour du jour.
     */
    data class Creneau(val rang: Int, val finDuCreneau: LocalDateTime?)

    /**
     * @param nombreDeTitres ce que la tablette a réellement installé, pas ce
     *   que le flux annonçait : un titre refusé ne doit pas laisser un trou
     *   d'une demi-heure sur l'écran de Jean.
     */
    fun creneau(
        maintenant: LocalDateTime,
        nombreDeTitres: Int,
        config: AdminConfig,
    ): Creneau? {
        if (nombreDeTitres <= 0) return null

        val nuit = config.blockWakeAtNight
        val débutJour = if (nuit) config.nightEndHour else 0
        val finJour = if (nuit) config.nightStartHour else 24

        // Une fenêtre de nuit qui couvre tout, ou qui est mal réglée : on ne
        // divise pas par zéro et on n'affiche rien, plutôt que de deviner.
        val heuresDeVeille = heuresEntre(débutJour, finJour)
        if (heuresDeVeille <= 0) return null

        if (nuit && config.isCurrentlyNightWindow(maintenant.hour)) {
            return Creneau(rang = 0, finDuCreneau = null)
        }

        // Minutes écoulées depuis le début de la journée éveillée. Le modulo
        // traite le cas d'une nuit qui enjambe minuit (22h → 7h) : le matin
        // est « après » le début du jour, pas avant.
        val minutesDepuisDébut =
            ((maintenant.hour - débutJour + 24) % 24) * 60 + maintenant.minute
        val minutesDeVeille = heuresDeVeille * 60
        val duréeCréneau = minutesDeVeille.toDouble() / nombreDeTitres

        val rang = (minutesDepuisDébut / duréeCréneau).toInt()
            .coerceIn(0, nombreDeTitres - 1)

        // La fin est calculée depuis le DÉBUT de la journée et non « maintenant
        // plus la durée » : additionner des durées dérive, et au bout de trente
        // créneaux le dernier titre déborderait sur la nuit.
        val minutesFin = ((rang + 1) * duréeCréneau).toLong()
        val débutDuJour = maintenant.toLocalDate().atStartOfDay()
            .plusHours(débutJour.toLong())
            .let { if (maintenant.hour < débutJour) it.minusDays(1) else it }

        return Creneau(rang, débutDuJour.plusMinutes(minutesFin))
    }

    /** Longueur de la plage de veille, en heures, nuit à cheval sur minuit comprise. */
    private fun heuresEntre(début: Int, fin: Int): Int =
        if (début <= fin) fin - début else 24 - début + fin
}
