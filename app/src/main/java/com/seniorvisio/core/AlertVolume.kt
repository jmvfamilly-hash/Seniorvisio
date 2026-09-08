package com.seniorvisio.core

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Baisse le flux « Notifications » de la tablette pendant que le moteur de
 * reconnaissance d'Android écoute la pièce.
 *
 * Ce moteur joue deux bips — un au début de chaque énoncé, un à la fin — et
 * rien dans son interface ne permet de les désactiver. Comme il faut le
 * relancer à chaque silence pour obtenir une écoute continue (voir
 * AndroidSpeechSession), ces deux bips reviennent en boucle toute la journée
 * dans la chambre de Jean.
 *
 * Ce sont des alertes système : le seul levier est le volume de ce flux.
 *
 * Deux précautions, sans lesquelles le remède serait pire que le mal :
 *
 *  - La sonnerie d'appel est sortie de ce flux au préalable (voir
 *    IncomingCallActivity.playDiscreetAlertSound, passée sur le flux alarme).
 *    Sans ça, baisser les alertes aurait rendu les appels inaudibles — un
 *    proche appelant dans le vide, ce que Jean n'aurait eu aucun moyen de
 *    comprendre ni de signaler.
 *  - Le plus bas cran audible, jamais zéro. Mettre ce flux à zéro fait
 *    basculer la tablette en mode silencieux, ce qu'Android refuse depuis la
 *    version 7 sans l'autorisation « Ne pas déranger » — autorisation qu'un
 *    Device Owner ne peut pas s'accorder lui-même.
 *
 * Le niveau d'origine est restauré à l'arrêt de l'écoute. Si l'application
 * est tuée entre les deux, il reste bas : conséquence acceptable maintenant
 * que plus rien d'important ne passe par ce flux, et corrigée au prochain
 * changement de moteur.
 */
object AlertVolume {

    private var savedLevel: Int? = null

    /** Baisse les alertes au plus bas cran audible, en mémorisant le niveau courant. */
    fun duck(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            if (current <= QUIET_LEVEL) return
            if (savedLevel == null) savedLevel = current
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, QUIET_LEVEL, 0)
        } catch (e: SecurityException) {
            // Refus du système (politique « Ne pas déranger ») : les bips
            // restent audibles, et on le dit plutôt que de laisser croire que
            // le réglage a pris.
            TranscriptionDiagnostics.record("volume des alertes non modifiable : ${e.message}")
            Log.w(TAG, "Impossible de baisser le volume des alertes", e)
        }
    }

    /** Rétablit le niveau d'alertes d'avant [duck]. Sans effet si rien n'a été baissé. */
    fun restore(context: Context) {
        val level = savedLevel ?: return
        savedLevel = null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, level, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Impossible de rétablir le volume des alertes", e)
        }
    }

    /**
     * Le plus bas cran qui reste audible. Voir plus haut : zéro ferait
     * basculer la tablette en mode silencieux, et Android le refuse.
     */
    private const val QUIET_LEVEL = 1
    private const val TAG = "AlertVolume"
}
