package com.seniorvisio.admin

import android.app.admin.DeviceAdminReceiver

/**
 * Permet à Senior Visio d'être désigné "Device Owner" de la tablette — mode
 * kiosque natif Android (impossible de quitter l'appli), sans dépendre d'un
 * MDM tiers. Remplace Headwind, abandonné après un blocage Knox suite à la
 * suppression d'un appareil côté console sans désenrôlement propre (voir
 * README > Déploiement). Provisionné une seule fois via QR code au tout
 * premier démarrage de la tablette, avant tout compte.
 */
class SeniorVisioDeviceAdminReceiver : DeviceAdminReceiver()
