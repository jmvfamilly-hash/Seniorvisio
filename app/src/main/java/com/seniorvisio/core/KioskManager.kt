package com.seniorvisio.core

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.seniorvisio.BuildConfig
import com.seniorvisio.admin.SeniorVisioDeviceAdminReceiver

/**
 * Verrouille l'écran courant en mode kiosque (impossible d'en sortir avec le
 * bouton Récents) si — et seulement si — l'appli est effectivement Device
 * Owner de la tablette. Sans MDM tiers, c'est le seul mécanisme
 * officiellement prévu par Android pour empêcher de quitter l'appli. Ne fait
 * rien si l'appli n'est pas (encore) provisionnée comme Device Owner — ex.
 * avant le premier enrôlement, ou sur un appareil de développement/test —
 * pour ne jamais planter en dehors du déploiement final.
 *
 * L'écran d'accueil (MainActivity) passe en plus par [registerAsHomeApp] :
 * c'est ce qui donne au bouton Accueil un point de chute maîtrisé, et ce qui
 * évite que la tablette retombe sur le lanceur Samsung dès que le mode
 * kiosque lâche pour une raison quelconque (processus tué, mise à jour).
 */
object KioskManager {

    /**
     * @param homeActivity à renseigner uniquement depuis l'écran d'accueil :
     *   cette activité devient alors le lanceur de la tablette. Les autres
     *   écrans (ex. l'appel entrant) se contentent du verrouillage kiosque.
     */
    fun startIfDeviceOwner(activity: Activity, homeActivity: Class<out Activity>? = null) {
        // ═══ LA VARIANTE DE VALIDATION NE VERROUILLE JAMAIS RIEN ═══
        //
        // Le test qui suit — être Device Owner — suffisait tant qu'il n'y
        // avait qu'une tablette : un appareil de développement ne l'est pas,
        // donc rien ne se verrouillait. Il ne suffit plus. La tablette
        // d'essai peut parfaitement être provisionnée Device Owner un jour,
        // pour éprouver l'installation silencieuse ou le provisionnement
        // lui-même — et se retrouverait alors verrouillée sans qu'on l'ait
        // demandé, sur un appareil dont on doit pouvoir sortir.
        //
        // Un refus à la compilation plutôt qu'une condition d'exécution :
        // « pas de kiosque en validation » devient une propriété de la
        // variante, pas un état qui dépend de la façon dont la tablette a
        // été provisionnée.
        if (!BuildConfig.KIOSK_ENABLED) {
            Log.i(TAG, "Variante ${BuildConfig.ENVIRONMENT} : mode kiosque volontairement désactivé.")
            return
        }
        val dpm = activity.getSystemService(Activity.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(activity.packageName)) return
        val admin = ComponentName(activity, SeniorVisioDeviceAdminReceiver::class.java)

        // Annule toute session navigateur encore ouverte (voir
        // grantTemporaryBrowserAccess) : revenir sur cet écran, par n'importe
        // quel chemin, referme la fenêtre de maintenance plutôt que
        // d'attendre son expiration.
        cancelBrowserAccessTimeout()
        dpm.setLockTaskPackages(admin, standardLockTaskPackages(activity))
        allowHomeButton(dpm, admin)
        protectCompanionApps(activity, dpm, admin)
        grantLocationPermissionSilently(activity, dpm, admin)
        if (homeActivity != null) registerAsHomeApp(activity, dpm, admin, homeActivity)

        try {
            activity.startLockTask()
        } catch (_: IllegalArgumentException) {
            // Déjà verrouillé, ou appelé depuis un contexte qui ne le permet pas
            // (ex. Activity non au premier plan) — sans conséquence, le prochain
            // écran qui appelle startIfDeviceOwner() réessaiera.
        }
    }

    /**
     * Réactive le bouton Accueil, désactivé par défaut en mode kiosque.
     * Nécessaire dès qu'une application compagne peut être lancée depuis
     * Senior Visio (transcription, photos...) : c'est le seul chemin de retour
     * compréhensible pour Jean, et il est fiable puisque l'accueil est
     * précisément Senior Visio (voir registerAsHomeApp).
     *
     * GLOBAL_ACTIONS (menu du bouton Marche/Arrêt) est conservé au passage :
     * il est actif par défaut tant qu'on n'appelle pas setLockTaskFeatures, et
     * le retirer priverait un intervenant sur place du seul moyen d'éteindre
     * proprement la tablette.
     */
    private fun allowHomeButton(dpm: DevicePolicyManager, admin: ComponentName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        dpm.setLockTaskFeatures(
            admin,
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        )
    }


    /**
     * Accorde silencieusement la localisation approximative (voir
     * WeatherClient, qui l'utilise pour la météo de l'écran d'accueil), sans
     * jamais passer par la popup système habituelle : en Device Owner,
     * setPermissionGrantState l'accorde directement. Sans ça, il faudrait
     * compter sur un appui manuel sur "Autoriser" au premier lancement après
     * mise à jour — que le mode kiosque (lock task) empêche parfois
     * d'afficher, laissant la météo indéfiniment absente sans recours.
     *
     * Silencieux en cas d'échec (ex. appareil non Device Owner en test) :
     * la fonction se contente alors de rester invisible, comme prévu par
     * WeatherClient quand la permission manque.
     */
    private fun grantLocationPermissionSilently(
        activity: Activity,
        dpm: DevicePolicyManager,
        admin: ComponentName,
    ) {
        try {
            dpm.setPermissionGrantState(
                admin,
                activity.packageName,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Impossible d'accorder la localisation automatiquement", e)
        }
    }

    /**
     * Déclare Senior Visio comme lanceur de la tablette, sans que le choix
     * soit jamais redemandé à Jean (addPersistentPreferredActivity, réservé au
     * Device Owner). Le filtre déclaré ici doit correspondre à l'intent-filter
     * CATEGORY_HOME du manifeste, sinon Android continue de proposer le
     * lanceur d'origine.
     *
     * Les préférences existantes de ce paquet sont effacées d'abord : sans ça,
     * chaque lancement empilerait une association supplémentaire.
     */
    private fun registerAsHomeApp(
        activity: Activity,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        homeActivity: Class<out Activity>,
    ) {
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
        dpm.addPersistentPreferredActivity(
            admin, homeFilter, ComponentName(activity, homeActivity)
        )
    }

    /**
     * Empêche la désinstallation des applications compagnes. Sans ça, une
     * fausse manœuvre suffirait à faire disparaître la transcription de la
     * tablette, avec pour seul symptôme un bouton qui ne fait plus rien —
     * et aucun moyen de la réinstaller à distance, l'appareil n'ayant pas de
     * compte Google.
     *
     * Silencieux si le paquet est absent : la tablette de Jean l'a
     * préinstallée, mais un appareil de test n'y est pas tenu.
     */
    private fun protectCompanionApps(
        activity: Activity,
        dpm: DevicePolicyManager,
        admin: ComponentName,
    ) {
        CompanionApps.allowedPackages.forEach { packageName ->
            try {
                dpm.setUninstallBlocked(admin, packageName, true)
            } catch (_: IllegalArgumentException) {
                // Paquet non installé sur cet appareil : rien à protéger.
            }
        }
    }

    private fun standardLockTaskPackages(context: Context): Array<String> =
        arrayOf(context.packageName) + CompanionApps.allowedPackages

    private val handler = Handler(Looper.getMainLooper())
    private var browserAccessTimeout: Runnable? = null

    /**
     * Ouvre une fenêtre de maintenance temporaire autorisant un navigateur en
     * mode kiosque, pour la connexion initiale (ou une reconnexion) au réseau
     * de la résidence quand le portail ne se prête pas à l'écran captif
     * intégré (voir AdminSettingsActivity.showCaptivePortal, limité à une
     * simple WebView).
     *
     * Volontairement temporaire et jamais permanent : whitelister un
     * navigateur en continu viderait le mode kiosque de son sens, Jean se
     * retrouvant avec accès à tout le web depuis la liste des applications
     * récentes. La fenêtre se referme d'elle-même après [timeoutMs] — au cas
     * où l'admin reparte sans repasser par Senior Visio — et immédiatement
     * dès le retour sur l'écran d'accueil (voir startIfDeviceOwner).
     *
     * Ne prend qu'un Context (pas une Activity) : setLockTaskPackages ne
     * dépend d'aucun cycle de vie d'écran, ce qui permet à l'expiration de
     * révoquer l'accès même si l'écran d'origine a entre-temps disparu.
     */
    fun grantTemporaryBrowserAccess(
        context: Context,
        browserPackage: String,
        timeoutMs: Long = BROWSER_ACCESS_TIMEOUT_MS,
    ) {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) return
        val admin = ComponentName(appContext, SeniorVisioDeviceAdminReceiver::class.java)

        dpm.setLockTaskPackages(admin, standardLockTaskPackages(appContext) + browserPackage)
        Log.i(TAG, "Accès navigateur temporaire accordé à $browserPackage pour ${timeoutMs}ms")

        cancelBrowserAccessTimeout()
        val timeout = Runnable {
            Log.i(TAG, "Fenêtre de maintenance expirée, accès navigateur révoqué")
            revokeTemporaryBrowserAccess(appContext)
        }
        browserAccessTimeout = timeout
        handler.postDelayed(timeout, timeoutMs)
    }

    /** À appeler quand l'admin a terminé, sans attendre l'expiration — voir AdminSettingsActivity. */
    fun revokeTemporaryBrowserAccess(context: Context) {
        cancelBrowserAccessTimeout()
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) return
        val admin = ComponentName(appContext, SeniorVisioDeviceAdminReceiver::class.java)
        dpm.setLockTaskPackages(admin, standardLockTaskPackages(appContext))
    }

    private fun cancelBrowserAccessTimeout() {
        browserAccessTimeout?.let { handler.removeCallbacks(it) }
        browserAccessTimeout = null
    }

    private var fullAccessTimeout: Runnable? = null

    /**
     * Retire ENTIÈREMENT la protection de la tablette, pour un intervenant sur
     * place qui a besoin d'installer une application ou de changer un réglage
     * Android que Senior Visio n'expose pas — l'accès navigateur temporaire
     * n'ouvre qu'un navigateur ; celui-ci rend la tablette à elle-même.
     *
     * ═══ DEUX CHOSES À DÉFAIRE, PAS UNE ═══
     *
     * Sortir du mode kiosque (stopLockTask) ne suffit pas : Senior Visio reste
     * déclaré lanceur exclusif de l'appareil (addPersistentPreferredActivity,
     * voir registerAsHomeApp), donc le bouton Accueil rouvrirait Senior Visio
     * au lieu du vrai lanceur. Les deux protections doivent tomber ensemble
     * pour que la tablette redevienne un appareil Android ordinaire.
     *
     * ═══ TOUJOURS TEMPORAIRE, ET REVENANT TOUT SEUL ═══
     *
     * Un accès complet permanent n'aurait plus aucun sens : Jean se
     * retrouverait un jour sur un écran de réglages qu'il n'a pas demandé, ou
     * sur le lanceur d'origine, sans le moindre moyen de revenir seul à sa
     * tablette. Deux chemins de retour :
     *
     *  - l'intervenant relance Senior Visio lui-même (son icône reste dans le
     *    tiroir d'applications) — startIfDeviceOwner s'exécute à son
     *    ouverture normale (onCreate/onResume) et reverrouille tout ;
     *  - à défaut, [timeoutMs] plus tard, CETTE fonction ramène Senior Visio
     *    au premier plan de force (voir revokeTemporaryFullAccess) — un
     *    Device Owner reste autorisé à démarrer une activité depuis
     *    l'arrière-plan, exactement comme un appel entrant le fait déjà
     *    (voir IncomingCallService.launchAlertScreen).
     *
     * @param homeActivity la même classe que celle enregistrée comme accueil
     *   (voir startIfDeviceOwner) : c'est elle qui sera rappelée au premier
     *   plan pour reverrouiller, qu'on soit revenu seul ou non.
     */
    fun grantTemporaryFullAccess(
        activity: Activity,
        homeActivity: Class<out Activity>,
        timeoutMs: Long = FULL_ACCESS_TIMEOUT_MS,
    ) {
        val dpm = activity.getSystemService(Activity.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return
        if (!dpm.isDeviceOwnerApp(activity.packageName)) return
        val admin = ComponentName(activity, SeniorVisioDeviceAdminReceiver::class.java)

        // Le navigateur temporaire n'a plus d'objet dès lors que tout est
        // ouvert — et le laisser actif compliquerait le retour (il faudrait
        // se rappeler de fermer les deux fenêtres séparément).
        cancelBrowserAccessTimeout()

        dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
        try {
            activity.stopLockTask()
        } catch (_: IllegalStateException) {
            // Pas verrouillée pour commencer (déjà en accès complet, ou
            // variante de validation) : rien à défaire.
        }

        Log.i(TAG, "Accès complet accordé pour ${timeoutMs}ms")
        CallTrace.record("ADMIN accès complet", "accordé pour ${timeoutMs / 60_000} min")

        val appContext = activity.applicationContext
        cancelFullAccessTimeout()
        val timeout = Runnable {
            Log.i(TAG, "Fenêtre d'accès complet expirée, reverrouillage forcé")
            CallTrace.record("ADMIN accès complet", "délai écoulé — reverrouillage forcé")
            revokeTemporaryFullAccess(appContext, homeActivity)
        }
        fullAccessTimeout = timeout
        handler.postDelayed(timeout, timeoutMs)
    }

    /**
     * Ramène Senior Visio au premier plan pour qu'il reverrouille la
     * tablette (voir startIfDeviceOwner, appelé par [homeActivity] à sa
     * reprise). Appelée à l'expiration du délai, mais aussi si l'intervenant
     * relance l'application par un autre chemin que son icône — un appel
     * qu'on ne s'attend pas à voir échouer peut toujours l'être sans risque.
     */
    fun revokeTemporaryFullAccess(context: Context, homeActivity: Class<out Activity>) {
        cancelFullAccessTimeout()
        context.startActivity(
            Intent(context, homeActivity).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        )
    }

    private fun cancelFullAccessTimeout() {
        fullAccessTimeout?.let { handler.removeCallbacks(it) }
        fullAccessTimeout = null
    }

    private const val TAG = "KioskManager"
    private const val BROWSER_ACCESS_TIMEOUT_MS = 10 * 60 * 1000L
    private const val FULL_ACCESS_TIMEOUT_MS = 60 * 60 * 1000L
}
