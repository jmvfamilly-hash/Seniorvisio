package com.seniorvisio.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    fun isRunning(): Boolean = wanted

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
        handler.removeCallbacksAndMessages(null)
        handler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun listen() {
        if (!wanted) return
        recognizer?.destroy()

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
        }
        try {
            instance.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Démarrage de l'écoute Android impossible", e)
            scheduleRestart()
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
            firstResult(results)?.let { onText(it, true) }
            // Fin d'énoncé, pas fin d'écoute : on relance aussitôt.
            scheduleRestart(immediate = true)
        }

        override fun onError(error: Int) {
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
        val delay = if (immediate) RESTART_DELAY_MS
        else (RESTART_DELAY_MS shl consecutiveErrors.coerceAtMost(6)).coerceAtMost(MAX_RESTART_DELAY_MS)
        handler.postDelayed({ listen() }, delay)
    }

    private companion object {
        const val TAG = "AndroidSpeechSession"
        const val LANGUAGE = "fr-FR"
        const val RESTART_DELAY_MS = 300L
        const val MAX_RESTART_DELAY_MS = 30_000L
        const val MAX_CONSECUTIVE_ERRORS = 8

        /** Bornes du curseur de sensibilité, côté administration (voir index.html). */
        const val RMS_SCALE_MIN = 500f
        const val RMS_SCALE_MAX = 15_000f

        /** Ce que ce moteur produit en pratique : ~0 dans une pièce calme, ~10 sur une voix proche. */
        const val DB_SCALE_MIN = 0f
        const val DB_SCALE_MAX = 9f
    }
}
