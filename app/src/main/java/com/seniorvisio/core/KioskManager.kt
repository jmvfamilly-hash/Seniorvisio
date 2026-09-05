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

    private const val TAG = "KioskManager"
    private const val BROWSER_ACCESS_TIMEOUT_MS = 10 * 60 * 1000L
}
