package com.seniorvisio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
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
import com.seniorvisio.core.CallerPhotoCache
import com.seniorvisio.core.DeviceStatusReporter
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
    private val flux = RafraichisseurFlux(this)

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
        startForeground(FOREGROUND_ID, buildForegroundNotification())
        acquireWifiLock()
        UsageStats.init(this)
        recueils.démarrer()
        flux.démarrer()
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
        private const val TAG = "CallListenerService"
        private const val FOREGROUND_ID = 43
        private const val CHANNEL_ID = "senior_visio_listener"
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * Attente avant de réarmer l'écoute, multipliée par le nombre
         * d'échecs. Une erreur vient souvent d'un réseau absent : réessayer
         * aussitôt en boucle viderait la batterie sans rien rétablir.
         */
        private const val DÉLAI_RÉARMEMENT_MS = 5_000L
        private const val DÉLAI_RÉARMEMENT_MAX_MS = 2 * 60 * 1000L
    }
}
