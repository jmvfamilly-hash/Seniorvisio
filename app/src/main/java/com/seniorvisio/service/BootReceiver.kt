package com.seniorvisio.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Relance CallListenerService au démarrage de la tablette, sans avoir à
 * rouvrir l'app à la main — et aussi juste après une mise à jour de l'appli
 * (ACTION_MY_PACKAGE_REPLACED) : sans ce second cas, une mise à jour
 * silencieuse poussée par un MDM (Headwind) tue le processus en cours sans
 * jamais le relancer, laissant le service d'écoute des appels éteint jusqu'à
 * un redémarrage ou une ouverture manuelle — tout appel tenté entre-temps
 * serait raté (voir listenForRingingCalls, qui ignore volontairement tout
 * appel déjà "en sonnerie" au moment où l'écoute démarre).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ContextCompat.startForegroundService(context, Intent(context, CallListenerService::class.java))
        }
    }
}
