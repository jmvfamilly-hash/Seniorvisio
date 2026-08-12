package com.seniorvisio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.firebase.firestore.ListenerRegistration
import com.seniorvisio.signaling.CallSignalingClient

/**
 * Service de premier plan permanent : garde une écoute Firestore active en
 * continu (même écran éteint, app en arrière-plan), là où compter sur le
 * cycle de vie de MainActivity ne suffisait pas — dès que l'écran s'éteint
 * ou que l'app passe en arrière-plan, Android coupe le réseau des apps non
 * prioritaires (Doze/App Standby), sauf pour un vrai foreground service.
 *
 * Démarré une fois par MainActivity au lancement (et au boot, voir
 * BootReceiver) ; tourne indéfiniment avec une notification discrète.
 * Dès qu'un appel entrant apparaît dans Firestore, délègue à
 * IncomingCallService qui affiche l'écran plein écran (déjà capable de
 * réveiller l'appareil même verrouillé, voir IncomingCallActivity).
 */
class CallListenerService : LifecycleService() {

    private val signaling = CallSignalingClient()
    private var callListener: ListenerRegistration? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(FOREGROUND_ID, buildForegroundNotification())
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (callListener == null) startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (!signaling.isAvailable()) return
        callListener = signaling.listenForRingingCalls { callId, callerName ->
            val alertIntent = Intent(this, IncomingCallService::class.java).apply {
                putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
                putExtra(IncomingCallService.EXTRA_CALLER_NAME, callerName)
            }
            startForegroundService(alertIntent)
        }
    }

    override fun onDestroy() {
        callListener?.remove()
        callListener = null
        super.onDestroy()
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Écoute des appels Senior Visio", NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Senior Visio")
            .setContentText("En attente d'appel")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val FOREGROUND_ID = 43
        private const val CHANNEL_ID = "senior_visio_listener"
    }
}
