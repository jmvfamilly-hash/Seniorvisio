package com.seniorvisio.ui

import android.animation.ObjectAnimator
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.media.RingtoneManager
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
import android.widget.ScrollView
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
        val countdownFill = findViewById<View>(R.id.countdownProgressFill)
        buttonBlock = findViewById(R.id.buttonBlock)

        textCallerName.text = "On vous appelle"
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

        var forceConnectHandled = false
        callEngine.listenForForceConnect {
            runOnUiThread {
                if (forceConnectHandled || isConnected) return@runOnUiThread
                forceConnectHandled = true
                alertController.cancel()
                connectVideoCall()
            }
        }

        // Sans ça, un raccroché côté PWA (pendant l'attente ou une fois
        // connecté) n'était jamais détecté ici : la tablette restait bloquée
        // en communication. onDestroy() se charge du nettoyage (caméra/micro/
        // WebRTC) exactement comme pour le bouton "Bloquer"/"Raccrocher".
        callEngine.listenForRemoteHangup {
            runOnUiThread {
                if (!callHandled) finish()
            }
        }

        val durationSeconds = adminConfig.countdownSeconds
        callEngine.signalAlertStarted(durationSeconds)
        playDiscreetAlertSound()
        alertController.startCountdown(
            callerName = callerName,
            durationSeconds = durationSeconds,
            onTick = { remaining ->
                // Seule la barre qui se remplit doucement porte l'information visuelle
                // (pas de chiffre affiché : évite l'effet de décompte anxiogène d'un
                // gros chiffre qui défile — recommandation ergonomique).
                val elapsedFraction = 1f - (remaining.toFloat() / durationSeconds.toFloat())
                countdownFill.animate().scaleX(elapsedFraction).setDuration(950).start()
            },
            onTimeoutConnect = { connectVideoCall() },
            onBlocked = { /* déclenché via le bouton, voir ci-dessus */ }
        )
    }

    /** Petit son discret au tout début du décompte, pour signaler l'appel sans réveiller toute la maison. */
    private fun playDiscreetAlertSound() {
        try {
            val soundUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, soundUri)?.play()
        } catch (_: Exception) {
            // Pas de son système configuré : pas bloquant, le décompte visuel suffit.
        }
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
        val captionScroll = findViewById<ScrollView>(R.id.captionScroll)
        val textCaption = findViewById<TextView>(R.id.textCaption)
        // Le défilement est piloté par le code (voir plus bas), pas par Jean.
        captionScroll.setOnTouchListener { _, _ -> true }

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

        // Plus aucun texte n'est perdu : si la phrase dépasse l'espace visible,
        // on défile automatiquement (plutôt que de tronquer avec des "…"), et
        // on signale le débordement au proche pour qu'il puisse temporiser
        // (voir WebRtcCallEngine.signalCaptionOverflow / web-caller/app.js).
        //
        // Le défilement suit la parole en continu, façon sous-titrage TV en
        // direct ("roll-up", CEA-608) : tant que le texte reçu prolonge celui
        // d'avant (la personne continue de parler dans la même phrase, un mot
        // de plus toutes les ~500ms), on avance d'un cran sans revenir en
        // haut. Repartir de zéro à chaque mise à jour (comme avant) rendait
        // le défilement inutilisable en parole continue : l'animation n'avait
        // jamais le temps d'aller au bout avant d'être relancée depuis le
        // début. On ne revient en haut que lorsqu'une phrase réellement
        // nouvelle démarre (le texte ne prolonge plus le précédent).
        var scrollAnimator: ObjectAnimator? = null
        var lastOverflowSignaled: Boolean? = null
        var lastCaptionText = ""
        callEngine.listenForCaptions { text ->
            runOnUiThread {
                val isContinuation = lastCaptionText.isNotEmpty() && text.startsWith(lastCaptionText)
                lastCaptionText = text
                textCaption.text = text
                callEngine.signalDisplayedCaption(text)
                textCaption.post {
                    scrollAnimator?.cancel()
                    if (!isContinuation) {
                        captionScroll.scrollTo(0, 0)
                    }
                    val overflow = textCaption.height > captionScroll.height
                    val maxScroll = (textCaption.height - captionScroll.height).coerceAtLeast(0)
                    if (overflow) {
                        scrollAnimator = ObjectAnimator.ofInt(captionScroll, "scrollY", captionScroll.scrollY, maxScroll).apply {
                            duration = 280L
                            start()
                        }
                    }
                    if (lastOverflowSignaled != overflow) {
                        lastOverflowSignaled = overflow
                        callEngine.signalCaptionOverflow(overflow)
                    }
                }
            }
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

    /**
     * Ne raccroche que si cet écran se termine réellement (bouton "Bloquer"/
     * "Raccrocher", ou l'appel se termine côté proche). Un changement de
     * configuration (rotation, redimensionnement multi-fenêtre) détruit puis
     * recrée l'Activity par défaut sans que ce soit une vraie fin d'appel —
     * voir aussi android:configChanges sur cette Activity dans le manifest,
     * qui évite déjà cette destruction pour les cas courants (rotation...) ;
     * ce garde-fou couvre les cas non listés là-bas.
     */
    override fun onDestroy() {
        alertController.cancel()
        if (!callHandled && !isChangingConfigurations) {
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
