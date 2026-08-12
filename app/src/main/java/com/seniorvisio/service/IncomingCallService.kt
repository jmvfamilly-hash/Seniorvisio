package com.seniorvisio.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "un proche"
        launchAlertScreen(callerName)
        return START_NOT_STICKY
    }

    private fun launchAlertScreen(callerName: String) {
        val alertIntent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("callerName", callerName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(alertIntent)
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
        private const val FOREGROUND_ID = 42
        private const val SERVICE_CHANNEL_ID = "senior_visio_service"
    }
}
