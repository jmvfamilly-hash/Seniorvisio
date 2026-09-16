package com.seniorvisio.core

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.WindowManager
import com.seniorvisio.admin.SeniorVisioDeviceAdminReceiver

/**
 * Éteindre la dalle, à la demande de Jean.
 *
 * ═══ DEUX CHEMINS, PARCE QU'UN SEUL NE MARCHE PAS PARTOUT ═══
 *
 * Android n'autorise pas une application ordinaire à endormir l'écran. Deux
 * moyens existent, et le second n'est pas un repli décoratif :
 *
 *   1. `lockNow()`, réservé aux administrateurs d'appareil. La tablette de Jean
 *      en est un — c'est ce qui permet déjà le mode kiosque (voir
 *      KioskManager). L'extinction est alors IMMÉDIATE, ce que Jean attend
 *      quand il appuie.
 *
 *   2. Sinon, on relâche le maintien de l'écran allumé et on laisse la
 *      temporisation du système faire son travail. L'écran s'éteint, mais au
 *      bout de son délai habituel — une trentaine de secondes, parfois plus.
 *
 * La tablette d'essai n'est pas toujours provisionnée en administrateur : sans
 * le second chemin, le bouton n'y ferait RIEN, et on conclurait que la fonction
 * est cassée alors qu'elle marche chez Jean. Le chemin emprunté est donc écrit
 * dans le journal à chaque fois.
 *
 * ═══ CE QUE ÇA NE FAIT PAS ═══
 *
 * Rien n'est arrêté : ni l'écoute des appels, ni le service, ni le fil
 * d'information. L'écran se rallume au premier appel, au premier son de la
 * pièce si le réveil au son est actif, et au prochain changement de titre. Jean
 * ne peut donc pas se couper du monde en appuyant sur ce bouton, ce qui est la
 * condition pour le lui donner.
 */
object MiseEnVeille {

    /**
     * @param fenêtre la fenêtre de l'écran qui demande la veille, pour lui
     *   retirer son maintien allumé. Sans ce retrait, le drapeau reposé à
     *   chaque battement (voir MainActivity.screenAwakeTicker) rallumerait
     *   l'écran dans la seconde — le bouton aurait alors l'air de ne rien
     *   faire, ce qui est pire que de ne pas exister.
     */
    fun endormir(context: Context, fenêtre: android.view.Window?) {
        fenêtre?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(context, SeniorVisioDeviceAdminReceiver::class.java)
        if (dpm != null && dpm.isAdminActive(admin)) {
            runCatching { dpm.lockNow() }
                .onSuccess { CallTrace.record("VEILLE demandée", "extinction immédiate (administrateur d'appareil)") }
                .onFailure { e ->
                    // Refus du système malgré le statut d'administrateur : dit,
                    // et non avalé. Sans cette ligne, le bouton resterait sans
                    // effet visible et sans explication nulle part.
                    Log.w(TAG, "lockNow refusé", e)
                    CallTrace.record("VEILLE refusée", "${e.javaClass.simpleName} — repli sur la temporisation")
                }
        } else {
            CallTrace.record(
                "VEILLE demandée",
                "pas administrateur d'appareil — l'écran s'éteindra à la temporisation du système",
            )
        }
    }

    private const val TAG = "MiseEnVeille"
}
