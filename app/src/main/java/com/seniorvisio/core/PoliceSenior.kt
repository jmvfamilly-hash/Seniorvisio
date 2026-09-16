package com.seniorvisio.core

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.seniorvisio.R

/**
 * La police que Jean a sous les yeux, et la mise en forme qui va avec.
 *
 * ═══ POURQUOI CE N'EST PAS UN DÉTAIL D'APPARENCE ═══
 *
 * Les consignes d'accessibilité retenues pour ce projet ne demandent pas une
 * police « plus jolie » : elles demandent une police qui compense à la fois la
 * baisse d'acuité visuelle et la charge cognitive. Les trois familles ci-dessous
 * ont été choisies pour des raisons distinctes, et c'est pour ça qu'il y a un
 * choix plutôt qu'une valeur imposée — ce qui soulage le plus varie d'une
 * personne à l'autre, et cela se constate à l'usage, pas sur le papier.
 *
 * ═══ L'ESPACEMENT COMPTE AUTANT QUE LE DESSIN DES LETTRES ═══
 *
 * L'interligne et l'interlettrage sont posés ICI, avec la police, et non dans
 * chaque fichier de mise en page. Deux raisons, dont la seconde est la vraie :
 *
 *   - ces valeurs viennent des mêmes consignes que la police, les séparer
 *     inviterait à en changer une sans l'autre ;
 *   - un nouvel écran écrit plus tard hériterait sinon de l'interligne par
 *     défaut sans que personne ne s'en aperçoive. Une règle qu'il faut penser
 *     à répéter est une règle qui finit par manquer quelque part.
 *
 * Le « crowding » — les caractères qui fusionnent à l'œil — est un problème
 * majeur de l'attention visuelle chez les personnes âgées. C'est ce que ces
 * deux réglages traitent, et aucune police ne le fait à leur place.
 */
enum class PoliceSenior(
    /** Ce qui voyage dans Firestore et dans les préférences. Jamais l'ordinal. */
    val valeurDistante: String,
    /** Ce que l'administrateur lit dans le PWA. */
    val libellé: String,
) {
    /**
     * Exagère volontairement la différence entre les caractères ambigus — I
     * majuscule, l minuscule, 1 ; O et 0. C'est la confusion entre ces formes
     * qui coûte le plus cher en décodage sur une dalle lue de loin.
     */
    ATKINSON("atkinson", "Atkinson Hyperlegible"),

    /**
     * Conçue en France, sur des critères de déficience visuelle, pour le
     * français. Le choix par défaut de ce projet pour cette raison.
     */
    LUCIOLE("luciole", "Luciole"),

    /** Retenue pour réduire la sensation d'encombrement visuel. */
    LEXEND("lexend", "Lexend");

    companion object {

        /** Celle qui s'applique quand rien n'a été choisi. */
        val PAR_DÉFAUT = LUCIOLE

        /**
         * Interligne de 1,5, demandé par les consignes.
         *
         * Il se combine avec l'auto-réduction des zones de texte (voir
         * autoSizeTextType dans les mises en page) : un titre long dispose de
         * moins de hauteur, donc il descend plus vite vers le plancher de
         * taille. C'est un arbitrage assumé et il est à éprouver en usage.
         */
        const val INTERLIGNE = 1.5f

        /**
         * Interlettrage, en cadratins.
         *
         * Volontairement modeste : au-delà, les mots se délitent en suites de
         * lettres, et l'on perd la silhouette globale du mot — précisément ce
         * sur quoi s'appuie la reconnaissance rapide, et ce que les consignes
         * protègent en interdisant les capitales.
         */
        const val INTERLETTRAGE = 0.02f

        fun depuisValeurDistante(valeur: String?): PoliceSenior? =
            values().firstOrNull { it.valeurDistante == valeur }
    }

    /**
     * La fonte, ou null si elle n'est pas embarquée dans cette version.
     *
     * ═══ LUCIOLE EST RÉSOLUE PAR SON NOM, ET C'EST DÉLIBÉRÉ ═══
     *
     * Les deux autres sont désignées par leur identifiant de ressource, donc
     * vérifiées à la compilation. Luciole ne l'est pas : elle n'est distribuée
     * ni par Google Fonts ni par aucun dépôt public accessible, seulement
     * depuis le site de ses auteurs. Elle doit donc être déposée à la main
     * dans res/font (voir docs/polices-accessibilite.md).
     *
     * La résoudre par son nom permet que ce dépôt de fichiers SUFFISE : aucune
     * ligne de code à changer le jour où elle arrive. Et tant qu'elle n'est pas
     * là, l'absence est DITE dans le journal technique plutôt que remplacée en
     * silence par la police système — une substitution muette ferait croire que
     * le réglage n'a aucun effet.
     */
    fun fonte(context: Context): Typeface? {
        val id = when (this) {
            ATKINSON -> R.font.police_atkinson
            LEXEND -> R.font.police_lexend
            LUCIOLE -> context.resources.getIdentifier(
                "police_luciole", "font", context.packageName
            )
        }
        if (id == 0) {
            CallTrace.record("POLICE absente", "$libellé n'est pas embarquée — police système conservée")
            return null
        }
        return try {
            ResourcesCompat.getFont(context, id)
        } catch (e: Exception) {
            CallTrace.record("POLICE illisible", "$libellé : ${e.javaClass.simpleName}")
            null
        }
    }
}
