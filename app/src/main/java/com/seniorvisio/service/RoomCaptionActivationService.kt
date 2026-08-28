package com.seniorvisio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.seniorvisio.ui.MainActivity

/**
 * Service de premier plan déclenché par notification push (voir
 * SeniorVisioMessagingService, functions/index.js) quand le proche demande,
 * depuis le PWA (bouton "Réveiller & sous-titrer", sans appel vidéo),
 * d'activer les sous-titres de la pièce à distance — même tablette
 * verrouillée/éteinte. Même mécanisme qu'IncomingCallService (notification
 * plein écran) : un simple startActivity() depuis un contexte non visible
 * (ici le service de messagerie FCM) est bloqué sur les versions récentes
 * d'Android, la notification plein écran est la voie officiellement prévue.
 */
class RoomCaptionActivationService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(FOREGROUND_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        acquireWakeLock()
        launchMainActivity()
        releaseWakeLock()
        // Rôle terminé : MainActivity.handleRemoteActivationIntent prend le
        // relais seule (démarrage effectif des sous-titres). Sans ce
        // stopSelf(), le service restait actif indéfiniment après la
        // première demande.
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SeniorVisio:RoomCaptionWakeLock")
        lock.acquire(10_000L)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun launchMainActivity() {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ACTIVATE_ROOM_CAPTIONS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, REQUEST_CODE, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "Sous-titres à distance", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Sous-titres de la pièce")
            .setContentText("Activés à distance par un proche")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)

        try {
            startActivity(activityIntent)
        } catch (_: Exception) {
        }
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID, "Service Senior Visio", NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Senior Visio actif")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val FOREGROUND_ID = 45
        private const val NOTIFICATION_ID = 46
        private const val REQUEST_CODE = 4600
        private const val SERVICE_CHANNEL_ID = "senior_visio_service"
        private const val ALERT_CHANNEL_ID = "senior_visio_room_captions"
    }
}
