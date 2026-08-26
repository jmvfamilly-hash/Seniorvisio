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
import com.seniorvisio.core.AssemblyAiRealtimeTranscriber
import com.seniorvisio.core.KioskManager
import com.seniorvisio.core.MicPcmStreamer
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

    private var micStreamer: MicPcmStreamer? = null
    private var transcriber: AssemblyAiRealtimeTranscriber? = null
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
        val apiKey = AdminConfig(this).assemblyAiApiKey
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Clé AssemblyAI manquante (réglages admin)", Toast.LENGTH_LONG).show()
            roomCaptionsUserEnabled = false
            showIdleView()
            return
        }
        val newTranscriber = AssemblyAiRealtimeTranscriber(
            apiKey = apiKey,
            onTranscript = { text, isFinal -> runOnUiThread { onRoomTranscript(text, isFinal) } },
            onError = { message -> runOnUiThread { onRoomTranscriptionError(message) } },
        )
        val streamer = MicPcmStreamer { pcm, _ -> newTranscriber.feed(pcm) }
        if (!streamer.start()) {
            Toast.makeText(this, "Micro indisponible sur cette tablette", Toast.LENGTH_SHORT).show()
            roomCaptionsUserEnabled = false
            showIdleView()
            return
        }
        newTranscriber.start(sampleRate = 16000)
        transcriber = newTranscriber
        micStreamer = streamer
        roomCaptionsActive = true
    }

    private fun stopRoomCaptions() {
        roomCaptionsActive = false
        micStreamer?.stop()
        micStreamer = null
        transcriber?.stop()
        transcriber = null
    }

    /** Même logique partiel/final que l'ancien SpeechRecognizer : la phrase en
     * cours s'affiche en aperçu, et n'est ajoutée à l'historique qu'une fois
     * confirmée (end_of_turn), pour ne pas la dupliquer. */
    private fun onRoomTranscript(text: String, isFinal: Boolean) {
        if (isFinal) {
            if (text.isNotBlank()) appendRoomCaption(text)
        } else if (text.isNotBlank()) {
            val preview = if (roomCaptionHistory.isNotEmpty()) "$roomCaptionHistory\n$text" else text
            showRoomCaptionText(preview)
        }
    }

    // La connexion temps réel est fermée après une erreur (réseau, clé
    // invalide...) : contrairement à l'ancien SpeechRecognizer, qui déclenchait
    // une erreur bénigne à chaque silence prolongé, une erreur ici signale
    // une vraie coupure — inutile d'attendre plusieurs échecs avant d'abandonner.
    private fun onRoomTranscriptionError(message: String) {
        Log.w(TAG, "Sous-titres de la pièce : erreur AssemblyAI ($message)")
        roomCaptionsUserEnabled = false
        Toast.makeText(
            this,
            "La transcription ne fonctionne pas pour l'instant (connexion internet ?)",
            Toast.LENGTH_LONG
        ).show()
        stopRoomCaptions()
        showIdleView()
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
