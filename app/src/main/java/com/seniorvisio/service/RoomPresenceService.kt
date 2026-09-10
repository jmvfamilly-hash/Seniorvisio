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
import com.seniorvisio.core.EagleSpeakerRecogniser
import com.seniorvisio.core.EmbeddedSpeakerRecogniser
import com.seniorvisio.core.RoomHandoffController
import com.seniorvisio.core.RoomVoiceGate
import com.seniorvisio.core.TranscriptionEngine
import com.seniorvisio.core.TranscriptionEngineChoice
import com.seniorvisio.core.SpeakerEngineChoice
import com.seniorvisio.core.SpeakerRecogniser
import com.seniorvisio.core.TranscriptionSource
import com.seniorvisio.core.VoiceSignature
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

    /**
     * Reconnaissance du locuteur, pour atténuer les paroles de Jean à l'écran
     * (voir SpeakerRecogniser et ses deux implémentations).
     *
     * Alimentée depuis la boucle de capture elle-même, sans jamais consulter ni
     * le moteur choisi ni l'interrupteur du portier de voix payant. C'est une
     * exigence, pas un détail d'écriture : ce portier-là ne tourne que sur un
     * moteur facturé à la durée, donc jamais dans la configuration courante, et
     * y accrocher la reconnaissance du locuteur aurait livré une fonction
     * silencieusement inerte.
     *
     * Reconstruite quand le moteur, la signature ou le seuil changent à
     * distance, sans quoi un réglage n'aurait d'effet qu'au prochain
     * redémarrage.
     */
    private var speakerGate: SpeakerRecogniser? = null

    /** Ce dont le portier en place a été construit, pour savoir quand le refaire. */
    private var speakerGateKey: String? = null

    /** Pourquoi la reconnaissance ne fonctionne pas, quand elle ne fonctionne pas. */
    @Volatile private var speakerGateError: String? = null

    /** Avant cette date, on ne retente pas de construire un portier qui vient d'échouer. */
    private var speakerGateRetryAtMs = 0L

    /**
     * Bascule vers Transcription instantanée sur voix entendue (voir
     * RoomHandoffController). Créé paresseusement : inutile tant que le mode
     * n'est pas armé.
     */
    private var handoff: RoomHandoffController? = null

    /**
     * Vrai quand on a relâché le micro exprès, au profit d'une autre
     * application.
     *
     * Sans ce drapeau, scheduleCaptureRetry — qui par conception n'abandonne
     * JAMAIS — reprendrait le micro à Transcription instantanée toutes les
     * quelques secondes et la rendrait inutilisable. Cette obstination est une
     * qualité dans tous les autres cas : une tablette dont le métier est
     * d'écouter ne doit pas renoncer à écouter. Il faut donc lui dire
     * explicitement que ce silence-ci est voulu.
     */
    @Volatile private var micYieldedToCompanion = false

    /** Apprentissage de la voix de Jean en cours, ou null (voir startVoiceEnrollment). */
    private var enrollment: SpeakerRecogniser? = null
    private var enrollmentEndsAtMs = 0L
    @Volatile private var enrollmentProgress = 0f

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

        /**
         * Reconnaissance du locuteur (voir SpeakerRecogniser) : ce qu'elle fait,
         * et ce qu'elle mesure.
         *
         * [speakerMode] dit en une phrase pourquoi elle agit ou n'agit pas —
         * voix non enregistrée, atténuation coupée, moteur d'Android qui ne
         * laisse aucun son à analyser. Sans cette phrase, les trois cas
         * ressemblent tous à « ça ne marche pas », et se corrigent de trois
         * façons différentes.
         *
         * [jeanSimilarityPercent] est la ressemblance réellement mesurée sur la
         * dernière prise de parole. C'est de là que doit venir le réglage du
         * seuil, pas d'une valeur devinée : faire parler Jean puis un proche et
         * lire les deux nombres dit immédiatement où placer la limite.
         */
        val speakerMode: String = "",

        /**
         * Faux quand le mécanisme d'écoute en cours ne nous livre aucun son —
         * c'est le cas de la reconnaissance d'Android, qui tient le microphone
         * pour elle. Un booléen et non une comparaison sur [speakerMode] : ce
         * texte est destiné à être lu et reformulé, s'appuyer dessus pour
         * décider casserait silencieusement à la première retouche.
         */
        val canRecogniseSpeaker: Boolean = true,
        val jeanSimilarityPercent: Int? = null,
        val jeanSharePercent: Int? = null,
        val enrollmentProgressPercent: Int? = null,
        val enrollmentResult: String? = null,
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
        speakerMode = describeSpeakerRecognition(),
        canRecogniseSpeaker = adminConfig.roomEngine != TranscriptionEngineChoice.ANDROID,
        jeanSimilarityPercent = speakerGate?.lastSimilarityPercent(),
        jeanSharePercent = speakerGate?.consumeJeanSharePercent(),
        enrollmentProgressPercent = enrollmentProgress()?.let { (it * 100).toInt() },
        enrollmentResult = lastEnrollmentResult,
    )

    /**
     * Pourquoi la reconnaissance du locuteur agit, ou n'agit pas. Les causes
     * d'inaction sont énumérées dans l'ordre où elles s'appliquent, chacune
     * appelant une correction différente — et toutes se présentant, sans cette
     * phrase, sous la forme indistincte d'un écran qui n'atténue rien.
     */
    private fun describeSpeakerRecognition(): String {
        val engine = adminConfig.speakerEngine
        return when {
            enrollment != null -> "apprentissage en cours avec « ${engine.adminLabel} »"
            adminConfig.roomEngine == TranscriptionEngineChoice.ANDROID ->
                "impossible : la reconnaissance Android tient le micro, aucun son à analyser"
            !adminConfig.dimJeanSpeech -> "atténuation désactivée"
            // L'erreur avant tout le reste : c'est elle qui distingue « pas
            // encore de son » d'une clé absente ou d'un profil illisible, trois
            // situations qui se ressemblent exactement vues de l'écran.
            speakerGateError != null -> "${engine.adminLabel} — ${speakerGateError}"
            speakerGate == null -> "${engine.adminLabel} — en attente du premier son"
            else -> "${engine.adminLabel}, seuil ${adminConfig.jeanVoiceThresholdPercent(engine)}%"
        }
    }

    /**
     * Pendant du consumePeakRms de l'autre mécanisme d'écoute, et séparé de
     * currentStatus() pour la même raison qu'elle : lire le pic le remet à
     * zéro. L'écran d'administration de la tablette interroge l'état chaque
     * seconde ; si la lecture du pic était faite là, le signe de vie n'en
     * verrait plus jamais aucun, et inversement.
     */
    fun consumeAndroidPeakLevelDb(): Float? =
        androidSpeech?.takeIf { it.isRunning() }?.consumePeakLevelDb()
    /**
     * `fromJean` dit si la prise de parole en cours est attribuée à Jean (voir
     * SpeakerRecogniser). Porté par le texte lui-même et non consultable après
     * coup : le texte arrive avec du retard sur la parole, et interroger le
     * portier au moment de l'affichage donnerait l'identité de qui parle
     * maintenant, pas de qui a dit cette phrase-là.
     */
    private var roomTranscriptionOnText: ((text: String, isFinal: Boolean, fromJean: Boolean) -> Unit)? = null
    private var roomTranscriptionOnError: ((String) -> Unit)? = null

    /**
     * Faux dès qu'il y a le moindre doute — signature absente, verdict pas
     * encore rendu, moteur d'Android qui ne nous laisse aucun son à analyser.
     * Le doute profite à la lisibilité : afficher en clair une phrase de Jean
     * ne coûte que de la place, l'atténuer à tort retire à Jean ce qu'il a
     * besoin de lire.
     */
    private fun currentSpeakerIsJean(): Boolean = speakerGate?.currentSpeakerIsJean() == true

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
            ACTION_PAUSE -> {
                endHandoffForCall()
                stopListening()
            }
            ACTION_RESUME -> startListening()
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        voiceGate?.close()
        voiceGate = null
        // Eagle tient un modèle natif : le laisser derrière soi fuiterait de la
        // mémoire hors du tas Java, invisible aux outils habituels.
        speakerGate?.close()
        speakerGate = null
        enrollment?.close()
        enrollment = null
        // Le bandeau de retour est une fenêtre système : la laisser derrière
        // soi la ferait flotter sur la tablette sans plus personne pour la
        // retirer.
        handoff?.close()
        handoff = null
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
            // Ce moteur tient le microphone lui-même et ne nous livre aucun
            // son : aucune reconnaissance du locuteur n'y est possible, et tout
            // s'affiche en clair. Dit dans l'état (voir Status.speakerMode)
            // plutôt que laissé à constater comme une panne.
            onText = { text, isFinal -> roomTranscriptionOnText?.invoke(text, isFinal, false) },
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
        // Micro cédé volontairement à une application compagne : ne pas le lui
        // reprendre. C'est le seul cas où cette boucle doit se taire.
        if (micYieldedToCompanion) return
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
                    // Avant la transcription, et sans condition liée à elle :
                    // savoir qui parle ne dépend ni du moteur choisi ni de
                    // l'écran affiché.
                    feedSpeakerRecognition(buffer, read, rms)
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
        if (micYieldedToCompanion) return
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

    /**
     * Un appel prend l'écran : la bascule doit cesser immédiatement.
     *
     * Sans ça, le bandeau de retour — une fenêtre système, posée au-dessus de
     * tout — flotterait par-dessus le visage de l'appelant, et l'état interne
     * croirait encore la tablette sur l'application de Google.
     */
    private fun endHandoffForCall() {
        handoff?.returnToHomeScreen("appel entrant")
        micYieldedToCompanion = false
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
    fun startRoomTranscription(
        onText: (text: String, isFinal: Boolean, fromJean: Boolean) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        roomTranscriptionOnText = onText
        roomTranscriptionOnError = onError
        transcription = TranscriptionEngine(
            context = this,
            onText = { _, text, isFinal ->
                roomTranscriptionOnText?.invoke(text, isFinal, currentSpeakerIsJean())
            },
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

    /**
     * Alimente la reconnaissance du locuteur, et l'apprentissage de la voix de
     * Jean quand il est en cours.
     *
     * Appelée depuis la boucle de capture, avant et indépendamment de la
     * transcription. Aucune condition portant sur le moteur, sur son mode de
     * facturation ou sur l'interrupteur du portier de voix : ce sont deux
     * mécanismes sans rapport, et les lier aurait rendu celui-ci inerte.
     *
     * Le silence est écarté sur le niveau sonore déjà calculé, avant toute
     * analyse. Ce n'est pas une optimisation de confort : l'analyse fait une
     * transformée de Fourier et une autocorrélation par tranche de 64 ms, et
     * les faire tourner sur une pièce vide toute la journée coûterait de la
     * batterie pour rigoureusement rien.
     */
    private fun feedSpeakerRecognition(buffer: ShortArray, length: Int, rms: Double) {
        if (rms < SPEECH_FLOOR_RMS) return
        val now = System.currentTimeMillis()

        enrollment?.let { recogniser ->
            enrollmentProgress = recogniser.enroll(buffer, length)
            // On s'arrête quand le moteur estime en avoir assez entendu, pas au
            // bout d'un temps donné : vingt secondes pendant lesquelles Jean se
            // tait n'apprennent rien, et une minuterie aurait pourtant conclu
            // que c'était fait — puis l'enregistrement aurait échoué à la toute
            // fin, après avoir fait parler quelqu'un pour rien. La durée reste
            // un garde-fou, pour qu'un apprentissage ne dure pas indéfiniment
            // si personne ne parle.
            if (enrollmentProgress >= 1f || now >= enrollmentEndsAtMs) finishVoiceEnrollment()
            // Pendant l'apprentissage, on n'attribue rien : la signature de
            // référence est justement ce qu'on est en train de constituer.
            return
        }

        // Bascule vers Transcription instantanée : sur de la VOIX et non un
        // simple bruit. Basculer l'écran de Jean est un geste visible — bien
        // plus qu'ouvrir une session — et un aspirateur ne doit pas le
        // déclencher. Le portier Silero sert donc ici quel que soit le mode de
        // facturation du moteur, puisque ce mode n'en ouvre aucun.
        if (adminConfig.roomHandoffEnabled) {
            // Nous sommes en train de lire le micro : il nous est donc revenu,
            // et nous ne sommes plus basculés quoi qu'en dise l'état. Réconcilié
            // ici plutôt que d'attendre un chemin de retour qui n'arrivera
            // peut-être jamais.
            ensureHandoff().noteMicrophoneHeld()
            val voiceGate = ensureVoiceGate()
            voiceGate.accept(buffer, length)
            // Détecteur indisponible : on ne bascule PAS. Il rend alors « oui »
            // à tout, par sécurité — un choix juste pour l'ouverture d'une
            // session de transcription, où trop transcrire ne coûte que de
            // l'argent. Ici il ferait changer l'écran de Jean au premier
            // aspirateur, c'est-à-dire exactement ce que ce mode promet de ne
            // pas faire. Mieux vaut ne pas basculer, et le dire.
            if (!voiceGate.available) {
                handoffUnavailableReason = "détection de voix indisponible, bascule suspendue"
            } else {
                handoffUnavailableReason = null
                if (voiceGate.isVoiceActive()) ensureHandoff().onVoiceHeard()
            }
        }

        if (!adminConfig.dimJeanSpeech) return
        val gate = ensureSpeakerGate() ?: return
        gate.accept(buffer, length, now)
    }

    private fun ensureHandoff(): RoomHandoffController {
        handoff?.let { return it }
        return RoomHandoffController(
            context = this,
            adminConfig = adminConfig,
            onRelease = {
                micYieldedToCompanion = true
                stopListening()
            },
            onResume = {
                micYieldedToCompanion = false
                startListening()
            },
        ).also { handoff = it }
    }

    /** Senior Visio est revenu au premier plan : voir RoomHandoffController.noteBackOnHomeScreen. */
    fun noteBackOnHomeScreen() {
        handoff?.noteBackOnHomeScreen()
    }

    /**
     * Pourquoi la bascule est suspendue alors qu'elle est armée. Distinct des
     * refus du contrôleur lui-même : celui-ci ne peut pas savoir que le
     * détecteur de voix qui l'alimente ne s'est pas chargé.
     */
    @Volatile private var handoffUnavailableReason: String? = null

    /** Ce que fait la bascule, en une phrase, pour l'écran admin et le signe de vie. */
    fun describeHandoff(): String = when {
        !adminConfig.roomHandoffEnabled -> "désactivée"
        // Capture arrêtée : le déclencheur n'est jamais consulté. C'est le cas
        // du moteur d'Android, qui tient le micro lui-même — la bascule est
        // alors structurellement impossible, et le dire vaut mieux que de
        // laisser chercher.
        adminConfig.roomEngine == TranscriptionEngineChoice.ANDROID ->
            "impossible : la reconnaissance Android tient le micro, aucun son à analyser"
        !isCapturing && !micYieldedToCompanion -> "capture micro arrêtée, rien à analyser"
        handoffUnavailableReason != null -> handoffUnavailableReason!!
        else -> ensureHandoff().describe()
    }

    /**
     * Bascule sur commande, sans condition, depuis l'écran d'administration.
     * Rend le message d'échec, ou null si c'est parti.
     */
    fun testHandoff(): String? = ensureHandoff().forceHandOff()

    /**
     * Construit le portier, ou le reconstruit si le moteur, la signature ou le
     * seuil ont changé à distance. Null quand la reconnaissance ne peut pas
     * fonctionner — auquel cas [speakerGateError] dit pourquoi, et la
     * transcription s'affiche entièrement en clair.
     *
     * Aucun repli automatique d'un moteur sur l'autre, contrairement à ce que
     * fait la transcription quand un plafond est atteint. Ici ce serait nuisible
     * : les signatures ne sont pas interchangeables et les seuils ne veulent pas
     * dire la même chose d'un moteur à l'autre. Un repli silencieux appliquerait
     * donc une exigence qui n'a aucun sens, et le dirait d'autant moins qu'il
     * aurait l'air de fonctionner.
     */
    private fun ensureSpeakerGate(): SpeakerRecogniser? {
        val engine = adminConfig.speakerEngine
        val signature = adminConfig.jeanVoiceSignature(engine)
        val threshold = adminConfig.jeanVoiceThresholdPercent(engine)
        val key = "${engine.remoteValue}|$threshold|${signature.hashCode()}"
        val now = System.currentTimeMillis()
        // Un échec ne se retente pas au bloc suivant. Cette fonction est
        // appelée à chaque bloc de son, soit une quinzaine de fois par
        // seconde : sans cette retenue, une clé absente ou refusée ferait
        // reconstruire un modèle natif en boucle, indéfiniment, pour échouer
        // à chaque fois. On réessaie de loin en loin, ce qui laisse une chance
        // à une panne passagère sans transformer un réglage manquant en
        // tempête.
        if (key == speakerGateKey && (speakerGate != null || now < speakerGateRetryAtMs)) {
            return speakerGate
        }

        speakerGate?.close()
        speakerGate = null
        speakerGateKey = key
        speakerGateRetryAtMs = now + SPEAKER_GATE_RETRY_MS

        if (signature.isBlank()) {
            speakerGateError = "voix de Jean non apprise avec « ${engine.adminLabel} »"
            return null
        }

        val built = buildSpeakerRecogniser(engine, signature, threshold, forEnrollment = false)
        speakerGate = built
        return built
    }

    /**
     * Fabrique un moteur de reconnaissance, pour reconnaître ou pour apprendre.
     * Rend null en renseignant [speakerGateError] : tout ce qui peut empêcher un
     * moteur de démarrer doit se lire à distance, sans quoi les causes se
     * présentent toutes sous la même forme — un écran qui n'atténue rien.
     */
    private fun buildSpeakerRecogniser(
        engine: SpeakerEngineChoice,
        signature: String?,
        threshold: Int,
        forEnrollment: Boolean,
    ): SpeakerRecogniser? = when (engine) {
        SpeakerEngineChoice.EMBEDDED -> {
            val reference = if (forEnrollment) null else VoiceSignature.parse(signature)
            if (!forEnrollment && reference == null) {
                speakerGateError = "signature intégrée illisible, à réapprendre"
                null
            } else {
                speakerGateError = null
                EmbeddedSpeakerRecogniser(reference, threshold)
            }
        }
        SpeakerEngineChoice.PICOVOICE -> {
            val result = EagleSpeakerRecogniser.create(
                context = this,
                accessKey = adminConfig.picovoiceAccessKey,
                storedProfile = signature,
                thresholdPercent = threshold,
                forEnrollment = forEnrollment,
            )
            speakerGateError = result.error
            result.error?.let { Log.w(TAG, "Reconnaissance de locuteur : $it") }
            result.recogniser
        }
    }

    /**
     * Démarre l'apprentissage de la voix de Jean avec le moteur actuellement
     * choisi : la signature obtenue n'a de sens que pour lui.
     *
     * Jean n'a rien à manipuler — il parle, c'est tout, et c'est un proche qui
     * lance l'apprentissage depuis l'écran d'administration. Cela suppose
     * évidemment que personne d'autre ne parle pendant ce temps : la signature
     * mélangerait les deux voix et ne désignerait plus personne.
     */
    fun startVoiceEnrollment(): String? {
        val engine = adminConfig.speakerEngine
        val recogniser = buildSpeakerRecogniser(
            engine = engine,
            signature = null,
            threshold = adminConfig.jeanVoiceThresholdPercent(engine),
            forEnrollment = true,
        ) ?: return speakerGateError ?: "moteur de reconnaissance indisponible"
        enrollment?.close()
        enrollment = recogniser
        enrollmentProgress = 0f
        enrollmentEndsAtMs = System.currentTimeMillis() + ENROLLMENT_TIMEOUT_MS
        lastEnrollmentResult = null
        return null
    }

    /** Avancement de l'apprentissage, de 0 à 1, ou null s'il n'y en a pas en cours. */
    fun enrollmentProgress(): Float? {
        enrollment ?: return null
        return enrollmentProgress
    }

    /**
     * Résultat du dernier apprentissage, à afficher. Null tant qu'aucun n'a été
     * mené à son terme depuis le démarrage.
     */
    @Volatile var lastEnrollmentResult: String? = null
        private set

    private fun finishVoiceEnrollment() {
        val recogniser = enrollment ?: return
        enrollment = null
        val engine = recogniser.engine
        val signature = recogniser.finishEnrollment()
        recogniser.close()
        if (signature == null) {
            // Trop peu de voix entendue. Le dire précisément plutôt que de
            // stocker une signature bâtie sur trois syllabes, qui ne
            // reconnaîtrait rien et ferait chercher la panne ailleurs.
            lastEnrollmentResult =
                "apprentissage incomplet (${(enrollmentProgress * 100).toInt()} %) — " +
                    "rapprochez-vous du micro, faites parler Jean sans interruption, et recommencez"
            Log.w(TAG, "Apprentissage de la voix insuffisant (${(enrollmentProgress * 100).toInt()} %)")
            return
        }
        adminConfig.setJeanVoiceSignature(engine, signature)
        // Le portier se reconstruira tout seul au prochain bloc de son, la
        // signature stockée ayant changé (voir ensureSpeakerGate).
        speakerGateKey = null
        lastEnrollmentResult = "voix apprise avec « ${engine.adminLabel} »"
        Log.i(TAG, "Voix de Jean apprise avec ${engine.remoteValue}")
    }

    /** Efface la signature du moteur en cours : l'atténuation redevient sans effet. */
    fun forgetVoiceSignature() {
        adminConfig.setJeanVoiceSignature(adminConfig.speakerEngine, "")
        speakerGate?.close()
        speakerGate = null
        speakerGateKey = null
        lastEnrollmentResult = "voix oubliée pour « ${adminConfig.speakerEngine.adminLabel} »"
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

        /**
         * Garde-fou de durée de l'apprentissage, et non sa durée nominale : il
         * s'achève normalement quand le moteur estime avoir assez entendu (voir
         * SpeakerRecogniser.enroll). Deux minutes laissent largement le temps de
         * faire parler Jean, y compris s'il faut le relancer une ou deux fois,
         * et empêchent un apprentissage lancé par mégarde de rester ouvert
         * indéfiniment dans une pièce vide.
         */
        private const val ENROLLMENT_TIMEOUT_MS = 120_000L

        /**
         * Espacement entre deux tentatives de construction du portier de
         * locuteur après un échec. Une minute : assez pour qu'une clé
         * fraîchement saisie prenne effet sans redémarrage, assez peu pour ne
         * pas relancer un modèle natif quinze fois par seconde.
         */
        private const val SPEAKER_GATE_RETRY_MS = 60_000L
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
