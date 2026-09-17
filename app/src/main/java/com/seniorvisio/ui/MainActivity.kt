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
import com.seniorvisio.core.CallTrace
import com.seniorvisio.core.CommandesVocales
import com.seniorvisio.core.MiseEnVeille
import com.seniorvisio.core.Environnement
import com.seniorvisio.core.KioskManager
import com.seniorvisio.core.TranscriptionSource
import com.seniorvisio.service.CallListenerService
import com.seniorvisio.recueil.Element
import com.seniorvisio.recueil.RecueilStore
import com.seniorvisio.recueil.Rendu
import com.seniorvisio.recueil.renduPour
import com.seniorvisio.service.OrdonnanceurActualites
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
            // Dit que l'écoute a été DEMANDÉE. Sans cette ligne, « aucune
            // commande ne marche » ne distingue pas « le moteur ne rend rien »
            // de « rien n'a jamais été lancé » — deux pannes qui se cherchent
            // à des endroits opposés.
            CallTrace.record(
                "VOIX écoute",
                "transcription de la pièce demandée · commandes=" +
                    if (adminConfig.commandesVocalesActives) "actives" else "éteintes",
            )
            service.startRoomTranscription(
                onText = { text, isFinal, fromJean ->
                    runOnUiThread {
                        // La transcription de la pièce est mise de côté : c'est
                        // sa zone que le fil d'information occupe désormais
                        // (voir AdminConfig.transcriptionPieceAffichee). Le
                        // réglage est relu à chaque phrase plutôt que mémorisé
                        // au démarrage, pour qu'un retour en arrière prenne
                        // effet sans relancer l'application.
                        // ═══ LES COMMANDES D'ABORD, ET SEULEMENT SUR DU
                        //     TEXTE DÉFINITIF ═══
                        //
                        // Avant le filtre d'affichage : les commandes doivent
                        // marcher même quand la transcription de la pièce est
                        // masquée, ce qui est le réglage par défaut depuis que
                        // le fil d'information occupe sa place. Placées après,
                        // elles n'auraient jamais été atteintes.
                        //
                        // ═══ PROVISOIRE COMPRIS, ET C'EST UN CORRECTIF ═══
                        //
                        // Ce traitement n'acceptait que les résultats marqués
                        // DÉFINITIFS, pour éviter qu'un moteur corrigeant sa
                        // phrase en route — « suit… », « suivant »,
                        // « suivante » — ne déclenche trois fois la même
                        // commande.
                        //
                        // C'était se rendre dépendant d'un drapeau que rien
                        // n'oblige le moteur de la pièce à lever. Selon le
                        // moteur réglé par l'administrateur, un flux continu
                        // peut ne produire que des résultats provisoires : les
                        // commandes n'étaient alors JAMAIS atteintes, sans que
                        // rien ne le signale.
                        //
                        // La répétition est désormais empêchée là où elle se
                        // produit — un délai de garde après chaque commande
                        // reconnue (voir traiterCommandeVocale) — plutôt qu'en
                        // refusant une catégorie entière de résultats.
                        traiterCommandeVocale(text)
                        if (adminConfig.transcriptionPieceAffichee && !zones.actualiteAffichee) {
                            zones.submitTranscription(TranscriptionSource.ROOM, text, isFinal, fromJean)
                        }
                    }
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
    /** Ce que réappliquerPoliceSiChangée a posé la dernière fois. */
    private var policeAppliquée: com.seniorvisio.core.PoliceSenior? = null

    private fun applyCaptionErgonomics() {
        zones.setVisibleLines(adminConfig.captionVisibleLines)
        zones.setScrollSpeedDpPerSec(adminConfig.captionScrollSpeedDp.toFloat())
        zones.setClearDelaySeconds(adminConfig.captionClearDelaySeconds)
        // Relu à chaque battement comme les autres réglages d'ergonomie :
        // l'administrateur qui bouge le curseur à distance doit en voir l'effet
        // sur la tablette dans la seconde, et surtout PENDANT un appel — c'est
        // là qu'il juge s'il en veut plus ou moins. Les valeurs identiques sont
        // ignorées en aval, la relecture ne coûte donc rien.
        zones.setCaptionLineSpacing(adminConfig.captionInterligne)
        // La police aussi, et pour la même raison que tout le reste ici : le
        // choix se fait depuis le PWA pendant que l'écran est déjà affiché.
        // L'appliquer au seul gonflage revenait à n'en tenir compte qu'au
        // redémarrage suivant.
        policeAppliquée = réappliquerPoliceSiChangée(policeAppliquée)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Juste après le gonflage, avant que quoi que ce soit ne soit mesuré :
        // la police et l'interligne changent la hauteur des textes, donc toute
        // mise en page calculée avant serait à refaire.
        appliquerMiseEnFormeSenior()
        hideNavigationBar()
        applyWakeOnSoundRequest(intent)

        val textBuildRev = findViewById<TextView>(R.id.textBuildRev)
        textBuildRev.text = Environnement.étiquetteVersion()
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
        // Senior Visio est de nouveau devant : si la tablette était basculée
        // sur Transcription instantanée, elle ne l'est plus — que ce soit par
        // le bouton Accueil ou par un appel qui a pris l'écran. Sans ce signal,
        // le bandeau de retour continuerait de flotter par-dessus, et le micro
        // ne serait jamais repris (voir RoomHandoffController).
        RoomPresenceService.running?.noteBackOnHomeScreen()
        // L'écran revient au premier plan : il redemande le titre du créneau
        // en cours plutôt que d'attendre le prochain changement. C'est ce qui
        // fait qu'une dalle allumée par un bruit, ou par Jean lui-même, montre
        // déjà l'actualité de l'heure au lieu d'un écran vide.
        brancherActualites()
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

    /**
     * S'abonne au fil d'information tenu par le service permanent.
     *
     * Le décodage de la vignette part sur un fil à part : une image décodée
     * sur le fil principal fait sauter l'animation des zones, et sur cette
     * tablette-ci cela se voit.
     */
    /**
     * Les titres installés, et celui que Jean regarde en ce moment.
     *
     * ═══ POURQUOI L'ÉCRAN TIENT SON PROPRE RANG ═══
     *
     * L'ordonnanceur décide du titre de l'heure ; il ne sait pas, et n'a pas à
     * savoir, que Jean a peut-être touché un bouton. Si cet écran se contentait
     * de montrer ce que l'ordonnanceur lui envoie, un geste de Jean serait
     * effacé au prochain battement du service.
     *
     * L'écran garde donc sa propre position. L'ordonnanceur la repose au
     * changement de créneau, et seulement là — ce qui donne exactement le
     * comportement voulu : un choix fait à la main tient, puis le fil reprend
     * sa route tout seul. Rien à annuler, rien à refermer.
     */
    private var titresActualite: List<Element> = emptyList()
    private var recueilActualiteId: String? = null
    private var rangActualite = 0

    private fun brancherActualites() {
        zones.brancherSommeil { endormir() }
        zones.brancherNavigationActualite(
            surPrécédent = { déplacerActualite(-1) },
            surSuivant = { déplacerActualite(+1) },
            // Glissement vers la gauche = on avance, comme on tourne une page.
            surSwipe = { versLAvant -> déplacerActualite(if (versLAvant) +1 else -1) },
        )

        val service = CallListenerService.enService
        if (service == null) {
            // Le service n'est pas encore là au tout premier lancement. Rien à
            // faire : il appellera de lui-même dès son démarrage.
            return
        }
        service.actualites.observateur = OrdonnanceurActualites.Observateur { element, recueilId ->
            if (element == null || recueilId == null) {
                runOnUiThread {
                    titresActualite = emptyList()
                    zones.masquerActualite()
                }
                return@Observateur
            }
            val magasin = RecueilStore.actif
            val recueil = magasin?.disponibles()?.firstOrNull { it.id == recueilId }
            val prêts = recueil?.prêts.orEmpty()
            runOnUiThread {
                titresActualite = prêts
                recueilActualiteId = recueilId
                // L'ordonnanceur impose SA position : c'est un changement de
                // créneau, donc le fil a repris la main sur le choix de Jean.
                rangActualite = prêts.indexOf(element).coerceAtLeast(0)
                afficherActualiteCourante()
            }
        }
        service.actualites.réévaluer(réveillerLÉcran = false)
    }

    /**
     * Exécute une commande dite à voix haute, s'il y en a une.
     *
     * ═══ TROIS MOTS, ET AUCUN POUVOIR NOUVEAU ═══
     *
     * Chacune de ces commandes double un bouton présent à l'écran. C'est la
     * même règle que pour le glissement du doigt, et pour la même raison : une
     * fonction qui n'existerait qu'à la voix serait invisible — rien à l'écran
     * ne dirait qu'elle existe — et perdue le jour où la reconnaissance
     * bronche, ce qui arrive.
     *
     * Débrayable depuis le panneau d'administration, et il le faut : une
     * commande vocale qui se déclencherait à tort pendant les visites se
     * manifesterait par « l'écran fait n'importe quoi », sans que personne
     * puisse relier l'effet à sa cause. Pouvoir l'éteindre à distance est ce
     * qui permet de trancher en trente secondes.
     */
    private fun traiterCommandeVocale(texte: String) {
        if (!adminConfig.commandesVocalesActives) {
            noterÉcouteVocale(texte, "réglage éteint")
            return
        }
        val commande = CommandesVocales.détecter(texte)
        if (commande == null) {
            noterÉcouteVocale(texte, "aucune commande")
            return
        }
        // Délai de garde : un moteur rend la même phrase plusieurs fois en la
        // corrigeant, et « suivant » arrive alors deux ou trois fois de suite.
        // C'est ici que la répétition se traite, et non en refusant les
        // résultats provisoires — ce qui rendait les commandes tributaires
        // d'un drapeau que le moteur n'est pas obligé de lever.
        val maintenant = System.currentTimeMillis()
        if (commande == dernièreCommande && maintenant - dernièreCommandeMs < GARDE_COMMANDE_MS) {
            noterÉcouteVocale(texte, "répétition ignorée")
            return
        }
        dernièreCommande = commande
        dernièreCommandeMs = maintenant
        CallTrace.record("VOIX commande", commande.name.lowercase())
        when (commande) {
            CommandesVocales.Commande.SUIVANT -> déplacerActualite(+1)
            CommandesVocales.Commande.PRECEDENT -> déplacerActualite(-1)
            CommandesVocales.Commande.SOMMEIL -> endormir()
        }
    }

    private var dernièreCommande: CommandesVocales.Commande? = null
    private var dernièreCommandeMs = 0L

    /**
     * Dit que de la parole est arrivée jusqu'ici, et ce qu'on en a fait.
     *
     * ═══ SANS UN SEUL MOT PRONONCÉ ═══
     *
     * CallTrace ne contient AUCUNE donnée personnelle, et cette garantie tient
     * à ce qu'aucun texte reconnu n'y entre jamais — pas à une consigne qu'on
     * se donne. Cette ligne compte donc des mots et des signes ; elle n'en
     * transporte aucun.
     *
     * C'est déjà ce qu'il faut pour trancher entre trois causes qui demandent
     * des recherches opposées : si aucune ligne n'apparaît, la transcription de
     * la pièce ne produit rien et c'est le moteur qu'il faut regarder ; si des
     * lignes apparaissent sans jamais de commande, le texte arrive mais ne
     * correspond pas, et ce sont les formulations qu'il faut revoir ; si le
     * réglage est éteint, la ligne le dit en toutes lettres.
     *
     * Espacé de deux secondes : un moteur en flux continu rend plusieurs
     * résultats par seconde, et le journal n'a pas à être noyé par son propre
     * instrument.
     */
    private fun noterÉcouteVocale(texte: String, issue: String) {
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernièreÉcouteNotéeMs < ÉCOUTE_NOTÉE_MS) return
        dernièreÉcouteNotéeMs = maintenant
        val mots = texte.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        CallTrace.record("VOIX entendue", "$mots mot(s), ${texte.length} signe(s) — $issue")
    }

    private var dernièreÉcouteNotéeMs = 0L

    /**
     * Éteint la dalle, à la demande de Jean — bouton ou voix.
     *
     * Le compteur d'inactivité est remis à zéro AVANT : sans ça, le battement
     * qui garde l'écran allumé tant qu'il reste du texte (voir
     * screenAwakeTicker) reposerait son drapeau à la seconde suivante, et
     * l'écran se rallumerait tout seul. Le bouton aurait alors l'air de ne rien
     * faire, ce qui est pire que de ne pas exister.
     */
    private fun endormir() {
        textGoneSinceMs = 1L
        zones.clearTranscriptions()
        MiseEnVeille.endormir(this, window)
    }

    /**
     * Un cran en avant ou en arrière, sans jamais boucler.
     *
     * Pas de rebouclage aux extrémités, volontairement : arriver au dernier
     * titre et retomber sur le premier donne l'impression d'une liste sans fin,
     * où l'on ne sait plus si l'on a tout vu. Le bouton se grise, et c'est
     * clair.
     */
    private fun déplacerActualite(pas: Int) {
        if (titresActualite.isEmpty()) return
        val nouveau = (rangActualite + pas).coerceIn(0, titresActualite.size - 1)
        if (nouveau == rangActualite) return
        rangActualite = nouveau
        CallTrace.record("ACCUEIL actualité main", "titre ${nouveau + 1}/${titresActualite.size}")
        afficherActualiteCourante()
    }

    private fun afficherActualiteCourante() {
        val element = titresActualite.getOrNull(rangActualite) ?: return
        val magasin = RecueilStore.actif
        val recueil = magasin?.disponibles()?.firstOrNull { it.id == recueilActualiteId }
        val fichier = if (recueil != null) magasin.fichier(recueil, element) else null
        Thread {
            val rendu = renduPour(element.type).préparer(element, fichier)
            runOnUiThread {
                when (rendu) {
                    is Rendu.Texte -> {
                        zones.afficherActualite(rendu.texte, rendu.vignette, rendu.origine)
                        zones.majNavigationActualite(rangActualite, titresActualite.size)
                    }
                    else -> zones.masquerActualite()
                }
            }
        }.apply { isDaemon = true; name = "SeniorVisio-ActualiteAccueil" }.start()
    }

    override fun onPause() {
        // Le service est permanent, cet écran ne l'est pas : lui laisser un
        // rappel qui capture l'Activity la retiendrait en mémoire bien après sa
        // fermeture. On se débranche, et on se rebranchera à onResume.
        CallListenerService.enService?.actualites?.observateur = null
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
         * Après une commande reconnue, la même est ignorée pendant ce délai.
         *
         * Deux secondes : un moteur qui corrige sa phrase rend ses versions
         * successives en quelques centaines de millisecondes, et personne ne
         * dit « suivant » deux fois de suite en moins de deux secondes en
         * s'attendant à avancer de deux crans.
         */
        private const val GARDE_COMMANDE_MS = 2_000L

        /** Espacement des lignes de diagnostic vocal, pour ne pas noyer le journal. */
        private const val ÉCOUTE_NOTÉE_MS = 2_000L
    }
}
