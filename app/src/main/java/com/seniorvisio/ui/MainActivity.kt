package com.seniorvisio.ui

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.text.InputType
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
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.seniorvisio.BuildConfig
import com.seniorvisio.R
import com.seniorvisio.admin.AdminSettingsActivity
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.KioskManager
import com.seniorvisio.service.CallListenerService
import com.seniorvisio.signaling.CallSignalingClient

/**
 * Écran affiché quand aucun appel n'est en cours. Volontairement épuré pour
 * l'usage senior : juste un message d'accueil et un unique bouton pour
 * activer/désactiver le sous-titrage des conversations de la pièce (voir
 * plus bas) — rien d'autre à comprendre.
 *
 * La détection d'appel entrant ne dépend pas du cycle de vie de cet écran :
 * elle tourne en continu dans CallListenerService (démarré ci-dessous),
 * pour fonctionner même écran éteint ou app en arrière-plan.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op : voir startLocalMedia() pour le repli si refusé */ }

    private lateinit var idleContent: View
    private lateinit var roomCaptionScroll: ScrollView
    private lateinit var textRoomCaption: TextView
    private lateinit var buttonRoomCaptions: Button
    private lateinit var buttonStopRoomCaptions: Button

    private var speechRecognizer: SpeechRecognizer? = null
    private var roomCaptionsActive = false
    // Distinct de roomCaptionsActive : reflète le choix de Jean, pas l'état
    // technique du moment (coupé pendant un appel entrant, voir onPause/
    // onResume) — permet de reprendre automatiquement les sous-titres de la
    // pièce au retour sur cet écran, sans que Jean ait à rappuyer dessus.
    private var roomCaptionsUserEnabled = false
    private val roomCaptionHistory = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textBuildRev = findViewById<TextView>(R.id.textBuildRev)
        textBuildRev.text = BuildConfig.BUILD_REV
        // Point d'entrée discret vers les réglages admin (Wi-Fi, PIN, durée
        // du décompte) : une fois en mode kiosque, plus aucun autre moyen d'y
        // accéder (Réglages système bloqués), voir KioskManager.
        textBuildRev.setOnLongClickListener { promptAdminPin(); true }

        idleContent = findViewById(R.id.idleContent)
        roomCaptionScroll = findViewById(R.id.roomCaptionScroll)
        textRoomCaption = findViewById(R.id.textRoomCaption)
        buttonRoomCaptions = findViewById(R.id.buttonRoomCaptions)
        buttonStopRoomCaptions = findViewById(R.id.buttonStopRoomCaptions)
        buttonRoomCaptions.setOnClickListener { toggleRoomCaptions() }
        buttonStopRoomCaptions.setOnClickListener { toggleRoomCaptions() }
        // Appui long : labo d'étude comparant les moteurs de transcription
        // (voir TranscriptionLabActivity), sans toucher à l'usage normal du
        // bouton (appui simple, inchangé).
        buttonRoomCaptions.setOnLongClickListener {
            startActivity(Intent(this, TranscriptionLabActivity::class.java))
            true
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
        registerFcmToken()
        KioskManager.startIfDeviceOwner(this)
    }

    private fun promptAdminPin() {
        val input = android.widget.EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("PIN admin")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == AdminConfig(this).adminPin) {
                    startActivity(Intent(this, AdminSettingsActivity::class.java))
                } else {
                    Toast.makeText(this, "PIN incorrect", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /**
     * Renvoie le token FCM courant au démarrage, en plus de
     * SeniorVisioMessagingService.onNewToken : ce dernier n'est appelé que
     * lorsqu'Android (re)génère le token, pas s'il existait déjà avant que ce
     * service ait eu l'occasion de tourner (ex. premier lancement après
     * l'installation).
     */
    private fun registerFcmToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            CallSignalingClient().registerDeviceToken(token)
        }
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
    // tourner sur la tablette elle-même) ----

    private fun toggleRoomCaptions() {
        if (roomCaptionsUserEnabled) {
            roomCaptionsUserEnabled = false
            stopRoomCaptions()
            showIdleView()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Micro non autorisé", Toast.LENGTH_SHORT).show()
                return
            }
            roomCaptionsUserEnabled = true
            showRoomCaptionView()
            startRoomCaptions()
        }
    }

    private fun showIdleView() {
        idleContent.visibility = View.VISIBLE
        roomCaptionScroll.visibility = View.GONE
        buttonStopRoomCaptions.visibility = View.GONE
    }

    private fun showRoomCaptionView() {
        idleContent.visibility = View.GONE
        roomCaptionScroll.visibility = View.VISIBLE
        buttonStopRoomCaptions.visibility = View.VISIBLE
        roomCaptionHistory.clear()
        textRoomCaption.text = ""
    }

    private fun startRoomCaptions() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconnaissance vocale indisponible sur cette tablette", Toast.LENGTH_SHORT).show()
            roomCaptionsUserEnabled = false
            showIdleView()
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
            // Le silence par défaut (courte pause naturelle entre deux phrases
            // d'une conversation) fait sinon expirer la reconnaissance très
            // vite (ERROR_SPEECH_TIMEOUT), relançant en boucle rapprochée —
            // et chaque relance rejoue le bip de début d'écoute du service de
            // reconnaissance d'Android, d'où des bips répétitifs sans jamais
            // laisser le temps de capter une phrase entière.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
        }
        speechRecognizer?.startListening(intent)
        roomCaptionsActive = true
    }

    private fun stopRoomCaptions() {
        roomCaptionsActive = false
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
    }

    /**
     * L'API SpeechRecognizer traite une phrase à la fois : sans relance
     * après chaque résultat/erreur, elle s'arrête au premier silence — même
     * principe que côté PWA (voir webrtc-engine.js, recognition.onend).
     */
    private fun restartRoomCaptionsIfEnabled() {
        if (!roomCaptionsUserEnabled) return
        textRoomCaption.postDelayed({ if (roomCaptionsUserEnabled) startRoomCaptions() }, 300)
    }

    // Nombre d'échecs à la suite (aucun résultat entre-temps) avant
    // d'abandonner : sans ce garde-fou, une erreur qui se reproduit à
    // chaque relance (micro indisponible, service de reconnaissance non
    // fonctionnel sur cet appareil...) bouclait indéfiniment, avec à chaque
    // tentative le bip de début d'écoute du service Android — de vrais bips
    // répétitifs sans jamais rien afficher, plutôt qu'un message clair.
    private var consecutiveErrorCount = 0
    private val maxConsecutiveErrors = 3

    private val roomCaptionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            Log.w(TAG, "Sous-titres de la pièce : erreur reconnaissance vocale (code $error)")
            consecutiveErrorCount++
            if (consecutiveErrorCount >= maxConsecutiveErrors) {
                consecutiveErrorCount = 0
                roomCaptionsUserEnabled = false
                Toast.makeText(
                    this@MainActivity,
                    "La reconnaissance vocale ne fonctionne pas pour l'instant sur cette tablette",
                    Toast.LENGTH_LONG
                ).show()
                stopRoomCaptions()
                showIdleView()
                return
            }
            restartRoomCaptionsIfEnabled()
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrorCount = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrEmpty()) appendRoomCaption(text)
            restartRoomCaptionsIfEnabled()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            consecutiveErrorCount = 0
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrEmpty()) {
                val preview = if (roomCaptionHistory.isNotEmpty()) "$roomCaptionHistory\n$text" else text
                showRoomCaptionText(preview)
            }
        }
    }

    private fun appendRoomCaption(text: String) {
        if (roomCaptionHistory.isNotEmpty()) roomCaptionHistory.append("\n")
        roomCaptionHistory.append(text)
        // Historique borné : évite une zone de texte qui grossirait indéfiniment.
        val maxChars = 2000
        if (roomCaptionHistory.length > maxChars) {
            roomCaptionHistory.delete(0, roomCaptionHistory.length - maxChars)
        }
        showRoomCaptionText(roomCaptionHistory.toString())
    }

    private fun showRoomCaptionText(text: String) {
        textRoomCaption.text = text
        textRoomCaption.post { roomCaptionScroll.smoothScrollTo(0, textRoomCaption.height) }
    }

    /**
     * Coupe le micro dès que cet écran n'est plus au premier plan (typiquement
     * un appel entrant qui prend l'écran) : sans ça, la reconnaissance de la
     * pièce entrerait en conflit avec le micro de l'appel vidéo.
     */
    override fun onPause() {
        super.onPause()
        if (roomCaptionsActive) stopRoomCaptions()
    }

    /** Reprend automatiquement les sous-titres de la pièce si Jean les avait activés. */
    override fun onResume() {
        super.onResume()
        if (roomCaptionsUserEnabled && !roomCaptionsActive) startRoomCaptions()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRoomCaptions()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
