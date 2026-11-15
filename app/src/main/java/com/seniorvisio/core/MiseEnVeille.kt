package com.seniorvisio.core

import android.content.Context

/**
 * Éteindre la dalle n'est plus permis qu'au bouton physique d'alimentation de
 * la tablette.
 *
 * ═══ CE QUI EXISTAIT AVANT, ET POURQUOI C'EST COUPÉ ═══
 *
 * Ce bouton logiciel (écran d'appel, voir IncomingCallActivity) posait une
 * échéance de sommeil, douze heures par défaut, levée par un appel ou par le
 * bouton « Réveiller » du panneau d'administration. Désactivé sur demande
 * explicite : seul un geste physique sur la tablette doit désormais pouvoir
 * éteindre l'écran, pour ne plus jamais risquer une veille que personne sur
 * place n'aurait demandée. [endormir] est gardé en no-op plutôt que supprimé
 * partout où il est appelé.
 */
object MiseEnVeille {

    /**
     * Désactivé, demandé explicitement : seul le bouton physique d'alimentation
     * de la tablette doit pouvoir éteindre l'écran, plus aucun chemin logiciel
     * (ce bouton compris). Gardé en no-op plutôt que supprimé partout où il
     * est appelé (voir IncomingCallActivity) : un appui n'a simplement plus
     * aucun effet, journalisé pour que ça ne ressemble pas à une panne.
     */
    fun endormir(context: Context, fenêtre: android.view.Window?) {
        CallTrace.record("SOMMEIL ignoré", "désactivé — seul le bouton physique éteint l'écran")
    }

    /**
     * Lève le sommeil en cours, s'il y en a un.
     *
     * Appelée à l'arrivée d'un appel, et par le bouton « Réveiller » du
     * panneau d'administration. Rend vrai quand il y avait effectivement un
     * sommeil à lever, pour que l'appelant puisse le dire au journal sans
     * avoir à relire le réglage lui-même.
     */
    fun réveiller(context: Context, motif: String): Boolean {
        val config = AdminConfig(context)
        if (!config.isSleeping()) return false
        val restantMin = (config.sleepUntilMs - System.currentTimeMillis()) / 60_000L
        config.sleepUntilMs = 0L
        CallTrace.record("SOMMEIL levé", "$motif — il restait $restantMin min")
        return true
    }

    /** Ce que le panneau d'administration et le signe de vie affichent. */
    fun décrire(context: Context): String {
        val config = AdminConfig(context)
        if (!config.isSleeping()) return "éveillée (appui sur « Sommeil » : ${config.sleepHours} h)"
        val restantMin = (config.sleepUntilMs - System.currentTimeMillis()) / 60_000L
        return "en sommeil encore $restantMin min"
    }
}
