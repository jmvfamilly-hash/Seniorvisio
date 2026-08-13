package com.seniorvisio.ui

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.WebRtcCallEngine
import com.seniorvisio.service.TimedCallAlertController
import org.webrtc.SurfaceViewRenderer

/**
 * Écran plein format affiché à chaque appel entrant : décompte visible
 * de `AdminConfig.countdownSeconds` (30s par défaut), avec un bouton
 * "Bloquer l'appel" que Jean peut presser à tout moment. Si le délai
 * s'écoule sans action, la connexion vidéo démarre automatiquement.
 */
class IncomingCallActivity : AppCompatActivity() {

    private val alertController = TimedCallAlertController()
    private lateinit var adminConfig: AdminConfig
    private lateinit var callEngine: WebRtcCallEngine
    private lateinit var buttonBlock: Button
    private var isConnected = false
    private var callHandled = false
    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adminConfig = AdminConfig(this)
        callEngine = WebRtcCallEngine(applicationContext)

        // Réveille l'écran et l'affiche même si verrouillé, sans son.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_incoming_call)

        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        if (callId == null) {
            finish()
            return
        }

        val callerName = intent.getStringExtra("callerName") ?: "un proche"
        val textCallerName = findViewById<TextView>(R.id.textCallerName)
        val textCountdown = findViewById<TextView>(R.id.textCountdown)
        buttonBlock = findViewById(R.id.buttonBlock)

        textCallerName.text = "Appel de $callerName"

        buttonBlock.setOnClickListener {
            alertController.cancel()
            callHandled = true
            if (isConnected) {
                callEngine.hangUp()
            } else {
                callEngine.blockCall()
            }
            finish()
        }

        callEngine.prepareIncomingCall(
            callId = callId,
            onReady = { /* offre reçue, prête à être acceptée à la fin du décompte */ },
            onError = {
                runOnUiThread {
                    Toast.makeText(this, "Appel indisponible", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        )

        callEngine.signalAlertStarted(adminConfig.countdownSeconds)
        alertController.startCountdown(
            callerName = callerName,
            durationSeconds = adminConfig.countdownSeconds,
            onTick = { remaining -> textCountdown.text = "$remaining s" },
            onTimeoutConnect = { connectVideoCall() },
            onBlocked = { /* déclenché via le bouton, voir ci-dessus */ }
        )
    }

    private fun connectVideoCall() {
        isConnected = true
        findViewById<View>(R.id.alertContent).visibility = View.GONE
        val localRenderer = findViewById<SurfaceViewRenderer>(R.id.localRenderer)
        val remoteRenderer = findViewById<SurfaceViewRenderer>(R.id.remoteRenderer)
        val captionRemoteRenderer = findViewById<SurfaceViewRenderer>(R.id.captionRemoteRenderer)
        localRenderer.visibility = View.VISIBLE
        remoteRenderer.visibility = View.VISIBLE
        callEngine.attachRenderers(localRenderer, remoteRenderer, captionRemoteRenderer)
        callEngine.answer()
        buttonBlock.text = "Raccrocher"
        startStatsPolling()
        setupCaptionMode()
        callEngine.listenForRemoteVolumeControl()
    }

    /**
     * Mode "sous-titres géants" : les paroles de l'appelant, transcrites en
     * direct côté navigateur (voir web-caller/webrtc-engine.js), s'affichent
     * en très gros sur 80% de l'écran, avec sa vidéo réduite dans les 20%
     * restants. Activé/désactivé à distance par le proche depuis le PWA
     * (pas de bouton local sur la tablette) — voir listenForCaptionMode.
     * Fonctionne uniquement si le proche appelle depuis un navigateur
     * supportant la reconnaissance vocale (Chrome ; pas Safari).
     */
    private fun setupCaptionMode() {
        val captionContent = findViewById<View>(R.id.captionContent)
        val textCaption = findViewById<TextView>(R.id.textCaption)
        val remoteRenderer = findViewById<SurfaceViewRenderer>(R.id.remoteRenderer)
        val localRenderer = findViewById<SurfaceViewRenderer>(R.id.localRenderer)

        callEngine.listenForCaptionMode { enabled ->
            runOnUiThread {
                captionContent.visibility = if (enabled) View.VISIBLE else View.GONE
                remoteRenderer.visibility = if (enabled) View.GONE else View.VISIBLE
                localRenderer.visibility = if (enabled) View.GONE else View.VISIBLE
            }
        }

        callEngine.listenForCaptions { text ->
            runOnUiThread { textCaption.text = text }
        }

        callEngine.listenForCaptionTextSize { sizeSp ->
            runOnUiThread { textCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp) }
        }
    }

    /**
     * Bloque les boutons physiques de volume pendant l'appel : sans ça, Jean
     * peut couper le son que le proche a réglé à distance (le volume système
     * multiplie en dernier le gain envoyé par le curseur du PWA, voir
     * WebRtcCallEngine.configureAudioForCall). Seul le curseur du proche doit
     * faire foi tant que l'appel est connecté.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isConnected && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            callEngine.pinSystemVolumeToMax()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Rafraîchit toutes les 2s les métriques réelles (résolution/fps vidéo). */
    private fun startStatsPolling() {
        val textStats = findViewById<TextView>(R.id.textStats)
        textStats.visibility = View.VISIBLE
        val runnable = object : Runnable {
            override fun run() {
                callEngine.fetchStatsSummary { summary ->
                    runOnUiThread { textStats.text = summary }
                }
                statsHandler.postDelayed(this, 2000)
            }
        }
        statsRunnable = runnable
        statsHandler.post(runnable)
    }

    private fun stopStatsPolling() {
        statsRunnable?.let { statsHandler.removeCallbacks(it) }
        statsRunnable = null
    }

    override fun onDestroy() {
        alertController.cancel()
        stopStatsPolling()
        if (!callHandled) {
            callHandled = true
            callEngine.hangUp()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
    }
}
