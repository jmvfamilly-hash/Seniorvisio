package com.seniorvisio.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.seniorvisio.service.CallListenerService

/**
 * Écran affiché quand aucun appel n'est en cours. Volontairement épuré
 * pour l'usage senior : pas de menu, pas de bouton, juste un message
 * d'accueil (à enrichir selon les retours terrain).
 *
 * La détection d'appel entrant ne dépend plus du cycle de vie de cet écran :
 * elle tourne en continu dans CallListenerService (démarré ci-dessous),
 * pour fonctionner même écran éteint ou app en arrière-plan.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op : voir startLocalMedia() pour le repli si refusé */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Senior Visio\n(en attente d'appel)"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
        }
        setContentView(tv)

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )

        ContextCompat.startForegroundService(this, Intent(this, CallListenerService::class.java))
        requestIgnoreBatteryOptimizations()
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
}
