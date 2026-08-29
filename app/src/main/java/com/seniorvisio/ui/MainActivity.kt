package com.seniorvisio.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.text.InputType
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import com.seniorvisio.admin.SeniorVisioDeviceAdminReceiver
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.KioskManager
import com.seniorvisio.service.CallListenerService
import com.seniorvisio.signaling.CallSignalingClient
import kotlin.math.roundToInt

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
    private lateinit var roomCaptionBanner: LinearLayout
    private lateinit var roomCaptionScroll: ScrollView
    private lateinit var textRoomCaption: TextView
    private lateinit var buttonRoomCaptions: Button
    private lateinit var buttonStopRoomCaptions: Button
    private lateinit var roomCaptionTextSizeControls: LinearLayout
    private lateinit var buttonRoomTextSmaller: Button
    private lateinit var buttonRoomTextLarger: Button
    private lateinit var roomCaptionScrollAnimator: CaptionScrollAnimator
    private lateinit var roomCaptionWebView: WebView

    private val adminConfig by lazy { AdminConfig(this) }

    private var roomCaptionsActive = false
    // Distinct de roomCaptionsActive : reflète le choix de Jean, pas l'état
    // technique du moment (coupé pendant un appel entrant, voir onPause/
    // onResume) — permet de reprendre automatiquement les sous-titres de la
    // pièce au retour sur cet écran, sans que Jean ait à rappuyer dessus.
    private var roomCaptionsUserEnabled = false

    private var roomCaptionTextSizeSp = AdminConfig.ROOM_CAPTION_TEXT_SIZE_DEFAULT_SP

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
        roomCaptionBanner = findViewById(R.id.roomCaptionBanner)
        roomCaptionScroll = findViewById(R.id.roomCaptionScroll)
        textRoomCaption = findViewById(R.id.textRoomCaption)
        buttonRoomCaptions = findViewById(R.id.buttonRoomCaptions)
        buttonStopRoomCaptions = findViewById(R.id.buttonStopRoomCaptions)
        roomCaptionTextSizeControls = findViewById(R.id.roomCaptionTextSizeControls)
        buttonRoomTextSmaller = findViewById(R.id.buttonRoomTextSmaller)
        buttonRoomTextLarger = findViewById(R.id.buttonRoomTextLarger)
        // Défilement à vitesse plafonnée si le texte dépasse l'espace visible
        // (voir updateRoomCaptionText), même logique que les sous-titres d'appel.
        roomCaptionScrollAnimator = CaptionScrollAnimator(
            scrollView = roomCaptionScroll,
            maxSpeedPxPerSec = { ROOM_CAPTION_SCROLL_SPEED_DP_PER_SEC * resources.displayMetrics.density },
        )
        applyRoomCaptionLayout(resources.configuration.orientation)
        setupRoomCaptionWebView()
        roomCaptionTextSizeSp = adminConfig.roomCaptionTextSizeSp
        buttonRoomCaptions.setOnClickListener { toggleRoomCaptions() }
        buttonStopRoomCaptions.setOnClickListener { toggleRoomCaptions() }
        buttonRoomTextSmaller.setOnClickListener { adjustRoomCaptionTextSize(-AdminConfig.ROOM_CAPTION_TEXT_SIZE_STEP_SP) }
        buttonRoomTextLarger.setOnClickListener { adjustRoomCaptionTextSize(AdminConfig.ROOM_CAPTION_TEXT_SIZE_STEP_SP) }
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
                if (input.text.toString() == adminConfig.adminPin) {
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
        roomCaptionBanner.visibility = View.GONE
        buttonStopRoomCaptions.visibility = View.GONE
        roomCaptionTextSizeControls.visibility = View.GONE
        // Plus besoin de garder l'écran allumé de force une fois sorti du
        // mode sous-titré (voir showRoomCaptionView) : la mise en veille
        // normale de la tablette reprend son cours.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showRoomCaptionView() {
        idleContent.visibility = View.GONE
        roomCaptionBanner.visibility = View.VISIBLE
        buttonStopRoomCaptions.visibility = View.VISIBLE
        roomCaptionTextSizeControls.visibility = View.VISIBLE
        lastRoomCaptionText = ""
        textRoomCaption.text = ""
        // Jean regarde l'écran pour lire les sous-titres : la tablette ne
        // doit pas s'éteindre toute seule pendant qu'il les utilise (retiré
        // dès la sortie du mode, voir showIdleView).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Rotation de la tablette pendant les sous-titres de la pièce :
     * configChanges (voir AndroidManifest) empêche déjà la destruction de
     * l'Activity, il ne reste qu'à réadapter la disposition — même principe
     * qu'IncomingCallActivity.onConfigurationChanged pour l'appel.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyRoomCaptionLayout(newConfig.orientation)
    }

    /**
     * Bandeau de sous-titres géré exactement comme celui des appels (voir
     * IncomingCallActivity.applyOrientationLayout) : mêmes marges latérales
     * adaptées à l'orientation, même fond, mais centré à l'écran plutôt
     * qu'en bas (pas de vidéo ici à laisser plein écran). Plafond de hauteur
     * augmenté de 50% par rapport à l'équivalent côté appel, puisque rien
     * d'autre ne se dispute la place sur cet écran.
     */
    private fun applyRoomCaptionLayout(orientation: Int) {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val sideMargin = ((if (isLandscape) 12 else 24) * density).roundToInt()
        // Rembourrage haut+bas du bandeau, fixé dans le layout XML (android:padding="20dp").
        val bannerVerticalPadding = (20 * density * 2).roundToInt()

        val captionScrollHeight = if (isLandscape) {
            // Côté appel : plafonné à la moitié basse de l'écran. Ici, +50%.
            val maxBannerAreaPx = (resources.displayMetrics.heightPixels * 0.75f).roundToInt()
            (maxBannerAreaPx - bannerVerticalPadding).coerceAtLeast((80 * density).roundToInt())
        } else {
            // Côté appel : 220dp fixe en portrait. Ici, +50%.
            (330 * density).roundToInt()
        }

        (roomCaptionBanner.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
            marginStart = sideMargin
            marginEnd = sideMargin
            roomCaptionBanner.layoutParams = this
        }

        (roomCaptionScroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = captionScrollHeight
            roomCaptionScroll.layoutParams = this
        }
    }

    /**
     * Sur cette tablette Samsung, SpeechRecognizer.createSpeechRecognizer(this)
     * traite une phrase à la fois : il faut le relancer après chaque silence,
     * ce qui rejoue à chaque fois le bip système de début d'écoute et fait
     * clignoter le voyant micro Android 12+, en plus d'un trou de latence
     * entre deux phrases. Le mode appel n'a jamais ce problème : le
     * navigateur y utilise webkitSpeechRecognition en continuous = true, une
     * seule session longue sans redémarrage entre les phrases (voir
     * webrtc-engine.js, _startCaptioning). On rejoue exactement le même
     * moteur ici, dans une WebView invisible, plutôt que de patcher
     * SpeechRecognizer.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupRoomCaptionWebView() {
        roomCaptionWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray())
                }
            }
            addJavascriptInterface(RoomCaptionJsBridge(), "AndroidRoomCaptions")
        }
        // Invisible (1x1) : seul le résultat affiché dans textRoomCaption
        // compte pour Jean, cette WebView n'est qu'un moteur de reconnaissance.
        (idleContent.parent as ViewGroup).addView(roomCaptionWebView, ViewGroup.LayoutParams(1, 1))
    }

    private inner class RoomCaptionJsBridge {
        @JavascriptInterface
        fun onResult(text: String) {
            if (text.isNotBlank()) runOnUiThread { updateRoomCaptionText(text) }
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.w(TAG, "Sous-titres de la pièce : erreur reconnaissance vocale ($message)")
        }
    }

    private fun startRoomCaptions() {
        roomCaptionWebView.loadDataWithBaseURL(
            "https://localhost/", ROOM_CAPTION_RECOGNITION_HTML, "text/html", "utf-8", null
        )
        roomCaptionsActive = true
        setDeviceMuted(true)
    }

    private fun stopRoomCaptions() {
        roomCaptionsActive = false
        roomCaptionWebView.loadUrl("about:blank")
        // Toujours remis en place ici, pas seulement quand Jean coupe les
        // sous-titres à la main (voir toggleRoomCaptions) : stopRoomCaptions
        // est aussi appelé par onPause quand un appel entrant interrompt ce
        // mode (voir onPause plus bas) — sans ça, la tablette resterait
        // muette pendant l'appel lui-même.
        setDeviceMuted(false)
    }

    /**
     * Coupe tout le son de la tablette (y compris les notifications) pendant
     * les sous-titres de la pièce : Jean lit le texte à l'écran, un bip de
     * notification ou une sonnerie quelconque pendant qu'il lit n'apporte
     * rien et peut le déconcentrer. Mute général plutôt qu'un blocage ciblé
     * (ex. Ne pas déranger) : agit immédiatement sans demander à Jean
     * d'accorder une permission supplémentaire (Device Owner en dispense),
     * et se lève tout seul dès la sortie du mode, sans rien à mémoriser côté
     * app — c'est une coupure du volume principal, pas un changement des
     * niveaux eux-mêmes.
     */
    private fun setDeviceMuted(muted: Boolean) {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val admin = ComponentName(this, SeniorVisioDeviceAdminReceiver::class.java)
        dpm.setMasterVolumeMuted(admin, muted)
    }

    // Dernier texte affiché (aperçu ou phrase confirmée, sans distinction —
    // pas d'historique conservé, voir updateRoomCaptionText) : mémorisé pour
    // pouvoir le réafficher immédiatement à la nouvelle taille dès que Jean
    // appuie sur A-/A+, plutôt que d'attendre la prochaine phrase reconnue.
    private var lastRoomCaptionText = ""

    /**
     * Affiche le texte reconnu (aperçu ou phrase confirmée) tel quel, sans
     * accumuler avec ce qui précède : chaque mise à jour remplace la
     * précédente. Défile jusqu'en bas si le texte dépasse l'espace visible,
     * même logique de vitesse plafonnée que les sous-titres d'appel (voir
     * CaptionScrollAnimator).
     */
    private fun updateRoomCaptionText(text: String) {
        lastRoomCaptionText = text
        textRoomCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, roomCaptionTextSizeSp)
        textRoomCaption.text = text
        textRoomCaption.post {
            val maxScroll = (textRoomCaption.height - roomCaptionScroll.height).coerceAtLeast(0)
            roomCaptionScrollAnimator.scrollTo(maxScroll)
        }
    }

    private fun adjustRoomCaptionTextSize(deltaSp: Float) {
        roomCaptionTextSizeSp = (roomCaptionTextSizeSp + deltaSp)
            .coerceIn(AdminConfig.ROOM_CAPTION_TEXT_SIZE_MIN_SP, AdminConfig.ROOM_CAPTION_TEXT_SIZE_MAX_SP)
        adminConfig.roomCaptionTextSizeSp = roomCaptionTextSizeSp
        if (lastRoomCaptionText.isNotBlank()) updateRoomCaptionText(lastRoomCaptionText)
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

        // Pas de proche pour ajuster ce réglage à distance ici (contrairement
        // aux sous-titres d'appel, voir WebRtcCallEngine.listenForCaptionScrollSpeed) :
        // valeur fixe, identique à celle de départ côté appel.
        private const val ROOM_CAPTION_SCROLL_SPEED_DP_PER_SEC = 50f

        // Reprend exactement le moteur de reconnaissance utilisé côté appel
        // (voir web-caller/webrtc-engine.js, _startCaptioning) : une seule
        // session continue (continuous = true) relancée uniquement si le
        // navigateur l'arrête vraiment (onend), pas à chaque phrase.
        private const val ROOM_CAPTION_RECOGNITION_HTML = """
            <!DOCTYPE html><html><body><script>
            var shouldRun = true;
            function startRecognition() {
              var Ctor = window.SpeechRecognition || window.webkitSpeechRecognition;
              if (!Ctor) { AndroidRoomCaptions.onError('non supporté'); return; }
              var recognition = new Ctor();
              recognition.continuous = true;
              recognition.interimResults = true;
              recognition.lang = 'fr-FR';
              recognition.onresult = function(event) {
                var text = '';
                for (var i = event.resultIndex; i < event.results.length; i++) {
                  text += event.results[i][0].transcript;
                }
                if (text) AndroidRoomCaptions.onResult(text);
              };
              recognition.onerror = function(e) { AndroidRoomCaptions.onError(e.error); };
              recognition.onend = function() { if (shouldRun) { try { recognition.start(); } catch (e) {} } };
              try { recognition.start(); } catch (e) { AndroidRoomCaptions.onError(String(e)); }
            }
            startRecognition();
            </script></body></html>
        """
    }
}
