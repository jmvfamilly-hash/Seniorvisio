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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.AssemblyAiRealtimeTranscriber
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 *
 * Sert aussi de source unique du micro pour la transcription de la pièce
 * (voir RoomTranscriptionActivity, startRoomTranscription) : la même
 * capture déjà en cours pour le réveil au son est réutilisée, jamais une
 * deuxième capture concurrente — exactement le problème qui rendait les
 * sous-titres d'appel peu fiables (deux consommateurs du même micro),
 * résolu ici par construction plutôt qu'en espérant que le système tolère
 * les deux captures à la fois.
 */
class RoomPresenceService : Service() {

    private lateinit var adminConfig: AdminConfig
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLoudAtMs = 0L

    private var roomTranscriber: AssemblyAiRealtimeTranscriber? = null
    @Volatile private var roomTranscriptionActive = false
    private var roomTranscriptionOnText: ((text: String, isFinal: Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): RoomPresenceService = this@RoomPresenceService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        adminConfig = AdminConfig(this)
        startForeground(FOREGROUND_ID, buildForegroundNotification())
    }

    /**
     * Lié par RoomTranscriptionActivity (voir startRoomTranscription) — reste
     * par ailleurs un service démarré classique (startForegroundService) pour
     * le réveil au son, les deux modes de communication Android coexistant
     * sans conflit sur un même service.
     */
    override fun onBind(intent: Intent?): IBinder = binder

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

    /**
     * Volontairement pas de garde-fou sur roomWakeEnabled ici : la capture
     * sert aussi à la transcription de la pièce (voir startRoomTranscription),
     * qui doit rester disponible même quand le réveil au son est désactivé.
     * Ce réglage ne fait que dispenser ensureAwake() d'agir, plus bas.
     */
    private fun startCapture() {
        if (isCapturing) return

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
                if (read > 0) {
                    handleLevel(computeRms(buffer, read))
                    feedRoomTranscription(buffer, read)
                }
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
        roomTranscriber?.stop()
        roomTranscriber = null
    }

    /**
     * Démarre les sous-titres de la pièce (voir RoomTranscriptionActivity),
     * en réutilisant la capture micro déjà en cours ou en la démarrant si
     * besoin (ex. réveil au son désactivé, voir startCapture).
     */
    fun startRoomTranscription(onText: (text: String, isFinal: Boolean) -> Unit) {
        roomTranscriptionOnText = onText
        roomTranscriptionActive = true
        startCapture()
    }

    /** À appeler quand l'écran de transcription de la pièce se ferme. */
    fun stopRoomTranscription() {
        roomTranscriptionActive = false
        roomTranscriptionOnText = null
        roomTranscriber?.stop()
        roomTranscriber = null
    }

    private fun feedRoomTranscription(buffer: ShortArray, length: Int) {
        if (!roomTranscriptionActive) return
        val onText = roomTranscriptionOnText ?: return
        val apiKey = adminConfig.assemblyAiApiKey
        if (apiKey.isBlank()) return
        val instance = roomTranscriber ?: AssemblyAiRealtimeTranscriber(apiKey).also {
            roomTranscriber = it
            it.start(onText) { message -> Log.w(TAG, "AssemblyAI temps réel (pièce) : $message") }
        }
        val bytes = ByteArray(length * 2)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) byteBuffer.putShort(buffer[i])
        instance.sendAudio(bytes, SAMPLE_RATE_HZ, 1)
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
