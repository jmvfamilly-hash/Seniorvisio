package com.seniorvisio.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.PoliceSenior

/**
 * Habille tout l'écran selon le réglage en cours.
 *
 * Une extension, et une seule, appelée juste après setContentView par les deux
 * écrans que Jean voit. Écrire l'appel deux fois avec deux façons de lire le
 * réglage aurait suffi à les laisser diverger — et une divergence de police
 * entre l'accueil et l'écran d'appel se verrait exactement au moment le plus
 * mauvais, quand quelqu'un appelle.
 */
fun Activity.appliquerMiseEnFormeSenior() {
    MiseEnFormeSenior.appliquer(findViewById(android.R.id.content), policeChoisie(this))
}

/**
 * Le choix en cours, avec repli si la valeur rangée n'est plus reconnue —
 * une police retirée d'une version à l'autre, par exemple.
 *
 * Exposé plutôt que privé : tout ce qui est créé à la volée, hors d'un arbre
 * gonflé depuis une mise en page, doit pouvoir s'habiller pareil (voir
 * ReturnBannerOverlay). Un seul endroit lit le réglage, donc un seul endroit
 * peut se tromper.
 */
fun policeChoisie(context: Context): PoliceSenior =
    PoliceSenior.depuisValeurDistante(AdminConfig(context).policeSenior)
        ?: PoliceSenior.PAR_DÉFAUT

/**
 * Ré-applique la police si, et seulement si, le choix a changé.
 *
 * ═══ POURQUOI CE N'ÉTAIT PAS SUFFISANT DE LE FAIRE AU GONFLAGE ═══
 *
 * La mise en forme n'était posée qu'une fois, juste après setContentView.
 * Choisir une police depuis le PWA pendant qu'un écran est déjà affiché —
 * c'est-à-dire tout le temps, et en particulier PENDANT UN APPEL, qui est le
 * moment où l'on juge la lisibilité — n'avait donc aucun effet. Le réglage
 * partait, la tablette l'enregistrait, et l'écran gardait l'ancienne police :
 * indiscernable d'un réglage qui n'arrive pas.
 *
 * Appelée sur le battement d'ergonomie, comme les autres réglages d'écran. La
 * comparaison évite de reparcourir l'arbre des vues toutes les secondes pour
 * rien : on ne le fait qu'au changement.
 *
 * @param déjàAppliquée ce que l'appelant a posé la dernière fois, ou null.
 * @return le choix désormais en vigueur, à conserver pour le prochain appel.
 */
fun Activity.réappliquerPoliceSiChangée(déjàAppliquée: PoliceSenior?): PoliceSenior {
    val choix = policeChoisie(this)
    if (choix != déjàAppliquée) {
        MiseEnFormeSenior.appliquer(findViewById(android.R.id.content), choix)
    }
    return choix
}

/**
 * Pose la police et la mise en forme sur tout un écran, d'un coup.
 *
 * Parcourt l'arbre plutôt que d'énumérer les vues : un texte ajouté demain à
 * une mise en page existante est couvert sans que personne ait à y penser.
 */
object MiseEnFormeSenior {

    fun appliquer(racine: View, police: PoliceSenior) {
        val fonte = police.fonte(racine.context)
        parcourir(racine, fonte)
    }

    private fun parcourir(vue: View, fonte: Typeface?) {
        when (vue) {
            is ViewGroup -> for (i in 0 until vue.childCount) parcourir(vue.getChildAt(i), fonte)
            is TextView -> habiller(vue, fonte)
        }
    }

    private fun habiller(vue: TextView, fonte: Typeface?) {
        // La graisse est RELUE sur la vue et réappliquée, au lieu d'être
        // écrasée : le gras porte la hiérarchie de ces écrans (l'heure, le nom
        // de l'appelant), et le perdre aplatirait tout au même niveau.
        if (fonte != null) {
            val graisse = vue.typeface?.style ?: Typeface.NORMAL
            vue.typeface = Typeface.create(fonte, graisse)
        }
        vue.setLineSpacing(0f, PoliceSenior.INTERLIGNE)
        vue.letterSpacing = PoliceSenior.INTERLETTRAGE
        // Les consignes bannissent le TOUT MAJUSCULE : le cerveau reconnaît un
        // mot à sa silhouette, et des capitales transforment les mots en blocs
        // rectangulaires uniformes qu'il faut déchiffrer lettre par lettre.
        //
        // Posé ici et pas seulement dans les mises en page, parce que le défaut
        // vient du THÈME : Widget.AppCompat.Button impose textAllCaps, donc
        // tout bouton ajouté plus tard repartirait en capitales sans que sa
        // mise en page ne mentionne quoi que ce soit.
        if (vue is Button) vue.isAllCaps = false
    }
}
