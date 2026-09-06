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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.TranscriptionEngine
import com.seniorvisio.core.TranscriptionSource
import com.seniorvisio.core.VoskModelProvider
import com.seniorvisio.ui.MainActivity
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
 * (voir MainActivity, zone 2 de l'écran, et startRoomTranscription) : la même
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

    private var transcription: TranscriptionEngine? = null
    private var lastRoomSoundAtMs = 0L

    @Volatile private var lastRms = 0
    @Volatile private var lastCaptureError: String? = null
    @Volatile private var wakeRequests = 0
    private var lastWakeRequestAtMs = 0L
    private var captureRetries = 0
    private val retryHandler = Handler(Looper.getMainLooper())

    /**
     * Photographie de ce que fait réellement le service, affichée en direct
     * dans l'écran admin (voir AdminSettingsActivity).
     *
     * Sans ça, le réveil au son se règle à l'aveugle : le seuil dépend du
     * microphone et de l'acoustique de la pièce, et rien ne permettait de voir
     * le niveau réellement mesuré face à ce seuil, ni de distinguer "le son
     * n'atteint pas le seuil" de "la capture ne tourne pas" ou de "le réveil
     * est désactivé". Trois causes très différentes, jusqu'ici impossibles à
     * départager sans brancher la tablette à un ordinateur.
     */
    data class Status(
        val capturing: Boolean,
        val lastRms: Int,
        val threshold: Int,
        val wakeEnabled: Boolean,
        val inNightWindow: Boolean,
        val wakeLockHeld: Boolean,
        val screenOn: Boolean,
        val wakeRequests: Int,
        val transcribing: Boolean,
        val captureError: String?,
        val voskModel: String,
    )

    fun currentStatus() = Status(
        capturing = isCapturing,
        lastRms = lastRms,
        threshold = adminConfig.roomWakeSensitivityThreshold,
        wakeEnabled = adminConfig.roomWakeEnabled,
        inNightWindow = adminConfig.blockWakeAtNight &&
            adminConfig.isCurrentlyNightWindow(LocalDateTime.now().hour),
        wakeLockHeld = wakeLock?.isHeld == true,
        screenOn = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true,
        wakeRequests = wakeRequests,
        transcribing = transcription?.activeSource() != null,
        captureError = lastCaptureError,
        voskModel = when (val modelState = VoskModelProvider.state()) {
            VoskModelProvider.State.Ready -> "prêt (transcription de la pièce gratuite)"
            VoskModelProvider.State.Downloading -> "téléchargement en cours (~45 Mo)"
            VoskModelProvider.State.Absent -> "pas encore demandé"
            is VoskModelProvider.State.Failed -> "échec : ${modelState.reason}"
        },
    )
    private var roomTranscriptionOnText: ((text: String, isFinal: Boolean) -> Unit)? = null
    private var roomTranscriptionOnError: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): RoomPresenceService = this@RoomPresenceService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        adminConfig = AdminConfig(this)
        startForeground(FOREGROUND_ID, buildForegroundNotification())
        // Le modèle de reconnaissance embarqué se télécharge une seule fois
        // (~45 Mo) : lancé ici, au démarrage du service permanent, pour qu'il
        // soit prêt bien avant qu'on en ait besoin. Sans effet s'il est déjà
        // en place (voir VoskModelProvider.prepare).
        VoskModelProvider.prepare(this)
    }

    /**
     * Lié par l'écran d'accueil (voir MainActivity, startRoomTranscription) —
     * reste par ailleurs un service démarré classique (startForegroundService)
     * pour le réveil au son, les deux modes de communication Android
     * coexistant sans conflit sur un même service.
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
        retryHandler.removeCallbacksAndMessages(null)

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            lastCaptureError = "configuration audio non supportée par cet appareil"
            Log.w(TAG, "Configuration audio non supportée par cet appareil, réveil au son désactivé")
            return
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2
            )
        } catch (e: SecurityException) {
            lastCaptureError = "permission micro refusée"
            Log.w(TAG, "Permission micro refusée, réveil au son désactivé", e)
            null
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            scheduleCaptureRetry()
            return
        }

        lastCaptureError = null
        captureRetries = 0
        audioRecord = record
        isCapturing = true
        record.startRecording()

        captureThread = Thread {
            val buffer = ShortArray(minBufferSize)
            while (isCapturing) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val rms = computeRms(buffer, read)
                    handleLevel(rms)
                    feedRoomTranscription(buffer, read, rms)
                }
            }
        }.apply { start() }
    }

    /**
     * Le micro est parfois encore tenu par quelqu'un d'autre au moment où on
     * le réclame — typiquement WebRTC en fin d'appel, dont la libération se
     * fait sur un autre fil et prend un instant. Sans ce réessai, l'échec
     * était silencieux et définitif : plus aucune surveillance du son jusqu'au
     * redémarrage de la tablette, sans le moindre signe extérieur.
     */
    private fun scheduleCaptureRetry() {
        if (captureRetries >= MAX_CAPTURE_RETRIES) {
            lastCaptureError = "micro indisponible après $MAX_CAPTURE_RETRIES tentatives"
            Log.w(TAG, "Micro toujours indisponible, abandon de la capture")
            return
        }
        captureRetries++
        lastCaptureError = "micro occupé, nouvelle tentative ($captureRetries/$MAX_CAPTURE_RETRIES)"
        retryHandler.postDelayed({ startCapture() }, CAPTURE_RETRY_DELAY_MS)
    }

    private fun stopCapture() {
        retryHandler.removeCallbacksAndMessages(null)
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
        transcription?.stop()
    }

    /**
     * Démarre les sous-titres de la pièce (voir MainActivity, zone 2), en
     * réutilisant la capture micro déjà en cours ou en la démarrant si besoin
     * (ex. réveil au son désactivé, voir startCapture). onError remonte un
     * échec de connexion AssemblyAI à l'écran appelant, sans quoi seul le
     * journal système (inaccessible ici) le révélerait.
     *
     * "Démarre" ne veut pas dire "transcrit en permanence" : voir
     * feedRoomTranscription, qui n'ouvre une session AssemblyAI que le temps
     * qu'il y a effectivement du son dans la pièce.
     */
    fun startRoomTranscription(onText: (text: String, isFinal: Boolean) -> Unit, onError: (String) -> Unit = {}) {
        roomTranscriptionOnText = onText
        roomTranscriptionOnError = onError
        transcription = TranscriptionEngine(
            context = this,
            onText = { _, text, isFinal -> roomTranscriptionOnText?.invoke(text, isFinal) },
            onDiagnostic = { message -> roomTranscriptionOnError?.invoke(message) },
        )
        startCapture()
    }

    /** À appeler quand l'écran qui affiche les paroles de la pièce passe en arrière-plan. */
    fun stopRoomTranscription() {
        roomTranscriptionOnText = null
        roomTranscriptionOnError = null
        transcription?.stop()
        transcription = null
    }

    /**
     * N'ouvre une session AssemblyAI que tant qu'il y a du son dans la pièce,
     * et la referme après quelques secondes de silence.
     *
     * AssemblyAI est facturé à la durée de connexion : laisser la session
     * ouverte en permanence sur une tablette allumée 24h/24 coûterait une
     * centaine d'euros par mois pour transcrire, l'essentiel du temps, une
     * pièce vide. Le seuil réutilisé est celui du réveil au son, déjà réglé
     * sur place pour cette pièce et ce microphone (voir AdminConfig) — une
     * seule sensibilité à ajuster, pas deux qui se contredisent.
     *
     * Le maintien de quelques secondes après le dernier son évite de couper
     * la session entre deux phrases d'une même conversation, ce qui ferait
     * perdre le début de la phrase suivante le temps de rétablir la connexion.
     */
    private fun feedRoomTranscription(buffer: ShortArray, length: Int, rms: Double) {
        val engine = transcription ?: return

        val now = System.currentTimeMillis()
        if (rms >= adminConfig.roomWakeSensitivityThreshold) lastRoomSoundAtMs = now
        // Silence prolongé : on rend la source inactive, ce qui ferme la
        // session AssemblyAI. Elle se rouvrira au premier son suivant.
        val someoneIsSpeaking = now - lastRoomSoundAtMs <= TRANSCRIPTION_HOLD_MS
        engine.setActiveSource(if (someoneIsSpeaking) TranscriptionSource.ROOM else null)
        if (!someoneIsSpeaking) return

        val bytes = ByteArray(length * 2)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) byteBuffer.putShort(buffer[i])
        engine.feed(TranscriptionSource.ROOM, bytes, SAMPLE_RATE_HZ, 1)
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
        lastRms = rms.toInt()
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
    /**
     * Rallume l'écran quand un son dépasse le seuil.
     *
     * Deux mécanismes, et le second fait tout le travail sur les versions
     * récentes d'Android. Le verrou de réveil d'écran est déprécié depuis
     * longtemps et n'a plus d'effet garanti : il a cessé d'allumer l'écran sur
     * cette tablette sans que rien dans le code ne change, ce qui est
     * exactement le mode d'échec d'une interface dépréciée que le système
     * finit par ignorer. Il est conservé — il ne coûte rien et fonctionne
     * encore sur certaines versions — mais il ne suffit plus.
     *
     * Le second est celui qui marche déjà pour les appels entrants (voir
     * IncomingCallActivity, setTurnScreenOn) : amener l'écran d'accueil au
     * premier plan en lui demandant d'allumer la dalle. C'est la méthode que
     * le système prévoit aujourd'hui pour ça, et le commentaire de
     * IncomingCallService.launchAlertScreen constate déjà, pour l'appel
     * entrant, que l'ancienne ne suffit plus.
     */
    @Suppress("DEPRECATION")
    private fun ensureAwake() {
        if (!adminConfig.roomWakeEnabled) return
        if (adminConfig.blockWakeAtNight && adminConfig.isCurrentlyNightWindow(LocalDateTime.now().hour)) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

        if (wakeLock?.isHeld != true) {
            val lock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "SeniorVisio:RoomSoundWakeLock"
            )
            // Filet de sécurité en cas de bug empêchant le release explicite
            // (voir handleLevel) : jamais un écran forcé allumé indéfiniment.
            lock.acquire(MAX_WAKE_LOCK_MS)
            wakeLock = lock
        }

        // Écran déjà allumé : rien à faire de plus, et surtout ne pas ramener
        // l'écran d'accueil au premier plan par-dessus ce que Jean regarde.
        if (powerManager.isInteractive) return

        val now = System.currentTimeMillis()
        if (now - lastWakeRequestAtMs < WAKE_REQUEST_MIN_INTERVAL_MS) return
        lastWakeRequestAtMs = now
        wakeRequests++
        retryHandler.post {
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_WAKE_ON_SOUND, true)
                )
            } catch (e: Exception) {
                // Démarrage d'activité refusé par le système : on le dit dans
                // l'état affiché côté admin plutôt que de rester muet.
                lastCaptureError = "réveil de l'écran refusé : ${e.message}"
                Log.w(TAG, "Impossible de rallumer l'écran", e)
            }
        }
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

        /**
         * Durée de maintien de la session AssemblyAI après le dernier son
         * détecté (voir feedRoomTranscription). Plus long que SILENCE_HOLD_MS,
         * qui ne pilote que l'écran : une pause de réflexion au milieu d'une
         * phrase dure facilement plus de trois secondes, et rétablir la
         * connexion coûte le début de la phrase suivante.
         */
        private const val TRANSCRIPTION_HOLD_MS = 8_000L
        private const val MAX_WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val CAPTURE_RETRY_DELAY_MS = 2_000L
        private const val MAX_CAPTURE_RETRIES = 15

        /** Un seul rallumage d'écran demandé par intervalle : le son arrive par blocs, plusieurs fois par seconde. */
        private const val WAKE_REQUEST_MIN_INTERVAL_MS = 5_000L
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
