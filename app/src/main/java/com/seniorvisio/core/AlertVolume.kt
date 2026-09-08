package com.seniorvisio.core

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Tient le flux « Notifications » de la tablette au plus bas hors appel, et
 * lui rend son niveau dès qu'un appel se présente.
 *
 * Ce qu'il s'agit de faire taire : le moteur de reconnaissance d'Android joue
 * deux sons — un au début de chaque énoncé, un à la fin — qui sont sa
 * signalétique d'interaction, pas un effet secondaire. Aucun réglage ne les
 * désactive, et ils sont émis par le processus du service de reconnaissance,
 * pas par le nôtre. Comme l'écoute permanente n'existe pas dans cette API et
 * qu'il faut relancer le moteur à chaque silence, ces deux sons reviennent en
 * boucle toute la journée dans la chambre.
 *
 * La cause première est cette relance, et elle est assumée : ralentir les
 * relances rendrait la tablette sourde par intermittence, donc lui ferait
 * manquer des paroles. Ne jamais rater ce qui se dit dans la pièce prime sur
 * le confort sonore. Le volume est alors le seul levier qui ne coûte aucune
 * seconde d'écoute.
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
 * Le niveau d'origine est écrit sur disque, pas gardé en mémoire. Sans ça, un
 * redémarrage de l'application pendant que le volume est bas — mise à jour,
 * processus tué par le système, redémarrage de la tablette — perdrait la
 * valeur d'origine, et les alertes seraient basses pour toujours sans que
 * personne sache pourquoi.
 */
object AlertVolume {

    /** Hors appel : les alertes descendent au plus bas cran audible. */
    fun quiet(context: Context) {
        val audioManager = audioManager(context) ?: return
        try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            // Déjà bas : il n'y a rien à faire, et surtout rien à mémoriser —
            // écraser le niveau retenu par la valeur basse reviendrait à
            // oublier définitivement le niveau d'origine.
            if (current <= QUIET_LEVEL) return
            prefs(context).edit().putInt(KEY_SAVED_LEVEL, current).apply()
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, QUIET_LEVEL, 0)
        } catch (e: SecurityException) {
            // Refus du système (politique « Ne pas déranger ») : les sons
            // restent audibles, et on le dit plutôt que de laisser croire que
            // le réglage a pris.
            TranscriptionDiagnostics.record("volume des alertes non modifiable : ${e.message}")
            Log.w(TAG, "Impossible de baisser le volume des alertes", e)
        }
    }

    /** Appel en cours ou qui se présente : le niveau d'avant est rétabli. */
    fun normal(context: Context) {
        val prefs = prefs(context)
        val level = prefs.getInt(KEY_SAVED_LEVEL, -1)
        if (level < 0) return
        prefs.edit().remove(KEY_SAVED_LEVEL).apply()
        val audioManager = audioManager(context) ?: return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, level, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Impossible de rétablir le volume des alertes", e)
        }
    }

    private fun audioManager(context: Context): AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Le plus bas cran qui reste audible. Voir plus haut : zéro ferait
     * basculer la tablette en mode silencieux, et Android le refuse.
     */
    private const val QUIET_LEVEL = 1
    private const val PREFS_NAME = "senior_visio_alert_volume"
    private const val KEY_SAVED_LEVEL = "saved_notification_level"
    private const val TAG = "AlertVolume"
}
