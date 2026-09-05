package com.seniorvisio.signaling

import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

data class RemoteIceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String
)

/**
 * Boîte aux lettres Firestore pour l'échange SDP/ICE entre la tablette et le
 * PWA appelant (voir web-caller/webrtc-engine.js pour le pendant navigateur).
 * Un document par appel dans `calls`, avec une sous-collection de candidats
 * ICE par camp. Ne connaît rien à WebRTC : uniquement de la lecture/écriture
 * Firestore, pour garder WebRtcCallEngine indépendant du transport réseau.
 */
class CallSignalingClient {

    private val db get() = FirebaseFirestore.getInstance()
    private fun callDoc(callId: String) = db.collection(CALLS_COLLECTION).document(callId)

    /** false tant que google-services.json n'a pas été fourni (voir README). */
    fun isAvailable(): Boolean = try {
        FirebaseApp.getInstance()
        true
    } catch (e: IllegalStateException) {
        false
    }

    /**
     * Notifie uniquement des appels *nouvellement* mis en sonnerie après
     * l'appel de cette fonction. `callerPhotoBase64` est une photo (JPEG,
     * capturée sur le navigateur du proche à l'ouverture de l'appel) pour
     * une reconnaissance visuelle immédiate — null si absente ou trop lourde.
     */
    fun listenForRingingCalls(
        onIncoming: (callId: String, callerName: String, callerPhotoBase64: String?) -> Unit
    ): ListenerRegistration {
        var isFirstSnapshot = true
        return db.collection(CALLS_COLLECTION)
            .whereEqualTo(FIELD_STATUS, STATUS_RINGING)
            .addSnapshotListener { snapshot, _ ->
                val wasFirst = isFirstSnapshot
                isFirstSnapshot = false
                if (snapshot == null || wasFirst) return@addSnapshotListener
                snapshot.documentChanges.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val callerName = change.document.getString(FIELD_CALLER_NAME) ?: "un proche"
                        val photo = change.document.getString(FIELD_CALLER_PHOTO)
                        onIncoming(change.document.id, callerName, photo)
                    }
                }
            }
    }

    fun fetchOfferSdp(callId: String, onResult: (String?) -> Unit) {
        callDoc(callId).get()
            .addOnSuccessListener { doc -> onResult(doc.getString(FIELD_OFFER_SDP)) }
            .addOnFailureListener { onResult(null) }
    }

    fun sendAnswer(callId: String, sdp: String) {
        callDoc(callId).update(mapOf(FIELD_ANSWER_SDP to sdp, FIELD_STATUS to STATUS_CONNECTED))
    }

    fun updateStatus(callId: String, status: String) {
        callDoc(callId).update(FIELD_STATUS, status)
    }

    /**
     * Cause exacte d'un échec de préparation d'appel côté tablette (voir
     * WebRtcCallEngine.reportPreparationError) — jusqu'ici entièrement
     * silencieuse, un raccroché sans la moindre explication ni pour le
     * proche, ni depuis la console Firebase.
     */
    fun reportCalleeError(callId: String, message: String) {
        callDoc(callId).update(FIELD_CALLEE_ERROR, message)
    }

    /**
     * Diagnostic de la transcription temps réel de l'appel (voir
     * WebRtcCallEngine.attachTranscriptionSink) : confirme si le flux audio
     * distant atteint bien le transcripteur, et remonte les échecs de
     * connexion AssemblyAI — utile tant que ce circuit n'a pas encore été
     * confirmé fiable en usage réel, sans quoi seul le journal système
     * (inaccessible sans Mac ni ordinateur relié à la tablette) le révélerait.
     */
    fun reportCaptionDebug(callId: String, message: String) {
        callDoc(callId).update(FIELD_CAPTION_DEBUG, message)
    }

    fun addCandidate(callId: String, candidate: RemoteIceCandidate) {
        callDoc(callId).collection(CALLEE_CANDIDATES).add(
            mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "candidate" to candidate.candidate
            )
        )
    }

    /** Niveau de volume choisi à distance par l'appelant (voir web-caller/webrtc-engine.js). */
    fun listenForRemoteVolume(callId: String, onVolume: (Double) -> Unit): ListenerRegistration {
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            val volume = snapshot?.getDouble(FIELD_REMOTE_VOLUME)
            if (volume != null) onVolume(volume)
        }
    }

    /**
     * Signale le début du décompte d'alerte côté tablette, pour que le PWA
     * appelant puisse en afficher la progression en direct (voir
     * web-caller/app.js). L'horodatage vient du serveur Firestore, pas de
     * l'horloge locale de la tablette, pour rester cohérent malgré un
     * éventuel décalage d'horloge entre les deux appareils.
     */
    fun startAlertCountdown(callId: String, durationSeconds: Int) {
        callDoc(callId).update(
            mapOf(
                FIELD_ALERT_STARTED_AT to FieldValue.serverTimestamp(),
                FIELD_ALERT_DURATION to durationSeconds
            )
        )
    }

    /** Active/désactive à distance le mode "sous-titres géants" côté tablette (voir web-caller/app.js). */
    fun listenForCaptionMode(callId: String, onEnabled: (Boolean) -> Unit): ListenerRegistration {
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            val enabled = snapshot?.getBoolean(FIELD_CAPTION_MODE)
            if (enabled != null) onEnabled(enabled)
        }
    }

    /** Taille du texte des sous-titres (en sp), choisie à distance par l'appelant. */
    fun listenForCaptionTextSize(callId: String, onSizeSp: (Double) -> Unit): ListenerRegistration {
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            val size = snapshot?.getDouble(FIELD_CAPTION_TEXT_SIZE)
            if (size != null) onSizeSp(size)
        }
    }

    /**
     * Active/désactive à distance l'aperçu de sa propre caméra affiché à
     * Jean (petite vignette en haut de son écran) — masqué par défaut,
     * c'est le proche qui décide de l'activer depuis le PWA, pas un bouton
     * sur la tablette.
     */
    fun listenForSelfPreviewMode(callId: String, onEnabled: (Boolean) -> Unit): ListenerRegistration {
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            val enabled = snapshot?.getBoolean(FIELD_SELF_PREVIEW)
            if (enabled != null) onEnabled(enabled)
        }
    }

    /**
     * Photo actuellement affichée en grand chez Jean pendant un diaporama
     * commenté à distance (voir WebRtcCallEngine.listenForSlideshowPhoto).
     *
     * Une seule photo à la fois dans le document d'appel, remplacée à chaque
     * fois que le proche fait défiler : envoyer toute la série d'un coup
     * dépasserait la limite de taille d'un document Firestore dès quelques
     * images. Valeur nulle ou vide = fin du diaporama, retour à la vidéo.
     */
    fun listenForSlideshowPhoto(callId: String, onPhoto: (String?) -> Unit): ListenerRegistration {
        var lastPhoto: String? = null
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            if (snapshot == null) return@addSnapshotListener
            // Ce listener écoute tout le document, donc il se redéclenche à
            // chaque écriture (sous-titres, volume...) : sans cette
            // comparaison, on redécoderait la même photo des dizaines de fois
            // par minute pendant que le proche parle.
            val photo = snapshot.getString(FIELD_SLIDESHOW_PHOTO)
            if (photo == lastPhoto) return@addSnapshotListener
            lastPhoto = photo
            onPhoto(photo)
        }
    }

    /**
     * Micro de la tablette coupé à distance par le proche (voir
     * WebRtcCallEngine.listenForMicMute) : sert à localiser un écho sans
     * ambiguïté, et à couper un bruit de fond gênant chez Jean.
     */
    fun listenForMicMute(callId: String, onMuted: (Boolean) -> Unit): ListenerRegistration {
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            val muted = snapshot?.getBoolean(FIELD_MIC_MUTED)
            if (muted != null) onMuted(muted)
        }
    }

    /**
     * L'appelant signale qu'il est dans la même pièce que Jean (voir
     * WebRtcCallEngine.listenForSameRoomMode) : le son de la tablette est
     * alors entièrement coupé, le texte continue de s'afficher.
     */
    fun listenForSameRoomMode(callId: String, onEnabled: (Boolean) -> Unit): ListenerRegistration {
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            val enabled = snapshot?.getBoolean(FIELD_SAME_ROOM_MODE)
            if (enabled != null) onEnabled(enabled)
        }
    }

    /**
     * La tablette a reconnu la balise sonore du téléphone de l'appelant : ils
     * sont dans la même pièce (voir SameRoomDetector). Le PWA s'en sert pour
     * cocher le mode "même pièce" de lui-même, sans que le proche ait à y
     * penser — c'est justement la situation où il a autre chose en tête.
     */
    fun reportSameRoomDetected(callId: String) {
        callDoc(callId).update(FIELD_SAME_ROOM_DETECTED, true)
    }

    /** Vitesse maximale (dp/s) à laquelle le texte défile chez Jean, choisie à distance par l'appelant. */
    fun listenForCaptionScrollSpeed(callId: String, onDpPerSec: (Double) -> Unit): ListenerRegistration {
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            val speed = snapshot?.getDouble(FIELD_CAPTION_SCROLL_SPEED)
            if (speed != null) onDpPerSec(speed)
        }
    }

    /**
     * Publie l'état de l'écran de Jean : le texte réellement affiché dans
     * chacune de ses deux zones (null quand la zone est vide), et l'avance en
     * secondes que le proche a prise sur ce que Jean a eu le temps de lire
     * (voir PacedCaptionZone.pendingSeconds).
     *
     * Le PWA rejoue ces textes tels quels plutôt que la transcription brute,
     * qui a toujours de l'avance : c'est ce qui lui permet de montrer au
     * proche exactement ce que Jean a sous les yeux, au même instant (voir
     * web-caller/app.js).
     *
     * Les trois valeurs partent dans une seule écriture : appelée plusieurs
     * fois par minute pendant tout l'appel, la découper en trois multiplierait
     * d'autant les écritures Firestore et les réveils du listener d'en face,
     * qui écoute le document entier.
     */
    /**
     * Décrit l'écran de Jean au PWA pour qu'il puisse en dessiner une réplique
     * fidèle : proportions réelles de la dalle, ordre des zones tel que réglé
     * par l'admin, palette claire ou sombre en cours, et contenu de la zone
     * d'information.
     *
     * Ces valeurs sont publiées plutôt que recalculées côté PWA : le proche
     * peut être dans une autre ville (météo différente), sur une tablette aux
     * proportions différentes, et l'ordre des zones n'existe que côté
     * tablette. Tout recalcul divergerait, ce qui viderait de son sens l'idée
     * même de montrer au proche ce que Jean voit.
     *
     * Écrit rarement (à la connexion, puis au changement de palette ou au
     * rafraîchissement du quart d'heure), contrairement à publishScreenState
     * qui suit le rythme de la parole — d'où deux méthodes séparées.
     */
    fun publishScreenLayout(
        callId: String,
        aspectRatio: Double,
        zoneOrder: String,
        isDark: Boolean,
        infoMoment: String?,
        infoWeather: String?,
        infoDate: String?,
    ) {
        callDoc(callId).update(
            mapOf(
                FIELD_SCREEN_ASPECT_RATIO to aspectRatio,
                FIELD_SCREEN_ZONE_ORDER to zoneOrder,
                FIELD_SCREEN_IS_DARK to isDark,
                FIELD_SCREEN_INFO_MOMENT to infoMoment,
                FIELD_SCREEN_INFO_WEATHER to infoWeather,
                FIELD_SCREEN_INFO_DATE to infoDate,
            )
        )
    }

    fun publishScreenState(callId: String, roomText: String?, callText: String?, lagSeconds: Float) {
        callDoc(callId).update(
            mapOf(
                FIELD_DISPLAYED_ROOM_TEXT to roomText,
                FIELD_DISPLAYED_CALL_TEXT to callText,
                FIELD_CAPTION_CATCHUP_LAG to lagSeconds.toDouble(),
            )
        )
    }

    /**
     * Écoute la demande de connexion immédiate déclenchée à distance par le
     * proche (bouton "Se connecter maintenant" côté PWA), pour ne pas
     * attendre la fin du décompte.
     */
    fun listenForForceConnect(callId: String, onForce: () -> Unit): ListenerRegistration {
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            val requested = snapshot?.getBoolean(FIELD_FORCE_CONNECT) ?: false
            if (requested) onForce()
        }
    }

    /**
     * Écoute la fin d'appel déclenchée à distance par le proche (bouton
     * "Annuler" pendant l'attente, ou "Raccrocher" une fois connecté), pour
     * que la tablette se referme aussi — sans ça, un raccroché côté proche
     * laissait la communication tourner indéfiniment côté tablette.
     */
    fun listenForRemoteEnded(callId: String, onEnded: () -> Unit): ListenerRegistration {
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            if (snapshot?.getString(FIELD_STATUS) == STATUS_ENDED) onEnded()
        }
    }

    /**
     * Enregistre le token FCM courant de la tablette, pour que la Cloud
     * Function (functions/index.js) puisse la réveiller par notification
     * push dès qu'un appel apparaît — voir SeniorVisioMessagingService.
     * Appelé au démarrage de l'appli et à chaque renouvellement du token
     * (celui-ci peut changer à tout moment, décision d'Android/Firebase).
     */
    fun registerDeviceToken(token: String) {
        if (!isAvailable()) return
        db.document(DEVICE_TOKEN_DOC).set(mapOf(FIELD_FCM_TOKEN to token), SetOptions.merge())
    }

    fun listenForCallerCandidates(callId: String, onCandidate: (RemoteIceCandidate) -> Unit): ListenerRegistration {
        return callDoc(callId).collection(CALLER_CANDIDATES)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val d = change.document
                        val candidate = d.getString("candidate") ?: return@forEach
                        onCandidate(
                            RemoteIceCandidate(
                                sdpMid = d.getString("sdpMid"),
                                sdpMLineIndex = (d.getLong("sdpMLineIndex") ?: 0L).toInt(),
                                candidate = candidate
                            )
                        )
                    }
                }
            }
    }

    companion object {
        private const val CALLS_COLLECTION = "calls"
        private const val CALLER_CANDIDATES = "callerCandidates"
        private const val CALLEE_CANDIDATES = "calleeCandidates"

        private const val FIELD_STATUS = "status"
        private const val FIELD_CALLER_NAME = "callerName"
        private const val FIELD_CALLER_PHOTO = "callerPhotoBase64"
        private const val FIELD_OFFER_SDP = "offerSdp"
        private const val FIELD_ANSWER_SDP = "answerSdp"
        private const val FIELD_REMOTE_VOLUME = "remoteVolume"
        private const val FIELD_ALERT_STARTED_AT = "alertStartedAt"
        private const val FIELD_ALERT_DURATION = "alertDurationSeconds"
        private const val FIELD_CAPTION_MODE = "captionModeEnabled"
        private const val FIELD_CAPTION_TEXT_SIZE = "captionTextSize"
        private const val FIELD_FORCE_CONNECT = "forceConnectRequested"
        private const val FIELD_SELF_PREVIEW = "selfPreviewEnabled"
        private const val FIELD_CAPTION_SCROLL_SPEED = "captionMaxScrollSpeedDpPerSec"
        private const val FIELD_CAPTION_CATCHUP_LAG = "captionCatchUpLagSeconds"
        private const val FIELD_DISPLAYED_ROOM_TEXT = "displayedRoomText"
        private const val FIELD_DISPLAYED_CALL_TEXT = "displayedCallText"
        private const val FIELD_SCREEN_ASPECT_RATIO = "screenAspectRatio"
        private const val FIELD_SCREEN_ZONE_ORDER = "screenZoneOrder"
        private const val FIELD_SCREEN_IS_DARK = "screenIsDark"
        private const val FIELD_SCREEN_INFO_MOMENT = "screenInfoMoment"
        private const val FIELD_SCREEN_INFO_WEATHER = "screenInfoWeather"
        private const val FIELD_SCREEN_INFO_DATE = "screenInfoDate"
        private const val FIELD_CALLEE_ERROR = "calleeErrorMessage"
        private const val FIELD_CAPTION_DEBUG = "captionDebugMessage"
        private const val FIELD_MIC_MUTED = "tabletMicMuted"
        private const val FIELD_SAME_ROOM_MODE = "sameRoomMode"
        private const val FIELD_SAME_ROOM_DETECTED = "sameRoomDetected"
        private const val FIELD_SLIDESHOW_PHOTO = "slideshowPhotoBase64"

        private const val DEVICE_TOKEN_DOC = "devices/jean_tablet"
        private const val FIELD_FCM_TOKEN = "fcmToken"

        const val STATUS_RINGING = "ringing"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_ENDED = "ended"
    }
}
