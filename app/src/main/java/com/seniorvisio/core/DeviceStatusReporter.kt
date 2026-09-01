package com.seniorvisio.core

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.seniorvisio.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remplace le tableau de bord Headwind (abandonné, voir README > Déploiement) :
 * publie un statut régulier dans Firestore (batterie, version installée,
 * dernier signe de vie) et écoute une demande de mise à jour à distance,
 * appliquée silencieusement — seul un Device Owner peut installer un APK
 * sans confirmation manuelle sur l'écran de la tablette.
 */
class DeviceStatusReporter(private val context: Context) {

    private val db get() = FirebaseFirestore.getInstance()
    private val deviceDoc get() = db.document(DEVICE_DOC_PATH)

    /** À appeler périodiquement (voir CallListenerService, déjà un foreground service permanent). */
    fun reportHeartbeat() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPercent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        deviceDoc.set(
            mapOf(
                FIELD_APP_VERSION to BuildConfig.BUILD_REV,
                FIELD_BATTERY_PERCENT to batteryPercent,
                FIELD_COMPANION_APPS to companionAppVersions(),
                FIELD_LAST_HEARTBEAT_AT to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge()
        ).addOnFailureListener { e -> Log.e(TAG, "Échec de l'envoi du signe de vie à Firestore", e) }
    }

    /**
     * Version installée de chaque application compagne, remontée avec le signe
     * de vie. Senior Visio ne maîtrise pas ces versions (c'est Google qui met
     * à jour la transcription) : sans cette remontée, une régression due à une
     * mise à jour tierce ne se découvrirait que par Jean, sur place.
     *
     * Valeur "absent" plutôt qu'omission quand le paquet n'est pas là : c'est
     * une information en soi, et elle se distingue ainsi d'un heartbeat trop
     * ancien pour contenir le champ.
     */
    private fun companionAppVersions(): Map<String, String> =
        CompanionApps.allowedPackages.associateWith { packageName ->
            try {
                context.packageManager.getPackageInfo(packageName, 0).versionName ?: "inconnue"
            } catch (_: PackageManager.NameNotFoundException) {
                "absent"
            }
        }

    /**
     * Écoute une mise à jour demandée à distance (URL de l'APK + version
     * cible, écrites dans Firestore) et l'installe dès qu'elle diffère de la
     * version en cours. À appeler une seule fois au démarrage du service
     * permanent.
     */
    fun listenForRemoteUpdate() {
        deviceDoc.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Écoute Firestore de mise à jour à distance interrompue", error)
                return@addSnapshotListener
            }
            val requestedVersion = snapshot?.getString(FIELD_REQUESTED_VERSION) ?: return@addSnapshotListener
            val apkUrl = snapshot.getString(FIELD_REQUESTED_APK_URL) ?: return@addSnapshotListener
            if (requestedVersion == BuildConfig.BUILD_REV) return@addSnapshotListener
            Log.i(TAG, "Mise à jour à distance détectée : $requestedVersion (version actuelle ${BuildConfig.BUILD_REV})")
            installUpdate(apkUrl)
        }
    }

    private fun installUpdate(apkUrl: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Mise à jour à distance demandée mais l'appli n'est pas Device Owner : impossible d'installer sans confirmation manuelle.")
            return
        }
        Thread {
            try {
                val apkFile = downloadApk(apkUrl)
                silentInstall(apkFile)
            } catch (e: Exception) {
                Log.e(TAG, "Échec du téléchargement/installation de la mise à jour", e)
            }
        }.start()
    }

    private fun downloadApk(apkUrl: String): File {
        val outFile = File(context.cacheDir, "update.apk")
        val connection = URL(apkUrl).openConnection() as HttpURLConnection
        connection.connect()
        connection.inputStream.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }

    private fun silentInstall(apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("update", 0, apkFile.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // Device Owner : aucune confirmation utilisateur affichée, le
            // PendingIntent n'est requis que par la signature de l'API.
            val statusIntent = PendingIntent.getBroadcast(
                context, 0, Intent(context, UpdateStatusReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(statusIntent.intentSender)
        }
    }

    companion object {
        private const val TAG = "DeviceStatusReporter"
        private const val DEVICE_DOC_PATH = "devices/jean_tablet"
        private const val FIELD_APP_VERSION = "appVersion"
        private const val FIELD_BATTERY_PERCENT = "batteryPercent"
        private const val FIELD_COMPANION_APPS = "companionAppVersions"
        private const val FIELD_LAST_HEARTBEAT_AT = "lastHeartbeatAt"
        private const val FIELD_REQUESTED_VERSION = "requestedVersion"
        private const val FIELD_REQUESTED_APK_URL = "requestedApkUrl"
    }
}
