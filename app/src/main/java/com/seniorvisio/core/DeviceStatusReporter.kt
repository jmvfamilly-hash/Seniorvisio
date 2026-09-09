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
import com.seniorvisio.service.RoomPresenceService
import java.time.LocalDate
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
                FIELD_ADMIN_PIN_FINGERPRINT to adminPinFingerprint(),
                FIELD_ROOM_LISTENING to describeRoomListening(),
                // Les mêmes messages que pendant un appel, mais lisibles hors
                // appel : c'est là qu'on règle le moteur de la pièce, et c'est
                // là qu'ils manquaient (voir TranscriptionDiagnostics).
                FIELD_TRANSCRIPTION_DIAGNOSTIC to TranscriptionDiagnostics.describe(),
            ),
            SetOptions.merge()
        ).addOnFailureListener { e -> Log.e(TAG, "Échec de l'envoi du signe de vie à Firestore", e) }
        publishUsage()
    }

    /**
     * La journée en cours dans son propre document, une par jour (voir
     * UsageStats). Séparée du document d'appareil pour deux raisons : elle
     * grossit au fil de la journée, et surtout le PWA doit pouvoir relire les
     * jours précédents pour tracer la semaine — ce qu'un champ unique écrasé à
     * chaque envoi ne permettrait pas.
     */
    private fun publishUsage() {
        val today = LocalDate.now()
        val day = UsageStats.dayAsJson(today)
        val fields = mutableMapOf<String, Any?>()
        day.keys().forEach { key -> fields[key] = jsonToFirestore(day.get(key)) }
        if (fields.isEmpty()) return
        deviceDoc.collection(USAGE_COLLECTION).document(today.toString())
            .set(fields, SetOptions.merge())
            .addOnFailureListener { e -> Log.e(TAG, "Échec de la publication de l'usage", e) }
    }

    /**
     * Firestore n'accepte ni JSONObject ni JSONArray : ils passent par des Map
     * et des List. Conversion récursive, les journées contenant à la fois des
     * tableaux (les tranches de quart d'heure), des objets (les secondes par
     * moteur) et une liste d'objets (les appels).
     */
    private fun jsonToFirestore(value: Any?): Any? = when (value) {
        is org.json.JSONObject -> value.keys().asSequence()
            .associateWith { jsonToFirestore(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).map { jsonToFirestore(value.get(it)) }
        else -> value
    }



    /**
     * État de l'écoute de la pièce en une phrase, jointe au signe de vie.
     *
     * Le réveil au son est la fonction qui échoue le plus silencieusement de
     * toute l'application : quand il ne marche plus, rien ne l'annonce, et les
     * trois causes possibles — capture morte, seuil trop haut, blocage
     * nocturne — sont indiscernables de l'extérieur. Jusqu'ici il fallait
     * marcher jusqu'à la tablette et entrer le code admin pour les départager.
     *
     * Le niveau remonté est le PIC depuis le dernier signe de vie, pas le
     * niveau instantané : cinq minutes séparent deux envois, et l'instant
     * précis où l'on mesure a toutes les chances d'être un instant de silence.
     * Comparé au seuil, ce pic dit tout de suite si le son de la pièce
     * atteint, ou non, de quoi réveiller l'écran.
     */
    /** Une décimale suffit : c'est un repère de réglage, pas une mesure de laboratoire. */
    private fun format1(value: Float): String = String.format(java.util.Locale.FRANCE, "%.1f", value)

    private fun describeRoomListening(): String {
        val service = RoomPresenceService.running
            ?: return "service d'écoute non démarré"
        val status = service.currentStatus()
        val peak = service.consumePeakRms()
        return buildString {
            append(status.listeningMode)
            status.captureError?.let { append(" ($it)") }
            // Les deux mécanismes mesurent, mais pas dans la même unité : une
            // valeur efficace sur 16 bits pour notre capture, des décibels
            // relatifs pour le moteur d'Android. Chacun affiche la sienne
            // face à son propre seuil — c'est ce qui permet de régler la
            // sensibilité sur une mesure plutôt qu'au jugé.
            val androidPeak = service.consumeAndroidPeakLevelDb()
            val androidThreshold = status.androidThresholdDb
            if (status.capturing) {
                append(" — pic ").append(peak).append(" / seuil ").append(status.threshold)
            } else if (androidPeak != null && androidThreshold != null) {
                append(" — pic ").append(format1(androidPeak))
                append(" dB / seuil ").append(format1(androidThreshold)).append(" dB")
                // Ce moteur ne sait écouter qu'un énoncé à la fois et doit être
                // relancé sans cesse. Chaque relance est une couture pendant
                // laquelle plus rien n'est écouté — c'est là que des mots se
                // perdent — et un son de démarrage de plus. Le chiffre est donc
                // le meilleur indicateur de santé de ce mode : quelques
                // relances par minute est normal, plusieurs dizaines signale
                // que le moteur coupe au moindre silence.
                status.androidRestartsPerMinute?.let {
                    append(" — ").append(it).append(" relances/min")
                }
            }
            if (!status.wakeEnabled) append(" — réveil désactivé")
            if (status.inNightWindow) append(" — réveil bloqué (nuit)")
            append(" — réveils demandés : ").append(status.wakeRequests)
        }
    }

    /**
     * Empreinte du code d'accès admin de la tablette, republiée avec le signe
     * de vie pour que le PWA puisse déverrouiller son propre panneau
     * d'administration avec le MÊME code (voir web-caller/app.js). Un seul code
     * à retenir pour les deux, et il se change au même endroit.
     *
     * Une empreinte plutôt que le code en clair, pour qu'il ne se lise pas
     * d'un coup d'œil dans la console Firestore. Ce n'est pas pour autant une
     * barrière : quatre chiffres se retrouvent instantanément à partir de leur
     * empreinte, et les règles Firestore de ce projet laissent de toute façon
     * écrire quiconque connaît l'adresse. Le code protège contre la fausse
     * manœuvre d'un proche qui explore l'application, pas contre quelqu'un de
     * mal intentionné — et il ne faut pas lui faire dire autre chose.
     */
    private fun adminPinFingerprint(): String {
        val pin = AdminConfig(context).adminPin
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
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
        applyRemoteCommand(snapshot)
        val requestedVersion = snapshot.getString(FIELD_REQUESTED_VERSION) ?: return
        val apkUrl = snapshot.getString(FIELD_REQUESTED_APK_URL) ?: return
        if (requestedVersion == BuildConfig.BUILD_REV) return
        Log.i(TAG, "Mise à jour à distance détectée : $requestedVersion (version actuelle ${BuildConfig.BUILD_REV})")
        installUpdate(apkUrl)
    }


    /**
     * Relance de l'application ou redémarrage de la tablette, demandés depuis
     * le panneau d'administration.
     *
     * Ce sont les deux gestes qu'un proche ou un aidant finit par faire à la
     * main quand quelque chose s'est bloqué — c'est-à-dire en se déplaçant
     * jusqu'à la tablette, et en appuyant sur un bouton dont on ne sait pas ce
     * qu'il interrompt. Pouvoir les faire à distance, c'est éviter le
     * déplacement ET la coupure sauvage.
     *
     * L'identifiant de commande est ce qui empêche la boucle : un champ
     * "redémarre" resterait vrai après le redémarrage et relancerait
     * l'appareil indéfiniment. Ici la commande n'est exécutée que si son
     * identifiant diffère du dernier exécuté, et celui-ci est enregistré
     * localement AVANT d'agir — un redémarrage n'a pas de suite, il faut donc
     * que la trace soit déjà écrite quand il arrive.
     */
    private fun applyRemoteCommand(snapshot: DocumentSnapshot) {
        val command = snapshot.getString(FIELD_COMMAND) ?: return
        val commandId = snapshot.getString(FIELD_COMMAND_ID) ?: return
        val adminConfig = AdminConfig(context)
        if (adminConfig.lastExecutedCommandId == commandId) return
        adminConfig.lastExecutedCommandId = commandId

        Log.i(TAG, "Commande à distance reçue : $command ($commandId)")
        deviceDoc.set(
            mapOf(
                FIELD_LAST_COMMAND to command,
                FIELD_LAST_COMMAND_AT to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge()
        )

        when (command) {
            COMMAND_RESTART_APP -> restartApp()
            COMMAND_REBOOT -> rebootDevice()
            else -> Log.w(TAG, "Commande à distance inconnue : $command")
        }
    }

    /**
     * Referme le processus après avoir programmé sa réouverture. Tuer sans
     * programmer suffirait sur le papier — le service permanent est START_STICKY
     * — mais Android se réserve le droit d'attendre plusieurs minutes avant de
     * le relancer, et l'écran de Jean resterait noir pendant tout ce temps.
     */
    private fun restartApp() {
        val intent = Intent(context, com.seniorvisio.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            context, RESTART_REQUEST_CODE, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
        alarms?.setExactAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + RESTART_DELAY_MS,
            pending
        )
        // Laisse partir l'accusé de réception et l'alarme avant de disparaître.
        retryHandler.postDelayed({ kotlin.system.exitProcess(0) }, 1_000L)
    }

    /**
     * Redémarrage complet, réservé au Device Owner — sans ce statut, aucune
     * application ne peut redémarrer un appareil Android, et il n'y a pas de
     * contournement. Refusé aussi pendant un appel par le système lui-même.
     */
    private fun rebootDevice() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = android.content.ComponentName(
            context, com.seniorvisio.admin.SeniorVisioDeviceAdminReceiver::class.java
        )
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Redémarrage refusé : la tablette n'est pas en Device Owner")
            deviceDoc.set(
                mapOf(FIELD_LAST_COMMAND to "redémarrage impossible (pas Device Owner)"),
                SetOptions.merge()
            )
            return
        }
        try {
            dpm.reboot(admin)
        } catch (e: Exception) {
            Log.w(TAG, "Redémarrage refusé par le système", e)
            deviceDoc.set(
                mapOf(FIELD_LAST_COMMAND to "redémarrage refusé (${e.message})"),
                SetOptions.merge()
            )
        }
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
                // Contrairement aux deux autres moteurs, celui d'Android change
                // le mécanisme qui tient le micro (voir
                // RoomPresenceService.startListening) : il faut donc basculer
                // tout de suite, sans quoi le réglage n'aurait d'effet qu'au
                // prochain redémarrage, des heures plus tard.
                RoomPresenceService.running?.onRoomEngineChanged()
            }
        }
        TranscriptionEngineChoice.fromRemoteValue(snapshot.getString(FIELD_CALL_ENGINE))?.let {
            if (adminConfig.callEngine != it) {
                adminConfig.callEngine = it
                Log.i(TAG, "Moteur des appels réglé à distance : ${it.remoteValue}")
            }
        }
        snapshot.getBoolean(FIELD_ROOM_WAKE_ENABLED)?.let {
            adminConfig.roomWakeEnabled = it
        }
        snapshot.getLong(FIELD_ROOM_WAKE_THRESHOLD)?.let {
            if (it > 0) adminConfig.roomWakeSensitivityThreshold = it.toInt()
        }
        snapshot.getBoolean(FIELD_BLOCK_WAKE_AT_NIGHT)?.let {
            adminConfig.blockWakeAtNight = it
        }

        snapshot.getLong(FIELD_CAPTION_VISIBLE_LINES)?.let {
            adminConfig.captionVisibleLines = it.toInt()
        }
        snapshot.getLong(FIELD_CAPTION_SCROLL_SPEED)?.let {
            adminConfig.captionScrollSpeedDp = it.toInt()
        }
        snapshot.getLong(FIELD_CAPTION_CLEAR_DELAY)?.let {
            adminConfig.captionClearDelaySeconds = it.toInt()
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

        /** Une journée d'usage par document (voir publishUsage, UsageStats). */
        private const val USAGE_COLLECTION = "usage"
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
        private const val FIELD_CAPTION_VISIBLE_LINES = "captionVisibleLines"
        private const val FIELD_CAPTION_SCROLL_SPEED = "captionScrollSpeedDp"
        private const val FIELD_CAPTION_CLEAR_DELAY = "captionClearDelaySeconds"
        private const val FIELD_ADMIN_PIN_FINGERPRINT = "adminPinFingerprint"
        private const val FIELD_ROOM_LISTENING = "roomListening"
        private const val FIELD_TRANSCRIPTION_DIAGNOSTIC = "transcriptionDiagnostic"
        private const val FIELD_COMMAND = "command"
        private const val FIELD_COMMAND_ID = "commandId"
        private const val FIELD_LAST_COMMAND = "lastCommand"
        private const val FIELD_LAST_COMMAND_AT = "lastCommandAt"

        const val COMMAND_RESTART_APP = "restart-app"
        const val COMMAND_REBOOT = "reboot"

        private const val RESTART_REQUEST_CODE = 4207
        private const val RESTART_DELAY_MS = 1_500L
        private const val FIELD_ROOM_WAKE_ENABLED = "roomWakeEnabled"
        private const val FIELD_ROOM_WAKE_THRESHOLD = "roomWakeThreshold"
        private const val FIELD_BLOCK_WAKE_AT_NIGHT = "blockWakeAtNight"

        private const val LISTENER_RETRY_DELAY_MS = 60_000L
    }
}
