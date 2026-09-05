package com.seniorvisio.ui

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.seniorvisio.BuildConfig
import com.seniorvisio.R
import com.seniorvisio.admin.AdminSettingsActivity
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.KioskManager
import com.seniorvisio.core.TimeContext
import com.seniorvisio.core.WeatherClient
import com.seniorvisio.service.CallListenerService
import com.seniorvisio.service.RoomPresenceService
import com.seniorvisio.signaling.CallSignalingClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Écran affiché quand aucun appel n'est en cours. Volontairement épuré pour
 * l'usage senior : un message d'accueil et un unique bouton, qui bascule vers
 * la transcription des conversations de la pièce — rien d'autre à comprendre.
 *
 * La transcription (voir RoomTranscriptionActivity, AssemblyAI) est un
 * écran à part de Senior Visio, pas une application tierce : Jean en revient
 * par le bouton Accueil, Senior Visio étant le lanceur de la tablette (voir
 * KioskManager). Aucun démarrage automatique : la tablette reste sur cet
 * écran au repos, donc pas de micro ni de coupure du son en permanence.
 *
 * La détection d'appel entrant ne dépend pas du cycle de vie de cet écran :
 * elle tourne en continu dans CallListenerService (démarré ci-dessous),
 * pour fonctionner même écran éteint ou app en arrière-plan.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op : voir startLocalMedia() pour le repli si refusé */ }

    private val adminConfig by lazy { AdminConfig(this) }

    private lateinit var textMomentIcon: TextView
    private lateinit var textMomentLabel: TextView
    private lateinit var textMomentWeatherSeparator: TextView
    private lateinit var textWeatherIcon: TextView
    private lateinit var textWeatherLabel: TextView
    private lateinit var textClockDate: TextView
    private val clockHandler = Handler(Looper.getMainLooper())
    private val weatherClient by lazy { WeatherClient(this) }

    /**
     * Recalé sur le quart d'heure suivant à chaque tour, pas chaque minute :
     * le moment de la journée (matin/après-midi/soir/nuit) ne change que
     * quelques fois par jour, inutile de réveiller le processeur plus souvent
     * sur une tablette allumée en permanence.
     */
    private val clockTicker = object : Runnable {
        override fun run() {
            updateClock()
            val quarterHourMs = 15 * 60_000L
            clockHandler.postDelayed(this, quarterHourMs - (System.currentTimeMillis() % quarterHourMs))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textMomentIcon = findViewById(R.id.textMomentIcon)
        textMomentLabel = findViewById(R.id.textMomentLabel)
        textMomentWeatherSeparator = findViewById(R.id.textMomentWeatherSeparator)
        textWeatherIcon = findViewById(R.id.textWeatherIcon)
        textWeatherLabel = findViewById(R.id.textWeatherLabel)
        textClockDate = findViewById(R.id.textClockDate)
        updateClock()

        val textBuildRev = findViewById<TextView>(R.id.textBuildRev)
        textBuildRev.text = BuildConfig.BUILD_REV
        // Point d'entrée discret vers les réglages admin (Wi-Fi, PIN, durée
        // du décompte) : une fois en mode kiosque, plus aucun autre moyen d'y
        // accéder (Réglages système bloqués), voir KioskManager.
        textBuildRev.setOnLongClickListener { promptAdminPin(); true }

        val cardRoomCaptions = findViewById<View>(R.id.cardRoomCaptions)
        cardRoomCaptions.setOnClickListener { launchRoomTranscription() }
        // Appui long : labo d'étude comparant les moteurs de transcription
        // (voir TranscriptionLabActivity), sans toucher à l'usage normal du
        // bouton (appui simple, inchangé).
        cardRoomCaptions.setOnLongClickListener {
            startActivity(Intent(this, TranscriptionLabActivity::class.java))
            true
        }

        // Touché plutôt que scanné : c'est le geste naturel de quelqu'un qui
        // découvre un code sans savoir à quoi il sert. On lui explique.
        findViewById<View>(R.id.cardCaregiverQr).setOnClickListener { showCaregiverHelp() }
        showCaregiverQrCode()

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        ContextCompat.startForegroundService(this, Intent(this, CallListenerService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, RoomPresenceService::class.java))
        requestIgnoreBatteryOptimizations()
        requestFullScreenIntentPermission()
        registerFcmToken()
        // Seul écran à se déclarer comme lanceur de la tablette : c'est lui
        // que le bouton Accueil doit ramener, depuis n'importe quelle
        // application compagne.
        KioskManager.startIfDeviceOwner(this, MainActivity::class.java)
    }

    /**
     * Referme toute fenêtre de maintenance encore ouverte (voir
     * KioskManager.grantTemporaryBrowserAccess, déclenchée depuis l'écran
     * admin pour se connecter au réseau de la résidence) dès le retour ici
     * par le bouton Accueil — sans attendre l'expiration au bout de 10
     * minutes. startIfDeviceOwner réapplique simplement la liste standard
     * des applications autorisées en mode kiosque, navigateur exclu.
     */
    override fun onResume() {
        super.onResume()
        KioskManager.startIfDeviceOwner(this, MainActivity::class.java)
        // Relancée ici plutôt qu'une seule fois au démarrage : la tablette peut
        // rester des heures écran éteint, le repère temporel doit être juste
        // dès qu'elle se rallume, pas au prochain quart d'heure.
        clockHandler.removeCallbacks(clockTicker)
        clockHandler.post(clockTicker)
        // Remet le libellé d'origine après un "Ouverture…" ou un échec : au
        // retour de la transcription, la carte doit réinviter à l'utiliser.
        findViewById<TextView>(R.id.textRoomCaptionsHint).text =
            getString(R.string.room_captions_hint)
    }

    /** Inutile de faire tourner l'horloge quand l'écran ne l'affiche pas. */
    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTicker)
    }

    /**
     * Pictogramme + mot ("🌤️ Après-midi") à la place d'une heure exacte : se
     * reconnaît d'un coup d'œil, là où "14:35" demande de déchiffrer deux
     * nombres. Ce qui compte au quotidien, c'est de savoir où on en est dans
     * la journée, pas l'heure à la minute près. Date en toutes lettres
     * ("Mercredi 3 septembre 2026") plutôt qu'en chiffres, pour la même
     * raison : plus long à écrire, immédiatement lisible.
     *
     * La météo (voir WeatherClient) est demandée à chaque tour mais ne fait
     * un vrai appel réseau qu'une fois par heure (cache interne) : rien à
     * gérer de spécial ici, juste rappeler la fonction régulièrement.
     */
    private fun updateClock() {
        val now = LocalDateTime.now()
        val moment = TimeContext.momentOfDay(now.hour)
        textMomentIcon.text = moment.icon
        textMomentLabel.text = moment.label
        textClockDate.text = now.format(DATE_FORMAT)
            .replaceFirstChar { it.titlecase(Locale.FRENCH) }

        weatherClient.fetchWeather { weather ->
            val visibility = if (weather == null) View.GONE else View.VISIBLE
            textMomentWeatherSeparator.visibility = visibility
            textWeatherIcon.visibility = visibility
            textWeatherLabel.visibility = visibility
            if (weather != null) {
                textWeatherIcon.text = weather.icon
                textWeatherLabel.text = weather.label
            }
        }
    }

    /**
     * Génère et affiche le QR code que scanne un soignant présent dans la
     * pièce. Généré à la volée plutôt que stocké en image : l'adresse peut
     * changer (déploiement du PWA ailleurs) sans avoir à refabriquer un
     * fichier, et rien ne peut se désynchroniser entre l'image et l'adresse
     * réelle.
     *
     * Dessiné en noir sur blanc et non aux couleurs de l'écran : les
     * applications d'appareil photo reconnaissent nettement mieux un QR code
     * franchement contrasté, surtout photographié de biais dans une chambre
     * mal éclairée.
     */
    private fun showCaregiverQrCode() {
        val imageQr = findViewById<ImageView>(R.id.imageCaregiverQr)
        try {
            val size = 480
            val matrix = QRCodeWriter().encode(CAREGIVER_URL, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            imageQr.setImageBitmap(bitmap)
        } catch (e: Exception) {
            // Un QR code illisible vaut mieux qu'un écran d'accueil qui plante :
            // le reste (horloge, sous-titres) doit rester utilisable.
            Log.e(TAG, "Génération du QR code soignant impossible", e)
        }
    }

    /**
     * Explique la fonction à qui touche le QR code sans savoir ce que c'est —
     * cas le plus probable pour un soignant qui entre dans la chambre. Trois
     * étapes imagées (voir dialog_caregiver_help.xml) plutôt qu'un texte à
     * lire debout.
     */
    private fun showCaregiverHelp() {
        val content = layoutInflater.inflate(R.layout.dialog_caregiver_help, null)
        AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("J'ai compris", null)
            .show()
    }

    /**
     * Ouvre les sous-titres de la pièce (voir RoomTranscriptionActivity,
     * AssemblyAI) — plus d'application tierce à résoudre ni de risque
     * d'échec de lancement, cet écran fait partie de Senior Visio.
     */
    private fun launchRoomTranscription() {
        findViewById<TextView>(R.id.textRoomCaptionsHint).text = "Ouverture…"
        startActivity(Intent(this, RoomTranscriptionActivity::class.java))
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

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Adresse encodée dans le QR code de l'écran d'accueil. Le paramètre
         * `soignant` fait ouvrir le PWA dans son mode simplifié : connexion
         * immédiate sans décompte ni photo, son de la tablette coupé,
         * sous-titres activés d'office (voir web-caller/app.js).
         */
        private const val CAREGIVER_URL = "https://jmvfamilly-hash.github.io/Seniorvisio/?soignant=1"

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
    }
}
