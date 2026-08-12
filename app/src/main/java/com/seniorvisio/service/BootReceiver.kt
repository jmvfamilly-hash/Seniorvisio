package com.seniorvisio.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Relance CallListenerService au démarrage de la tablette, sans avoir à rouvrir l'app à la main. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ContextCompat.startForegroundService(context, Intent(context, CallListenerService::class.java))
        }
    }
}
