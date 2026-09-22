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
    /**
     * Le créneau d'une GALERIE PHOTO : une durée fixe, et on recommence.
     *
     * ═══ POURQUOI PAS LA MÊME RÈGLE QUE LES TITRES ═══
     *
     * Un fil d'information doit occuper exactement la journée : chaque titre
     * est vu une fois, aucun n'est sauté, et demain la liste est remplacée.
     * D'où la division.
     *
     * Une galerie de famille n'a pas cette contrainte. Les photos sont les
     * mêmes demain, et les revoir n'est pas un défaut — c'est l'objet. Une
     * durée fixe donne donc un rythme prévisible quel que soit le nombre de
     * photos, là où la division ferait défiler une galerie de cent photos
     * toutes les neuf minutes et une de trois photos toutes les cinq heures.
     *
     * Le BOUCLAGE est ce qui rend la durée fixe tenable : trente photos à un
     * quart d'heure font sept heures et demie, donc deux tours dans une
     * journée éveillée. Sans lui, la trentième resterait affichée jusqu'au
     * soir — exactement le défaut que la division existe pour éviter.
     *
     * Sans état, comme [creneau] : le rang se déduit de l'heure. Une tablette
     * qui redémarre à midi retrouve la photo de midi, et l'écran qui se
     * rallume montre la bonne sans que personne n'ait rien mémorisé.
     */
    fun creneauFixe(
        maintenant: LocalDateTime,
        nombreDePhotos: Int,
        duréeMinutes: Int,
        config: AdminConfig,
    ): Creneau? {
        if (nombreDePhotos <= 0 || duréeMinutes <= 0) return null
        val jour = fenêtreDeJour(maintenant, config) ?: return null
        if (jour.enNuit) return Creneau(rang = 0, finDuCreneau = null)

        val écoulées = jour.minutesÉcoulées
        val rang = ((écoulées / duréeMinutes) % nombreDePhotos).toInt()
        // La fin est calculée depuis le début de la journée, comme pour les
        // titres : « maintenant + durée » dérive d'une poignée de secondes à
        // chaque tour, et au bout de cinquante tours les photos changeraient à
        // des heures qui n'ont plus rien de rond.
        val minutesFin = (écoulées / duréeMinutes + 1) * duréeMinutes
        return Creneau(rang, jour.débutDuJour.plusMinutes(minutesFin))
    }

    /**
     * La journée éveillée : son début, et où l'on en est dedans.
     *
     * Extrait des deux cadences parce qu'elles en ont besoin à l'identique.
     * Écrit deux fois, ce calcul aurait divergé au premier ajustement de la
     * fenêtre de nuit — et l'un des deux écrans aurait changé de rythme sans
     * que rien ne le dise.
     */
    private data class FenêtreDeJour(
        val enNuit: Boolean,
        val minutesÉcoulées: Long,
        val débutDuJour: LocalDateTime,
    )

    private fun fenêtreDeJour(maintenant: LocalDateTime, config: AdminConfig): FenêtreDeJour? {
        val nuit = config.blockWakeAtNight
        val débutJour = if (nuit) config.nightEndHour else 0
        val finJour = if (nuit) config.nightStartHour else 24
        if (heuresEntre(débutJour, finJour) <= 0) return null
        if (nuit && config.isCurrentlyNightWindow(maintenant.hour)) {
            return FenêtreDeJour(enNuit = true, minutesÉcoulées = 0, débutDuJour = maintenant)
        }
        val minutes = ((maintenant.hour - débutJour + 24) % 24) * 60L + maintenant.minute
        val début = maintenant.toLocalDate().atStartOfDay()
            .plusHours(débutJour.toLong())
            .let { if (maintenant.hour < débutJour) it.minusDays(1) else it }
        return FenêtreDeJour(enNuit = false, minutesÉcoulées = minutes, débutDuJour = début)
    }

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
