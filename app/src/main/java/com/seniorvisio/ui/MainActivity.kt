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
import com.seniorvisio.core.AlertVolume
import com.seniorvisio.core.CallTrace
import com.seniorvisio.core.CompanionApps
import com.seniorvisio.core.MiseEnVeille
import com.seniorvisio.core.Environnement
import com.seniorvisio.core.KioskManager
import com.seniorvisio.core.TranscriptionSource
import com.seniorvisio.core.UsageStats
import com.seniorvisio.service.CallListenerService
import com.seniorvisio.recueil.Element
import com.seniorvisio.recueil.RecueilStore
import com.seniorvisio.service.OrdonnanceurPhotos
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
     * La liaison au service permanent.
     *
     * Elle servait à recevoir les paroles de la pièce. Cette écoute est
     * retirée ; la liaison reste pour deux choses que le service seul peut
     * faire : basculer vers Transcription instantanée quand Jean appuie sur
     * « Sous-titres », et savoir que l'écran d'accueil est revenu au premier
     * plan.
     */
    private val roomConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // ═══ ON NE DEMANDE PLUS LA TRANSCRIPTION DE LA PIÈCE ═══
            //
            // Ce rappel posait ici un écouteur de texte, et l'écran affichait
            // ce qui se disait autour de Jean. L'écoute de la pièce est
            // retirée (voir RoomPresenceService.startListening) : il n'y a
            // plus de texte à recevoir, et le demander ouvrirait un moteur
            // que rien n'alimente.
            //
            // La liaison RESTE, et ce n'est pas un vestige : le bouton
            // « Sous-titres » a besoin du service pour basculer, et le retour
            // au premier plan doit lui être signalé.
            roomService = (binder as? RoomPresenceService.LocalBinder)?.getService()
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
        // L'écran revient au premier plan : il redemande la photo du créneau
        // en cours plutôt que d'attendre le prochain changement. C'est ce qui
        // fait qu'une dalle rallumée par un bruit montre déjà la photo du
        // moment au lieu d'un écran vide.
        brancherGalerie()
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
     * Les photos installées, et celle que Jean regarde en ce moment.
     *
     * ═══ POURQUOI L'ÉCRAN TIENT SON PROPRE RANG ═══
     *
     * L'ordonnanceur décide de la photo du créneau ; il ne sait pas, et n'a
     * pas à savoir, que Jean a peut-être fait glisser l'image. Si cet écran se
     * contentait de montrer ce que l'ordonnanceur lui envoie, un geste de Jean
     * serait effacé au prochain battement du service.
     *
     * L'écran garde donc sa propre position. L'ordonnanceur la repose au
     * changement de créneau, et seulement là — ce qui donne exactement le
     * comportement voulu : un choix fait à la main tient, puis la galerie
     * reprend sa route toute seule. Rien à annuler, rien à refermer.
     */
    private var photosGalerie: List<Element> = emptyList()

    /**
     * Le dernier motif de refus journalisé, pour ne pas le répéter.
     *
     * Remis à zéro nulle part exprès : si la panne cesse puis revient à
     * l'identique, la deuxième occurrence n'apprend rien de plus que la
     * première, et cet écran ne vit que le temps qu'il est affiché.
     */
    private var dernierRefus: String? = null
    private var recueilGalerieId: String? = null
    private var rangPhoto = 0

    private fun brancherGalerie() {
        zones.brancherSommeil { endormir() }
        brancherBoutonTranscription()
        // ═══ LE GLISSEMENT VIENT DU PAGER, PLUS DE NOUS ═══
        //
        // Il était détecté à la main par GlissementHorizontal. C'est la
        // visionneuse qui s'en charge désormais, avec l'inertie et le
        // rattrapage de bord d'une vraie bibliothèque — demandé en ces termes :
        // ne pas inventer les interactions tactiles.
        //
        // Ce rappel ne fait donc plus glisser : il ENREGISTRE où Jean s'est
        // arrêté, pour que le prochain changement de créneau ne le ramène pas
        // en arrière.
        zones.brancherRangPhoto { rang ->
            if (rang == rangPhoto) return@brancherRangPhoto
            rangPhoto = rang
            UsageStats.noteGeste(UsageStats.GESTE_PHOTO_SUIVANTE_GLISSE)
            CallTrace.record("ACCUEIL photo main", "photo ${rang + 1}/${photosGalerie.size}")
        }

        val service = CallListenerService.enService
        if (service == null) {
            // Le service n'est pas encore là au tout premier lancement. Rien à
            // faire : il appellera de lui-même dès son démarrage.
            return
        }
        // brancher, et non « observateur = » : la pose seule ne livrait rien
        // tant que le rang n'avait pas bougé, et l'écran restait vide après
        // chaque mise en pause (voir OrdonnanceurPhotos.brancher). C'est
        // aussi ce qui rend tenable la règle « une photo ne rallume pas la
        // dalle » : l'écran qui se rallume reçoit la photo du moment.
        service.galerie.brancher(OrdonnanceurPhotos.Observateur { element, recueilId ->
            if (element == null || recueilId == null) {
                runOnUiThread {
                    photosGalerie = emptyList()
                    zones.masquerPhotos()
                }
                return@Observateur
            }
            val magasin = RecueilStore.actif
            val recueil = magasin?.disponibles()?.firstOrNull { it.id == recueilId }
            val prêts = recueil?.prêts.orEmpty()
            runOnUiThread {
                photosGalerie = prêts
                recueilGalerieId = recueilId
                // L'ordonnanceur impose SA position : c'est un changement de
                // créneau, donc le fil a repris la main sur le choix de Jean.
                rangPhoto = prêts.indexOf(element).coerceAtLeast(0)
                afficherPhotoCourante()
            }
        })
        // Toujours après : brancher livre l'état, réévaluer reprogramme
        // l'alarme et rattrape un réveil perdu. Les deux ne font pas la même
        // chose, et celui-ci ne poussera rien de plus — le rang n'a pas bougé.
        service.galerie.réévaluer()
    }


    /**
     * Éteint la dalle, à la demande de Jean.
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
     * Le bouton « Sous-titres », en haut à gauche.
     *
     * ═══ SANS AUCUNE CONDITION, ET C'EST LA DEMANDE ═══
     *
     * Il passe par forceHandOff, qui ne consulte aucune garde : ni le mode
     * automatique armé, ni une voix détectée, ni la nuit, ni le délai de
     * retenue après un retour. Ces gardes protègent la bascule AUTOMATIQUE
     * d'un aspirateur ou d'un aller-retour sans fin ; elles n'ont aucun sens
     * quand c'est Jean qui demande.
     *
     * ═══ MASQUÉ PLUTÔT QU'INERTE ═══
     *
     * Si « Transcription instantanée » n'est pas installée, le bouton
     * n'apparaît pas. Une cible qui ne fait rien est pire qu'une absence :
     * Jean y revient, conclut que la tablette ne répond plus, et ce n'est pas
     * une conclusion qu'il peut vérifier ni signaler.
     *
     * Réévalué à chaque retour sur l'écran, et non une fois au démarrage :
     * l'application peut être installée pendant la vie du processus, et une
     * tablette qui exige un redémarrage pour voir un bouton apparaître est une
     * tablette qu'il faut aller toucher.
     *
     * ═══ ET S'IL ÉCHOUE, IL LE DIT EN FRANÇAIS ═══
     *
     * Le message vient de forceHandOff, écrit pour un développeur. Il n'est
     * PAS montré tel quel : Jean lirait « bascule refusée : ActivityNotFound »
     * sur son écran. Une phrase unique et lisible, et le détail part au
     * journal, où quelqu'un saura le lire.
     */
    private fun brancherBoutonTranscription() {
        val bouton = findViewById<Button>(R.id.boutonTranscription) ?: return
        bouton.visibility =
            if (CompanionApps.isTranscriptionInstalled(this)) View.VISIBLE else View.GONE
        bouton.setOnClickListener {
            UsageStats.noteGeste(UsageStats.GESTE_TRANSCRIPTION)
            val service = RoomPresenceService.running
            if (service == null) {
                CallTrace.record("BASCULE bouton", "service d'écoute non joignable")
                Toast.makeText(this, MESSAGE_TRANSCRIPTION_INDISPONIBLE, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val échec = service.testHandoff()
            CallTrace.record(
                "BASCULE bouton",
                if (échec == null) "demandée par Jean — partie" else "demandée par Jean — refusée : $échec",
            )
            if (échec != null) {
                Toast.makeText(this, MESSAGE_TRANSCRIPTION_INDISPONIBLE, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Un cran en avant ou en arrière, sans jamais boucler.
     *
     * Pas de rebouclage aux extrémités, volontairement : arriver à la dernière
     * photo et retomber sur la première donne l'impression d'une liste sans
     * fin, où l'on ne sait plus si l'on a tout vu. Le glissement ne donne
     * simplement plus rien, et c'est clair.
     */
    private fun déplacerPhoto(pas: Int) {
        if (photosGalerie.isEmpty()) return
        val nouveau = (rangPhoto + pas).coerceIn(0, photosGalerie.size - 1)
        if (nouveau == rangPhoto) return
        rangPhoto = nouveau
        CallTrace.record("ACCUEIL photo main", "photo ${nouveau + 1}/${photosGalerie.size}")
        afficherPhotoCourante()
    }

    /**
     * Remet la galerie sous les yeux de Jean, au rang courant.
     *
     * ═══ TOUT LE DÉCODAGE A DISPARU D'ICI ═══
     *
     * Cette méthode tenait un exécuteur à fil unique, un compteur de
     * génération, et posait des bitmaps qu'il fallait ensuite recycler à la
     * main. Elle existait sous cette forme à cause d'une panne mesurée : le
     * tas natif passant de 39 à 759 mégaoctets en dix secondes, quand chaque
     * changement d'image lançait son propre décodage.
     *
     * Rien de tout cela n'est nécessaire avec la visionneuse : Coil décode
     * hors du fil principal et n'garde qu'un cache borné, Telephoto ne lit que
     * les tuiles visibles. On passe des FICHIERS, et la question de la mémoire
     * cesse d'être la nôtre.
     *
     * ═══ LA LISTE ENTIÈRE, ET NON LA PHOTO COURANTE ═══
     *
     * C'est le pager qui fait glisser d'une image à l'autre. Lui donner les
     * photos une par une reviendrait à lui retirer ce pour quoi on l'a pris.
     */
    private fun afficherPhotoCourante() {
        val magasin = RecueilStore.actif
        val recueil = magasin?.disponibles()?.firstOrNull { it.id == recueilGalerieId }
        if (magasin == null || recueil == null) {
            zones.masquerPhotos()
            return
        }

        // Les fichiers réellement présents. Un élément déclaré PRÊT dont le
        // fichier a disparu — vidage de cache, élagage mal tombé — rend null
        // ici, et on l'écarte plutôt que de laisser la visionneuse afficher un
        // trou au milieu de la galerie.
        val fichiers = photosGalerie.mapNotNull { magasin.fichier(recueil, it) }
        if (fichiers.isEmpty()) {
            zones.masquerPhotos()
            // ═══ LE SEUL CHEMIN QUI NE DIRAIT RIEN ═══
            //
            // Une galerie installée, un écran vide, et aucune ligne pour
            // l'expliquer : c'est exactement la panne muette que ce journal
            // existe pour éviter.
            //
            // UNE FOIS PAR MOTIF et non par affichage : cette ligne est
            // protégée dans le tampon d'état, et vingt occurrences
            // chasseraient les lignes qu'on vient y chercher.
            //
            // AUCUNE DONNÉE PERSONNELLE : des comptes et un identifiant de
            // recueil, jamais un mot entendu ni un nom de fichier.
            val motif = "aucun fichier présent sur ${photosGalerie.size} élément(s) · " +
                "recueil=$recueilGalerieId"
            if (motif != dernierRefus) {
                dernierRefus = motif
                CallTrace.record("ACCUEIL élément refusé", motif)
            }
            return
        }
        dernierRefus = null

        val rang = rangPhoto.coerceIn(0, fichiers.lastIndex)
        rangPhoto = rang
        zones.afficherPhotos(fichiers, rang)
        CallTrace.record(
            "ACCUEIL photo",
            "${rang + 1}/${fichiers.size} · résident=${CallTrace.résidentMo() ?: "?"} Mo",
        )
    }

    override fun onPause() {
        // Le service est permanent, cet écran ne l'est pas : lui laisser un
        // rappel qui capture l'Activity la retiendrait en mémoire bien après sa
        // fermeture. On se débranche, et on se rebranchera à onResume.
        CallListenerService.enService?.galerie?.observateur = null
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
        // Plus de fil de décodage à arrêter ici : il est parti avec le
        // décodage manuel des photos (voir afficherPhotoCourante). Coil tient
        // le sien, borné, et pour toute l'application.
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

        /**
         * Ce que Jean lit quand la transcription ne peut pas s'ouvrir.
         *
         * Une phrase, la même quelle que soit la cause. Les causes réelles —
         * application absente, écran déjà basculé, service non joignable —
         * partent au journal, où quelqu'un saura les lire. Les montrer ici
         * demanderait à Jean de distinguer des situations sur lesquelles il
         * n'a aucune prise.
         */
        private const val MESSAGE_TRANSCRIPTION_INDISPONIBLE =
            "Les sous-titres ne sont pas disponibles en ce moment"

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
