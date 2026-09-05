package com.seniorvisio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seniorvisio.core.AdminConfig
import java.time.LocalDateTime
import kotlin.math.sqrt

/**
 * Réveille l'écran au moindre son détecté dans la pièce, et ne rend la main
 * au mécanisme de mise en veille normal qu'une fois le silence revenu — la
 * tablette n'a pas de capteur de présence dédié (pas de PIR, pas de caméra
 * grand angle exploitable pour ça), le microphone est le seul capteur déjà
 * présent et déjà autorisé (RECORD_AUDIO) qui puisse jouer ce rôle.
 *
 * Tourne en continu, démarré une fois par MainActivity (et au boot, voir
 * BootReceiver) comme CallListenerService — sans un vrai foreground service,
 * Android coupe l'accès au microphone dès que l'app passe en arrière-plan ou
 * que l'écran s'éteint, exactement le moment où cette fonction doit agir.
 *
 * Suspendue pendant un vrai appel (voir pauseForCall/resumeAfterCall,
 * appelées depuis IncomingCallActivity) : un seul composant à la fois peut
 * tenir le microphone, et l'appel (WebRTC) est prioritaire.
 *
 * Seuil de déclenchement et durée de silence réglables depuis le panneau
 * admin (voir AdminConfig.roomWakeSensitivityThreshold) : la sensibilité
 * dépend du microphone et de l'acoustique de la pièce, impossible à calibrer
 * une fois pour toutes sans avoir la tablette en main — à ajuster sur place
 * si le réveil est trop capricieux (déclenche pour rien) ou trop mou (ne
 * déclenche pas assez).
 */
class RoomPresenceService : Service() {

    private lateinit var adminConfig: AdminConfig
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLoudAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        adminConfig = AdminConfig(this)
        startForeground(FOREGROUND_ID, buildForegroundNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> stopCapture()
            ACTION_RESUME -> startCapture()
            else -> startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture() {
        if (isCapturing) return
        if (!adminConfig.roomWakeEnabled) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            Log.w(TAG, "Configuration audio non supportée par cet appareil, réveil au son désactivé")
            return
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission micro refusée, réveil au son désactivé", e)
            null
        } ?: return

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        audioRecord = record
        isCapturing = true
        record.startRecording()

        captureThread = Thread {
            val buffer = ShortArray(minBufferSize)
            while (isCapturing) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) handleLevel(computeRms(buffer, read))
            }
        }.apply { start() }
    }

    private fun stopCapture() {
        isCapturing = false
        captureThread?.interrupt()
        captureThread = null
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // Pas démarré ou déjà arrêté : sans conséquence.
            }
            it.release()
        }
        audioRecord = null
        releaseWakeLockIfHeld()
    }

    private fun computeRms(buffer: ShortArray, length: Int): Double {
        var sumOfSquares = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sumOfSquares += sample * sample
        }
        return sqrt(sumOfSquares / length)
    }

    /**
     * Appelée à chaque bloc audio lu (plusieurs fois par seconde) : déclenche
     * le réveil dès que le niveau dépasse le seuil, et vérifie à chaque
     * passage si le silence dure depuis assez longtemps pour rendre la main
     * au mécanisme de mise en veille normal — pas besoin d'une minuterie
     * séparée, les blocs audio arrivent déjà à un rythme largement suffisant.
     */
    private fun handleLevel(rms: Double) {
        val now = System.currentTimeMillis()
        if (rms >= adminConfig.roomWakeSensitivityThreshold) {
            lastLoudAtMs = now
            ensureAwake()
        } else if (now - lastLoudAtMs > SILENCE_HOLD_MS) {
            releaseWakeLockIfHeld()
        }
    }

    /**
     * Combinaison dépréciée depuis l'API 17 (remplacée pour les usages en
     * premier plan par Activity.setTurnScreenOn, voir IncomingCallActivity),
     * mais toujours pleinement fonctionnelle et strictement le seul outil
     * prévu pour ce cas précis : réveiller l'écran depuis un composant sans
     * fenêtre (ce service), sur un simple événement capteur, puis rendre la
     * main au minuteur de veille système via ON_AFTER_RELEASE plutôt que
     * d'éteindre l'écran d'un coup.
     */
    @Suppress("DEPRECATION")
    private fun ensureAwake() {
        if (!adminConfig.roomWakeEnabled) return
        if (adminConfig.nightModeEnabled && adminConfig.isCurrentlyNightWindow(LocalDateTime.now().hour)) return
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "SeniorVisio:RoomSoundWakeLock"
        )
        // Filet de sécurité en cas de bug empêchant le release explicite
        // (voir handleLevel) : jamais un écran forcé allumé indéfiniment.
        lock.acquire(MAX_WAKE_LOCK_MS)
        wakeLock = lock
    }

    private fun releaseWakeLockIfHeld() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Réveil au son Senior Visio", NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Senior Visio")
            .setContentText("Écoute la pièce pour réveiller l'écran")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RoomPresenceService"
        private const val FOREGROUND_ID = 45
        private const val CHANNEL_ID = "senior_visio_room_presence"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val SILENCE_HOLD_MS = 3_000L
        private const val MAX_WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val ACTION_PAUSE = "com.seniorvisio.action.PAUSE_ROOM_PRESENCE"
        private const val ACTION_RESUME = "com.seniorvisio.action.RESUME_ROOM_PRESENCE"

        /** Suspend l'écoute le temps d'un vrai appel (voir IncomingCallActivity) : le micro ne peut servir qu'à un composant à la fois. */
        fun pauseForCall(context: Context) {
            context.startService(Intent(context, RoomPresenceService::class.java).setAction(ACTION_PAUSE))
        }

        /** Reprend l'écoute une fois l'appel terminé. */
        fun resumeAfterCall(context: Context) {
            context.startService(Intent(context, RoomPresenceService::class.java).setAction(ACTION_RESUME))
        }
    }
}
