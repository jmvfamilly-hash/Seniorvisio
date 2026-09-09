package com.seniorvisio.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Troisième moteur de transcription : celui d'Android lui-même.
 *
 * Contrairement aux deux autres (voir com.seniorvisio.core.SpeechRecognizer),
 * celui-ci ne se nourrit pas d'un flux audio qu'on lui donne — **il écoute le
 * micro lui-même**, et c'est une contrainte de l'API, pas un choix. Il ne peut
 * donc pas implémenter l'interface commune, et il ne peut pas transcrire un
 * appel : le son d'un appel arrive par WebRTC, jamais par le micro. Il ne sert
 * que pour ce qui se dit dans la pièce.
 *
 * Ce qui a une deuxième conséquence, moins évidente : puisqu'il prend le micro,
 * notre propre capture doit le lui laisser. C'est donc lui qui signale aussi la
 * présence de parole pour le réveil de l'écran (voir onSpeechDetected et
 * RoomPresenceService) — sans quoi choisir ce moteur éteindrait silencieusement
 * la fonction principale de la tablette.
 *
 * Reconnaissance sur l'appareil demandée explicitement. Une tablette qui écoute
 * une chambre du matin au soir n'a pas à en envoyer le son chez un tiers, et
 * mieux vaut un moteur qui refuse de démarrer faute de modèle français
 * installé — cas signalé dans le diagnostic — qu'un moteur qui marche en
 * expédiant discrètement la pièce sur le réseau.
 */
class AndroidSpeechSession(
    private val context: Context,
    private val onText: (text: String, isFinal: Boolean) -> Unit,
    /** Appelé dès que le moteur entend quelqu'un parler : c'est ce qui réveille l'écran. */
    private val onSpeechDetected: () -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
) {

    /** Vers l'appelant et vers le journal partagé (voir TranscriptionDiagnostics). */
    private fun diagnose(message: String) {
        TranscriptionDiagnostics.record(message)
        onDiagnostic(message)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val adminConfig = AdminConfig(context)

    @Volatile private var lastLevelDb = 0f
    @Volatile private var peakLevelDb = 0f

    /** Dernier niveau observé, en décibels relatifs à ce moteur. Pour le diagnostic. */
    fun lastLevelDb(): Float = lastLevelDb

    /**
     * Le plus fort niveau depuis la dernière lecture, puis remis à zéro —
     * même raison que pour l'autre mécanisme d'écoute (voir
     * RoomPresenceService.consumePeakRms) : entre deux signes de vie il se
     * passe cinq minutes, et l'instant précis où l'on regarde a toutes les
     * chances d'être un instant de silence.
     */
    fun consumePeakLevelDb(): Float {
        val peak = peakLevelDb
        peakLevelDb = 0f
        return peak
    }

    /**
     * Le seuil réglé par l'administrateur, reporté sur l'échelle de ce moteur.
     *
     * Les bornes RMS sont celles du curseur côté administration : ce qui est
     * transposé, c'est la position du curseur dans sa course, pas une valeur
     * physique. Les bornes en décibels encadrent ce que ce moteur produit en
     * pratique — autour de zéro dans une pièce calme, une dizaine sur une voix
     * proche.
     */
    fun wakeThresholdDb(): Float {
        val raw = adminConfig.roomWakeSensitivityThreshold.toFloat()
        val fraction = ((raw - RMS_SCALE_MIN) / (RMS_SCALE_MAX - RMS_SCALE_MIN)).coerceIn(0f, 1f)
        return DB_SCALE_MIN + fraction * (DB_SCALE_MAX - DB_SCALE_MIN)
    }

    /** Vrai entre start() et stop() : distingue un arrêt voulu d'une fin d'énoncé. */
    private var wanted = false
    private var consecutiveErrors = 0
    private var reportedEngine = false
    private var earconsMuted = false

    /**
     * Vrai entre le démarrage effectif d'un énoncé et l'événement qui le clôt.
     * Distinct de [wanted], qui dit ce qu'on veut et non ce qui se passe :
     * confondre les deux revenait à ne pas savoir si le moteur écoute
     * réellement à cet instant.
     */
    private var listening = false

    /**
     * Vrai tant qu'une relance est déjà programmée.
     *
     * Sans ce garde-fou, plusieurs callbacks pouvaient en programmer chacun
     * une pour le même énoncé — onResults puis onError, par exemple. Aucune
     * instance concurrente n'en résultait, listen() détruisant la précédente
     * avant d'en créer une, mais on payait un cycle de démarrage entier pour
     * rien : une paire de bips de plus, et une couture de plus pendant
     * laquelle rien n'est écouté. C'est exactement là que des mots se
     * perdent.
     */
    private var restartPending = false

    /**
     * Objet unique, et non une lambda créée à chaque fois : c'est ce qui
     * permet de retirer une relance encore en attente (voir stop()).
     */
    private val restartRunnable = Runnable {
        restartPending = false
        listen()
    }

    /** Même raison que restartRunnable : un objet unique se retire du handler. */
    private val unmuteRunnable = Runnable { unmuteEarcons() }

    /**
     * Horodatage des relances de la dernière minute. Le nombre de relances
     * par minute est la mesure qui dit si ce moteur va bien : c'est lui qui
     * commande à la fois la fréquence des bips et le nombre de coutures où
     * la parole n'est pas écoutée. Sans ce chiffre, « ça coupe moins »
     * resterait une impression invérifiable.
     */
    private val restartTimes = ArrayDeque<Long>()

    fun isRunning(): Boolean = wanted

    /** Relances observées sur la dernière minute glissante (voir restartTimes). */
    fun restartsPerMinute(): Int {
        pruneRestartTimes(System.currentTimeMillis())
        return restartTimes.size
    }

    private fun noteRestart() {
        val now = System.currentTimeMillis()
        restartTimes.addLast(now)
        pruneRestartTimes(now)
    }

    private fun pruneRestartTimes(now: Long) {
        while (restartTimes.isNotEmpty() && now - restartTimes.first() > 60_000L) {
            restartTimes.removeFirst()
        }
    }

    /**
     * Démarre l'écoute continue. Le moteur d'Android, lui, ne sait écouter
     * qu'un énoncé à la fois : il s'arrête à chaque silence, et c'est à nous de
     * le relancer indéfiniment. D'où la boucle ci-dessous, qui est le prix à
     * payer pour une écoute permanente avec cette API.
     */
    fun start() {
        if (wanted) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            diagnose("reconnaissance Android : permission micro refusée")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            diagnose("reconnaissance Android indisponible sur cette tablette")
            return
        }
        wanted = true
        consecutiveErrors = 0
        // Les sons de début et de fin d'énoncé sont traités ailleurs : ce
        // n'est pas l'écoute qui décide du volume des alertes, c'est
        // l'absence d'appel (voir AlertVolume, MainActivity et
        // IncomingCallActivity). Les lier à cette session revenait à les
        // remonter en pleine conversation, où le moteur est justement arrêté.
        UsageStats.noteTranscriptionStart(UsageStats.ENGINE_ANDROID)
        handler.post { listen() }
    }

    fun stop() {
        if (wanted) UsageStats.noteTranscriptionStop()
        wanted = false
        listening = false
        restartPending = false
        handler.removeCallbacksAndMessages(null)
        // Sans ça, un arrêt tombant dans la fenêtre de coupure laisserait les
        // flux muets indéfiniment — le rétablissement était programmé sur un
        // handler qu'on vient de vider.
        unmuteEarcons()
        handler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun listen() {
        if (!wanted) return
        recognizer?.destroy()
        listening = false

        val instance = try {
            createRecognizer()
        } catch (e: Exception) {
            Log.w(TAG, "Création du moteur Android impossible", e)
            diagnose("reconnaissance Android : ${e.message}")
            wanted = false
            return
        }
        recognizer = instance
        instance.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            // Le texte doit s'afficher au fil de la phrase, comme pour les deux
            // autres moteurs : sans ça, rien n'apparaît avant le silence final.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Une seule hypothèse. Les suivantes ne sont jamais lues — on
            // n'affiche que la meilleure — et les demander revient à faire
            // travailler le moteur pour du texte qu'on jette.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Le cœur du correctif. Par défaut ce moteur clôt l'énoncé au
            // premier silence un peu net, c'est-à-dire entre deux phrases
            // d'une même conversation, voire au milieu d'une phrase de
            // quelqu'un qui cherche son mot. Chaque clôture coûte une relance,
            // et chaque relance une couture pendant laquelle plus rien n'est
            // écouté : ce sont les mots mangés en début de reprise.
            //
            // Ces deux réglages ne sont pas garantis — la documentation les
            // donne pour indicatifs, et certains moteurs les ignorent ou les
            // plafonnent. Leur effet réel se lira dans le nombre de relances
            // par minute publié au diagnostic (voir restartsPerMinute).
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TOLERANCE_MS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TOLERANCE_MS
            )
        }
        try {
            // Le son de démarrage est joué par le service de reconnaissance au
            // moment précis de cet appel : on coupe juste avant, on rétablit
            // peu après (voir muteEarcons).
            muteEarcons()
            instance.startListening(intent)
            listening = true
            noteRestart()
        } catch (e: Exception) {
            Log.w(TAG, "Démarrage de l'écoute Android impossible", e)
            unmuteEarcons()
            scheduleRestart()
        }
    }

    /**
     * Coupe brièvement les flux multimédia et système autour du démarrage d'un
     * énoncé, pour avaler le son que le service de reconnaissance joue à ce
     * moment-là.
     *
     * Complément, et non remplacement, du volume d'alertes tenu bas hors appel
     * (voir AlertVolume) : ce sont deux flux différents, et le diagnostic de
     * terrain désignait les alertes. On agit ici sur les deux autres, au cas
     * où l'appareil y jouerait aussi quelque chose.
     *
     * Deux limites à connaître. Cette fenêtre ne peut pas couvrir le son de
     * FIN d'énoncé, qui survient bien plus tard et à un instant qu'on ne
     * connaît pas. Et ces flux ne sont pas ceux de la sonnerie d'appel (flux
     * alarme) ni de la conversation (flux appel), qui restent donc intacts.
     */
    private fun muteEarcons() {
        handler.removeCallbacks(unmuteRunnable)
        // Déjà coupé : on se contente de repousser le rétablissement. Android
        // compte les coupures par client — deux ADJUST_MUTE de suite
        // demanderaient deux ADJUST_UNMUTE, et le flux resterait muet.
        if (earconsMuted) {
            handler.postDelayed(unmuteRunnable, EARCON_MUTE_MS)
            return
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // Posé avant d'agir, et non après : si la coupure du second flux
        // échoue, le premier doit quand même être rétabli. Un drapeau posé en
        // fin de bloc laissait le son coupé pour de bon.
        earconsMuted = true
        try {
            EARCON_STREAMS.forEach {
                audioManager.adjustStreamVolume(it, AudioManager.ADJUST_MUTE, 0)
            }
        } catch (e: SecurityException) {
            // Refus du système : on n'insiste pas, les sons resteront audibles.
            Log.w(TAG, "Coupure des sons de démarrage refusée", e)
        }
        handler.postDelayed(unmuteRunnable, EARCON_MUTE_MS)
    }

    private fun unmuteEarcons() {
        if (!earconsMuted) return
        earconsMuted = false
        handler.removeCallbacks(unmuteRunnable)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            EARCON_STREAMS.forEach {
                audioManager.adjustStreamVolume(it, AudioManager.ADJUST_UNMUTE, 0)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Rétablissement des sons refusé", e)
        }
    }

    /**
     * Sur Android 12 et au-delà, un constructeur garantit la reconnaissance sur
     * l'appareil. En dessous, EXTRA_PREFER_OFFLINE n'est qu'une préférence que
     * le moteur peut ignorer — c'est dit dans le diagnostic plutôt que laissé
     * à supposer.
     */
    private fun createRecognizer(): SpeechRecognizer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!reportedEngine) {
                reportedEngine = true
                diagnose("reconnaissance Android sur l'appareil")
            }
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        if (!reportedEngine) {
            reportedEngine = true
            diagnose("reconnaissance Android (hors ligne demandée, non garantie sur cette version)")
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            consecutiveErrors = 0
        }

        override fun onBeginningOfSpeech() {
            // Volontairement sans effet sur le réveil. C'est ici que le réveil
            // était déclenché, et c'était le défaut : ce signal est la
            // détection de parole de Google, qui n'a pas de seuil réglable et
            // se déclenche sur un bruit de clavier à deux mètres. Le curseur
            // de sensibilité de l'écran d'administration ne servait alors
            // strictement à rien dans ce mode — il ne commandait que l'autre
            // mécanisme d'écoute.
        }

        /**
         * Le réveil passe par là, comme sur l'autre mécanisme d'écoute (voir
         * RoomPresenceService.handleLevel) : un niveau sonore comparé à un
         * seuil réglable, et rien d'autre.
         *
         * L'unité n'est pas la même — ce moteur donne des décibels relatifs,
         * notre capture donne une valeur efficace sur 16 bits — et aucune
         * conversion honnête n'existe entre les deux. Le seuil réglé est donc
         * reporté en proportion de sa propre échelle (voir wakeThresholdDb) :
         * un curseur à mi-course reste à mi-course dans les deux modes, ce qui
         * est ce qu'on attend d'un curseur, à défaut d'être une mesure.
         */
        override fun onRmsChanged(rmsdB: Float) {
            lastLevelDb = rmsdB
            if (rmsdB > peakLevelDb) peakLevelDb = rmsdB
            if (rmsdB >= wakeThresholdDb()) onSpeechDetected()
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { onText(it, false) }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            firstResult(results)?.let { onText(it, true) }
            // Fin d'énoncé, pas fin d'écoute : on relance aussitôt.
            scheduleRestart(immediate = true)
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                // Silence ou phrase incomprise : le cas ordinaire d'une pièce
                // vide. On relance sans compter ça comme un échec, sinon la
                // moindre heure de calme épuiserait le compteur.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(immediate = true)

                // Sans modèle français installé, insister ne sert à rien : ça
                // se règle sur la tablette, dans les paramètres de Google.
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    diagnose("reconnaissance Android : permission micro refusée")
                    wanted = false
                    UsageStats.noteTranscriptionStop()
                }

                else -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        diagnose("reconnaissance Android en échec répété (code $error), écoute arrêtée")
                        wanted = false
                        UsageStats.noteTranscriptionStop()
                        return
                    }
                    scheduleRestart()
                }
            }
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Relance après un délai qui grandit avec les échecs consécutifs. Relancer
     * sans répit un moteur qui refuse de démarrer le ferait rejeter par le
     * système (et, sur certaines tablettes, émettrait un bip à chaque essai —
     * inacceptable dans une chambre).
     */
    private fun scheduleRestart(immediate: Boolean = false) {
        if (!wanted) return
        // Une seule relance en vol à la fois. Voir restartPending : c'est le
        // correctif de la couture qui mangeait des mots.
        if (restartPending) return
        restartPending = true
        val delay = if (immediate) RESTART_DELAY_MS
        else (RESTART_DELAY_MS shl consecutiveErrors.coerceAtMost(6)).coerceAtMost(MAX_RESTART_DELAY_MS)
        handler.postDelayed(restartRunnable, delay)
    }

    private companion object {
        const val TAG = "AndroidSpeechSession"
        const val LANGUAGE = "fr-FR"
        const val RESTART_DELAY_MS = 300L
        const val MAX_RESTART_DELAY_MS = 30_000L
        const val MAX_CONSECUTIVE_ERRORS = 8

        /**
         * Silence toléré avant que le moteur ne déclare l'énoncé terminé.
         * Dix secondes : une pause de réflexion, une hésitation, un « voilà… »
         * suivi d'une reprise ne doivent pas couper l'écoute, puisque chaque
         * coupure coûte une relance et une couture pendant laquelle des mots
         * se perdent.
         *
         * Contrepartie assumée : le texte définitif d'une phrase arrive
         * d'autant plus tard. L'affichage n'attend pas pour autant — les
         * résultats partiels continuent d'arriver et la zone les montre au fil
         * de l'eau (voir RollingCaptionZone) — mais la clôture du segment, et
         * donc le repère de silence qui la suit, se décalent.
         */
        const val SILENCE_TOLERANCE_MS = 10_000

        /**
         * Durée de la coupure autour du démarrage d'un énoncé. Assez pour
         * avaler le son de démarrage, assez court pour ne pas retenir le son
         * de la tablette de façon perceptible.
         */
        const val EARCON_MUTE_MS = 400L

        /**
         * Volontairement ni le flux d'alertes — traité ailleurs, et dont la
         * mise à zéro ferait basculer la tablette en silencieux, ce qu'Android
         * refuse sans autorisation « Ne pas déranger » — ni le flux alarme, qui
         * porte la sonnerie d'appel, ni celui de la conversation.
         */
        val EARCON_STREAMS = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)

        /** Bornes du curseur de sensibilité, côté administration (voir index.html). */
        const val RMS_SCALE_MIN = 500f
        const val RMS_SCALE_MAX = 15_000f

        /** Ce que ce moteur produit en pratique : ~0 dans une pièce calme, ~10 sur une voix proche. */
        const val DB_SCALE_MIN = 0f
        const val DB_SCALE_MAX = 9f
    }
}
