package com.seniorvisio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.firebase.firestore.ListenerRegistration
import com.seniorvisio.BuildConfig
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.CallTrace
import com.seniorvisio.core.CallerPhotoCache
import com.seniorvisio.core.DeviceStatusReporter
import com.seniorvisio.core.Environnement
import com.seniorvisio.recueil.RafraichisseurFlux
import com.seniorvisio.recueil.RecueilStore
import com.seniorvisio.core.UsageStats
import com.seniorvisio.signaling.CallSignalingClient

/**
 * Service de premier plan permanent : garde une écoute Firestore active en
 * continu (même écran éteint, app en arrière-plan), là où compter sur le
 * cycle de vie de MainActivity ne suffisait pas — dès que l'écran s'éteint
 * ou que l'app passe en arrière-plan, Android coupe le réseau des apps non
 * prioritaires (Doze/App Standby), sauf pour un vrai foreground service.
 *
 * Démarré une fois par MainActivity au lancement (et au boot, voir
 * BootReceiver) ; tourne indéfiniment avec une notification discrète.
 * Dès qu'un appel entrant apparaît dans Firestore, délègue à
 * IncomingCallService qui affiche l'écran plein écran (déjà capable de
 * réveiller l'appareil même verrouillé, voir IncomingCallActivity).
 */
class CallListenerService : LifecycleService() {


    private val signaling = CallSignalingClient()
    private var callListener: ListenerRegistration? = null

    /**
     * Nombre de fois que l'écoute des appels a dû être réarmée depuis le
     * démarrage. Remonté avec le signe de vie : sans ce chiffre, une écoute
     * qui meurt et renaît vingt fois par heure est indiscernable d'une qui
     * n'a jamais bronché.
     */
    @Volatile
    private var échecsDÉcoute = 0

    // Remplace le tableau de bord Headwind (abandonné, voir README > Déploiement) :
    // statut régulier + mise à jour à distance, portés par ce service permanent
    // plutôt qu'un composant séparé, pour ne pas dépendre d'un cycle de vie
    // supplémentaire à maintenir en vie.
    private val statusReporter = DeviceStatusReporter(this)

    /**
     * Installe et vérifie les recueils composés depuis le PWA.
     *
     * Ici plutôt que dans un écran : ce service tourne en permanence, donc
     * l'installation se fait quand personne n'attend — la tablette au repos,
     * la nuit, sur son Wi-Fi. Un téléchargement lancé à l'ouverture d'un écran
     * se produirait au pire moment, celui où quelqu'un regarde.
     */
    private val recueils = RecueilStore(this)

    /**
     * Tient à jour le recueil fait des titres de l'actualité.
     *
     * Éteint en production, faute d'adresse de flux (voir app/build.gradle) :
     * un fil d'information qui apparaîtrait de lui-même sur la tablette de
     * Jean serait un changement d'écran que personne ne lui a demandé.
     */
    val flux = RafraichisseurFlux(this)

    /**
     * Cadence l'affichage des titres sur l'écran d'accueil et réveille la
     * dalle à chaque changement.
     *
     * Porté par ce service et non par l'écran : l'écran naît et meurt, ce
     * service vit en permanence. Un titre doit changer à l'heure dite même
     * quand personne ne regarde — c'est justement la condition pour qu'il soit
     * déjà en place quand la dalle s'allume.
     */
    val actualites = OrdonnanceurActualites(this)

    // Sans ce verrou, Android coupe l'économiseur d'énergie Wi-Fi une fois
    // l'écran éteint : l'association tombe au bout de quelques heures, et la
    // tablette devient injoignable (plus d'appel entrant, plus de mise à jour
    // à distance, plus de signe de vie) jusqu'à ce que quelqu'un la réveille
    // à la main. Le statut foreground du service ne protège que le processus,
    // pas la liaison Wi-Fi elle-même. HIGH_PERF plutôt que FULL (sans effet
    // depuis Android 10) : la tablette est sur secteur en permanence, le
    // surcoût en énergie est sans importance ici.
    private var wifiLock: WifiManager.WifiLock? = null
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            // Rattrape le temps écoulé avant de publier : sans ça, la
            // journée en cours ne comptabiliserait que les changements
            // d'état, jamais les longues plages sans le moindre événement.
            UsageStats.flush()
            UsageStats.pruneOldDays()
            // Même rythme, même raison : sans purge, les cumuls mensuels
            // s'accumuleraient indéfiniment sur une tablette qui tourne des
            // années. Le mois en cours et le précédent sont conservés.
            UsageStats.pruneOldMonths()
            // ═══ DERNIER FILET : L'ÉCOUTE EST-ELLE SEULEMENT VIVANTE ? ═══
            //
            // Le réarmement sur erreur (voir réarmerAprèsErreur) couvre le cas
            // où Firestore PRÉVIENT. Il ne couvre pas celui où le réarmement
            // échoue lui-même, ni celui où l'écoute n'a jamais démarré faute
            // de Firebase au lancement.
            //
            // Ce contrôle-ci ne suppose rien : il regarde s'il y a une écoute,
            // et en recrée une sinon. Toutes les cinq minutes, sur un service
            // qui tourne déjà — le coût est nul, et c'est la différence entre
            // une tablette qui se rétablit seule et une tablette qu'il faut
            // aller redémarrer chez quelqu'un.
            if (callListener == null) {
                Log.w(TAG, "Aucune écoute des appels active : rétablissement")
                échecsDÉcoute++
                startListening()
            }
            // Rattrapage du fil d'information : si un réveil programmé
            // s'est perdu — système qui a repoussé l'alarme, tablette éteinte
            // à l'heure dite — le titre se remet d'aplomb ici. Sans réveiller
            // la dalle : un rattrapage n'est pas un changement.
            actualites.réévaluer(réveillerLÉcran = false)
            surveillerMémoireAuRepos()
            // Le fil d'information vit-il ? Une ligne par battement, et rien
            // quand un titre reste simplement posé (voir VieDuFil).
            com.seniorvisio.core.VieDuFil.publierBilan()
            statusReporter.reportHeartbeat(échecsDÉcoute)
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    /**
     * Éveil et sommeil de l'écran, à la source plutôt que par sondage : ce sont
     * eux qui dessinent la journée de Jean (voir UsageStats), et un sondage
     * toutes les cinq minutes manquerait la moitié des réveils au son, qui ne
     * durent souvent qu'une poignée de secondes.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> UsageStats.noteScreenState(true)
                Intent.ACTION_SCREEN_OFF -> UsageStats.noteScreenState(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        enService = this

        // ═══ LA PREMIÈRE LIGNE DU JOURNAL EST ÉCRITE ICI, ET C'EST VOULU ═══
        //
        // Le journal technique n'était publié que s'il avait changé, et rien
        // n'y écrivait hors appel. Après une mise à jour, le document
        // Firestore gardait donc le texte — en-tête et NUMÉRO DE VERSION
        // compris — laissé par l'application précédente, parfois pendant des
        // jours. On a cherché une tablette non mise à jour qui l'était.
        //
        // Une ligne au démarrage suffit à refermer ce trou : une installation
        // d'APK redémarre toujours le processus, donc le journal repart dans
        // les vingt secondes avec le bon numéro. Et elle vaut par elle-même —
        // savoir qu'un service a redémarré à quatre heures du matin est
        // exactement ce que ce journal devrait dire, et ne disait pas.
        //
        // L'ordre compte : on relit ce que le processus précédent a laissé
        // AVANT d'installer la persistance, qui écrasera ce fichier.
        val journalPrécédent = CallTrace.récupérerJournalPrécédent(this)
        CallTrace.installerPersistance(this, BuildConfig.BUILD_REV)
        CallTrace.record(
            "DÉMARRAGE",
            "${Environnement.étiquetteVersion()} · ${Environnement.description()}",
        )
        if (journalPrécédent != null) {
            CallTrace.record(
                "DÉMARRAGE journal précédent",
                "${journalPrécédent.length} caractères récupérés — publiés à part",
            )
            statusReporter.publierJournalPrécédent(journalPrécédent)
        }

        startForeground(FOREGROUND_ID, buildForegroundNotification())
        acquireWifiLock()
        UsageStats.init(this)
        // ═══ UN ÉTAT, ET PAS SEULEMENT UNE TRANSITION ═══
        //
        // « EXPOSITION réglée » ne part qu'au CHANGEMENT de valeur (voir
        // DeviceStatusReporter). Le réglage vit en préférences, donc il
        // survit au redémarrage : après un relancement, plus une seule ligne
        // ne disait ce que cette tablette est censée présenter, et on
        // cherchait une exposition absente dans un journal qui ne mentionnait
        // jamais l'exposition.
        //
        // Cette ligne-ci répond à la question dans tous les cas, y compris
        // celui — le plus courant — où rien n'a changé depuis le démarrage.
        val expo = AdminConfig(this).recueilOeuvres
        CallTrace.record(
            "EXPOSITION en place",
            if (expo.isBlank()) "aucune — l'accueil présente le fil d'information"
            else "recueil « $expo »",
        )
        recueils.démarrer()
        flux.démarrer()
        actualites.démarrer()
        // L'état de départ ne se déduit d'aucune diffusion : elles ne
        // signalent que les changements. Sans cette lecture initiale, tout le
        // temps précédant le premier basculement serait attribué au sommeil.
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        UsageStats.noteScreenState(powerManager?.isInteractive == true)
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        )
        startListening()
        statusReporter.listenForRemoteCommands()
        heartbeatHandler.post(heartbeatRunnable)
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SeniorVisio:KeepWifiAlive"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /**
     * Android prévient AVANT de tuer. Ce projet n'écoutait pas.
     *
     * ═══ LA SEULE PREUVE QUI TRANCHE ═══
     *
     * Une application qui disparaît sans ligne PLANTAGE n'a pas levé
     * d'exception : elle a été tuée. Reste à savoir par quoi — un plantage
     * natif, ou le système qui reprend la mémoire. Les deux demandent des
     * recherches opposées, et rien ne permettait de choisir.
     *
     * Or le système ne tue pas sans prévenir : il réclame d'abord, par
     * paliers. TRIM_MEMORY_COMPLETE signifie littéralement « vous êtes le
     * prochain sur la liste ». Cette ligne, juste avant un journal qui
     * s'arrête net, est un verdict — et son ABSENCE en est un aussi, qui
     * innocente la mémoire et désigne le code natif.
     *
     * La mesure accompagne le palier : savoir qu'on a été prévenu ne vaut que
     * si l'on sait à quel niveau de consommation ça s'est produit.
     */
    /**
     * La mémoire HORS APPEL, et c'est là que manquait la mesure.
     *
     * ═══ CE QUE LE DERNIER JOURNAL A MONTRÉ ═══
     *
     * Le tas natif était déjà à 904 Mo à la PREMIÈRE mesure d'un appel, sur un
     * processus démarré depuis quatre-vingt-trois minutes, et il n'a plus bougé
     * de tout l'appel. La croissance ne s'était donc pas produite pendant une
     * conversation : elle avait eu lieu avant, pendant que la tablette ne
     * faisait rien de visible.
     *
     * Or la mesure était accrochée au chien de garde média, qui ne tourne que
     * pendant un appel. On regardait exactement là où il ne se passait rien.
     *
     * Ici, sur le battement du service, qui vit en permanence. Cinq minutes est
     * grossier pour saisir un saut, mais suffisant pour dire si la courbe monte
     * au repos — ce qui est la question ouverte. Au-delà de cinquante
     * mégaoctets d'écart entre deux battements, la ventilation par catégorie
     * est payée, et elle nommera ce qui enfle.
     */
    /**
     * Le plafond de plateau de CETTE tablette (voir PART_PLATEAU_DE_LA_RAM).
     *
     * Recalculé à chaque battement plutôt que mémorisé : cela coûte une
     * lecture de /proc/meminfo toutes les cinq minutes, et évite d'avoir à se
     * demander si la valeur a été posée avant que le gestionnaire ne soit
     * disponible — une question dont la mauvaise réponse est un seuil figé à
     * sa valeur de repli, en silence.
     */
    private fun plafondDePlateauMo(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return PLATEAU_PLANCHER_MO
        return runCatching {
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val totalMo = info.totalMem / (1024L * 1024L)
            maxOf((totalMo * PART_PLATEAU_DE_LA_RAM).toLong(), PLATEAU_PLANCHER_MO)
        }.getOrDefault(PLATEAU_PLANCHER_MO)
    }

    private fun surveillerMémoireAuRepos() {
        // ═══ LE RÉSIDENT, ET NON L'ALLOCATEUR ═══
        //
        // Ces seuils ont été réglés contre getNativeHeapAllocatedSize, qui
        // compte ce que l'allocateur a distribué et non ce qui occupe la
        // mémoire vive. Ils se sont donc déclenchés sur un nombre qui ne
        // représentait rien : « +2971 Mo » n'était pas un saut d'empreinte, et
        // la chasse qui a suivi cherchait trois gigaoctets qui n'étaient pas
        // résidents.
        //
        // Repli sur l'allocateur si /proc est illisible : mieux vaut une
        // surveillance imparfaite qu'aucune, et la ligne dira lequel des deux
        // a servi.
        val résident = CallTrace.résidentMo()
        val natifMo = résident
            ?: (android.os.Debug.getNativeHeapAllocatedSize() / (1024L * 1024L))
        val précédent = dernierRésidentAuReposMo
        dernierRésidentAuReposMo = natifMo
        if (précédent < 0) {
            CallTrace.record(
                "REPOS mémoire",
                "${CallTrace.mesureMémoire()} · ${CallTrace.mesureSystème()} · référence",
            )
            return
        }
        val écart = natifMo - précédent
        // ═══ UN PLATEAU HAUT SE DÉCRIT AUSSI, ET PAS SEULEMENT UN SAUT ═══
        //
        // La règle ne payait la ventilation que sur un écart de cinquante
        // mégaoctets. Or le journal a montré le tas natif à 883 mégaoctets,
        // PLAT, pendant plus d'une heure : « écart +0 » à chaque battement.
        // La ventilation ne s'est donc jamais déclenchée, et l'état qui nous
        // intéressait — celui qui faisait crier le système toutes les minutes
        // — n'a jamais été décrit.
        //
        // Une règle qui ne décrit que les transitions reste muette sur l'état
        // installé. C'est exactement la panne qu'on cherchait, et elle était
        // déjà là quand on a commencé à mesurer.
        //
        // La ventilation coûte quelques dizaines de millisecondes ; sur un
        // battement de cinq minutes, et seulement au-dessus de trois cents
        // mégaoctets, c'est sans conséquence.
        // ═══ LE PLATEAU EST UNE PART DE LA TABLETTE, ET NON UN NOMBRE ═══
        //
        // Trois cents mégaoctets veulent dire « un sixième de la machine » sur
        // le banc d'essai, qui en a 1896, et « un vingtième » chez Jean, qui
        // en a 5625. Un seuil absolu aurait donc crié sur l'une et dormi sur
        // l'autre, pour la même situation — et c'est chez Jean que ça compte.
        val plateauMo = plafondDePlateauMo()
        if (écart >= SAUT_REPOS_MO || natifMo >= plateauMo) {
            val motif = if (écart >= SAUT_REPOS_MO) "+$écart Mo depuis le battement précédent"
                        else "plateau (≥ $plateauMo Mo)"
            CallTrace.record(
                "REPOS mémoire SAUT",
                "$motif → $natifMo Mo ${if (résident != null) "résident" else "alloué (résident illisible)"} · " +
                    "${CallTrace.mesureSystème()} · ${CallTrace.ventilationMémoire()}",
            )
        } else {
            val signe = if (écart >= 0) "+" else ""
            CallTrace.record(
                "REPOS mémoire",
                "${CallTrace.mesureMémoire()} · écart $signe$écart Mo · ${CallTrace.mesureSystème()}",
            )
        }
    }

    /**
     * Le dernier relevé de mémoire RÉSIDENTE au repos, pour l'écart.
     *
     * Renommé avec ce qu'il contient : il portait « Natif » et gardait
     * désormais le résident. Un nom qui ment sur son contenu est
     * exactement ce qui a fait lire un chiffre d'allocateur comme une
     * empreinte pendant deux jours.
     */
    private var dernierRésidentAuReposMo = -1L

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        CallTrace.record(
            "MÉMOIRE réclamée",
            "${nomDuPalier(level)} · ${CallTrace.mesureMémoire()} · ${CallTrace.mesureSystème()}",
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        CallTrace.record(
            "MÉMOIRE critique",
            "le système manque de mémoire · ${CallTrace.mesureMémoire()} · ${CallTrace.mesureSystème()}",
        )
    }

    /** Le palier en toutes lettres : un entier nu ne se relit pas six mois plus tard. */
    private fun nomDuPalier(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLET — prochaine application tuée"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODÉRÉ — tuée si la pression continue"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "ARRIÈRE-PLAN — en bout de liste"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "INTERFACE MASQUÉE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "CRITIQUE — au premier plan, le système va tuer des services"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "BAS — au premier plan"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "MODÉRÉ — au premier plan"
        else -> "palier $level"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (callListener == null) startListening()
        return START_STICKY
    }

    /**
     * ═══ UNE ÉCOUTE MORTE DOIT SE RÉARMER, PAS ATTENDRE UN REDÉMARRAGE ═══
     *
     * Un écouteur Firestore qui reçoit une erreur est définitivement terminé.
     * Celui-ci ignorait la sienne, et `onStartCommand` ne le recréait que
     * `if (callListener == null)` — jamais remis à null. Le service continuait
     * donc de tourner avec une écoute morte.
     *
     * Ce service est de PREMIER PLAN : il survit à la fermeture de
     * l'application. Relancer l'application ne le recrée pas, donc ne
     * ressuscitait pas l'écoute. Seul un redémarrage de la tablette y
     * parvenait — exactement ce qui a été constaté sur la tablette d'essai,
     * devenue injoignable jusqu'au redémarrage.
     *
     * Sur la tablette de Jean, ce défaut signifie : plus personne ne peut
     * l'appeler, rien ne l'indique, et il n'a aucun moyen de s'en apercevoir
     * ni de le corriger. C'est la panne la plus grave que ce projet puisse
     * produire.
     *
     * Le réarmement est différé et croissant : une erreur vient souvent d'un
     * réseau absent, et réessayer aussitôt en boucle ne ferait que vider la
     * batterie sans rien rétablir.
     */
    private fun réarmerAprèsErreur(erreur: Exception) {
        Log.e(TAG, "Écoute des appels interrompue par Firestore", erreur)
        callListener?.remove()
        callListener = null
        échecsDÉcoute++
        val délai = (DÉLAI_RÉARMEMENT_MS * échecsDÉcoute).coerceAtMost(DÉLAI_RÉARMEMENT_MAX_MS)
        heartbeatHandler.postDelayed({
            if (callListener == null) {
                Log.i(TAG, "Réarmement de l'écoute des appels (tentative $échecsDÉcoute)")
                startListening()
            }
        }, délai)
    }

    private fun startListening() {
        if (!signaling.isAvailable()) return
        callListener = signaling.listenForRingingCalls(onErreur = ::réarmerAprèsErreur) { callId, callerName, callerPhotoBase64 ->
            // La photo passe par un fichier, jamais par l'extra directement
            // (voir CallerPhotoCache) : au-delà d'une certaine taille, elle
            // fait planter ce démarrage de service avec
            // TransactionTooLargeException, sans aucun écran d'appel affiché.
            val alertIntent = Intent(this, IncomingCallService::class.java).apply {
                putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
                putExtra(IncomingCallService.EXTRA_CALLER_NAME, callerName)
                putExtra(IncomingCallService.EXTRA_CALLER_PHOTO_PATH, CallerPhotoCache.save(this@CallListenerService, callerPhotoBase64))
            }
            startForegroundService(alertIntent)
        }
    }

    override fun onDestroy() {
        UsageStats.flush()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // Jamais enregistré (création interrompue) : sans conséquence.
        }
        callListener?.remove()
        callListener = null
        recueils.arrêter()
        flux.arrêter()
        actualites.arrêter()
        if (enService === this) enService = null
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        super.onDestroy()
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Écoute des appels Senior Visio", NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Senior Visio")
            .setContentText("En attente d'appel")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        /**
         * Le service en cours, pour que l'écran d'accueil s'abonne au fil
         * d'information sans avoir à se lier à lui.
         *
         * Même raison que RecueilStore.actif : ce service est permanent,
         * l'écran est éphémère, et c'est l'éphémère qui emprunte au permanent.
         * Posé au démarrage, retiré à l'arrêt — donc null exactement quand il
         * n'y a rien à emprunter.
         */
        @Volatile
        var enService: CallListenerService? = null
            private set

        private const val TAG = "CallListenerService"
        private const val FOREGROUND_ID = 43
        private const val CHANNEL_ID = "senior_visio_listener"
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * Au-delà de cet écart entre deux battements de cinq minutes, on paie
         * la ventilation par catégorie. Cinquante mégaoctets : un régime sain
         * n'en bouge pas de tant, et la croissance constatée en dépassait
         * plusieurs centaines.
         */
        private const val SAUT_REPOS_MO = 50L

        /**
         * Part de la mémoire de la tablette au-delà de laquelle la ventilation
         * est payée à chaque battement, même si rien ne bouge.
         *
         * ═══ UNE PART, ET NON UN NOMBRE ═══
         *
         * Le seuil valait trois cents mégaoctets en dur. C'est un sixième du
         * banc d'essai, qui a 1896 Mo, et un vingtième de la tablette de Jean,
         * qui en a 5625 : la même situation aurait fait crier l'une et dormir
         * l'autre, et c'est chez Jean que ça compte.
         *
         * Un cinquième : au-dessus, une seule application tient une part de la
         * machine qui mérite d'être décrite, quelle que soit la machine. En
         * dessous, on n'écrit qu'une ligne de relevé.
         *
         * À RECALIBRER avec les premiers relevés de résident : ce seuil est le
         * premier posé contre une mesure qui veut dire quelque chose, et je
         * n'ai pas encore vu un seul chiffre de VmRSS sur ces tablettes. Le
         * dire plutôt que de laisser croire qu'il sort d'une observation.
         */
        private const val PART_PLATEAU_DE_LA_RAM = 0.20

        /**
         * Plancher, pour une tablette minuscule ou une lecture aberrante :
         * en dessous de deux cents mégaoctets résidents, aucune application de
         * visiophonie n'est en difficulté, et payer la ventilation à chaque
         * battement n'apprendrait rien.
         */
        private const val PLATEAU_PLANCHER_MO = 200L

        /**
         * Attente avant de réarmer l'écoute, multipliée par le nombre
         * d'échecs. Une erreur vient souvent d'un réseau absent : réessayer
         * aussitôt en boucle viderait la batterie sans rien rétablir.
         */
        private const val DÉLAI_RÉARMEMENT_MS = 5_000L
        private const val DÉLAI_RÉARMEMENT_MAX_MS = 2 * 60 * 1000L
    }
}
