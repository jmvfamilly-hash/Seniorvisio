package com.seniorvisio.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import com.seniorvisio.BuildConfig
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.KioskManager
import com.seniorvisio.core.ScreenTheme
import com.seniorvisio.core.TranscriptionSource
import com.seniorvisio.core.WebRtcCallEngine
import com.seniorvisio.service.IncomingCallService
import com.seniorvisio.service.RoomPresenceService
import com.seniorvisio.service.TimedCallAlertController
import org.webrtc.SurfaceViewRenderer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Écran plein format affiché à chaque appel entrant : décompte visible
 * de `AdminConfig.countdownSeconds` (30s par défaut), avec un bouton
 * "Bloquer l'appel" que Jean peut presser à tout moment. Si le délai
 * s'écoule sans action, la connexion vidéo démarre automatiquement.
 *
 * Une fois connecté, ce n'est plus vraiment un autre écran que l'accueil du
 * point de vue de Jean : les mêmes trois zones restent en place (voir
 * HomeZonesController), seul le fond change — la vidéo du proche remplace le
 * fond uni, puis ses photos s'il lance un diaporama. Le texte de l'appel
 * s'affiche dans la zone 3, avec les mêmes règles de défilement que celui de
 * la pièce (voir RollingCaptionZone).
 *
 * L'appelant peut à tout moment demander que la transcription écoute la pièce
 * plutôt que sa propre voix (voir setupCaptionMode, listenForMicToRoom) : le
 * texte passe alors en zone 2, la zone 3 s'efface faute de source, et les deux
 * gardent leur place — Jean retrouve toujours chaque chose au même endroit. Le
 * son, lui, continue de circuler dans les deux sens : l'appelant peut parler
 * avec la personne présente auprès de Jean pendant ce temps.
 */
class IncomingCallActivity : AppCompatActivity() {

    private val alertController = TimedCallAlertController()
    private lateinit var adminConfig: AdminConfig
    private lateinit var callEngine: WebRtcCallEngine
    private lateinit var zones: HomeZonesController
    private lateinit var buttonBlock: Button
    private var isConnected = false
    private var callHandled = false

    /** Vrai dès que l'offre WebRTC du proche est reçue et acceptée (voir prepareIncomingCall). */
    private var isPrepared = false

    /** Connexion immédiate demandée avant que l'offre ne soit prête (mode soignant). */
    private var pendingForceConnect = false

    // Références gardées pour adapter la disposition à chaque rotation (voir
    // onConfigurationChanged / applyOrientationLayout) sans jamais recréer
    // l'Activity ni rattacher les renderers WebRTC — l'appel en cours n'est
    // jamais interrompu par une rotation.
    private var remoteRendererRef: SurfaceViewRenderer? = null
    private var localRendererRef: SurfaceViewRenderer? = null

    // Dernier état publié au PWA (voir publishScreenState) : sert à n'écrire
    // que lorsque quelque chose a réellement changé.
    private var lastPublishedCallText: String? = null
    private var lastPublishedRoomText: String? = null
    private var lastPublishedLagSeconds = -1f

    // Description de l'écran pour la réplique côté PWA (voir
    // publishScreenLayout). Conservée ici parce qu'elle se construit en deux
    // temps — la palette et la zone d'information n'arrivent pas ensemble.
    private var screenIsDark = true
    private var lastInfo: HomeZonesController.InfoSnapshot? = null

    private var roomService: RoomPresenceService? = null

    /**
     * Pendant la sonnerie, le microphone appartient encore au service d'écoute
     * de la pièce : la zone 2 continue donc de fonctionner exactement comme sur
     * l'écran d'accueil, et Jean voit ce qui se dit autour de lui pendant que
     * la tablette sonne. Le micro ne change de main qu'au décrochage (voir
     * connectVideoCall), où c'est WebRTC qui le réclame.
     */
    private val roomConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (isConnected) return
            val service = (binder as? RoomPresenceService.LocalBinder)?.getService() ?: return
            roomService = service
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

    private var boundToRoomService = false

    /** Rend le micro au moment où WebRTC en a besoin, ou à la fin de l'écran. */
    private fun stopRoomTranscription() {
        roomService?.stopRoomTranscription()
        roomService = null
        if (!boundToRoomService) return
        boundToRoomService = false
        unbindService(roomConnection)
    }

    private val screenStateHandler = Handler(Looper.getMainLooper())
    private val screenStatePublisher = object : Runnable {
        override fun run() {
            publishScreenStateIfChanged()
            screenStateHandler.postDelayed(this, SCREEN_STATE_PUBLISH_MS)
        }
    }

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

        // Les trois zones sont en place dès la sonnerie, pas seulement une
        // fois connecté : la date et la météo n'ont pas de raison de
        // disparaître parce que le téléphone sonne.
        zones = HomeZonesController(
            root = findViewById(R.id.callRoot),
            onPalette = { palette ->
                // Le fond n'est visible que tant que ni photo d'appelant ni
                // vidéo ne le recouvrent — d'où une palette qui sert surtout
                // aux premières secondes de la sonnerie.
                findViewById<View>(R.id.callRoot).setBackgroundColor(palette.background)
                applyPaletteToAlert(palette)
                screenIsDark = palette.isDark
                publishScreenLayout()
            },
        )
        zones.onInfoChanged = { snapshot ->
            lastInfo = snapshot
            publishScreenLayout()
        }

        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        if (callId == null) {
            finish()
            return
        }

        val callerName = intent.getStringExtra("callerName") ?: "un proche"
        val textCallerName = findViewById<TextView>(R.id.textCallerName)
        val countdownFill = findViewById<View>(R.id.countdownProgressFill)
        buttonBlock = findViewById(R.id.buttonBlock)

        // Prénom renseigné côté PWA (panneau "Qui appelle ?") affiché quand il
        // existe, plutôt qu'un générique systématique : "Marie vous appelle"
        // aide Jean à savoir qui va apparaître avant même la connexion. "Un
        // proche" est la valeur de repli du PWA quand rien n'est renseigné —
        // seul cas où le message reste générique.
        textCallerName.text = if (callerName.equals("un proche", ignoreCase = true)) {
            "On vous appelle"
        } else {
            "$callerName vous appelle"
        }
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
            onReady = {
                runOnUiThread {
                    isPrepared = true
                    // Demande de connexion immédiate arrivée avant l'offre (voir
                    // listenForForceConnect ci-dessous) : c'est maintenant qu'on
                    // peut y répondre.
                    if (pendingForceConnect && !isConnected) {
                        pendingForceConnect = false
                        connectVideoCall()
                    }
                }
            },
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

        // Écoutée dès maintenant, et non à la connexion : la consigne doit être
        // connue AVANT que answer() ne crée la piste micro (voir
        // WebRtcCallEngine.pendingMicMuted), sans quoi le micro de la tablette
        // émet le temps d'un aller-retour Firestore — assez pour un larsen avec
        // le téléphone du soignant posé à côté.
        callEngine.listenForMicMute()

        // Écouté dès maintenant pour la même raison que la coupure micro : la
        // consigne doit être connue AVANT qu'answer() ne crée la piste micro
        // et que la piste audio distante n'arrive, sans quoi la tablette
        // émet et diffuse le temps d'un aller-retour Firestore — assez pour
        // un larsen franc quand le téléphone du proche est dans la pièce.
        callEngine.listenForSameRoomMode()

        // Le mode soignant écrit cette demande dès la création de l'appel (voir
        // web-caller/app.js) : elle arrive donc souvent AVANT que l'offre WebRTC
        // n'ait été récupérée. Connecter à ce moment-là échouait en silence —
        // answer() abandonne sans rien dire tant que la connexion n'existe pas —
        // laissant le proche devant un décompte qui ne se termine jamais, alors
        // que la transcription, elle, fonctionnait (elle passe par Firestore, pas
        // par WebRTC) et donnait l'illusion d'un appel établi. La demande est
        // donc mise en attente jusqu'à onReady si l'offre n'est pas encore là.
        var forceConnectHandled = false
        callEngine.listenForForceConnect {
            runOnUiThread {
                if (forceConnectHandled || isConnected) return@runOnUiThread
                forceConnectHandled = true
                alertController.cancel()
                if (isPrepared) connectVideoCall() else pendingForceConnect = true
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
     * Le nom de l'appelant, la barre d'attente et sa légende suivent la
     * palette du moment. Leurs couleurs étaient écrites en dur du temps où le
     * fond était toujours bleu foncé : sur la palette claire du jour, l'écran
     * de sonnerie devenait du blanc sur blanc — un écran entièrement vide, avec
     * seulement le son de notification pour dire qu'il se passait quelque
     * chose.
     *
     * La piste du décompte est dessinée en code plutôt que teintée : sa
     * couleur doit rester lisible aussi bien sur fond clair que sombre, ce
     * qu'une teinte unique ne permet pas. Le vert de remplissage, lui, ne
     * bouge pas — il contraste avec les deux.
     */
    private fun applyPaletteToAlert(palette: ScreenTheme.Palette) {
        findViewById<TextView>(R.id.textCallerName).setTextColor(palette.primaryText)
        findViewById<TextView>(R.id.textCountdownHint).setTextColor(palette.secondaryText)
        findViewById<View>(R.id.countdownProgressContainer).setBackgroundColor(
            if (palette.isDark) COUNTDOWN_TRACK_ON_DARK else COUNTDOWN_TRACK_ON_LIGHT
        )
    }

    override fun onResume() {
        super.onResume()
        zones.onResume()
        if (!isConnected && !boundToRoomService) {
            boundToRoomService = bindService(
                Intent(this, RoomPresenceService::class.java), roomConnection, Context.BIND_AUTO_CREATE
            )
        }
    }

    override fun onPause() {
        super.onPause()
        zones.onPause()
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
    override fun onNewIntent(intent: android.content.Intent) {
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

    /**
     * Affiche (ou retire) la photo que le proche commente en direct depuis le
     * PWA. Le décodage se fait hors du thread principal : une photo de
     * plusieurs centaines de kilo-octets décodée à chaque changement ferait
     * saccader la vidéo et le défilement des sous-titres, très visible sur une
     * tablette d'entrée de gamme.
     *
     * La vidéo du proche continue de tourner derrière : c'est volontaire, elle
     * réapparaît instantanément à la fin du diaporama sans rien à relancer.
     */
    private fun showSlideshowPhoto(photoBase64: String?) {
        val imageSlideshow = findViewById<ImageView>(R.id.imageSlideshow)
        if (photoBase64.isNullOrEmpty()) {
            runOnUiThread {
                imageSlideshow.visibility = View.GONE
                zones.setBackground(HomeZonesController.Background.VIDEO)
            }
            return
        }
        Thread {
            val bitmap = try {
                val bytes = Base64.decode(photoBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                Log.e(TAG, "Photo de diaporama illisible", e)
                null
            } ?: return@Thread
            runOnUiThread {
                imageSlideshow.setImageBitmap(bitmap)
                imageSlideshow.visibility = View.VISIBLE
                zones.setBackground(HomeZonesController.Background.SLIDESHOW)
            }
        }.start()
    }

    private fun connectVideoCall() {
        // Deux chemins mènent ici (fin du décompte et demande de connexion
        // immédiate) : sans ce garde-fou, ils pouvaient se déclencher tous les
        // deux et réinitialiser des surfaces vidéo déjà initialisées, ce qui
        // faisait planter l'écran d'appel en pleine conversation — et
        // raccrochait donc côté proche, sans explication.
        if (isConnected) return
        isConnected = true
        // C'est ici, et pas à l'arrivée de l'appel, que le micro change de
        // main : pendant toute la sonnerie il reste à l'écoute de la pièce,
        // pour que la zone 2 continue de fonctionner normalement. WebRTC le
        // réclame maintenant, et un seul composant à la fois peut le tenir.
        stopRoomTranscription()
        RoomPresenceService.pauseForCall(this)
        // Le fond n'est plus uni : la date et la météo s'effacent en fondu et
        // laissent la place à la vidéo (voir HomeZonesController.setBackground).
        zones.setBackground(HomeZonesController.Background.VIDEO)
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
        callEngine.listenForSlideshowPhoto { photoBase64 -> showSlideshowPhoto(photoBase64) }
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
        screenStateHandler.post(screenStatePublisher)
        // Applique tout de suite la disposition correspondant à l'orientation
        // actuelle (la tablette peut déjà être en paysage au moment où
        // l'appel se connecte, pas seulement lors d'une rotation ultérieure).
        applyOrientationLayout(resources.configuration.orientation)
    }

    /**
     * Adapte à l'orientation ce qui ne s'y adapte pas tout seul. Les trois
     * zones, elles, n'ont plus rien à recalculer : leur hauteur vient d'un
     * partage proportionnel de l'espace disponible (voir view_home_zones.xml),
     * qui suit la rotation de lui-même — contrairement au bandeau de
     * sous-titres qu'elles remplacent, dont la hauteur en pixels devait être
     * recalculée à la main à chaque rotation, à chaque changement de taille de
     * texte et à chaque changement du nombre de lignes.
     *
     * En portrait, le bouton Bloquer/Raccrocher reste en bas à droite. En
     * paysage, où les zones de texte occupent toute la largeur jusqu'assez
     * bas, il passe en haut à droite, par-dessus la vidéo du proche plutôt que
     * sur le texte — la miniature de Jean (localRenderer, elle aussi en haut à
     * droite par défaut) descend d'autant pour ne pas être recouverte.
     */
    private fun applyOrientationLayout(orientation: Int) {
        val localRenderer = localRendererRef ?: return
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val margin16 = (16 * density).roundToInt()

        (buttonBlock.layoutParams as FrameLayout.LayoutParams).apply {
            if (isLandscape) {
                gravity = Gravity.TOP or Gravity.END
                topMargin = margin16; bottomMargin = 0
            } else {
                gravity = Gravity.BOTTOM or Gravity.END
                topMargin = 0; bottomMargin = (24 * density).roundToInt()
            }
            marginEnd = if (isLandscape) margin16 else (24 * density).roundToInt()
            buttonBlock.layoutParams = this
        }

        (localRenderer.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.END
            marginStart = margin16; marginEnd = margin16; bottomMargin = 0
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
        if (!isConnected) return
        applyOrientationLayout(newConfig.orientation)
        // Les proportions de l'écran viennent de changer : la réplique côté
        // PWA doit tourner avec, sans quoi le proche verrait des zones aux
        // mauvaises places jusqu'au prochain rafraîchissement horaire.
        publishScreenLayout()
    }

    /**
     * Branche la zone 3 (les paroles de l'appel) sur la transcription temps
     * réel de la tablette, et relaie les réglages que le proche pilote depuis
     * le PWA.
     *
     * Le texte vient d'AssemblyAI, alimenté par le son déjà reçu par l'appel
     * (voir WebRtcCallEngine.attachTranscriptionSink) : plus rien ne dépend de
     * la reconnaissance vocale du navigateur du proche, absente sur Safari/iOS
     * et privée de son sur Android/Chrome (micro accaparé par l'appel
     * lui-même) — c'était le point de fragilité du projet.
     *
     * Les réglages "nombre de lignes" et "délai d'effacement" ont disparu avec
     * le bandeau : la zone occupe une part fixe de l'écran, et elle s'efface
     * quand il n'y a plus rien à lire, pas au bout d'un délai réglé à
     * l'avance (voir RollingCaptionZone).
     */
    private fun setupCaptionMode() {
        // La source du texte suffit à décider de sa zone : rien ici n'a à
        // savoir laquelle (voir HomeZonesController).
        callEngine.listenForCaptions { source, text, isFinal ->
            runOnUiThread { zones.submitTranscription(source, text, isFinal) }
        }

        callEngine.listenForCaptionScrollSpeed { dpPerSec ->
            runOnUiThread { zones.setScrollSpeedDpPerSec(dpPerSec) }
        }

        // Ce listener écoute tout le document d'appel Firestore, donc il se
        // redéclenche à chaque écriture (volume, etc.), pas seulement quand
        // l'activation change — d'où le garde-fou, répété plus bas pour les
        // autres réglages.
        var captionsCurrentlyEnabled: Boolean? = null
        callEngine.listenForCaptionMode { enabled ->
            runOnUiThread {
                if (captionsCurrentlyEnabled == enabled) return@runOnUiThread
                captionsCurrentlyEnabled = enabled
                // Démarre/arrête la transcription temps réel AssemblyAI en
                // même temps que la zone : service payant, inutile de le
                // faire tourner quand le proche n'a pas activé les sous-titres.
                callEngine.setCaptionsActive(enabled)
                if (!enabled) zones.clearTranscriptions()
            }
        }

        var currentVisibleLines: Int? = null
        callEngine.listenForCaptionVisibleLines { lines ->
            runOnUiThread {
                if (currentVisibleLines == lines) return@runOnUiThread
                currentVisibleLines = lines
                zones.setVisibleLines(lines)
            }
        }

        var currentClearDelay: Int? = null
        callEngine.listenForCaptionClearDelay { seconds ->
            runOnUiThread {
                if (currentClearDelay == seconds) return@runOnUiThread
                currentClearDelay = seconds
                zones.setClearDelaySeconds(seconds)
            }
        }

        // Bascule de la transcription vers le microphone de la tablette : Jean
        // lit alors ce que dit quelqu'un présent dans sa pièce plutôt que son
        // correspondant. La zone d'appel n'est pas masquée, elle perd
        // simplement sa source et s'efface d'elle-même après le délai habituel
        // — les deux zones gardent leur place, pour que Jean retrouve toujours
        // le texte de la pièce au même endroit.
        var currentMicToRoom: Boolean? = null
        callEngine.listenForMicToRoom { enabled ->
            runOnUiThread {
                if (currentMicToRoom == enabled) return@runOnUiThread
                currentMicToRoom = enabled
                callEngine.setMicToRoom(enabled)
            }
        }
    }

    /**
     * Envoie au PWA ce que Jean a réellement sous les yeux, pour qu'il montre
     * la même chose au même instant (voir CallSignalingClient.
     * publishScreenState). N'écrit que lorsque le texte affiché change, ou que
     * l'avance de lecture bouge d'au moins une demi-seconde : appelée
     * plusieurs fois par minute pendant tout l'appel, une écriture
     * systématique multiplierait sans raison les écritures Firestore et les
     * réveils du listener d'en face.
     *
     * La zone 2 (paroles de la pièce) est toujours vide pendant un appel — le
     * micro appartient alors à WebRTC, et RoomPresenceService est suspendu —
     * mais elle est publiée quand même : le jour où la tablette saura faire
     * les deux, le PWA n'aura rien à changer.
     */
    /**
     * Décrit l'écran de Jean au PWA, pour qu'il en dessine une réplique
     * fidèle (voir CallSignalingClient.publishScreenLayout). Appelée à chaque
     * changement de palette et à chaque rafraîchissement de la zone
     * d'information, soit quelques fois par heure — sans garde-fou, donc,
     * contrairement à l'état du texte affiché qui suit le rythme de la parole.
     *
     * Les proportions viennent des dimensions réelles de la dalle et non
     * d'une valeur codée en dur : la même application tourne sur des
     * tablettes différentes, et une réplique aux mauvaises proportions
     * donnerait au proche une idée fausse de la place qu'occupe chaque zone.
     */
    private fun publishScreenLayout() {
        val metrics = resources.displayMetrics
        if (metrics.heightPixels <= 0) return
        val info = lastInfo
        callEngine.publishScreenLayout(
            aspectRatio = metrics.widthPixels.toDouble() / metrics.heightPixels.toDouble(),
            zoneOrder = zones.zoneOrderNames(),
            isDark = screenIsDark,
            infoMoment = info?.moment,
            infoWeather = info?.weather,
            infoDate = info?.date,
        )
    }

    private fun publishScreenStateIfChanged() {
        val callText = zones.displayedText(TranscriptionSource.CALL)
        val roomText = zones.displayedText(TranscriptionSource.ROOM)
        val lag = zones.pendingSeconds()
        val lagMoved = abs(lag - lastPublishedLagSeconds) >= LAG_PUBLISH_THRESHOLD_SECONDS
        if (callText == lastPublishedCallText && roomText == lastPublishedRoomText && !lagMoved) return
        lastPublishedCallText = callText
        lastPublishedRoomText = roomText
        lastPublishedLagSeconds = lag
        callEngine.publishScreenState(roomText = roomText, callText = callText, lagSeconds = lag)
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
            callEngine.pinSystemVolume()
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
        screenStateHandler.removeCallbacks(screenStatePublisher)
        stopRoomTranscription()
        zones.release()
        RoomPresenceService.resumeAfterCall(this)
        alertController.cancel()
        if (!callHandled && !isChangingConfigurations) {
            callHandled = true
            callEngine.hangUp()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IncomingCallActivity"

        /** Cadence de publication de l'état de l'écran de Jean vers le PWA (voir publishScreenStateIfChanged). */
        /** Piste du décompte : un voile clair sur fond sombre, sombre sur fond clair. */
        private const val COUNTDOWN_TRACK_ON_DARK = 0x33FFFFFF
        private const val COUNTDOWN_TRACK_ON_LIGHT = 0x22000000

        private const val SCREEN_STATE_PUBLISH_MS = 1_000L
        private const val LAG_PUBLISH_THRESHOLD_SECONDS = 0.5f

        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_PHOTO_PATH = "extra_caller_photo_path"
        const val EXTRA_SIGNAL_RECEIVED_AT = "extra_signal_received_at"
    }
}
