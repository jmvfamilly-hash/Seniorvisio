package com.seniorvisio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.firebase.firestore.ListenerRegistration
import com.seniorvisio.core.DeviceStatusReporter
import com.seniorvisio.core.VoskModelProvider
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

    // Remplace le tableau de bord Headwind (abandonné, voir README > Déploiement) :
    // statut régulier + mise à jour à distance, portés par ce service permanent
    // plutôt qu'un composant séparé, pour ne pas dépendre d'un cycle de vie
    // supplémentaire à maintenir en vie.
    private val statusReporter = DeviceStatusReporter(this)
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            statusReporter.reportHeartbeat()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(FOREGROUND_ID, buildForegroundNotification())
        // Démarré ici plutôt qu'à l'ouverture d'un appel : le modèle Vosk
        // prend du temps à télécharger (~45 Mo) et à charger, autant lancer
        // ça dès que la tablette est prête à recevoir des appels (voir
        // VoskModelProvider, idempotent).
        VoskModelProvider.prepare(applicationContext)
        startListening()
        statusReporter.listenForRemoteUpdate()
        heartbeatHandler.post(heartbeatRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (callListener == null) startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (!signaling.isAvailable()) return
        callListener = signaling.listenForRingingCalls { callId, callerName, callerPhotoBase64 ->
            val alertIntent = Intent(this, IncomingCallService::class.java).apply {
                putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
                putExtra(IncomingCallService.EXTRA_CALLER_NAME, callerName)
                putExtra(IncomingCallService.EXTRA_CALLER_PHOTO, callerPhotoBase64)
            }
            startForegroundService(alertIntent)
        }
    }

    override fun onDestroy() {
        callListener?.remove()
        callListener = null
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
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
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
    }
}
