package com.seniorvisio.ui

import android.app.NotificationManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.animation.ValueAnimator
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
import com.seniorvisio.core.AlertVolume
import com.seniorvisio.core.Environnement
import com.seniorvisio.core.KioskManager
import com.seniorvisio.core.ScreenTheme
import com.seniorvisio.core.CallTrace
import com.seniorvisio.core.TranscriptionSource
import com.seniorvisio.core.UsageStats
import com.seniorvisio.core.WebRtcCallEngine
import com.seniorvisio.recueil.LecteurRecueil
import com.seniorvisio.recueil.RecueilStore
import com.seniorvisio.recueil.Rendu
import com.seniorvisio.signaling.CallSignalingClient
import com.seniorvisio.service.IncomingCallService
import com.seniorvisio.service.RoomPresenceService
import com.seniorvisio.service.TimedCallAlertController
import org.webrtc.RendererCommon
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

    /** Répondre sans attendre la fin du décompte. Masqué dès la connexion (voir connectVideoCall). */
    private lateinit var buttonAnswerNow: Button
    private var isConnected = false
    private var callHandled = false

    /**
     * Instant du décrochage effectif, pour mesurer la durée de la conversation
     * (voir UsageStats.noteCall). Zéro tant que l'appel n'a pas abouti : une
     * sonnerie sans réponse n'est pas un appel, et la compter comme telle
     * fausserait autant le nombre que la durée moyenne.
     */
    private var connectedAtMs = 0L

    /** Vrai dès que l'offre WebRTC du proche est reçue et acceptée (voir prepareIncomingCall). */
    private var isPrepared = false

    /** Connexion immédiate demandée avant que l'offre ne soit prête (bouton du PWA). */
    private var pendingForceConnect = false

    // Références gardées pour adapter la disposition à chaque rotation (voir
    // onConfigurationChanged / applyOrientationLayout) sans jamais recréer
    // l'Activity ni rattacher les renderers WebRTC — l'appel en cours n'est
    // jamais interrompu par une rotation.
    private var remoteRendererRef: SurfaceViewRenderer? = null
    private var localRendererRef: SurfaceViewRenderer? = null

    /** Agrandissement/rétrécissement en cours de la vidéo (voir fitVideoAboveCaptions). */
    private var videoHeightAnimator: ValueAnimator? = null

    /**
     * Le lecteur de recueil de CET appel. Null tant qu'aucun magasin n'est
     * en service — l'appel fonctionne alors normalement, sans recueils.
     */
    private var lecteurRecueil: LecteurRecueil? = null

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

    // Cet écran ne se lie plus au service d'écoute de la pièce. Il l'a fait
    // jusqu'ici pour que la zone 2 continue de suivre la pièce pendant la
    // sonnerie ; l'écoute est désormais suspendue dès la demande de connexion
    // (voir onCreate), et il n'y a donc plus rien à afficher là pendant ces
    // quelques secondes. La zone 3, elle, reste alimentée par le son de
    // l'appel une fois connecté (voir WebRtcCallEngine).

    private val screenStateHandler = Handler(Looper.getMainLooper())
    private val screenStatePublisher = object : Runnable {
        override fun run() {
            // Relu à chaque passage : un réglage d'ergonomie changé depuis le
            // PWA pendant l'appel doit prendre effet dans la seconde, pas au
            // prochain appel.
            applyCaptionErgonomics()
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
        hideNavigationBar()

        // Une demande de connexion, et le micro change de main tout de suite —
        // pas au décrochage comme auparavant. Deux raisons :
        //
        //  - Le moteur de reconnaissance d'Android émet ses sons de début et
        //    de fin d'énoncé en boucle. Les laisser tourner pendant que la
        //    tablette sonne, c'est les mélanger à la sonnerie au moment précis
        //    où Jean doit comprendre qu'on l'appelle.
        //  - Un seul composant à la fois peut tenir le micro. Le rendre
        //    maintenant plutôt qu'au décrochage laisse au système tout le
        //    temps du décompte pour le libérer, au lieu de l'exiger dans la
        //    seconde où WebRTC le réclame.
        //
        // Le prix : la zone 2 ne suit plus la pièce pendant la sonnerie. C'est
        // assumé — pendant ces quelques secondes, ce qui compte à l'écran est
        // qui appelle, pas ce qui se dit autour.
        RoomPresenceService.pauseForCall(this)
        // Un appel se présente : les alertes retrouvent leur niveau, qu'elles
        // avaient quitté sur l'écran d'accueil (voir AlertVolume).
        AlertVolume.normal(this)
        findViewById<TextView>(R.id.textBuildRev).text = Environnement.étiquetteVersion()
        KioskManager.startIfDeviceOwner(this)

        // Les trois zones sont en place dès la sonnerie, pas seulement une
        // fois connecté — mais la zone d'information, elle, s'efface tout de
        // suite (voir setBackground juste après) : dès qu'un appel se
        // présente, l'écran ne doit plus montrer qu'une chose, qui appelle.
        // La date et la météo sont un repère d'écran au repos, et les laisser
        // au-dessus de la photo du proche et du décompte ajoute à lire là où
        // il faut au contraire que tout soit évident d'un coup d'œil.
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
        // Sans fondu : l'écran n'est pas encore visible, un fondu commencerait
        // par afficher le bandeau. La place reste réservée, les zones 2 et 3
        // ne bougent donc pas quand la vidéo prend le relais.
        zones.setBackground(HomeZonesController.Background.VIDEO, animate = false)

        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        if (callId == null) {
            terminer("aucun identifiant d'appel dans l'intention reçue")
            return
        }
        handledCallId = callId

        val callerName = intent.getStringExtra("callerName") ?: "un proche"
        val textCallerName = findViewById<TextView>(R.id.textCallerName)
        val countdownFill = findViewById<View>(R.id.countdownProgressFill)
        buttonBlock = findViewById(R.id.buttonBlock)
        buttonAnswerNow = findViewById(R.id.buttonAnswerNow)

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

        // Le même chemin exactement que la fin du décompte (onTimeoutConnect) :
        // on ne fait qu'abréger l'attente, on ne connecte pas autrement.
        // connectVideoCall se protège elle-même d'un second déclenchement, ce
        // qui couvre le cas où le décompte arrive à son terme dans la même
        // seconde que l'appui.
        buttonAnswerNow.setOnClickListener {
            CallTrace.record("APPEL décompte", "abrégé par le bouton de la tablette")
            alertController.cancel()
            connectVideoCall()
        }

        buttonBlock.setOnClickListener {
            alertController.cancel()
            callHandled = true
            if (isConnected) {
                callEngine.hangUp()
            } else {
                callEngine.blockCall()
            }
            terminer(if (isConnected) "bouton de la tablette : raccroché" else "bouton de la tablette : appel bloqué")
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
                    terminer("préparation impossible : ${error.message ?: error.javaClass.simpleName}")
                }
            }
        )

        // Écoutée dès maintenant, et non à la connexion : la consigne doit être
        // connue AVANT que answer() ne crée la piste micro (voir
        // WebRtcCallEngine.pendingMicMuted), sans quoi le micro de la tablette
        // émet le temps d'un aller-retour Firestore — assez pour un larsen avec
        // le téléphone du proche posé à côté.
        callEngine.listenForMicMute()

        // Écouté dès maintenant pour la même raison que la coupure micro : la
        // consigne doit être connue AVANT qu'answer() ne crée la piste micro
        // et que la piste audio distante n'arrive, sans quoi la tablette
        // émet et diffuse le temps d'un aller-retour Firestore — assez pour
        // un larsen franc quand le téléphone du proche est dans la pièce.
        callEngine.listenForSameRoomMode()

        // Le bouton « Connexion immédiate » du PWA écrit cette demande (voir
        // web-caller/app.js), et le proche peut le toucher AVANT que l'offre
        // WebRTC n'ait été récupérée. Connecter à ce moment-là échouait en silence —
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
                if (!callHandled) terminer("raccroché demandé par le proche depuis le PWA")
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
    }

    override fun onPause() {
        super.onPause()
        zones.onPause()
    }

    /** Voir MainActivity.onWindowFocusChanged — même raison. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
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
        val incomingCallId = intent.getStringExtra(EXTRA_CALL_ID)
        if (incomingCallId == null || incomingCallId == handledCallId) {
            Log.i(TAG, "Second déclenchement ignoré pour un appel déjà affiché")
            return
        }
        // Un AUTRE appel, donc un autre proche. Il ne doit normalement plus
        // arriver jusqu'ici — IncomingCallService le renvoie occupé avant même
        // d'afficher quoi que ce soit — mais l'ignorer en silence était
        // précisément le défaut : l'appelant restait sur « Connexion à sa
        // tablette… » indéfiniment, sans décompte et sans explication, caméra
        // et micro allumés. Dernier filet, au cas où le service serait
        // court-circuité.
        Log.w(TAG, "Appel $incomingCallId reçu pendant $handledCallId : renvoyé occupé")
        CallSignalingClient().updateStatus(incomingCallId, CallSignalingClient.STATUS_BUSY)
    }

    /**
     * Petit son discret au tout début du décompte, pour signaler l'appel sans
     * réveiller toute la maison.
     *
     * Le son reste celui des notifications — discret, c'est le but — mais il
     * est joué sur le flux ALARME et non sur celui des notifications. La
     * distinction n'a l'air de rien et décide pourtant si un appel arrive :
     *
     *  - Le moteur de reconnaissance d'Android émet des bips d'alerte en
     *    boucle qu'on ne peut faire taire qu'en baissant ce flux (voir
     *    AlertVolume). Laisser la sonnerie dessus, c'était choisir entre des
     *    bips toute la journée et des appels muets.
     *  - Le flux alarme ignore le mode silencieux. Un appel qui n'émet aucun
     *    son parce que la tablette a été mise en silencieux par une fausse
     *    manœuvre est exactement le genre de panne que Jean ne peut ni
     *    constater ni signaler.
     */
    private fun playDiscreetAlertSound() {
        try {
            val soundUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(this, soundUri) ?: return
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
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

    /**
     * Montre, ou retire, l'élément de recueil que le proche présente.
     *
     * ═══ LA PHOTO PREND LA PLACE DU VISAGE, PAS CELLE DU TEXTE ═══
     *
     * Elle occupe exactement la bande que fitVideoAboveCaptions accorde à la
     * vidéo, et le visage du proche s'efface le temps de la présentation. Les
     * zones de texte, elles, ne bougent pas d'un pixel.
     *
     * C'est le choix de l'administrateur, et il se défend : c'est précisément
     * pendant qu'un proche commente ses photos qu'il y a le plus à lire pour
     * Jean. Une photo en plein écran aurait emporté la transcription au pire
     * moment.
     *
     * Le visage revient de lui-même à la fermeture, sans rien à relancer : la
     * vidéo n'a jamais cessé de tourner derrière.
     */
    private fun afficherRecueil(état: LecteurRecueil.État) {
        val image = findViewById<ImageView>(R.id.imageRecueil) ?: return
        val message = findViewById<TextView>(R.id.textRecueilImpossible) ?: return
        val blocActu = findViewById<View>(R.id.blocActualite)
        val imageActu = findViewById<ImageView>(R.id.imageActualite)
        val texteActu = findViewById<TextView>(R.id.texteActualite)
        val renderer = remoteRendererRef

        // Trois affichages possibles se partagent la même bande. Les masquer
        // TOUS avant d'en montrer un : sans ça, passer d'une photo à un titre
        // laisserait la photo derrière le texte. Le coût est nul, et la règle
        // survit à l'ajout d'un quatrième rendu — ce qui n'est pas le cas
        // d'une bascule écrite à la main entre deux vues connues.
        image.setImageDrawable(null)
        image.visibility = View.GONE
        message.visibility = View.GONE
        blocActu?.visibility = View.GONE
        imageActu?.setImageDrawable(null)

        when (val rendu = état.rendu) {
            null -> {
                // INVISIBLE et non GONE pour la vidéo, ici comme ailleurs dans
                // cet écran : une surface de rendu retirée de la mise en page
                // est détruite, et la recréer donne un écran noir de plusieurs
                // centaines de millisecondes au retour.
                renderer?.visibility = View.VISIBLE
                zones.setBackground(HomeZonesController.Background.VIDEO)
                CallTrace.record("APPEL recueil", "refermé")
            }
            is Rendu.Image -> {
                image.setImageBitmap(rendu.bitmap)
                image.visibility = View.VISIBLE
                renderer?.visibility = View.INVISIBLE
                zones.setBackground(HomeZonesController.Background.SLIDESHOW)
                ajusterBandeRecueil()
                CallTrace.record(
                    "APPEL recueil",
                    "« ${état.titre} » ${état.position}/${état.total}",
                )
            }
            is Rendu.Texte -> {
                if (blocActu == null || texteActu == null || imageActu == null) {
                    // La mise en page de cet écran est réglable et a déjà
                    // changé plusieurs fois : mieux vaut le dire que d'afficher
                    // un titre invisible et laisser chercher pourquoi.
                    message.text = "Cet écran ne sait pas afficher ce titre"
                    message.visibility = View.VISIBLE
                } else {
                    texteActu.text = rendu.texte
                    // L'image n'apparaît QUE si le flux en a fourni une.
                    // Réserver sa place quand elle manque donnerait un titre
                    // serré à droite d'un vide inexpliqué.
                    if (rendu.vignette != null) {
                        imageActu.setImageBitmap(rendu.vignette)
                        imageActu.visibility = View.VISIBLE
                    } else {
                        imageActu.visibility = View.GONE
                    }
                    blocActu.visibility = View.VISIBLE
                    ajusterBandeActualite()
                }
                renderer?.visibility = View.INVISIBLE
                zones.setBackground(HomeZonesController.Background.SLIDESHOW)
                CallTrace.record(
                    "APPEL actualité",
                    "${état.position}/${état.total} · ${rendu.texte.length} signes · " +
                        (if (rendu.vignette != null) "avec vignette" else "sans vignette"),
                )
            }
            is Rendu.Impossible -> {
                message.text = rendu.raison
                message.visibility = View.VISIBLE
                renderer?.visibility = View.INVISIBLE
                zones.setBackground(HomeZonesController.Background.SLIDESHOW)
                CallTrace.record("APPEL recueil", "inaffichable : ${rendu.raison}")
            }
        }
    }

    /**
     * Donne à la photo la même bande que la vidéo.
     *
     * La hauteur est recalculée comme pour le visage — même plancher d'un
     * tiers d'écran, même repère pris sur les zones de texte réellement
     * affichées. Recopier le calcul serait le condamner à diverger : les deux
     * passent donc par topOfVisibleTextZones.
     */
    /**
     * Donne au bloc d'actualité la même bande que la vidéo et que les photos.
     *
     * Même calcul, même plancher, même repère pris sur les zones de texte
     * réellement affichées : les trois passent par topOfVisibleTextZones, pour
     * qu'aucun ne puisse dériver des deux autres.
     */
    private fun ajusterBandeActualite() {
        val bloc = findViewById<View>(R.id.blocActualite) ?: return
        if (bloc.visibility != View.VISIBLE) return
        val root = findViewById<View>(R.id.callRoot) ?: return
        if (root.height == 0) return
        val cible = (zones.topOfVisibleTextZones() ?: root.height)
            .coerceAtLeast(root.height / 3)
        val params = bloc.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.height == cible) return
        params.height = cible
        bloc.layoutParams = params
    }

    private fun ajusterBandeRecueil() {
        val image = findViewById<ImageView>(R.id.imageRecueil) ?: return
        if (image.visibility != View.VISIBLE) return
        val root = findViewById<View>(R.id.callRoot) ?: return
        if (root.height == 0) return
        val minimum = root.height / 3
        val cible = (zones.topOfVisibleTextZones() ?: root.height).coerceAtLeast(minimum)
        val params = image.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.height == cible) return
        params.height = cible
        image.layoutParams = params
    }

    private fun connectVideoCall() {
        // Deux chemins mènent ici (fin du décompte et demande de connexion
        // immédiate) : sans ce garde-fou, ils pouvaient se déclencher tous les
        // deux et réinitialiser des surfaces vidéo déjà initialisées, ce qui
        // faisait planter l'écran d'appel en pleine conversation — et
        // raccrochait donc côté proche, sans explication.
        if (isConnected) return
        isConnected = true
        buttonAnswerNow.visibility = View.GONE
        // Le micro a déjà changé de main à l'arrivée de l'appel (voir
        // onCreate) : WebRTC le trouve libre, sans avoir à attendre une
        // libération dans la seconde.
        // Sans effet dans le cas courant, le fond étant déjà en VIDEO depuis
        // onCreate : conservé pour le chemin où un diaporama s'est intercalé
        // avant la connexion (voir showSlideshowPhoto).
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
        // L'image entière du proche, à ses proportions réelles.
        //
        // Le réglage par défaut d'un SurfaceViewRenderer est un compromis qui
        // recadre : sur une dalle 16/10 recevant un flux de téléphone tenu à
        // la verticale, il rognait franchement les côtés — et donc, selon la
        // façon dont le proche tient son téléphone, une partie de son visage.
        // Une bande noire ne gêne personne ; un menton coupé, si.
        //
        // Après attachRenderers, donc après init() : ces deux réglages n'en
        // dépendent pas aujourd'hui, mais les poser avant reviendrait à
        // configurer une vue dont le rendu n'existe pas encore — un ordre qui
        // marche par hasard est un ordre qui cassera à la prochaine montée de
        // version de la bibliothèque.
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        // ═══ PAS DE MISE À L'ÉCHELLE MATÉRIELLE, ET C'EST UN RETRAIT ═══
        //
        // J'avais ajouté setEnableHardwareScaler(true) ici « par bonne
        // pratique », sans que personne le demande. C'est très probablement
        // lui qui annulait le letterboxing : il fait rendre la vidéo dans un
        // tampon dont les proportions sont celles de la VUE, que l'affichage
        // étire ensuite pour la remplir. Sur une dalle large recevant un flux
        // de téléphone tenu à la verticale, cela revient exactement au
        // symptôme constaté — l'image du proche élargie à toute la largeur.
        //
        // Ce réglage est fait pour les cas où la vue a déjà les proportions
        // de la vidéo, ce qui n'est pas le nôtre et ne peut pas l'être : la
        // bande vidéo suit la hauteur laissée libre par le texte, pas les
        // proportions du flux.
        //
        // Ce qu'on perd : un peu de travail confié au processeur graphique
        // plutôt qu'à l'affichage. Sur un flux de 720×1280, c'est sans
        // conséquence mesurable — et une image juste vaut mieux qu'une image
        // efficace.
        // AVANT answer(), et non après comme jusqu'ici. Même raison que pour la
        // coupure micro et le mode même pièce : la piste audio du proche peut
        // arriver dans la milliseconde qui suit answer(), et c'est à sa
        // création que son niveau est posé. Écouter le curseur ensuite, c'est
        // laisser la première valeur du proche arriver trop tard pour le
        // premier son — celui qu'on entend, ou pas, au début de l'appel.
        callEngine.listenForRemoteVolumeControl()
        callEngine.answer()
        buttonBlock.text = "Raccrocher"
        remoteRendererRef = remoteRenderer
        localRendererRef = localRenderer
        setupCaptionMode()
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
            runOnUiThread { if (!callHandled) terminer("connexion WebRTC perdue sans raccroché explicite") }
        }
        // ═══ LE RECUEIL, COMMANDÉ PAR LE PROCHE ═══
        //
        // Le lecteur ignore d'où vient la commande : c'est la couture qui
        // permettra d'ajouter plus tard la navigation par Jean, du doigt ou de
        // la voix, sans rouvrir ce fichier (docs/architecture-recueils.md § 5).
        //
        // Le magasin est celui du service de premier plan, emprunté : c'est
        // lui qui a téléchargé et vérifié les photos, bien avant cet appel.
        // Absent — service non démarré, tablette qui vient de redémarrer — le
        // reste de l'appel fonctionne sans, et c'est dit une fois dans le
        // journal plutôt que découvert à la première commande.
        val magasin = RecueilStore.actif
        if (magasin == null) {
            CallTrace.record("APPEL recueil", "indisponible : aucun magasin en service")
        } else {
            val lecteur = LecteurRecueil(magasin)
            lecteurRecueil = lecteur
            lecteur.onAffichage = { état -> afficherRecueil(état) }
            callEngine.listenForRecueilCommande { commande ->
                runOnUiThread {
                    val id = commande.recueilId
                    if (id.isNullOrEmpty()) lecteur.fermer() else lecteur.ouvrir(id, commande.index)
                }
            }
        }
        // La vidéo cède la place au texte, et la reprend quand il s'efface. La
        // photo d'un recueil suit la même bande, au même instant : les deux
        // sont recalculées ensemble pour qu'elles ne puissent pas diverger.
        zones.onTextZonesChanged = {
            fitVideoAboveCaptions()
            ajusterBandeRecueil()
            ajusterBandeActualite()
        }
        remoteRenderer.post { fitVideoAboveCaptions(animate = false) }
        connectedAtMs = System.currentTimeMillis()
        screenStateHandler.post(screenStatePublisher)
        // Applique tout de suite la disposition correspondant à l'orientation
        // actuelle (la tablette peut déjà être en paysage au moment où
        // l'appel se connecte, pas seulement lors d'une rotation ultérieure).
        applyOrientationLayout(resources.configuration.orientation)
    }

    /**
     * Donne à la vidéo du proche la bande libre au-dessus du texte, et toute
     * la hauteur quand il n'y a pas de texte.
     *
     * ═══ CE QUI CHANGE, ET POURQUOI ═══
     *
     * La vidéo était un fond plein écran que les zones de texte recouvraient.
     * Deux conséquences, toutes deux au détriment de ce que Jean regarde : le
     * visage du proche était en partie caché par un cadre semi-opaque, et il
     * était de toute façon recadré pour remplir une dalle qui n'a pas les
     * proportions du téléphone d'en face.
     *
     * Désormais les deux se partagent la hauteur : l'image occupe ce qui
     * reste au-dessus de la première zone de texte affichée, et la reprend
     * entièrement dès que le silence efface le texte. Pendant une
     * conversation, l'écran alterne donc entre « on parle, je lis » et « on
     * s'est tu, je regarde », ce qui est exactement le rythme d'un appel.
     *
     * ═══ POURQUOI EN PIXELS, ET PAS EN POIDS DE MISE EN PAGE ═══
     *
     * Un poids dans la pile des zones aurait été plus court à écrire, mais la
     * vidéo n'est pas dans cette pile : c'est un calque plein écran, frère du
     * diaporama et de la photo d'appelant, et l'y déplacer signifierait
     * détruire puis recréer la surface de rendu — un écran noir à chaque
     * apparition de texte.
     *
     * On lui donne donc une hauteur mesurée, prise sur la pile elle-même
     * telle qu'elle est réellement empilée à cet instant : l'ordre des zones
     * est réglable, et supposer que le texte est en bas serait faux le jour
     * où quelqu'un change ce réglage.
     *
     * L'animation dure le temps du fondu du texte (voir RollingCaptionZone) :
     * l'image s'agrandit pendant que la phrase s'efface, en un seul geste.
     */
    private fun fitVideoAboveCaptions(animate: Boolean = true) {
        val renderer = remoteRendererRef ?: return
        val root = findViewById<View>(R.id.callRoot) ?: return
        if (root.height == 0) return
        // Plancher indispensable, et pas seulement esthétique : l'ordre des
        // zones est réglable, et rien n'interdit de mettre une zone de texte
        // en tête — la bande libre au-dessus d'elle serait alors nulle. Un
        // SurfaceViewRenderer de hauteur nulle ne crée jamais sa surface, ce
        // qui donne un écran noir dont le projet a déjà payé le prix (voir le
        // commentaire sur INVISIBLE plutôt que GONE, plus haut).
        //
        // Sous ce plancher, on revient au comportement d'avant : le texte se
        // pose par-dessus l'image, qui est dessinée sous les zones. Une
        // dégradation lisible, jamais un écran noir.
        val minimum = root.height / 3
        val cible = (zones.topOfVisibleTextZones() ?: root.height).coerceAtLeast(minimum)
        val params = renderer.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.height == cible) return
        // Les proportions de la BANDE, à côté de celles du flux que le journal
        // réseau donne déjà (« ↓ 720×1280@25 »). Les deux ensemble suffisent à
        // trancher la prochaine fois : si la bande est large, le flux étroit,
        // et que l'image remplit quand même la largeur, alors ce n'est ni la
        // mise en page ni la source — c'est le rendu.
        CallTrace.record(
            "APPEL bande vidéo",
            "${root.width}×$cible px" + if (cible == root.height) " (pleine hauteur)" else "",
        )
        // ═══ LA LARGEUR DOIT RESTER LIBRE ═══
        //
        // SCALE_ASPECT_FIT ne dessine JAMAIS de bandes noires. EglRenderer
        // recadre toujours la vidéo pour remplir la vue ; l'ajustement vient
        // d'ailleurs — de VideoLayoutMeasure, qui redimensionne LA VUE aux
        // proportions du flux. Et il contient ceci :
        //
        //     // If the measure specification is forcing a specific size - yield.
        //     if (MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY)
        //         layoutSize.x = maxWidth;
        //
        // En laissant la largeur à match_parent, je la forçais, et j'annulais
        // ainsi le seul mécanisme qui produisait l'ajustement. La vue faisait
        // toute la largeur, et le rendu prélevait la tranche centrale du flux
        // pour l'y étirer : l'image zoomée constatée en usage réel, avec le
        // haut et le bas du visage coupés.
        //
        // WRAP_CONTENT laisse un mode AT_MOST, borné par le parent : la vue
        // prend alors la plus grande taille aux proportions du flux qui tienne
        // dans la bande, et le rendu n'a plus rien à recadrer. Centrée
        // horizontalement, elle laisse du fond de part et d'autre — ce qui
        // était l'intention depuis le début.
        params.width = FrameLayout.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // La taille réellement obtenue après mise en page, pour ne plus avoir à
        // la déduire. Trois nombres suffisent désormais à tout trancher : le
        // flux (journal réseau), la bande qu'on accorde, et ce que la vue prend.
        renderer.post {
            CallTrace.record("APPEL vue vidéo", "${renderer.width}×${renderer.height} px réellement occupés")
        }
        if (!animate) {
            params.height = cible
            renderer.layoutParams = params
            return
        }
        val départ = if (params.height > 0) params.height else root.height
        videoHeightAnimator?.cancel()
        videoHeightAnimator = ValueAnimator.ofInt(départ, cible).apply {
            duration = VIDEO_RESIZE_MS
            addUpdateListener { animation ->
                params.height = animation.animatedValue as Int
                renderer.layoutParams = params
            }
            start()
        }
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
        // La hauteur de l'écran et la position des zones viennent de changer :
        // la bande vidéo se recalcule, une fois la nouvelle mise en page faite
        // — la mesurer maintenant rendrait encore les valeurs d'avant.
        remoteRendererRef?.post { fitVideoAboveCaptions(animate = false) }
        // Les proportions de l'écran viennent de changer : la réplique côté
        // PWA doit tourner avec, sans quoi le proche verrait des zones aux
        // mauvaises places jusqu'au prochain rafraîchissement horaire.
        publishScreenLayout()
    }

    /**
     * Ergonomie de lecture réglée par l'administrateur depuis le PWA (voir
     * AdminConfig.captionVisibleLines et suivants). Même code que sur l'écran
     * d'accueil, pour que Jean lise de la même façon qu'un appel soit en cours
     * ou non.
     */
    private fun applyCaptionErgonomics() {
        zones.setVisibleLines(adminConfig.captionVisibleLines)
        zones.setScrollSpeedDpPerSec(adminConfig.captionScrollSpeedDp.toFloat())
        zones.setClearDelaySeconds(adminConfig.captionClearDelaySeconds)
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
            noteCaptionShape(source, text, isFinal)
            runOnUiThread { zones.submitTranscription(source, text, isFinal) }
        }

        // Ergonomie de lecture : réglages d'administrateur, pas d'appel. Ils
        // décrivent la façon dont Jean lit, qui ne change pas selon qui
        // l'appelle, et ils doivent valoir aussi pour la pièce hors de tout
        // appel — ils viennent donc d'AdminConfig, alimenté par le document
        // d'appareil (voir DeviceStatusReporter). Avoir laissé chaque appelant
        // les régler pour lui-même faisait varier l'écran de Jean d'un appel à
        // l'autre sans que personne ne sache pourquoi.
        applyCaptionErgonomics()

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
            captionCharsPerLine = zones.captionCharsPerLine(),
            captionLines = zones.captionLines(),
        )
    }

    /**
     * Note la FORME d'un texte transcrit dans le journal technique, jamais son
     * contenu.
     *
     * ═══ Pourquoi la forme suffit, et pourquoi le contenu est exclu ═══
     *
     * La question posée est précise : un nombre nu — « 75 » — apparaît sur
     * l'écran de Jean quand le proche bouge le curseur de volume. Or il
     * n'existe que deux écrivains dans ces zones, et tous deux ne font que
     * transmettre ce qu'un moteur de reconnaissance a produit. Si ce nombre
     * est bien arrivé par là, c'est donc que la tablette a ENTENDU quelque
     * chose qu'elle a transcrit ainsi — et non qu'un réglage s'affiche par
     * erreur. Les deux causes se traitent à l'opposé.
     *
     * « quatre mots, 23 caractères » ne dit rien de ce qui se passe dans la
     * chambre. « deux caractères, uniquement des chiffres » répond à la
     * question. Le journal technique reste donc ce qu'il promet d'être : des
     * nombres et des états, rien qui puisse être lu comme une parole.
     *
     * Seuls les textes COURTS ET ENTIÈREMENT NUMÉRIQUES sont signalés à part.
     * Un texte court et numérique n'est pas une parole : c'est exactement
     * l'objet de l'enquête, et le distinguer ne révèle rien d'autre.
     */
    private fun noteCaptionShape(source: TranscriptionSource, text: String, isFinal: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val numérique = trimmed.length <= 6 && trimmed.all { it.isDigit() || it == ' ' || it == '%' }
        if (numérique) {
            // ═══ ON NE CITE PLUS. LE COMPTE SUFFIT. ═══
            //
            // Cette ligne reproduisait le texte tel quel, sous prétexte qu'un
            // texte court et numérique « ne peut pas constituer une parole ».
            // C'était faux, et ça l'est devenu dangereux : un code de porte, un
            // âge, une posologie, un fragment de numéro de téléphone sont des
            // nombres — prononcés dans une chambre, recopiés dans un journal
            // que ces règles Firestore laissent lire à qui en connaît
            // l'adresse.
            //
            // La citation servait à identifier un nombre qui s'affichait chez
            // Jean. Cette recherche est close. Ce qui reste utile — savoir
            // qu'un texte numérique est passé — tient dans sa longueur.
            CallTrace.record(
                "APPEL texte",
                "source=$source figé=$isFinal — texte numérique, ${trimmed.length} caractère(s)",
            )
            return
        }
        // Seulement les textes FIGÉS, et non plus un relevé par seconde.
        //
        // Ce relevé-là a coûté une version entière. Un par seconde, sur un
        // appel de deux minutes, remplissait à lui seul le journal et poussait
        // dehors les lignes d'ouverture — version installée, permissions,
        // focus, routage. On en a conclu que le focus n'avait pas été demandé,
        // alors que sa ligne avait simplement été évincée par celle-ci.
        //
        // Un texte figé par phrase suffit largement à connaître la forme de ce
        // qui s'écrit, et ne noie plus rien.
        if (!isFinal) return
        CallTrace.record(
            "APPEL texte",
            "source=$source figé=$isFinal — ${trimmed.count { it == ' ' } + 1} mot(s), " +
                "${trimmed.length} caractère(s)",
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
    /**
     * Ferme l'écran d'appel en DISANT pourquoi.
     *
     * ═══ LA PILE D'APPEL NE SUFFIT PAS, ET ON L'A CRU ═══
     *
     * [WebRtcCallEngine.hangUp] journalise sa pile d'appel pour distinguer les
     * causes d'un appel qui « s'arrête tout seul ». Sauf qu'il est appelé
     * depuis [onDestroy], et que la destruction arrive bien après le geste qui
     * l'a demandée : la pile relevée décrit le chemin du framework, pas le
     * nôtre. Cinq `finish()` très différents — raccroché du proche, connexion
     * perdue, préparation impossible, bouton de la tablette, intention sans
     * identifiant — produisaient donc TOUS la même ligne.
     *
     * La raison est donc notée là où elle est connue : au moment de la
     * décision, pas au moment de ses conséquences.
     */
    private fun terminer(raison: String) {
        CallTrace.record("APPEL fermeture", raison)
        finish()
    }

    override fun onDestroy() {
        // Ces deux booléens tranchent une question que le journal laissait
        // ouverte : une activité qui disparaît a-t-elle été fermée, ou
        // seulement recréée pour un changement de configuration ? Les deux se
        // ressemblent trait pour trait dans une pile d'appel, et mènent à des
        // recherches opposées.
        CallTrace.record(
            "APPEL écran détruit",
            "fermeture=$isFinishing changementDeConfig=$isChangingConfigurations " +
                "déjàTraité=$callHandled connecté=$isConnected",
        )
        // Ne garde jamais l'écran forcé allumé hors de la fenêtre d'appel
        // (voir le flag posé dans onCreate) — usage 24/7, risque batterie/
        // chauffe/marquage d'écran sinon.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenStateHandler.removeCallbacks(screenStatePublisher)
        videoHeightAnimator?.cancel()
        videoHeightAnimator = null
        // Le fil de décodage du lecteur est un fil démon, donc il n'empêcherait
        // pas l'application de s'arrêter — mais il survivrait à l'appel, à
        // décoder une photo dont plus personne ne veut. Un appel qui se termine
        // ne doit rien laisser tourner derrière lui.
        lecteurRecueil?.libérer()
        lecteurRecueil = null
        zones.release()
        // L'écoute de la pièce reprend, et l'écran d'accueil qui réapparaît
        // rebaissera les alertes (voir MainActivity.onResume) : le volume
        // n'est pas rétabli ici, sans quoi une rotation d'écran suffirait à le
        // faire osciller.
        RoomPresenceService.resumeAfterCall(this)
        alertController.cancel()
        if (!callHandled && !isChangingConfigurations) {
            callHandled = true
            callEngine.hangUp()
        }
        if (connectedAtMs > 0L) {
            val seconds = ((System.currentTimeMillis() - connectedAtMs) / 1000L).toInt()
            UsageStats.noteCall(connectedAtMs, seconds)
            connectedAtMs = 0L
        }
        // La tablette redevient joignable. Pas pendant une rotation, où
        // l'Activity est détruite et aussitôt recréée : la déclarer libre un
        // instant renverrait « occupé » à personne, mais laisserait passer un
        // second appel en plein milieu de la conversation en cours.
        if (!isChangingConfigurations) handledCallId = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IncomingCallActivity"

        /** Cadence de publication de l'état de l'écran de Jean vers le PWA (voir publishScreenStateIfChanged). */
        /** Piste du décompte : un voile clair sur fond sombre, sombre sur fond clair. */
        private const val COUNTDOWN_TRACK_ON_DARK = 0x33FFFFFF
        private const val COUNTDOWN_TRACK_ON_LIGHT = 0x22000000

        /**
         * Durée de l'agrandissement de la vidéo quand le texte s'efface.
         *
         * Volontairement égale au fondu des zones de texte (voir
         * RollingCaptionZone.FADE_MS) : les deux mouvements doivent se lire
         * comme un seul. Une valeur différente donnerait une image qui
         * s'agrandit après coup, ou un texte qui s'efface sur une image déjà
         * repositionnée — dans les deux cas un à-coup, sur un écran dont
         * toute la règle est que rien ne surprenne Jean.
         */
        private const val VIDEO_RESIZE_MS = 400L

        private const val SCREEN_STATE_PUBLISH_MS = 1_000L
        private const val LAG_PUBLISH_THRESHOLD_SECONDS = 0.5f

        /**
         * L'appel actuellement traité par cet écran, ou null quand aucun appel
         * n'est en cours. Lu par IncomingCallService avant d'afficher quoi que
         * ce soit, pour renvoyer « occupé » à un second proche plutôt que de
         * le laisser attendre dans le vide (voir sa méthode onStartCommand).
         *
         * Une variable de classe, ce qui se justifie mal d'ordinaire — mais
         * l'information vit exactement le temps de cette Activity, qui est en
         * singleTask (une instance au plus) et dans le même processus que le
         * service. La faire transiter par Firestore reviendrait à demander au
         * réseau ce que la mémoire sait déjà, et à retarder d'autant la
         * réponse au second appelant.
         */
        @Volatile
        var handledCallId: String? = null
            private set

        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_PHOTO_PATH = "extra_caller_photo_path"
        const val EXTRA_SIGNAL_RECEIVED_AT = "extra_signal_received_at"
    }
}
