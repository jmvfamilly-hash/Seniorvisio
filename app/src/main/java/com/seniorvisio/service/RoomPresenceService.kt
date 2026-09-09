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
import com.seniorvisio.core.AndroidSpeechSession
import com.seniorvisio.core.RoomVoiceGate
import com.seniorvisio.core.TranscriptionEngine
import com.seniorvisio.core.TranscriptionEngineChoice
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

    /**
     * Écrit depuis le fil de capture (handleLevel) et depuis le fil principal
     * (moteur Android), lu par le chien de garde de silence : volatile, sinon
     * rien ne garantit que la valeur écrite d'un côté soit vue de l'autre.
     */
    @Volatile private var lastLoudAtMs = 0L

    private var transcription: TranscriptionEngine? = null
    private var voiceGate: RoomVoiceGate? = null
    private var androidSpeech: AndroidSpeechSession? = null
    private var lastRoomSoundAtMs = 0L

    @Volatile private var lastRms = 0
    @Volatile private var peakRmsSinceReport = 0
    @Volatile private var lastCaptureError: String? = null
    @Volatile private var wakeRequests = 0
    private var lastWakeRequestAtMs = 0L
    private var captureRetries = 0
    private val retryHandler = Handler(Looper.getMainLooper())

    /**
     * Séparé de retryHandler, qui se fait vider entièrement
     * (removeCallbacksAndMessages) à chaque arrêt de capture : le chien de
     * garde qui rend la tablette à sa veille ne doit pas disparaître avec.
     */
    private val wakeHandler = Handler(Looper.getMainLooper())

    /**
     * Rend la main à la veille quand le silence dure. Armé à la prise du
     * verrou de réveil, il se relance tant que le verrou est tenu.
     *
     * Existe parce que ce relâchement ne peut pas dépendre du mécanisme
     * d'écoute. Il était jusqu'ici dans handleLevel, c'est-à-dire dans la
     * boucle de capture AudioRecord — donc nulle part quand l'écoute passait
     * par le moteur de reconnaissance d'Android, qui tient le micro lui-même
     * et ne nous fait traverser aucune boucle. Résultat : le premier mot
     * prononcé prenait un verrou d'écran allumé de trente minutes que plus
     * rien ne venait relâcher, et le suivant en reprenait un. La tablette ne
     * s'endormait plus jamais.
     *
     * Le verrou expire de lui-même au bout de MAX_WAKE_LOCK_MS, ce qui a
     * probablement évité une tablette allumée en continu jusqu'à épuisement —
     * mais un filet de sécurité de trente minutes n'est pas une politique de
     * veille.
     */
    private val silenceWatchdog = object : Runnable {
        override fun run() {
            if (wakeLock?.isHeld != true) return
            if (System.currentTimeMillis() - lastLoudAtMs > SILENCE_HOLD_MS) {
                releaseWakeLockIfHeld()
                return
            }
            wakeHandler.postDelayed(this, SILENCE_WATCHDOG_TICK_MS)
        }
    }

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
        /** Lequel des deux mécanismes tient le micro (voir startListening). */
        val listeningMode: String,

        /**
         * Niveau instantané et seuil du moteur d'Android, en décibels relatifs
         * à ce moteur — nuls quand ce n'est pas lui qui écoute. Séparés de
         * lastRms et threshold volontairement : ce ne sont pas les mêmes
         * unités, et les confondre dans un même champ ferait comparer des
         * valeurs qui n'ont rien à voir.
         */
        val androidLevelDb: Float? = null,
        val androidThresholdDb: Float? = null,

        /**
         * Part du temps jugée vocale par le portier depuis la dernière
         * lecture, en pourcentage. Nulle quand le portier ne tourne pas — ce
         * qui est le cas dès que la pièce est sur un moteur gratuit.
         */
        val voiceSharePercent: Int? = null,
    )

    /**
     * Le plus fort niveau mesuré depuis la dernière lecture, puis remis à zéro.
     * C'est celui-là qu'il faut comparer au seuil, pas le niveau instantané :
     * entre deux signes de vie il se passe cinq minutes, et l'instant précis
     * où l'on regarde a toutes les chances d'être un instant de silence.
     */
    fun consumePeakRms(): Int {
        val peak = peakRmsSinceReport
        peakRmsSinceReport = 0
        return peak
    }

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
        voskModel = VoskModelProvider.describeState(),
        listeningMode = if (androidSpeech?.isRunning() == true) "reconnaissance Android"
        else if (isCapturing) "capture interne"
        else "aucune écoute",
        androidLevelDb = androidSpeech?.takeIf { it.isRunning() }?.lastLevelDb(),
        androidThresholdDb = androidSpeech?.takeIf { it.isRunning() }?.wakeThresholdDb(),
        voiceSharePercent = voiceGate?.consumeVoiceShare(),
    )

    /**
     * Pendant du consumePeakRms de l'autre mécanisme d'écoute, et séparé de
     * currentStatus() pour la même raison qu'elle : lire le pic le remet à
     * zéro. L'écran d'administration de la tablette interroge l'état chaque
     * seconde ; si la lecture du pic était faite là, le signe de vie n'en
     * verrait plus jamais aucun, et inversement.
     */
    fun consumeAndroidPeakLevelDb(): Float? =
        androidSpeech?.takeIf { it.isRunning() }?.consumePeakLevelDb()
    private var roomTranscriptionOnText: ((text: String, isFinal: Boolean) -> Unit)? = null
    private var roomTranscriptionOnError: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): RoomPresenceService = this@RoomPresenceService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        running = this
        adminConfig = AdminConfig(this)
        startForeground(FOREGROUND_ID, buildForegroundNotification())
        // Le modèle de reconnaissance embarqué se télécharge une seule fois
        // (~45 Mo) : lancé ici, au démarrage du service permanent, pour qu'il
        // soit prêt bien avant qu'on en ait besoin. Sans effet s'il est déjà
        // en place (voir VoskModelProvider.prepare).
        VoskModelProvider.prepare(this, adminConfig.voskModelSize)
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
            ACTION_PAUSE -> stopListening()
            ACTION_RESUME -> startListening()
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        voiceGate?.close()
        voiceGate = null
        if (running === this) running = null
        super.onDestroy()
    }

    /**
     * Volontairement pas de garde-fou sur roomWakeEnabled ici : la capture
     * sert aussi à la transcription de la pièce (voir startRoomTranscription),
     * qui doit rester disponible même quand le réveil au son est désactivé.
     * Ce réglage ne fait que dispenser ensureAwake() d'agir, plus bas.
     */
    /**
     * Démarre l'écoute de la pièce par le mécanisme choisi par
     * l'administrateur — et c'est le seul endroit qui décide lequel.
     *
     * Deux mécanismes exclusifs, parce qu'un seul composant à la fois peut
     * tenir le micro. Le nôtre (AudioRecord) mesure le niveau sonore et
     * alimente un moteur qu'on nourrit ; celui d'Android écoute le micro
     * lui-même et ne nous laisse rien à mesurer — c'est donc lui qui signale
     * la parole pour le réveil (voir AndroidSpeechSession). Les lancer tous
     * les deux ferait échouer l'un des deux, au hasard.
     */
    private fun startListening() {
        if (adminConfig.roomEngine == TranscriptionEngineChoice.ANDROID) {
            stopCapture()
            startAndroidSpeech()
        } else {
            stopAndroidSpeech()
            startCapture()
        }
    }

    private fun startAndroidSpeech() {
        if (androidSpeech?.isRunning() == true) return
        val session = AndroidSpeechSession(
            context = this,
            onText = { text, isFinal -> roomTranscriptionOnText?.invoke(text, isFinal) },
            // Ce moteur ne nous donne pas de niveau sonore exploitable, mais il
            // dit quand quelqu'un se met à parler : c'est tout ce dont le
            // réveil a besoin, et c'est même plus sûr qu'un seuil à calibrer.
            onSpeechDetected = {
                lastLoudAtMs = System.currentTimeMillis()
                ensureAwake()
            },
            onDiagnostic = { message ->
                lastCaptureError = message
                roomTranscriptionOnError?.invoke(message)
            },
        )
        androidSpeech = session
        session.start()
    }

    private fun stopAndroidSpeech() {
        androidSpeech?.stop()
        androidSpeech = null
    }

    /**
     * Le moteur de la pièce vient de changer à distance (voir
     * DeviceStatusReporter) : on bascule de mécanisme sans attendre le
     * prochain redémarrage, sinon le réglage ne prendrait effet que des heures
     * plus tard, et personne ne comprendrait pourquoi.
     */
    fun onRoomEngineChanged() {
        startListening()
    }

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
        captureRetries++
        // Jamais d'abandon définitif. La version précédente s'arrêtait au bout
        // de quinze tentatives, soit trente secondes : passé ce délai, la
        // tablette ne réessayait plus jamais de prendre le micro. L'écoute de
        // la pièce ET le réveil au son étaient alors morts jusqu'au prochain
        // redémarrage, sans rien à l'écran pour le dire — il suffisait d'un
        // appel dont WebRTC tardait à relâcher le micro pour perdre la
        // fonction principale de l'appareil pour la journée.
        //
        // Une tablette dont le métier est d'écouter ne doit jamais renoncer à
        // écouter. L'espacement grandit pour ne pas marteler le système quand
        // l'indisponibilité dure (micro physiquement occupé, permission
        // retirée), mais il ne s'arrête pas.
        val delay = (CAPTURE_RETRY_DELAY_MS shl (captureRetries - 1).coerceAtMost(5))
            .coerceAtMost(CAPTURE_RETRY_MAX_DELAY_MS)
        lastCaptureError = "micro occupé, nouvelle tentative n°$captureRetries dans ${delay / 1000}s"
        if (captureRetries == 1 || captureRetries % 10 == 0) {
            Log.w(TAG, "Micro indisponible, tentative $captureRetries (nouvel essai dans ${delay / 1000}s)")
        }
        retryHandler.postDelayed({ startCapture() }, delay)
    }

    private fun stopListening() {
        stopAndroidSpeech()
        stopCapture()
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
        startListening()
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
     * pièce vide.
     *
     * Le seuil employé ici n'est PLUS celui du réveil au son. Il l'a été, au
     * nom d'« une seule sensibilité à ajuster, pas deux qui se
     * contredisent » — et c'était une erreur de raisonnement : les deux
     * réglages ne répondent pas à la même question. Le réveil demande « ce
     * bruit mérite-t-il d'allumer l'écran ? », et sa réponse doit être assez
     * exigeante pour ignorer un clavier à deux mètres. La transcription
     * demande « y a-t-il quelque chose à écrire ? », et sa réponse doit être
     * la plus généreuse possible.
     *
     * Les avoir confondus avait une conséquence qu'on ne pouvait pas voir
     * tant que le seuil restait bas : monter la sensibilité du réveil coupait
     * l'attaque de chaque phrase, toujours plus faible que son milieu.
     * Plusieurs mots perdus à chaque prise de parole, et un réglage censé ne
     * concerner que l'écran.
     *
     * Le maintien de quelques secondes après le dernier son évite de couper
     * la session entre deux phrases d'une même conversation.
     */
    private fun feedRoomTranscription(buffer: ShortArray, length: Int, rms: Double) {
        val engine = transcription ?: return

        val now = System.currentTimeMillis()

        // Le portier de voix ne sert — et ne tourne — que pour un moteur
        // facturé à la durée. Sur un moteur embarqué il n'économiserait rien
        // et coûterait un petit réseau de neurones toutes les 32 ms, sur une
        // tablette qui écoute toute la journée.
        val gated = adminConfig.roomEngine.billedByDuration && adminConfig.voiceGateEnabled
        val gate = if (gated) ensureVoiceGate() else null
        gate?.accept(buffer, length)

        // Voix pour un service payant, simple présence de son sinon. C'est
        // toute la différence : un aspirateur franchit un plancher de niveau,
        // il ne franchit pas une détection de parole.
        val heard = if (gate != null) gate.isVoiceActive() else rms >= SPEECH_FLOOR_RMS
        if (heard) lastRoomSoundAtMs = now
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

    /** Chargé à la première utilisation : inutile de payer le modèle si aucun moteur payant n'écoute. */
    private fun ensureVoiceGate(): RoomVoiceGate {
        voiceGate?.let { return it }
        return RoomVoiceGate(this).also { voiceGate = it }
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
        if (lastRms > peakRmsSinceReport) peakRmsSinceReport = lastRms
        if (rms >= adminConfig.roomWakeSensitivityThreshold) {
            lastLoudAtMs = System.currentTimeMillis()
            ensureAwake()
        }
        // Le relâchement n'est plus ici : il appartient au chien de garde de
        // silence, qui vaut pour les deux mécanismes d'écoute (voir
        // silenceWatchdog). Le laisser dans cette boucle revenait à ne rendre
        // la tablette à sa veille que sur l'un des deux.
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
            wakeHandler.removeCallbacks(silenceWatchdog)
            wakeHandler.postDelayed(silenceWatchdog, SILENCE_WATCHDOG_TICK_MS)
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
        wakeHandler.removeCallbacks(silenceWatchdog)
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
         * Cadence du chien de garde de silence. Une seconde : assez fin pour
         * que la veille reprenne à peu près quand elle le doit, assez lâche
         * pour ne rien coûter — et il ne tourne que tant que le verrou est
         * effectivement tenu, donc jamais sur une pièce vide.
         */
        private const val SILENCE_WATCHDOG_TICK_MS = 1_000L

        /**
         * Durée de maintien de la session AssemblyAI après le dernier son
         * détecté (voir feedRoomTranscription). Plus long que SILENCE_HOLD_MS,
         * qui ne pilote que l'écran : une pause de réflexion au milieu d'une
         * phrase dure facilement plus de trois secondes, et rétablir la
         * connexion coûte le début de la phrase suivante.
         */
        private const val TRANSCRIPTION_HOLD_MS = 8_000L

        /**
         * En dessous, on considère qu'il n'y a rien à écrire. Fixe et bas,
         * volontairement : c'est un plancher de présence de son, pas un
         * réglage de confort. Il vaut le seuil de silence du moteur lui-même
         * (voir TranscriptionEngine.SILENCE_LEVEL) pour que les deux étages de
         * la chaîne prennent la même décision — deux planchers différents, et
         * l'un des deux couperait ce que l'autre laisse passer.
         *
         * Ne pas remonter cette valeur pour régler des réveils intempestifs :
         * c'est le curseur de sensibilité qui sert à ça, et c'est précisément
         * de les avoir confondus que venait la perte des débuts de phrase.
         */
        private const val SPEECH_FLOOR_RMS = 300.0
        private const val MAX_WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val CAPTURE_RETRY_DELAY_MS = 2_000L

        /** Plafond de l'espacement entre deux tentatives : on insiste sans marteler. */
        private const val CAPTURE_RETRY_MAX_DELAY_MS = 60_000L

        /** Un seul rallumage d'écran demandé par intervalle : le son arrive par blocs, plusieurs fois par seconde. */
        private const val WAKE_REQUEST_MIN_INTERVAL_MS = 5_000L
        private const val ACTION_PAUSE = "com.seniorvisio.action.PAUSE_ROOM_PRESENCE"
        private const val ACTION_RESUME = "com.seniorvisio.action.RESUME_ROOM_PRESENCE"

        /**
         * Le service en cours d'exécution, ou null s'il n'a pas démarré.
         *
         * Lu par DeviceStatusReporter pour joindre l'état de l'écoute au signe
         * de vie : sans ça, le réveil au son ne se diagnostique qu'en marchant
         * jusqu'à la tablette et en entrant le code admin — exactement ce qu'on
         * ne peut pas faire quand on est à l'autre bout du pays et qu'on
         * constate que plus rien ne s'affiche chez Jean.
         *
         * Une référence statique vers un Service, ce qui se discute — mais
         * celui-ci est un foreground service permanent et unique, qui vit aussi
         * longtemps que le processus : il n'y a rien à fuiter qui ne soit déjà
         * là pour la durée.
         */
        @Volatile
        var running: RoomPresenceService? = null
            private set

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
