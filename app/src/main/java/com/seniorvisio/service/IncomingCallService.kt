package com.seniorvisio.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.ui.IncomingCallActivity

/**
 * Service de premier plan (Foreground Service) déclenché par un appel
 * entrant (push signaling, voir TODO récepteur). Rôle unique désormais :
 * afficher IncomingCallActivity, qui gère elle-même le compte à rebours
 * de 30s (paramétrable via AdminConfig) et le bouton de blocage.
 *
 * La logique de décision (bloquer / connecter) est entièrement dans
 * IncomingCallActivity + TimedCallAlertController — ce service ne fait
 * que réveiller l'app et garder le processus vivant pendant l'appel.
 */
class IncomingCallService : LifecycleService() {

    private lateinit var adminConfig: AdminConfig

    override fun onCreate() {
        super.onCreate()
        adminConfig = AdminConfig(this)
        startForeground(FOREGROUND_ID, buildForegroundNotification())
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val signalReceivedAtMs = System.currentTimeMillis()
        // Réveil CPU immédiat dès la confirmation de l'appel entrant, avant
        // même l'affichage de l'écran d'alerte : sur certains appareils
        // (notamment les surcouches constructeur type Samsung), ce court
        // délai entre la réception du signal et le démarrage effectif de
        // l'Activity peut sinon être retardé par les optimisations de veille.
        // Toujours acquis avec un timeout de sécurité (voir
        // CONSIGNES_veille_reveil_appel.md) pour ne jamais fuiter en cas de
        // crash avant le release() ci-dessous.
        acquireWakeLock()
        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "un proche"
        val callerPhoto = intent?.getStringExtra(EXTRA_CALLER_PHOTO)
        val callId = intent?.getStringExtra(EXTRA_CALL_ID)
        if (callId != null) {
            launchAlertScreen(callId, callerName, callerPhoto, signalReceivedAtMs)
        }
        releaseWakeLock()
        // Le rôle de ce service s'arrête ici : IncomingCallActivity gère seule
        // la suite (décompte, appel, raccroché). Sans ce stopSelf(), le
        // service restait actif indéfiniment après le tout premier appel — sa
        // notification "Senior Visio actif" ne disparaissait jamais.
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SeniorVisio:IncomingCallWakeLock")
        lock.acquire(10_000L)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Démarrer l'Activity directement depuis ce service ne suffit plus de
     * façon fiable sur les versions récentes d'Android : constaté en test
     * réel (Android 16), l'écran d'appel ne s'affichait jamais si l'appli
     * n'avait pas déjà un contexte visible récent — les restrictions de
     * lancement d'activité en arrière-plan se sont renforcées à chaque
     * version depuis Android 10, indépendamment du réglage de mise en veille
     * par app (déjà vérifié non responsable ici). La voie officiellement
     * prévue par Android pour ce cas précis (appli d'appel) est une
     * notification "plein écran" (`setFullScreenIntent`), seule autorisée à
     * afficher une Activity par-dessus l'écran verrouillé/éteint depuis un
     * contexte non visible — d'où la permission USE_FULL_SCREEN_INTENT déjà
     * déclarée dans le manifest. `startActivity` reste tenté en complément,
     * sans conséquence s'il échoue silencieusement.
     */
    private fun launchAlertScreen(callId: String, callerName: String, callerPhotoBase64: String?, signalReceivedAtMs: Long) {
        val alertIntent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
            putExtra("callerName", callerName)
            putExtra(IncomingCallActivity.EXTRA_CALLER_PHOTO, callerPhotoBase64)
            putExtra(IncomingCallActivity.EXTRA_SIGNAL_RECEIVED_AT, signalReceivedAtMs)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, callId.hashCode(), alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CALL_CHANNEL_ID, "Appel entrant Senior Visio", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Appel entrant")
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(CALL_NOTIFICATION_ID, notification)

        try {
            startActivity(alertIntent)
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
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Senior Visio actif")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_CALLER_PHOTO = "extra_caller_photo"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val CALL_NOTIFICATION_ID = 44
        private const val FOREGROUND_ID = 42
        private const val SERVICE_CHANNEL_ID = "senior_visio_service"
        private const val CALL_CHANNEL_ID = "senior_visio_incoming_call"
    }
}
