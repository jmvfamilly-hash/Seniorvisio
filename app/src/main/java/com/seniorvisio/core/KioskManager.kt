package com.seniorvisio.core

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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

        dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
        allowHomeButton(dpm, admin)
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
}
