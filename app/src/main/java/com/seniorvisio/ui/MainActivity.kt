package com.seniorvisio.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import com.google.firebase.firestore.ListenerRegistration
import com.seniorvisio.service.IncomingCallService
import com.seniorvisio.signaling.CallSignalingClient

/**
 * Écran affiché quand aucun appel n'est en cours. Volontairement épuré
 * pour l'usage senior : pas de menu, pas de bouton, juste un message
 * d'accueil (à enrichir selon les retours terrain).
 *
 * Tant que cet écran est affiché, l'app écoute directement Firestore pour
 * détecter un nouvel appel (pas encore de réveil par notification push,
 * voir TODO dans IncomingCallService) : suffisant pour une tablette qui
 * reste allumée sur cet écran en permanence.
 */
class MainActivity : AppCompatActivity() {

    private val signaling = CallSignalingClient()
    private var callListener: ListenerRegistration? = null

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
    }

    override fun onStart() {
        super.onStart()
        if (signaling.isAvailable()) {
            callListener = signaling.listenForRingingCalls { callId, callerName ->
                val intent = Intent(this, IncomingCallService::class.java).apply {
                    putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
                    putExtra(IncomingCallService.EXTRA_CALLER_NAME, callerName)
                }
                startForegroundService(intent)
            }
        }
    }

    override fun onStop() {
        callListener?.remove()
        callListener = null
        super.onStop()
    }
}
