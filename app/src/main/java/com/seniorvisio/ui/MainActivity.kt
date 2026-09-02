package com.seniorvisio.ui

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.text.InputType
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
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
import com.seniorvisio.core.CompanionApps
import com.seniorvisio.core.KioskManager
import com.seniorvisio.service.CallListenerService
import com.seniorvisio.signaling.CallSignalingClient

/**
 * Écran affiché quand aucun appel n'est en cours. Volontairement épuré pour
 * l'usage senior : un message d'accueil et un unique bouton, qui bascule vers
 * la transcription des conversations de la pièce — rien d'autre à comprendre.
 *
 * La transcription elle-même n'est plus assurée par cette application (voir
 * CompanionApps) : Jean part dans celle de Google et revient ici par le
 * bouton Accueil, Senior Visio étant le lanceur de la tablette (voir
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textBuildRev = findViewById<TextView>(R.id.textBuildRev)
        textBuildRev.text = BuildConfig.BUILD_REV
        // Point d'entrée discret vers les réglages admin (Wi-Fi, PIN, durée
        // du décompte) : une fois en mode kiosque, plus aucun autre moyen d'y
        // accéder (Réglages système bloqués), voir KioskManager.
        textBuildRev.setOnLongClickListener { promptAdminPin(); true }

        val buttonRoomCaptions = findViewById<Button>(R.id.buttonRoomCaptions)
        buttonRoomCaptions.setOnClickListener { launchRoomTranscription() }
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
    }

    /**
     * Bascule vers "Transcription instantanée" (Google), autorisée en mode
     * kiosque par CompanionApps. Jean en revient par le bouton Accueil, qui
     * ramène ici — c'est le seul chemin de retour, et il est fiable puisque
     * cette activité est l'écran d'accueil de la tablette.
     *
     * Le message d'erreur nomme l'application plutôt que le paquet : c'est un
     * intervenant sur place qui le lira, pas Jean.
     */
    private fun launchRoomTranscription() {
        val launchIntent = resolveTranscriptionIntent()
        if (launchIntent == null) {
            Log.w(TAG, "Aucun écran lançable trouvé pour ${CompanionApps.TRANSCRIPTION}")
            Toast.makeText(
                this,
                "Impossible d'ouvrir Transcription instantanée sur cette tablette",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            startActivity(launchIntent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Lancement de la transcription refusé", e)
            Toast.makeText(this, "Impossible d'ouvrir Transcription instantanée", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Cherche par quel écran ouvrir l'application de transcription.
     *
     * Le chemin normal (getLaunchIntentForPackage) suppose une activité
     * déclarée en CATEGORY_LAUNCHER. Certaines applications d'accessibilité
     * n'en ont pas — elles sont prévues pour être ouvertes depuis les
     * Réglages ou le raccourci d'accessibilité — d'où le repli sur n'importe
     * quelle activité ACTION_MAIN exportée du paquet.
     *
     * Les deux chemins échouent tant que le paquet n'est pas déclaré dans la
     * section <queries> du manifeste : Android répond alors comme s'il
     * n'était pas installé.
     */
    private fun resolveTranscriptionIntent(): Intent? {
        packageManager.getLaunchIntentForPackage(CompanionApps.TRANSCRIPTION)?.let { return it }

        val mainIntent = Intent(Intent.ACTION_MAIN).setPackage(CompanionApps.TRANSCRIPTION)
        val activity = packageManager.queryIntentActivities(mainIntent, 0).firstOrNull() ?: return null
        return Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(activity.activityInfo.packageName, activity.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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
    }
}
