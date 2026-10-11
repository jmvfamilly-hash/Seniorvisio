package com.seniorvisio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.CallTrace
import com.seniorvisio.core.AndroidSpeechSession
import com.seniorvisio.core.RoomHandoffController
import com.seniorvisio.ui.MainActivity
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
 * Sert aussi de source unique du micro pour les sous-titres de la pièce (voir
 * MainActivity, zone 2 de l'écran, et setRoomCaptionListener) : jamais une
 * deuxième capture concurrente — exactement le problème qui rendait les
 * sous-titres d'appel peu fiables (deux consommateurs du même micro), résolu
 * ici par construction. Le moteur d'Android qui les produit ne tourne que
 * pendant que l'écran est allumé (voir applyListeningMode) ; écran éteint,
 * seul le réveil au son reste actif.
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

    /**
     * La session du moteur d'Android, quand c'est lui qui écoute la pièce.
     * Null le reste du temps : les deux mécanismes s'excluent (voir
     * startListening).
     */
    private var androidSpeech: AndroidSpeechSession? = null

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

    /** Vrai le temps d'un vrai appel : aucun des deux mécanismes d'écoute ne doit redémarrer tout seul. */
    @Volatile private var pausedForCall = false

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
        transcribing = androidSpeech?.isRunning() == true,
        captureError = lastCaptureError,
        listeningMode = if (androidSpeech?.isRunning() == true) "reconnaissance Android"
        else if (isCapturing) "capture interne"
        else "aucune écoute",
        androidLevelDb = androidSpeech?.takeIf { it.isRunning() }?.lastLevelDb(),
        androidThresholdDb = androidSpeech?.takeIf { it.isRunning() }?.wakeThresholdDb(),
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

    /**
     * Bascule le mécanisme d'écoute dès que l'écran s'allume ou s'éteint (voir
     * applyListeningMode) — en dehors d'un appel, qu'un drapeau dédié exclut
     * explicitement (voir pausedForCall) : un écran qui scintille pendant une
     * conversation ne doit surtout pas relancer un mécanisme qui disputerait
     * le micro à WebRTC.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (pausedForCall) return
            applyListeningMode()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
        adminConfig = AdminConfig(this)
        startForeground(FOREGROUND_ID, buildForegroundNotification())
        // Diffusion système protégée (aucune autre application ne peut
        // l'émettre) : NOT_EXPORTED est donc à la fois le flag exigé à partir
        // de l'API 34 et le plus restrictif des deux possibles.
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Lié par l'écran d'accueil (voir MainActivity, setRoomCaptionListener) —
     * reste par ailleurs un service démarré classique (startForegroundService)
     * pour le réveil au son, les deux modes de communication Android
     * coexistant sans conflit sur un même service.
     */
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                pausedForCall = true
                endHandoffForCall()
                stopListening()
            }
            ACTION_RESUME -> {
                pausedForCall = false
                startListening()
            }
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        unregisterReceiver(screenStateReceiver)
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
     * sert aussi aux sous-titres de la pièce (voir setRoomCaptionListener),
     * qui doivent rester disponibles même quand le réveil au son est
     * désactivé. Ce réglage ne fait que dispenser ensureAwake() d'agir, plus
     * bas.
     */
    /** Démarre l'écoute de la pièce par le mécanisme qui convient à l'état de l'écran (voir applyListeningMode). */
    private fun startListening() {
        applyListeningMode()
    }

    /**
     * Qui tient le micro : le moteur d'Android, ou notre propre capture ?
     *
     * ═══ DEUX MÉCANISMES EXCLUSIFS, JAMAIS LES DEUX À LA FOIS ═══
     *
     * Un seul composant peut tenir le microphone. La nôtre (AudioRecord)
     * mesure un simple niveau sonore ; le moteur d'Android écoute le micro
     * lui-même, ne nous laisse rien à mesurer, et signale donc lui-même la
     * parole pour le réveil (voir AndroidSpeechSession.onSpeechDetected).
     *
     * ═══ LE MOTEUR D'ANDROID, SEULEMENT ÉCRAN ALLUMÉ ═══
     *
     * Demandé explicitement, et pour une raison grave : ce moteur a déjà fait
     * planter l'application entière une fois — processus tué en pleine
     * capture, peu après un retour de la bascule vers Transcription
     * instantanée — ce qui avait motivé son retrait complet à l'époque. Le
     * borner aux heures où l'écran est allumé réduit nettement sa fenêtre
     * d'exposition : la tablette passe l'essentiel d'une journée écran
     * éteint, c'est-à-dire sans personne devant pour remarquer un plantage ni
     * le corriger. Écran allumé, quelqu'un est vraisemblablement dans la
     * pièce — exactement le moment où lire ce qui s'y dit a un sens.
     *
     * Le réveil au son reste sur notre capture, légère et déjà éprouvée des
     * mois durant en continu 24h/24 : elle seule tourne écran éteint, et
     * c'est elle qui allume la dalle. Le moteur d'Android ne prend le relais
     * qu'une fois l'écran effectivement allumé (voir screenStateReceiver), et
     * le lui rend dès qu'il s'éteint.
     *
     * CES SONS NE SE DÉSACTIVENT PAS, et ce n'est pas faute d'avoir cherché :
     * son interface est MODALE — un énoncé, une clôture, une relance — et le
     * service de Google joue ses deux sons d'interaction à chaque reprise, y
     * compris sur un silence qui finit par expirer (ERROR_SPEECH_TIMEOUT). Le
     * seul palliatif en place est le volume (voir AlertVolume), qui descend
     * les notifications à leur plus bas cran audible — jamais zéro, qu'Android
     * refuse sans une autorisation qu'un propriétaire d'appareil ne peut pas
     * s'accorder.
     */
    private fun applyListeningMode() {
        if (isScreenOn()) {
            stopCapture()
            startAndroidSpeech()
        } else {
            stopAndroidSpeech()
            startCapture()
        }
    }

    private fun isScreenOn(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true

    private fun startAndroidSpeech() {
        if (androidSpeech?.isRunning() == true) return
        // Même garde que pour notre capture : Transcription instantanée tient
        // le micro, et le lui reprendre la rendrait inutilisable.
        if (micYieldedToCompanion) return
        val session = AndroidSpeechSession(
            context = this,
            onText = { text, isFinal ->
                roomTranscriptionOnText?.invoke(text, isFinal)
            },
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
                    // Réveil au son uniquement. Plus de reconnaissance du
                    // locuteur, de détection de voix ni de transcription :
                    // l'écoute de la voix est retirée (voir startListening).
                    handleLevel(rms)
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

    /**
     * Arrête la capture et REND VRAIMENT le microphone avant de revenir.
     *
     * ═══ POURQUOI ATTENDRE LE FIL, ET CE QUE COÛTAIT DE NE PAS LE FAIRE ═══
     *
     * Ce code posait isCapturing à faux, interrompait le fil, puis appelait
     * stop() et release() depuis le fil appelant. Or interrupt() NE DÉBLOQUE
     * PAS AudioRecord.read() : c'est un appel natif bloquant, insensible à
     * l'interruption. Le fil de capture pouvait donc être encore à
     * l'intérieur de read() pendant qu'on libérait l'objet sous lui.
     *
     * Deux conséquences. La première est une course franche — libérer une
     * ressource native pendant qu'un autre fil la lit. La seconde est celle
     * qui s'est vue : le micro n'était pas réellement rendu au retour de
     * cette fonction, et l'application lancée juste après démarrait sur un
     * micro encore pris. Elle ne dit rien dans ce cas : elle affiche une
     * transcription vide.
     *
     * C'est exactement le symptôme rapporté — première bascule muette,
     * seconde correcte, parce qu'entre les deux le fil avait fini par sortir
     * de read().
     *
     * L'attente est bornée : un fil qui ne sort pas ne doit pas figer
     * l'appelant, qui est ici le fil principal. Au-delà, on libère quand même
     * et on le dit.
     */
    private fun stopCapture() {
        retryHandler.removeCallbacksAndMessages(null)
        val fil = captureThread
        isCapturing = false
        captureThread = null
        if (fil != null && fil.isAlive) {
            val début = System.currentTimeMillis()
            fil.join(ATTENTE_FIN_CAPTURE_MS)
            val attendu = System.currentTimeMillis() - début
            if (fil.isAlive) {
                Log.w(TAG, "Le fil de capture n'a pas rendu la main en $attendu ms")
                CallTrace.record(
                    "MICRO libération",
                    "le fil de capture tient encore après $attendu ms — micro peut-être occupé",
                )
            }
        }
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

    /**
     * Les sous-titres de la pièce sur l'écran d'accueil (voir MainActivity,
     * zone 2) s'abonnent ici au texte produit par le moteur d'Android — le
     * seul des deux mécanismes qui en produise, et seulement pendant qu'il
     * tourne (voir applyListeningMode, isRunning). Rien à démarrer de plus :
     * la capture elle-même suit déjà l'état de l'écran toute seule.
     */
    fun setRoomCaptionListener(
        onText: (text: String, isFinal: Boolean) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        roomTranscriptionOnText = onText
        roomTranscriptionOnError = onError
    }

    /** À appeler quand l'écran qui affiche les paroles de la pièce passe en arrière-plan. */
    fun clearRoomCaptionListener() {
        roomTranscriptionOnText = null
        roomTranscriptionOnError = null
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

    /** Ce que fait la bascule, en une phrase, pour l'écran admin et le signe de vie. */
    fun describeHandoff(): String =
        handoff?.let { "bouton « Sous-titres » seul — ${it.describe()}" }
            ?: "bouton « Sous-titres » seul (bascule automatique retirée)"

    /**
     * Bascule sur commande, sans condition, depuis l'écran d'administration.
     * Rend le message d'échec, ou null si c'est parti.
     */
    fun testHandoff(): String? = ensureHandoff().forceHandOff()

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
        // Jean a appuyé sur « Sommeil » (voir MiseEnVeille). C'était jusqu'ici
        // le chemin qui annulait son geste le plus vite : un raclement de
        // gorge, et l'écran se rallumait dans la seconde.
        if (adminConfig.isSleeping()) return

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

        private const val MAX_WAKE_LOCK_MS = 30 * 60 * 1000L
        /**
         * Combien de temps on attend que le fil de capture sorte de
         * AudioRecord.read() avant de libérer quand même.
         *
         * Une demi-seconde : un bloc de lecture dure quelques dizaines de
         * millisecondes, donc dix fois la marge. Au-delà, c'est que quelque
         * chose ne va pas, et figer le fil principal plus longtemps ferait
         * une tablette qui ne répond plus — ce qui est pire qu'un micro
         * rendu avec retard.
         */
        private const val ATTENTE_FIN_CAPTURE_MS = 500L

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
