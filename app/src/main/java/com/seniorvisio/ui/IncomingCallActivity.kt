package com.seniorvisio.ui

import android.app.NotificationManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import com.seniorvisio.BuildConfig
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.KioskManager
import com.seniorvisio.core.WebRtcCallEngine
import com.seniorvisio.service.IncomingCallService
import com.seniorvisio.service.TimedCallAlertController
import org.webrtc.SurfaceViewRenderer
import kotlin.math.roundToInt

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

    // Références gardées pour adapter la disposition à chaque rotation (voir
    // onConfigurationChanged / applyOrientationLayout) sans jamais recréer
    // l'Activity ni rattacher les renderers WebRTC — l'appel en cours n'est
    // jamais interrompu par une rotation.
    private var remoteRendererRef: SurfaceViewRenderer? = null
    private var localRendererRef: SurfaceViewRenderer? = null
    private var captionBannerRef: View? = null
    private var captionScrollRef: ScrollView? = null

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
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // setTurnScreenOn ne fait que réveiller l'écran une fois : sans ce
        // flag séparé (qui, lui, s'applique sur toutes les versions), rien
        // n'empêche l'écran de s'éteindre pendant le décompte ou l'appel une
        // fois le délai de veille système écoulé — retiré explicitement dans
        // onDestroy dès que l'écran d'appel se termine (voir plus bas), pour
        // ne pas garder l'écran forcé allumé hors fenêtre d'appel.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Repère de latence réveil (voir CONSIGNES_veille_reveil_appel.md,
        // section 5) : mesure le délai entre la réception du signal d'appel
        // côté service et l'affichage effectif de cet écran, pour pouvoir
        // diagnostiquer une régression après une future mise à jour Android.
        val signalReceivedAtMs = intent.getLongExtra(EXTRA_SIGNAL_RECEIVED_AT, 0L)
        if (signalReceivedAtMs > 0) {
            val wakeLatencyMs = System.currentTimeMillis() - signalReceivedAtMs
            Log.i(TAG, "Réveil écran d'appel : ${wakeLatencyMs}ms depuis réception du signal")
        }

        // La notification plein écran qui a potentiellement déclenché cet
        // écran (voir IncomingCallService.launchAlertScreen) n'a plus lieu
        // d'être une fois l'écran effectivement affiché.
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(IncomingCallService.CALL_NOTIFICATION_ID)

        setContentView(R.layout.activity_incoming_call)
        findViewById<TextView>(R.id.textBuildRev).text = BuildConfig.BUILD_REV
        KioskManager.startIfDeviceOwner(this)

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
        showCallerPhoto(intent.getStringExtra(EXTRA_CALLER_PHOTO_PATH))

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
            onError = { error ->
                // La cause réelle (offre introuvable, échec WebRTC...) était
                // jusqu'ici entièrement ignorée : ni journalisée, ni remontée
                // nulle part — impossible de savoir pourquoi un appel raccrochait
                // aussitôt sans brancher la tablette. Remontée maintenant dans le
                // document Firestore de l'appel (visible depuis la console, sans
                // accès physique) et relayée au proche côté PWA.
                Log.e(TAG, "Échec de préparation de l'appel entrant", error)
                callEngine.reportPreparationError(error.message ?: error.javaClass.simpleName)
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

    /**
     * Avec singleTask (voir AndroidManifest), un second déclenchement pour le
     * même appel (notification plein écran + startActivity explicite, voir
     * IncomingCallService.launchAlertScreen) est désormais livré ici plutôt
     * que de créer une seconde instance concurrente avec son propre moteur
     * WebRTC — cause du raccroché immédiat observé en test réel. Rien à faire
     * de plus : l'instance déjà affichée continue normalement son décompte ou
     * son appel en cours.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.i(TAG, "Second déclenchement ignoré pour un appel déjà affiché")
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

    /**
     * Photo du proche affichée en plein écran pendant la sonnerie, derrière le
     * nom et le décompte : c'est ce qui permet à Jean de reconnaître qui
     * l'appelle avant même de lire. Remplace la vignette ronde de 160dp,
     * minuscule sur une dalle de dix pouces.
     *
     * Photo choisie à l'avance par le proche depuis le PWA quand il en a
     * déposé une (voir app.js, panneau "Qui appelle ?"), sinon capture webcam
     * prise à l'ouverture de l'appel — d'où le voile, qui garantit la
     * lisibilité du texte quelle que soit la luminosité de l'image reçue.
     *
     * Décodée depuis un fichier (voir CallerPhotoCache), jamais depuis les
     * octets transportés directement dans l'Intent : au-delà d'une certaine
     * taille, ça faisait planter le service qui affiche cet écran avant même
     * qu'il n'apparaisse (TransactionTooLargeException), sans que la
     * tablette ne sonne jamais.
     */
    private fun showCallerPhoto(photoPath: String?) {
        if (photoPath.isNullOrEmpty()) return
        val imagePhoto = findViewById<ImageView>(R.id.imageCallerPhoto)
        val scrim = findViewById<View>(R.id.callerPhotoScrim)
        val bitmap = BitmapFactory.decodeFile(photoPath) ?: return
        imagePhoto.setImageBitmap(bitmap)
        imagePhoto.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
    }

    private fun connectVideoCall() {
        isConnected = true
        findViewById<View>(R.id.alertContent).visibility = View.GONE
        // La photo et son voile sont des calques plein écran, frères de
        // alertContent et non ses enfants : sans ça ils resteraient affichés
        // par-dessus la vidéo une fois l'appel connecté.
        findViewById<View>(R.id.imageCallerPhoto).visibility = View.GONE
        findViewById<View>(R.id.callerPhotoScrim).visibility = View.GONE
        val localRenderer = findViewById<SurfaceViewRenderer>(R.id.localRenderer)
        val remoteRenderer = findViewById<SurfaceViewRenderer>(R.id.remoteRenderer)
        // Miniature de Jean masquée par défaut (retirée de l'écran) : ne
        // s'affiche que si le proche l'active à distance depuis le PWA, voir
        // listenForSelfPreviewMode ci-dessous. INVISIBLE plutôt que GONE :
        // un SurfaceViewRenderer en GONE (taille nulle, jamais posé à
        // l'écran) ne crée jamais sa surface, ce qui perturbait aussi le
        // rendu de la vidéo du proche (écran noir constaté en test réel) —
        // les deux renderers partagent le même contexte EGL (voir
        // attachRenderers). INVISIBLE garde la vue mise en page normalement
        // (donc sa surface bien créée), juste non dessinée à l'écran.
        localRenderer.visibility = View.INVISIBLE
        remoteRenderer.visibility = View.VISIBLE
        callEngine.attachRenderers(localRenderer, remoteRenderer)
        callEngine.answer()
        buttonBlock.text = "Raccrocher"
        remoteRendererRef = remoteRenderer
        localRendererRef = localRenderer
        setupCaptionMode()
        callEngine.listenForRemoteVolumeControl()
        callEngine.listenForSelfPreviewMode { enabled ->
            runOnUiThread { localRenderer.visibility = if (enabled) View.VISIBLE else View.INVISIBLE }
        }
        // Ferme proprement l'écran si la connexion se perd sans qu'un
        // raccroché explicite n'ait été envoyé (Wi-Fi coupé, navigateur du
        // proche qui plante...) : sans ça, la caméra/le micro restaient
        // engagés indéfiniment côté tablette (voir WebRtcCallEngine.
        // onConnectionLost et le commentaire dans cleanup()).
        callEngine.onConnectionLost {
            runOnUiThread { if (!callHandled) finish() }
        }
        // Applique tout de suite la disposition correspondant à l'orientation
        // actuelle (la tablette peut déjà être en paysage au moment où
        // l'appel se connecte, pas seulement lors d'une rotation ultérieure).
        applyOrientationLayout(resources.configuration.orientation)
    }

    /**
     * Adapte la disposition de l'écran d'appel à l'orientation, sans jamais
     * recréer les vues (voir remoteRendererRef/captionBannerRef, remplies
     * dans connectVideoCall/setupCaptionMode) : seuls leurs LayoutParams
     * changent, donc le flux vidéo et le défilement des sous-titres ne sont
     * jamais interrompus par une rotation.
     *
     * La première itération du mode paysage (vidéo à droite, sous-titres en
     * colonne à gauche) s'est révélée plus perturbante à l'usage que le
     * bandeau en bas utilisé en portrait — la vidéo reste donc plein écran
     * et le bandeau de sous-titres en surimpression basse dans les deux
     * orientations. En paysage, le bandeau est aussi élargi au maximum
     * (marges latérales réduites) et sa hauteur totale (marge basse +
     * rembourrage interne + zone de texte, voir activity_incoming_call.xml)
     * est plafonnée à la moitié basse de l'écran, calculée dynamiquement à
     * partir de la résolution réelle plutôt qu'une valeur fixe — pour rester
     * valable quelle que soit la tablette utilisée.
     *
     * En portrait, le bouton Bloquer/Raccrocher reste tout en bas (loin du
     * bandeau, qui garde une grande marge basse). En paysage, où le bandeau
     * de sous-titres occupe toute la largeur près du bord bas, le bouton
     * passe en haut à droite, par-dessus la vidéo du proche plutôt que sur
     * le texte — la miniature de Jean (localRenderer, elle aussi en haut à
     * droite par défaut) descend d'autant pour ne pas être recouverte par le
     * bouton.
     */
    private fun applyOrientationLayout(orientation: Int) {
        val remoteRenderer = remoteRendererRef ?: return
        val captionBanner = captionBannerRef ?: return
        val captionScroll = captionScrollRef ?: return
        val localRenderer = localRendererRef ?: return
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val sideMargin = ((if (isLandscape) 12 else 24) * density).roundToInt()
        val margin16 = (16 * density).roundToInt()
        // Rembourrage haut+bas du bandeau, fixé dans le layout XML (android:padding="20dp").
        val bannerVerticalPadding = (20 * density * 2).roundToInt()

        val bottomMargin: Int
        val captionScrollHeight: Int
        if (isLandscape) {
            bottomMargin = (16 * density).roundToInt()
            val maxBannerAreaPx = resources.displayMetrics.heightPixels / 2
            captionScrollHeight = (maxBannerAreaPx - bottomMargin - bannerVerticalPadding)
                .coerceAtLeast((80 * density).roundToInt())
        } else {
            bottomMargin = (140 * density).roundToInt()
            captionScrollHeight = (220 * density).roundToInt()
        }

        (remoteRenderer.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
            gravity = Gravity.NO_GRAVITY
            marginStart = 0
            remoteRenderer.layoutParams = this
        }

        (captionBanner.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM
            marginStart = sideMargin; marginEnd = sideMargin; topMargin = 0; this.bottomMargin = bottomMargin
            captionBanner.layoutParams = this
        }

        (captionScroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = captionScrollHeight
            captionScroll.layoutParams = this
        }

        (buttonBlock.layoutParams as FrameLayout.LayoutParams).apply {
            if (isLandscape) {
                gravity = Gravity.TOP or Gravity.END
                topMargin = margin16; this.bottomMargin = 0
            } else {
                gravity = Gravity.BOTTOM or Gravity.END
                topMargin = 0; this.bottomMargin = (24 * density).roundToInt()
            }
            marginEnd = if (isLandscape) margin16 else sideMargin
            buttonBlock.layoutParams = this
        }

        (localRenderer.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.END
            marginStart = margin16; marginEnd = margin16; this.bottomMargin = 0
            // Décalée sous le bouton en paysage pour ne pas être recouverte.
            topMargin = if (isLandscape) margin16 + (74 * density).roundToInt() else margin16
            localRenderer.layoutParams = this
        }
    }

    /**
     * Rotation de la tablette pendant l'appel : configChanges (voir
     * AndroidManifest) empêche déjà la destruction de l'Activity, il ne
     * reste qu'à réadapter la disposition aux nouvelles dimensions.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isConnected) applyOrientationLayout(newConfig.orientation)
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
        captionBannerRef = captionBanner
        captionScrollRef = captionScroll
        // Le défilement est piloté par le code (voir plus bas), pas par Jean.
        captionScroll.setOnTouchListener { _, _ -> true }

        // Plus aucun texte n'est perdu : si la phrase dépasse l'espace visible,
        // on défile automatiquement (plutôt que de tronquer avec des "…"), et
        // on signale le retard de lecture au proche pour qu'il puisse temporiser
        // (voir WebRtcCallEngine.signalCaptionCatchUpLag / web-caller/app.js).
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
        //
        // Suivi continu par interpolation image par image (voir
        // CaptionScrollAnimator, logique commune aux sous-titres d'appel et
        // de la pièce), validé dans le labo de défilement
        // (experiment/caption-scroll) sur un enregistrement vocal réel —
        // 60 im/s en moyenne, quasi aucune image saccadée. Le pas par frame
        // était initialement proportionnel à la distance restante (facteur
        // de rattrapage 0,35) : plus le proche parlait vite (donc plus de
        // texte en attente), plus le défilement accélérait — l'inverse de ce
        // qu'il fallait pour laisser le temps à Jean de lire, constaté lors
        // des tests réels. Le pas est maintenant plafonné à une vitesse
        // constante et paramétrable (voir listenForCaptionScrollSpeed,
        // réglée à distance par le proche) : le texte reçu peut s'accumuler
        // en attente le temps que le défilement rattrape, plutôt que de
        // défiler plus vite. Le retard de lecture qui en résulte est signalé
        // en continu au proche (voir signalCaptionCatchUpLag) pour qu'il
        // sache précisément où en est Jean, plutôt qu'un simple indicateur
        // "ça déborde" ou non.
        var lastCaptionText = ""
        var lastLagSentAtMs = 0L
        // Valeur de départ raisonnable en l'absence de mesure labo pour ce
        // réglage (contrairement au lissage ci-dessus) — ajustée en direct
        // par le proche via le curseur du PWA.
        var maxScrollSpeedPxPerSec = 50f * resources.displayMetrics.density

        val scrollAnimator = CaptionScrollAnimator(
            scrollView = captionScroll,
            maxSpeedPxPerSec = { maxScrollSpeedPxPerSec },
            onFrame = { remainingPx ->
                val lagSeconds = if (maxScrollSpeedPxPerSec > 0f) remainingPx / maxScrollSpeedPxPerSec else 0f
                val now = System.currentTimeMillis()
                if (now - lastLagSentAtMs > 300) {
                    lastLagSentAtMs = now
                    callEngine.signalCaptionCatchUpLag(lagSeconds)
                }
            },
        )

        callEngine.listenForCaptionScrollSpeed { dpPerSec ->
            maxScrollSpeedPxPerSec = dpPerSec * resources.displayMetrics.density
        }

        // Texte relayé tel quel par le proche (reconnaissance vocale de son
        // propre navigateur, Web Speech API — voir web-caller/webrtc-engine.js
        // et WebRtcCallEngine.listenForCaptions) : ne fonctionne que sur les
        // navigateurs qui la supportent (Chrome desktop essentiellement, pas
        // Safari/iOS), limitation acceptée pour rester gratuit et sans
        // dépendance à un service tiers.
        callEngine.listenForCaptions { text ->
            runOnUiThread {
                val isContinuation = lastCaptionText.isNotEmpty() && text.startsWith(lastCaptionText)
                lastCaptionText = text
                textCaption.text = text
                textCaption.post {
                    if (!isContinuation) {
                        scrollAnimator.jumpTo(0)
                        callEngine.signalCaptionCatchUpLag(0f)
                    }
                    val overflow = textCaption.height > captionScroll.height
                    val maxScroll = (textCaption.height - captionScroll.height).coerceAtLeast(0)
                    if (overflow) {
                        scrollAnimator.scrollTo(maxScroll)
                    }
                }
            }
        }

        // Ce listener écoute tout le document d'appel Firestore, donc il se
        // redéclenche à chaque écriture (volume, etc.), pas seulement quand
        // l'activation change. Sans ce garde-fou, le fondu d'apparition
        // repartirait de zéro à chaque écriture, donnant un clignotement.
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
        // Ne garde jamais l'écran forcé allumé hors de la fenêtre d'appel
        // (voir le flag posé dans onCreate) — usage 24/7, risque batterie/
        // chauffe/marquage d'écran sinon.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        alertController.cancel()
        if (!callHandled && !isChangingConfigurations) {
            callHandled = true
            callEngine.hangUp()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IncomingCallActivity"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_PHOTO_PATH = "extra_caller_photo_path"
        const val EXTRA_SIGNAL_RECEIVED_AT = "extra_signal_received_at"
    }
}
