package com.seniorvisio.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.seniorvisio.R
import com.seniorvisio.service.CallListenerService
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Écran affiché quand aucun appel n'est en cours. Volontairement épuré pour
 * l'usage senior : un historique de dictées et un unique bouton à maintenir
 * enfoncé pour dicter (voir plus bas) — rien d'autre à comprendre.
 *
 * La détection d'appel entrant ne dépend pas du cycle de vie de cet écran :
 * elle tourne en continu dans CallListenerService (démarré ci-dessous),
 * pour fonctionner même écran éteint ou app en arrière-plan.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op : voir startLocalMedia() pour le repli si refusé */ }

    private lateinit var roomCaptionScroll: ScrollView
    private lateinit var textRoomCaption: TextView
    private lateinit var buttonRoomCaptions: Button

    private var speechRecognizer: SpeechRecognizer? = null

    // ---- État de la dictée en cours (bouton maintenu) ----
    private var isHeld = false
    private var currentDictationText = ""
    private var consecutiveErrorCount = 0
    private val maxConsecutiveErrors = 3

    // Texte déjà validé (dictées précédentes, séparateurs inclus).
    private val finalizedHistory = StringBuilder()

    // ---- Défilement automatique à vitesse plafonnée (même principe que les
    // sous-titres d'appel, voir IncomingCallActivity.setupCaptionMode) ----
    private var lerpTargetScroll = 0
    private var lerpFrameScheduled = false
    private var lastFrameTimeNanos = 0L
    private var userScrolling = false
    private val maxScrollSpeedPxPerSec by lazy { 50f * resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        roomCaptionScroll = findViewById(R.id.roomCaptionScroll)
        textRoomCaption = findViewById(R.id.textRoomCaption)
        buttonRoomCaptions = findViewById(R.id.buttonRoomCaptions)

        buttonRoomCaptions.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startDictation()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopDictation()
                    true
                }
                else -> false
            }
        }

        // Laisse le défilement tactile normal se produire (contrairement aux
        // sous-titres d'appel, entièrement pilotés par le code) : ne fait que
        // repérer quand Jean touche l'écran pour mettre en pause le
        // défilement automatique le temps qu'il navigue dans l'historique.
        roomCaptionScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> userScrolling = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userScrolling = false
                    requestLerpFrame()
                }
            }
            false
        }

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )

        ContextCompat.startForegroundService(this, Intent(this, CallListenerService::class.java))
        requestIgnoreBatteryOptimizations()
        requestFullScreenIntentPermission()
    }

    /**
     * Sans ça, Android peut geler le service d'écoute au bout d'un moment
     * (Doze) malgré le statut foreground, sur certains appareils/marques.
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    /**
     * À partir d'Android 14, la permission d'afficher une notification en
     * plein écran (voir IncomingCallService.launchAlertScreen — c'est ce qui
     * réveille l'écran d'appel de façon fiable depuis l'arrière-plan) n'est
     * plus accordée automatiquement à l'installation pour toutes les apps :
     * sans cette demande explicite, Android rétrograde silencieusement la
     * notification plein écran en simple notification discrète.
     */
    private fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < 34) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.canUseFullScreenIntent()) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    // ---- Sous-titres de la pièce (aucun proche impliqué : contrairement
    // aux sous-titres d'appel, la reconnaissance doit obligatoirement
    // tourner sur la tablette elle-même). Modèle "appui maintenu" façon
    // talkie-walkie : le bouton n'est actif que pendant qu'il est enfoncé,
    // une dictée = une pression. ----

    private fun startDictation() {
        if (isHeld) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Micro non autorisé", Toast.LENGTH_SHORT).show()
            return
        }
        isHeld = true
        currentDictationText = ""
        consecutiveErrorCount = 0
        buttonRoomCaptions.text = "🔴 Enregistrement…"
        startListeningOnce()
    }

    private fun stopDictation() {
        if (!isHeld) return
        isHeld = false
        buttonRoomCaptions.text = "🎙️ Maintenir pour dicter"
        // Le dernier résultat (ce qui vient d'être dit avant le relâchement)
        // arrive via onResults/onError, qui finalisera la dictée là-bas.
        speechRecognizer?.stopListening()
    }

    private fun startListeningOnce() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconnaissance vocale indisponible sur cette tablette", Toast.LENGTH_SHORT).show()
            isHeld = false
            buttonRoomCaptions.text = "🎙️ Maintenir pour dicter"
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(roomCaptionListener)
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Laisse une bonne marge avant qu'une pause naturelle ne mette fin
            // à l'écoute (voir plus bas : tant que le bouton reste maintenu,
            // une fin anticipée relance simplement l'écoute pour la même
            // dictée, mais autant limiter la fréquence des relances — et donc
            // du bip de début d'écoute du service Android à chaque fois).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
        }
        speechRecognizer?.startListening(intent)
    }

    /** Ajoute la dictée en cours à l'historique, séparée de la précédente, puis l'efface. */
    private fun finalizeDictation() {
        val text = currentDictationText.trim()
        currentDictationText = ""
        if (text.isNotEmpty()) {
            if (finalizedHistory.isNotEmpty()) finalizedHistory.append(DICTATION_SEPARATOR)
            finalizedHistory.append(text)
            // Historique borné : évite une zone de texte qui grossirait indéfiniment.
            val maxChars = 4000
            if (finalizedHistory.length > maxChars) {
                finalizedHistory.delete(0, finalizedHistory.length - maxChars)
            }
        }
        showRoomCaptionText(finalizedHistory.toString())
    }

    private fun showLivePreview(partialText: String) {
        val combined = if (finalizedHistory.isEmpty()) partialText else "$finalizedHistory$DICTATION_SEPARATOR$partialText"
        showRoomCaptionText(combined)
    }

    private fun showRoomCaptionText(text: String) {
        textRoomCaption.text = text
        textRoomCaption.post {
            lerpTargetScroll = (textRoomCaption.height - roomCaptionScroll.height).coerceAtLeast(0)
            requestLerpFrame()
        }
    }

    /**
     * Défilement à vitesse plafonnée plutôt qu'instantané, pour laisser à
     * Jean le temps de lire — même principe que les sous-titres d'appel
     * (voir IncomingCallActivity.setupCaptionMode). En pause tant que Jean
     * touche l'écran pour naviguer manuellement (voir userScrolling), reprend
     * son rythme dès qu'il relâche.
     */
    private fun requestLerpFrame() {
        if (lerpFrameScheduled || userScrolling) return
        lerpFrameScheduled = true
        Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
            lerpFrameScheduled = false
            if (userScrolling) {
                lastFrameTimeNanos = 0L
                return@postFrameCallback
            }
            val dtSeconds = if (lastFrameTimeNanos == 0L) {
                0f
            } else {
                ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            }
            lastFrameTimeNanos = frameTimeNanos
            val current = roomCaptionScroll.scrollY
            val diff = (lerpTargetScroll - current).toFloat()
            if (abs(diff) < 1f) {
                roomCaptionScroll.scrollTo(0, lerpTargetScroll)
            } else {
                val maxStep = (maxScrollSpeedPxPerSec * dtSeconds).coerceAtLeast(1f)
                val step = diff.coerceIn(-maxStep, maxStep)
                roomCaptionScroll.scrollTo(0, (current + step).roundToInt())
                requestLerpFrame()
            }
        }
    }

    private val roomCaptionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            Log.w(TAG, "Sous-titres de la pièce : erreur reconnaissance vocale (code $error)")
            if (!isHeld) {
                finalizeDictation()
                return
            }
            consecutiveErrorCount++
            if (consecutiveErrorCount >= maxConsecutiveErrors) {
                consecutiveErrorCount = 0
                isHeld = false
                buttonRoomCaptions.text = "🎙️ Maintenir pour dicter"
                Toast.makeText(
                    this@MainActivity,
                    "La reconnaissance vocale ne fonctionne pas pour l'instant sur cette tablette",
                    Toast.LENGTH_LONG
                ).show()
                finalizeDictation()
                return
            }
            // Bouton encore maintenu : Jean continue de parler, on relance
            // pour capter la suite de la même dictée.
            startListeningOnce()
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrorCount = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrEmpty()) {
                currentDictationText = if (currentDictationText.isEmpty()) text else "$currentDictationText $text"
            }
            if (isHeld) {
                showLivePreview(currentDictationText)
                startListeningOnce()
            } else {
                finalizeDictation()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrEmpty()) {
                val preview = if (currentDictationText.isEmpty()) text else "$currentDictationText $text"
                showLivePreview(preview)
            }
        }
    }

    /**
     * Sécurité si un appel entrant interrompt une dictée en cours (le doigt
     * ne peut de toute façon plus être sur le bouton une fois l'écran
     * changé) : coupe proprement plutôt que de laisser le micro engagé.
     */
    override fun onPause() {
        super.onPause()
        if (isHeld) {
            isHeld = false
            currentDictationText = ""
            buttonRoomCaptions.text = "🎙️ Maintenir pour dicter"
            speechRecognizer?.cancel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val DICTATION_SEPARATOR = "\n\n───────────\n\n"
    }
}
