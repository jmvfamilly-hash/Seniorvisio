package com.seniorvisio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.firebase.firestore.ListenerRegistration
import com.seniorvisio.core.CallerPhotoCache
import com.seniorvisio.core.DeviceStatusReporter
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

    // Sans ce verrou, Android coupe l'économiseur d'énergie Wi-Fi une fois
    // l'écran éteint : l'association tombe au bout de quelques heures, et la
    // tablette devient injoignable (plus d'appel entrant, plus de mise à jour
    // à distance, plus de signe de vie) jusqu'à ce que quelqu'un la réveille
    // à la main. Le statut foreground du service ne protège que le processus,
    // pas la liaison Wi-Fi elle-même. HIGH_PERF plutôt que FULL (sans effet
    // depuis Android 10) : la tablette est sur secteur en permanence, le
    // surcoût en énergie est sans importance ici.
    private var wifiLock: WifiManager.WifiLock? = null
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
        acquireWifiLock()
        startListening()
        statusReporter.listenForRemoteCommands()
        heartbeatHandler.post(heartbeatRunnable)
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SeniorVisio:KeepWifiAlive"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (callListener == null) startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (!signaling.isAvailable()) return
        callListener = signaling.listenForRingingCalls { callId, callerName, callerPhotoBase64 ->
            // La photo passe par un fichier, jamais par l'extra directement
            // (voir CallerPhotoCache) : au-delà d'une certaine taille, elle
            // fait planter ce démarrage de service avec
            // TransactionTooLargeException, sans aucun écran d'appel affiché.
            val alertIntent = Intent(this, IncomingCallService::class.java).apply {
                putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
                putExtra(IncomingCallService.EXTRA_CALLER_NAME, callerName)
                putExtra(IncomingCallService.EXTRA_CALLER_PHOTO_PATH, CallerPhotoCache.save(this@CallListenerService, callerPhotoBase64))
            }
            startForegroundService(alertIntent)
        }
    }

    override fun onDestroy() {
        callListener?.remove()
        callListener = null
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
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
