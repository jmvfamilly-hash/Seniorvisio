package com.seniorvisio.core

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.seniorvisio.BuildConfig
import com.seniorvisio.admin.SeniorVisioDeviceAdminReceiver
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
    fun listenForRemoteCommands() {
        // Un seul listener pour toutes les commandes à distance : chaque
        // addSnapshotListener sur ce document est facturé une lecture à chaque
        // écriture, y compris celles que la tablette fait elle-même.
        deviceDoc.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Écoute Firestore des commandes à distance interrompue", error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            handleRemoteUpdate(snapshot)
            handleTranscriptionReset(snapshot)
        }
    }

    private fun handleRemoteUpdate(snapshot: DocumentSnapshot) {
        val requestedVersion = snapshot.getString(FIELD_REQUESTED_VERSION) ?: return
        val apkUrl = snapshot.getString(FIELD_REQUESTED_APK_URL) ?: return
        if (requestedVersion == BuildConfig.BUILD_REV) return
        Log.i(TAG, "Mise à jour à distance détectée : $requestedVersion (version actuelle ${BuildConfig.BUILD_REV})")
        installUpdate(apkUrl)
    }

    /**
     * Réinitialise l'application de transcription sur demande d'un proche
     * depuis le PWA. Seul moyen de réparer à distance un réglage déréglé dans
     * une application tierce : son interface échappe à Senior Visio, et rien
     * ne permet d'y imposer une configuration depuis l'extérieur. L'appareil
     * repart donc sur les valeurs par défaut de Google — ce qui efface au
     * passage les transcriptions qu'elle conserve.
     *
     * La demande porte un horodatage plutôt qu'un booléen : sans ça, il
     * faudrait remettre le champ à false après chaque exécution, et un échec
     * d'écriture laisserait la tablette en boucle de réinitialisation.
     * L'horodatage traité est mémorisé localement, donc chaque demande n'agit
     * qu'une fois, même après un redémarrage.
     */
    private fun handleTranscriptionReset(snapshot: DocumentSnapshot) {
        val requestedAtMs = snapshot.getTimestamp(FIELD_RESET_TRANSCRIPTION_AT)?.toDate()?.time ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (requestedAtMs <= prefs.getLong(KEY_LAST_RESET_HANDLED_AT, 0L)) return
        // Mémorisé avant d'agir, pas après : un échec doit rester visible dans
        // le compte rendu, pas relancer indéfiniment la réinitialisation.
        prefs.edit().putLong(KEY_LAST_RESET_HANDLED_AT, requestedAtMs).apply()
        Log.i(TAG, "Réinitialisation demandée pour ${CompanionApps.TRANSCRIPTION}")
        clearCompanionAppData(CompanionApps.TRANSCRIPTION)
    }

    private fun clearCompanionAppData(packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            reportResetResult(false, "Android trop ancien pour la réinitialisation à distance")
            return
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            reportResetResult(false, "L'appli n'est pas Device Owner")
            return
        }
        val admin = ComponentName(context, SeniorVisioDeviceAdminReceiver::class.java)
        try {
            dpm.clearApplicationUserData(admin, packageName, context.mainExecutor) { pkg, succeeded ->
                Log.i(TAG, "Réinitialisation de $pkg : ${if (succeeded) "réussie" else "échouée"}")
                reportResetResult(succeeded, if (succeeded) "" else "Refusée par le système")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Réinitialisation refusée", e)
            reportResetResult(false, e.message ?: "SecurityException")
        }
    }

    private fun reportResetResult(succeeded: Boolean, message: String) {
        deviceDoc.set(
            mapOf(
                FIELD_LAST_RESET_SUCCEEDED to succeeded,
                FIELD_LAST_RESET_MESSAGE to message,
                FIELD_LAST_RESET_DONE_AT to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge()
        ).addOnFailureListener { e -> Log.e(TAG, "Échec du compte rendu de réinitialisation", e) }
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
        private const val FIELD_RESET_TRANSCRIPTION_AT = "resetTranscriptionRequestedAt"
        private const val FIELD_LAST_RESET_SUCCEEDED = "lastTranscriptionResetSucceeded"
        private const val FIELD_LAST_RESET_MESSAGE = "lastTranscriptionResetMessage"
        private const val FIELD_LAST_RESET_DONE_AT = "lastTranscriptionResetAt"

        private const val PREFS_NAME = "device_status_reporter"
        private const val KEY_LAST_RESET_HANDLED_AT = "last_reset_handled_at"
    }
}
