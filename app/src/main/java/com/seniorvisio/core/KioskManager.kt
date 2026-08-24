package com.seniorvisio.core

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.seniorvisio.admin.SeniorVisioDeviceAdminReceiver

/**
 * Verrouille l'écran courant en mode kiosque (impossible d'en sortir avec le
 * bouton Accueil/Récents) si — et seulement si — l'appli est effectivement
 * Device Owner de la tablette. Sans MDM tiers, c'est le seul mécanisme
 * officiellement prévu par Android pour empêcher de quitter l'appli. Ne fait
 * rien si l'appli n'est pas (encore) provisionnée comme Device Owner — ex.
 * avant le premier enrôlement, ou sur un appareil de développement/test —
 * pour ne jamais planter en dehors du déploiement final.
 */
object KioskManager {
    fun startIfDeviceOwner(activity: Activity) {
        val dpm = activity.getSystemService(Activity.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(activity.packageName)) return
        val admin = ComponentName(activity, SeniorVisioDeviceAdminReceiver::class.java)
        dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
        try {
            activity.startLockTask()
        } catch (_: IllegalArgumentException) {
            // Déjà verrouillé, ou appelé depuis un contexte qui ne le permet pas
            // (ex. Activity non au premier plan) — sans conséquence, le prochain
            // écran qui appelle startIfDeviceOwner() réessaiera.
        }
    }
}
