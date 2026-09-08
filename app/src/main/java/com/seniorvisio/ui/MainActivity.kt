package com.seniorvisio.ui

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.text.InputType
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
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
import com.seniorvisio.core.AlertVolume
import com.seniorvisio.core.KioskManager
import com.seniorvisio.core.TranscriptionSource
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
            // Argument nommé, pas un lambda en fin d'appel : celui-ci se
            // rattacherait au DERNIER paramètre (onError), pas à onText.
            service.startRoomTranscription(
                onText = { text, isFinal ->
                    runOnUiThread { zones.submitTranscription(TranscriptionSource.ROOM, text, isFinal) }
                },
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            roomService = null
        }
    }

    private val screenAwakeHandler = Handler(Looper.getMainLooper())
    private var textGoneSinceMs = 0L

    /**
     * Empêche la tablette de s'endormir tant qu'il reste du texte à l'écran,
     * et rend la main à la veille un moment après que tout a été affiché.
     *
     * Le réveil au son (voir RoomPresenceService) ne suffit pas pour ça : il
     * suit le SON, qu'il relâche quelques secondes après le dernier bruit. Or
     * la transcription arrive avec du retard sur la parole, et le texte
     * continue de défiler après que la personne s'est tue — l'écran
     * s'éteignait donc en plein milieu de ce que Jean était en train de lire.
     * Ce qui doit décider ici, c'est ce qui est affiché, pas ce qui s'entend.
     *
     * Passe par le drapeau de fenêtre plutôt que par un verrou de réveil :
     * c'est le mécanisme prévu pour "garder l'écran allumé pendant que cet
     * écran-ci est visible", il ne demande aucune permission et fonctionne
     * sur toutes les versions d'Android.
     */
    private val screenAwakeTicker = object : Runnable {
        override fun run() {
            applyCaptionErgonomics()
            val hasText = zones.hasTextOnScreen()
            val now = System.currentTimeMillis()
            if (hasText) textGoneSinceMs = 0L
            else if (textGoneSinceMs == 0L) textGoneSinceMs = now

            val keepAwake = hasText || (now - textGoneSinceMs) < READING_GRACE_MS
            if (keepAwake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            screenAwakeHandler.postDelayed(this, SCREEN_AWAKE_TICK_MS)
        }
    }

    /**
     * Applique l'ergonomie de lecture réglée par l'administrateur (lignes
     * visibles, vitesse de défilement, délai d'effacement). Relue au fil du
     * temps plutôt qu'une fois au démarrage : ces réglages arrivent depuis le
     * PWA par le document d'appareil (voir DeviceStatusReporter), et
     * l'administrateur qui bouge un curseur à distance doit en voir l'effet sur
     * la tablette dans la seconde, sans attendre le prochain appel ni un
     * redémarrage.
     *
     * Les valeurs identiques sont ignorées en aval (voir
     * RollingCaptionZone.setVisibleLines), la relecture ne coûte donc rien.
     */
    private fun applyCaptionErgonomics() {
        zones.setVisibleLines(adminConfig.captionVisibleLines)
        zones.setScrollSpeedDpPerSec(adminConfig.captionScrollSpeedDp.toFloat())
        zones.setClearDelaySeconds(adminConfig.captionClearDelaySeconds)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideNavigationBar()
        applyWakeOnSoundRequest(intent)

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
     * Cet écran étant déclaré singleTask (voir AndroidManifest), une demande
     * de réveil arrivée alors qu'il existe déjà passe par ici plutôt que de
     * créer une seconde instance.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyWakeOnSoundRequest(intent)
    }

    /**
     * Allume la dalle quand le service d'écoute a entendu du bruit dans la
     * pièce écran éteint (voir RoomPresenceService.ensureAwake).
     *
     * C'est le mécanisme que le système prévoit pour ça aujourd'hui, et celui
     * qui fonctionne déjà pour les appels entrants (voir
     * IncomingCallActivity) — le verrou de réveil d'écran employé jusqu'ici
     * est déprécié de longue date et a cessé d'avoir un effet sur cette
     * tablette sans qu'une ligne de code ne change.
     *
     * Le drapeau est retiré dès que cet écran passe à l'arrière-plan (voir
     * onPause) : laissé actif en permanence, la dalle se rallumerait à chaque
     * fois que cet écran revient au premier plan, pour n'importe quelle
     * raison — sur une tablette allumée 24h/24, de quoi ne plus jamais la
     * laisser s'éteindre.
     */
    private fun applyWakeOnSoundRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_ON_SOUND, false) != true) return
        intent.removeExtra(EXTRA_WAKE_ON_SOUND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
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
        // Cet écran, c'est la définition de « pas en appel » : la tablette y
        // revient dès qu'une conversation se termine. Les alertes y
        // descendent au plus bas, ce qui fait taire les sons du moteur de
        // reconnaissance d'Android sans jamais interrompre l'écoute (voir
        // AlertVolume). La sonnerie d'appel, elle, est sur le flux alarme et
        // n'en dépend pas.
        AlertVolume.quiet(this)
        KioskManager.startIfDeviceOwner(this, MainActivity::class.java)
        zones.onResume()
        screenAwakeHandler.removeCallbacks(screenAwakeTicker)
        screenAwakeHandler.post(screenAwakeTicker)
    }

    /**
     * Android réaffiche les barres système à chaque reprise de focus (retour
     * d'une boîte de dialogue, d'un écran admin, du menu Marche/Arrêt) :
     * sans ce rappel, la barre de navigation revient définitivement à la
     * première interruption venue. Voir hideNavigationBar.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
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
        zones.clearTranscriptions()
    }

    override fun onPause() {
        super.onPause()
        zones.onPause()
        screenAwakeHandler.removeCallbacks(screenAwakeTicker)
        // Jamais d'écran forcé allumé une fois cet écran quitté : usage 24h/24,
        // risque de marquage de dalle et de chauffe sinon.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Voir applyWakeOnSoundRequest : le rallumage ne vaut que pour la
        // demande qui l'a déclenché, jamais pour les retours suivants.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        zones.release()
        super.onDestroy()
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

        /** Voir RoomPresenceService.ensureAwake : un bruit dans la pièce demande d'allumer la dalle. */
        const val EXTRA_WAKE_ON_SOUND = "extra_wake_on_sound"

        private const val SCREEN_AWAKE_TICK_MS = 1_000L

        /**
         * Délai laissé après la disparition du dernier texte avant de rendre
         * la main à la veille : le temps de finir de lire ce qui vient de
         * s'effacer, et d'éviter qu'un écran s'éteigne pile au moment où on y
         * jetait un œil.
         */
        private const val READING_GRACE_MS = 20_000L

        /**
         * Adresse encodée dans le QR code de l'écran d'accueil. Le paramètre
         * `soignant` fait ouvrir le PWA dans son mode simplifié : connexion
         * immédiate sans décompte ni photo, son de la tablette coupé,
         * sous-titres activés d'office (voir web-caller/app.js).
         */
    }
}
