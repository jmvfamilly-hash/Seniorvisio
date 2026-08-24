package com.seniorvisio.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Reçoit le résultat (succès/échec) d'une installation silencieuse déclenchée
 * par DeviceStatusReporter.silentInstall, pour le remonter dans Firestore —
 * sans ça, un échec d'installation (APK corrompu, espace insuffisant...)
 * resterait invisible : la tablette continuerait de tourner sur l'ancienne
 * version sans que personne ne le sache tant qu'un appel ne révèle le
 * problème.
 */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val success = status == PackageInstaller.STATUS_SUCCESS
        Log.i(TAG, "Résultat de la mise à jour à distance : ${if (success) "succès" else "échec ($message)"}")
        FirebaseFirestore.getInstance().document("devices/jean_tablet").set(
            mapOf(
                "lastUpdateSucceeded" to success,
                "lastUpdateMessage" to (message ?: ""),
                "lastUpdateAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge()
        )
    }

    companion object {
        private const val TAG = "UpdateStatusReceiver"
    }
}
