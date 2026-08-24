package com.seniorvisio.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Configure le Wi-Fi de la tablette depuis l'écran de réglages admin — utile
 * en particulier à l'arrivée dans un nouveau lieu (ex. maison de retraite),
 * où le réseau diffère de celui utilisé pendant les tests. Sans accès aux
 * Réglages système une fois en mode kiosque (voir KioskManager), c'est le
 * seul moyen de changer de réseau sans désenrôler la tablette.
 *
 * Repose sur l'ancienne API WifiManager (dépréciée pour les apps classiques
 * depuis Android 10), mais Android continue explicitement de l'autoriser
 * pour les apps Device Owner — seul cas où elle fonctionne encore ici. Ne
 * fait rien sinon (ex. test sur un téléphone personnel non Device Owner).
 */
object WifiConfigurator {

    @Suppress("DEPRECATION")
    fun connect(context: Context, ssid: String, password: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Configuration Wi-Fi demandée mais l'appli n'est pas Device Owner : indisponible sur cet appareil.")
            return false
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return try {
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
            }
            val networkId = wifiManager.addNetwork(config)
            if (networkId == -1) return false
            wifiManager.disconnect()
            wifiManager.enableNetwork(networkId, true)
            wifiManager.reconnect()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Échec de la configuration Wi-Fi", e)
            false
        }
    }

    private const val TAG = "WifiConfigurator"
}
