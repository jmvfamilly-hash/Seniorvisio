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

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

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
            onDiagnostic("reconnaissance Android : permission micro refusée")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onDiagnostic("reconnaissance Android indisponible sur cette tablette")
            return
        }
        wanted = true
        consecutiveErrors = 0
        handler.post { listen() }
    }

    fun stop() {
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
            onDiagnostic("reconnaissance Android : ${e.message}")
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
                onDiagnostic("reconnaissance Android sur l'appareil")
            }
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        if (!reportedEngine) {
            reportedEngine = true
            onDiagnostic("reconnaissance Android (hors ligne demandée, non garantie sur cette version)")
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            consecutiveErrors = 0
        }

        override fun onBeginningOfSpeech() {
            // Le seul signal de présence dont on dispose dans ce mode : la
            // capture qui alimentait d'ordinaire le réveil a laissé le micro
            // à ce moteur.
            onSpeechDetected()
        }

        override fun onRmsChanged(rmsdB: Float) {}
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
                    onDiagnostic("reconnaissance Android : permission micro refusée")
                    wanted = false
                }

                else -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        onDiagnostic("reconnaissance Android en échec répété (code $error), écoute arrêtée")
                        wanted = false
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
    }
}
