package com.seniorvisio.core

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Applications tierces que Senior Visio a le droit de lancer, et vers
 * lesquelles Jean peut donc basculer sans sortir du mode kiosque.
 *
 * Le principe du produit reste inchangé : Jean ne navigue pas librement dans
 * la tablette, il bascule vers une application choisie ici et en revient — par
 * le bandeau de retour, par le bouton Accueil (qui ramène à Senior Visio, voir
 * KioskManager.registerAsHomeApp), ou tout seul (voir
 * AdminConfig.roomHandoffEnabled).
 */
object CompanionApps {

    /**
     * « Transcription instantanée » de Google, préinstallée sur la tablette de
     * Jean.
     *
     * Elle avait déjà porté les sous-titres de la pièce, puis avait été retirée
     * au profit d'une transcription intégrée à Senior Visio. Elle revient ici
     * pour ce qu'elle fait mieux que toute solution atteignable par une
     * application tierce : une reconnaissance **continue**. L'interface
     * publique d'Android est modale — un énoncé, puis arrêt, puis relance — et
     * c'est ce temps mort qui coupe les phrases et mange les premiers mots.
     * Google n'y est pas soumis dans sa propre application.
     */
    const val TRANSCRIPTION = "com.google.audio.hearing.visualization.accessibility.scribe"

    /** Paquets autorisés en mode kiosque, en plus de Senior Visio lui-même. */
    val allowedPackages = arrayOf(TRANSCRIPTION)

    /**
     * De quoi ouvrir Transcription instantanée, ou null si elle est absente.
     *
     * Deux précautions, l'une et l'autre payées d'un test réel raté par le
     * passé :
     *
     * Le paquet doit être déclaré dans `<queries>` du manifeste. Depuis
     * Android 11, sans cette déclaration, getLaunchIntentForPackage rend null
     * pour un paquet pourtant installé — exactement comme pour un paquet
     * absent, et sans la moindre erreur pour le dire.
     *
     * Le repli sur n'importe quelle activité ACTION_MAIN est nécessaire :
     * certaines applications d'accessibilité ne déclarent aucune activité
     * CATEGORY_LAUNCHER, étant prévues pour être ouvertes depuis les Réglages
     * ou par le raccourci d'accessibilité. Chercher uniquement une icône de
     * lanceur les déclare introuvables.
     */
    fun transcriptionLaunchIntent(context: Context): Intent? {
        val manager = context.packageManager
        manager.getLaunchIntentForPackage(TRANSCRIPTION)?.let { return it }

        val fallback = Intent(Intent.ACTION_MAIN).setPackage(TRANSCRIPTION)
        val resolved = manager.queryIntentActivities(fallback, 0).firstOrNull()
        if (resolved == null) {
            Log.w(TAG, "Transcription instantanée introuvable (paquet absent ou non déclaré dans <queries>)")
            return null
        }
        return Intent(Intent.ACTION_MAIN)
            .setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
    }

    fun isTranscriptionInstalled(context: Context): Boolean =
        transcriptionLaunchIntent(context) != null

    private const val TAG = "CompanionApps"
}
