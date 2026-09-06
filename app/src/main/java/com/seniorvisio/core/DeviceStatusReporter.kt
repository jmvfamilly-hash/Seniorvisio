package com.seniorvisio.core

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
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
    private val retryHandler = Handler(Looper.getMainLooper())

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
                // Renvoyé au PWA pour que la bascule de moteur à distance ne
                // soit pas aveugle : c'est le seul retour dont dispose le
                // proche qui vient de demander le grand modèle.
                FIELD_VOSK_MODEL_STATE to VoskModelProvider.describeState(),
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
     * permanent — mais se réabonne ensuite elle-même en cas d'erreur (voir
     * ci-dessous), donc pas besoin de la rappeler à la main.
     */
    fun listenForRemoteCommands() {
        // Un seul listener pour toutes les commandes à distance : chaque
        // addSnapshotListener sur ce document est facturé une lecture à chaque
        // écriture, y compris celles que la tablette fait elle-même.
        deviceDoc.addSnapshotListener { snapshot, error ->
            if (error != null) {
                // Une erreur ici (ex. règles Firestore refusant la lecture à ce
                // moment précis) termine définitivement CE listener côté SDK —
                // il ne se réabonne jamais tout seul, même si la cause de
                // l'erreur disparaît ensuite (ex. correction des règles). Constaté
                // en usage réel : le signe de vie (écriture simple, rejouée à
                // chaque cycle) s'était remis à fonctionner après une correction
                // des règles, mais cette écoute était restée muette indéfiniment,
                // faute de nouvelle tentative. Sans ce réabonnement différé, seul
                // un redémarrage physique de la tablette y remédierait.
                Log.e(TAG, "Écoute Firestore des commandes à distance interrompue, nouvelle tentative dans ${LISTENER_RETRY_DELAY_MS / 1000}s", error)
                retryHandler.postDelayed({ listenForRemoteCommands() }, LISTENER_RETRY_DELAY_MS)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            handleRemoteUpdate(snapshot)
        }
    }

    private fun handleRemoteUpdate(snapshot: DocumentSnapshot) {
        applyTranscriptionSettings(snapshot)
        val requestedVersion = snapshot.getString(FIELD_REQUESTED_VERSION) ?: return
        val apkUrl = snapshot.getString(FIELD_REQUESTED_APK_URL) ?: return
        if (requestedVersion == BuildConfig.BUILD_REV) return
        Log.i(TAG, "Mise à jour à distance détectée : $requestedVersion (version actuelle ${BuildConfig.BUILD_REV})")
        installUpdate(apkUrl)
    }

    /**
     * Applique le choix de moteur de reconnaissance vocale et la taille du
     * modèle embarqué, réglés à distance depuis le PWA (voir
     * web-caller/app.js). Passe par le document d'appareil et non par celui
     * d'un appel : le moteur de la pièce doit pouvoir changer sans qu'un appel
     * soit en cours, et la bascule doit survivre au raccroché.
     *
     * Aucune notification à faire au reste de l'application : le moteur de
     * transcription relit ce réglage à chaque bloc de son et referme sa
     * session si le moteur voulu a changé (voir TranscriptionEngine.feed). La
     * bascule prend donc effet en pleine phrase, ce qui est justement ce qu'on
     * veut pour comparer deux moteurs sur la même voix.
     */
    private fun applyTranscriptionSettings(snapshot: DocumentSnapshot) {
        val adminConfig = AdminConfig(context)

        TranscriptionEngineChoice.fromRemoteValue(snapshot.getString(FIELD_ROOM_ENGINE))?.let {
            if (adminConfig.roomEngine != it) {
                adminConfig.roomEngine = it
                Log.i(TAG, "Moteur de la pièce réglé à distance : ${it.remoteValue}")
            }
        }
        TranscriptionEngineChoice.fromRemoteValue(snapshot.getString(FIELD_CALL_ENGINE))?.let {
            if (adminConfig.callEngine != it) {
                adminConfig.callEngine = it
                Log.i(TAG, "Moteur des appels réglé à distance : ${it.remoteValue}")
            }
        }
        VoskModelSize.fromRemoteValue(snapshot.getString(FIELD_VOSK_MODEL_SIZE))?.let {
            if (adminConfig.voskModelSize != it) {
                adminConfig.voskModelSize = it
                Log.i(TAG, "Taille du modèle embarqué réglée à distance : ${it.remoteValue}")
            }
            // Appelé même quand la valeur n'a pas changé : c'est ce qui relance
            // un téléchargement précédemment échoué, sans rien demander à
            // personne (voir VoskModelProvider.prepare, sans effet si prêt).
            VoskModelProvider.prepare(context, it)
        }
    }

    private fun installUpdate(apkUrl: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Mise à jour à distance demandée mais l'appli n'est pas Device Owner : impossible d'installer sans confirmation manuelle.")
            reportUpdateFailure("Appli non Device Owner sur cet appareil")
            return
        }
        Thread {
            try {
                val apkFile = downloadApk(apkUrl)
                silentInstall(apkFile)
            } catch (e: Exception) {
                // Sans ce compte rendu, un échec ici (réseau, redirection GitHub
                // mal suivie, code HTTP inattendu...) restait invisible : rien
                // dans Firestore ne changeait, donc rien à voir depuis la
                // console — seul un adb logcat sur la tablette le révélait.
                // silentInstall a son propre compte rendu (voir
                // UpdateStatusReceiver) : celui-ci ne couvre que le
                // téléchargement, qui échouait silencieusement avant lui.
                Log.e(TAG, "Échec du téléchargement de la mise à jour", e)
                reportUpdateFailure("Téléchargement échoué : ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun reportUpdateFailure(message: String) {
        deviceDoc.set(
            mapOf(
                FIELD_LAST_UPDATE_SUCCEEDED to false,
                FIELD_LAST_UPDATE_MESSAGE to message,
                FIELD_LAST_UPDATE_AT to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge()
        ).addOnFailureListener { e -> Log.e(TAG, "Échec du compte rendu d'échec de mise à jour", e) }
    }

    /**
     * Les liens de release GitHub redirigent (302) vers objects.githubusercontent.com :
     * HttpURLConnection est censé suivre ça tout seul, mais silencieusement, sans
     * jamais dire si ça a marché. Suivi manuel ici pour deux raisons : vérifier le
     * code HTTP à chaque saut plutôt que d'écrire une page d'erreur dans le fichier
     * .apk sans s'en apercevoir, et obtenir un message d'échec précis (code HTTP,
     * en-tête manquant) au lieu d'une exception opaque en cas de problème.
     */
    private fun downloadApk(apkUrl: String): File {
        val outFile = File(context.cacheDir, "update.apk")
        var url = URL(apkUrl)
        var redirects = 0
        while (true) {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "SeniorVisio-Tablette")
            connection.connect()
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) throw java.io.IOException("Redirection sans en-tête Location (code $code)")
                redirects++
                if (redirects > 5) throw java.io.IOException("Trop de redirections lors du téléchargement de l'APK")
                url = URL(location)
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw java.io.IOException("Téléchargement de l'APK refusé par le serveur (code HTTP $code)")
            }
            connection.inputStream.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            return outFile
        }
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
        private const val FIELD_LAST_UPDATE_SUCCEEDED = "lastUpdateSucceeded"
        private const val FIELD_LAST_UPDATE_MESSAGE = "lastUpdateMessage"
        private const val FIELD_LAST_UPDATE_AT = "lastUpdateAt"
        private const val FIELD_ROOM_ENGINE = "roomTranscriptionEngine"
        private const val FIELD_CALL_ENGINE = "callTranscriptionEngine"
        private const val FIELD_VOSK_MODEL_SIZE = "voskModelSize"
        private const val FIELD_VOSK_MODEL_STATE = "voskModelState"

        private const val LISTENER_RETRY_DELAY_MS = 60_000L
    }
}
