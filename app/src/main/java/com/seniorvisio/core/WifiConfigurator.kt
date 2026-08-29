package com.seniorvisio.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    private const val TAG = "WifiConfigurator"
    private const val CONNECTION_TIMEOUT_MS = 15_000L
    private const val POLL_INTERVAL_MS = 1_000L

    /**
     * `onResult` arrive toujours sur le thread principal, appelé une seule
     * fois : true dès que la tablette est effectivement associée au réseau
     * demandé (pas seulement "configuration enregistrée" — un mot de passe
     * erroné, par exemple, laisse `addNetwork` réussir mais ne connecte
     * jamais), false si rien ne s'est produit avant expiration du délai.
     */
    @Suppress("DEPRECATION")
    fun connect(context: Context, ssid: String, password: String, onResult: (Boolean) -> Unit) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Configuration Wi-Fi demandée mais l'appli n'est pas Device Owner : indisponible sur cet appareil.")
            onResult(false)
            return
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            onResult(false)
            return
        }
        val networkId = try {
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
            }
            val id = wifiManager.addNetwork(config)
            if (id == -1) {
                onResult(false)
                return
            }
            wifiManager.disconnect()
            wifiManager.enableNetwork(id, true)
            wifiManager.reconnect()
            id
        } catch (e: Exception) {
            Log.e(TAG, "Échec de la configuration Wi-Fi", e)
            onResult(false)
            return
        }
        awaitConnection(wifiManager, networkId, onResult)
    }

    /**
     * Un Device Owner reste dispensé de la permission de localisation
     * normalement exigée pour lire le SSID/networkId réel depuis Android 10
     * (restriction pensée pour empêcher un suivi de localisation via le
     * Wi-Fi, sans objet ici) — sondage simple plutôt qu'un
     * ConnectivityManager.NetworkCallback, pas plus fiable pour ce cas précis
     * et plus complexe à mettre en place correctement.
     */
    @Suppress("DEPRECATION")
    private fun awaitConnection(wifiManager: WifiManager, networkId: Int, onResult: (Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val deadline = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
        lateinit var poll: Runnable
        poll = Runnable {
            val info = wifiManager.connectionInfo
            val connected = info != null && info.networkId == networkId && info.supplicantState == SupplicantState.COMPLETED
            when {
                connected -> onResult(true)
                SystemClock.elapsedRealtime() >= deadline -> onResult(false)
                else -> handler.postDelayed(poll, POLL_INTERVAL_MS)
            }
        }
        handler.post(poll)
    }

    /**
     * Format standard des QR Wi-Fi (celui généré par le partage Wi-Fi natif
     * d'Android, et par la quasi-totalité des générateurs de QR Wi-Fi) :
     * `WIFI:T:WPA;S:monreseau;P:monmotdepasse;;` — évite à Jean (ou la
     * personne sur place) de retaper un SSID/mot de passe long ou avec des
     * caractères spéciaux sur l'écran tactile de la tablette.
     */
    fun parseWifiQrPayload(content: String): Pair<String, String>? {
        if (!content.startsWith("WIFI:")) return null
        val fields = mutableMapOf<Char, String>()
        var i = "WIFI:".length
        while (i + 1 < content.length && content[i + 1] == ':') {
            val key = content[i]
            i += 2
            val value = StringBuilder()
            while (i < content.length && content[i] != ';') {
                if (content[i] == '\\' && i + 1 < content.length) i++
                value.append(content[i])
                i++
            }
            fields[key] = value.toString()
            i++ // saute le ';'
        }
        val ssid = fields['S'] ?: return null
        return ssid to (fields['P'] ?: "")
    }
}
