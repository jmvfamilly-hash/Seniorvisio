package com.seniorvisio.recueil

import com.seniorvisio.core.AdminConfig
import java.time.LocalDateTime

/**
 * Décide quelle photo de la galerie est « celle de maintenant ».
 *
 * ═══ UNE DURÉE FIXE, ET ON RECOMMENCE ═══
 *
 * Chaque photo tient l'écran pendant la durée réglée par l'administrateur,
 * puis cède la place à la suivante ; après la dernière, on repart de la
 * première.
 *
 * Ce cadenceur a d'abord servi un fil d'information, qui obéissait à la règle
 * inverse : la journée éveillée DIVISÉE par le nombre de titres, pour que
 * chacun soit vu une fois et qu'aucun ne déborde sur le lendemain. Cette
 * règle-là est partie avec le fil (voir la branche archive/fil-actualites).
 *
 * Une galerie de famille n'a pas cette contrainte : les photos sont les mêmes
 * demain, et les revoir n'est pas un défaut, c'est l'objet. Une durée fixe
 * donne un rythme prévisible quel que soit le nombre de photos, là où la
 * division ferait défiler cent photos toutes les neuf minutes et trois photos
 * toutes les cinq heures.
 *
 * Le BOUCLAGE est ce qui rend la durée fixe tenable : sans lui, la dernière
 * photo resterait affichée jusqu'au soir — exactement le défaut que la
 * division existait pour éviter.
 *
 * ═══ AUCUN ÉTAT ═══
 *
 * On lui donne une heure, un nombre de photos et une durée ; elle répond un
 * rang. Deux conséquences qui valent la peine : elle se raisonne entièrement
 * à la lecture, et la tablette qui redémarre à midi retrouve la photo de midi
 * sans avoir à savoir ce qui s'est passé avant. C'est aussi ce qui permet à
 * l'écran qui se rallume d'afficher tout de suite la bonne, sans attendre le
 * créneau suivant.
 */
object CadenceurPhotos {

    /**
     * Le rang de la photo à montrer, et l'instant où elle cédera la place.
     *
     * @param finDuCreneau null pendant la nuit : il n'y a alors rien à montrer
     *   et rien à programmer avant le retour du jour.
     */
    data class Creneau(val rang: Int, val finDuCreneau: LocalDateTime?)

    /** Voir l'en-tête de cette classe : durée fixe, bouclage, aucun état. */
    fun creneau(
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
        // La fin est calculée depuis le début de la journée et non
        // « maintenant + durée », qui dérive d'une poignée de secondes à
        // chaque tour, et au bout de cinquante tours les photos changeraient à
        // des heures qui n'ont plus rien de rond.
        val minutesFin = (écoulées / duréeMinutes + 1) * duréeMinutes
        return Creneau(rang, jour.débutDuJour.plusMinutes(minutesFin))
    }

    /**
     * La journée éveillée : son début, et où l'on en est dedans.
     *
     * À part, parce que la fenêtre de nuit est un réglage et que ce calcul
     * — le décalage de minuit, le début de journée de la veille — est ce qui
     * se relit le moins bien au milieu d'une arithmétique de rangs.
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

    /** Longueur de la plage de veille, en heures, nuit à cheval sur minuit comprise. */
    private fun heuresEntre(début: Int, fin: Int): Int =
        if (début <= fin) fin - début else 24 - début + fin
}
