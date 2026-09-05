package com.seniorvisio.ui

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import com.seniorvisio.service.CallListenerService
import com.seniorvisio.service.RoomPresenceService
import com.seniorvisio.signaling.CallSignalingClient

/**
 * Écran affiché quand aucun appel n'est en cours — et, du point de vue de
 * Jean, le seul écran de la tablette : les mêmes trois zones restent en place
 * pendant un appel (voir IncomingCallActivity), seul le fond change.
 *
 * Jean n'a rien à faire ni à toucher. La date, le moment de la journée et la
 * météo sont là en permanence ; ce qui se dit dans la pièce s'écrit tout seul
 * dès que quelqu'un parle (voir RoomPresenceService, lié ci-dessous) ; ce que
 * dit un proche au téléphone s'écrit tout seul dès qu'un appel démarre. Le
 * bouton "Voir ce qui se dit" qui occupait la moitié de cet écran a disparu
 * avec cette bascule : il demandait à Jean de savoir qu'une fonction existait
 * et de penser à la lancer, ce qui est exactement ce qu'il faut éviter ici.
 *
 * La détection d'appel entrant ne dépend pas du cycle de vie de cet écran :
 * elle tourne en continu dans CallListenerService (démarré ci-dessous),
 * pour fonctionner même écran éteint ou app en arrière-plan.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op : voir startLocalMedia() pour le repli si refusé */ }

    private val adminConfig by lazy { AdminConfig(this) }
    private lateinit var zones: HomeZonesController
    private var roomService: RoomPresenceService? = null

    /**
     * Les paroles de la pièce viennent du service qui tient déjà le micro
     * pour le réveil au son (voir RoomPresenceService) : une seule capture,
     * deux usages. Ouvrir une seconde capture concurrente était précisément
     * ce qui rendait les sous-titres peu fiables avant cette bascule.
     */
    private val roomConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? RoomPresenceService.LocalBinder)?.getService() ?: return
            roomService = service
            service.startRoomTranscription { text, isFinal ->
                runOnUiThread { zones.roomZone.submit(text, isFinal) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            roomService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textBuildRev = findViewById<TextView>(R.id.textBuildRev)
        textBuildRev.text = BuildConfig.BUILD_REV
        // Point d'entrée discret vers les réglages admin (Wi-Fi, PIN, durée
        // du décompte) : une fois en mode kiosque, plus aucun autre moyen d'y
        // accéder (Réglages système bloqués), voir KioskManager.
        textBuildRev.setOnLongClickListener { promptAdminPin(); true }

        zones = HomeZonesController(
            root = findViewById(R.id.homeRoot),
            onPalette = { palette ->
                findViewById<View>(R.id.homeRoot).setBackgroundColor(palette.background)
                textBuildRev.setTextColor(palette.secondaryText)
            },
        )

        val imageQr = findViewById<ImageView>(R.id.imageCaregiverQr)
        // Touché plutôt que scanné : c'est le geste naturel de quelqu'un qui
        // découvre un code sans savoir à quoi il sert. On lui explique.
        imageQr.setOnClickListener { showCaregiverHelp() }
        // Appui long : labo d'étude comparant les moteurs de transcription
        // (voir TranscriptionLabActivity). Il vivait sur la carte "Voir ce qui
        // se dit", disparue de cet écran ; il se rattache ici plutôt que de
        // devenir inaccessible, sans gêner l'usage normal de la vignette.
        imageQr.setOnLongClickListener {
            startActivity(Intent(this, TranscriptionLabActivity::class.java))
            true
        }
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
        zones.onResume()
    }

    /**
     * La transcription de la pièce s'arrête avec cet écran : pendant un appel
     * (IncomingCallActivity passe devant), le micro appartient à WebRTC, et
     * l'écran d'appel a sa propre source de texte.
     */
    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RoomPresenceService::class.java), roomConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        roomService?.stopRoomTranscription()
        roomService = null
        unbindService(roomConnection)
        zones.roomZone.clear()
    }

    override fun onPause() {
        super.onPause()
        zones.onPause()
    }

    override fun onDestroy() {
        zones.release()
        super.onDestroy()
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
     * mal éclairée. C'est aussi pour ça qu'il ne suit pas la palette
     * clair/sombre du reste de l'écran (voir ScreenTheme).
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
    }
}
