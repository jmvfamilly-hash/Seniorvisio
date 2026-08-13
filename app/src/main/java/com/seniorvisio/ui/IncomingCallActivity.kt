package com.seniorvisio.ui

import android.graphics.BitmapFactory
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
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
        val countdownFill = findViewById<View>(R.id.countdownProgressFill)
        buttonBlock = findViewById(R.id.buttonBlock)

        textCallerName.text = "Appel de $callerName"
        showCallerPhoto(intent.getStringExtra(EXTRA_CALLER_PHOTO))

        countdownFill.pivotX = 0f
        countdownFill.scaleX = 0f

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

        val durationSeconds = adminConfig.countdownSeconds
        callEngine.signalAlertStarted(durationSeconds)
        alertController.startCountdown(
            callerName = callerName,
            durationSeconds = durationSeconds,
            onTick = { remaining ->
                // Le chiffre reste discret ; la barre qui se remplit doucement porte
                // l'essentiel de l'information visuelle (évite l'effet de décompte
                // anxiogène d'un gros chiffre qui défile — recommandation ergonomique).
                textCountdown.text = "$remaining s"
                val elapsedFraction = 1f - (remaining.toFloat() / durationSeconds.toFloat())
                countdownFill.animate().scaleX(elapsedFraction).setDuration(950).start()
            },
            onTimeoutConnect = { connectVideoCall() },
            onBlocked = { /* déclenché via le bouton, voir ci-dessus */ }
        )
    }

    /** Photo du proche (capturée sur son navigateur à l'ouverture de l'appel) pour une reconnaissance immédiate. */
    private fun showCallerPhoto(photoBase64: String?) {
        if (photoBase64.isNullOrEmpty()) return
        val imagePhoto = findViewById<ImageView>(R.id.imageCallerPhoto)
        try {
            val bytes = Base64.decode(photoBase64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            imagePhoto.setImageBitmap(bitmap)
            imagePhoto.clipToOutline = true
            imagePhoto.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            imagePhoto.visibility = View.VISIBLE
        } catch (_: IllegalArgumentException) {
            // Photo corrompue/mal encodée : on garde simplement le nom, pas bloquant.
        }
    }

    private fun connectVideoCall() {
        isConnected = true
        findViewById<View>(R.id.alertContent).visibility = View.GONE
        val localRenderer = findViewById<SurfaceViewRenderer>(R.id.localRenderer)
        val remoteRenderer = findViewById<SurfaceViewRenderer>(R.id.remoteRenderer)
        localRenderer.visibility = View.VISIBLE
        remoteRenderer.visibility = View.VISIBLE
        callEngine.attachRenderers(localRenderer, remoteRenderer)
        callEngine.answer()
        buttonBlock.text = "Raccrocher"
        setupCaptionMode()
        callEngine.listenForRemoteVolumeControl()
    }

    /**
     * Sous-titres en surimpression façon sous-titrage TV (recommandation
     * ergonomique : remplace l'ancien mode 80%/20% en écran divisé) — la
     * vidéo reste plein écran, le texte apparaît dans un bandeau semi-opaque
     * en bas. Activé/désactivé à distance par le proche depuis le PWA, avec
     * une transition en fondu pour éviter tout changement brutal côté Jean.
     */
    private fun setupCaptionMode() {
        val captionBanner = findViewById<View>(R.id.captionBanner)
        val textCaption = findViewById<TextView>(R.id.textCaption)

        // L'écoute Firestore porte sur tout le document d'appel : elle se
        // redéclenche à chaque nouveau texte transcrit (toutes les ~500ms),
        // pas seulement quand l'activation change. Sans ce garde-fou, le
        // fondu d'apparition repartirait de zéro à chaque sous-titre reçu,
        // donnant un clignotement au lieu d'une simple mise à jour du texte.
        var captionsCurrentlyEnabled: Boolean? = null
        callEngine.listenForCaptionMode { enabled ->
            runOnUiThread {
                if (captionsCurrentlyEnabled == enabled) return@runOnUiThread
                captionsCurrentlyEnabled = enabled
                if (enabled) {
                    captionBanner.visibility = View.VISIBLE
                    captionBanner.animate().alpha(1f).setDuration(400).start()
                } else {
                    captionBanner.animate().alpha(0f).setDuration(400)
                        .withEndAction { captionBanner.visibility = View.GONE }
                        .start()
                }
            }
        }

        callEngine.listenForCaptions { text ->
            runOnUiThread { textCaption.text = text }
        }

        // Même garde-fou que ci-dessus : ce listener se redéclenche aussi à
        // chaque nouveau texte transcrit, pas seulement quand la taille change.
        var currentTextSizeSp: Float? = null
        callEngine.listenForCaptionTextSize { sizeSp ->
            runOnUiThread {
                if (currentTextSizeSp == sizeSp) return@runOnUiThread
                currentTextSizeSp = sizeSp
                textCaption.animate().alpha(0f).setDuration(150).withEndAction {
                    textCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                    textCaption.animate().alpha(1f).setDuration(150).start()
                }.start()
            }
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

    override fun onDestroy() {
        alertController.cancel()
        if (!callHandled) {
            callHandled = true
            callEngine.hangUp()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_PHOTO = "extra_caller_photo"
    }
}
