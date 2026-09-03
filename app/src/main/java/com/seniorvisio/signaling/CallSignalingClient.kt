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
     * Texte transcrit en direct de la voix de l'appelant, relayé par son
     * propre navigateur (Web Speech API, voir web-caller/webrtc-engine.js)
     * pour le mode "sous-titres géants" — gratuit, mais ne fonctionne que sur
     * les navigateurs supportant la reconnaissance vocale (Chrome desktop
     * essentiellement, pas Safari/iOS). Ce listener écoute tout le document
     * d'appel, donc il se redéclenche à chaque écriture Firestore pendant
     * l'appel (volume, etc.), pas seulement quand le texte change — on ne
     * notifie que sur un texte réellement différent, pour éviter de relancer
     * inutilement l'animation de défilement côté tablette.
     */
    fun listenForCallerSpeech(callId: String, onText: (String) -> Unit): ListenerRegistration {
        var lastText: String? = null
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            val text = snapshot?.getString(FIELD_CALLER_SPEECH)
            if (!text.isNullOrEmpty() && text != lastText) {
                lastText = text
                onText(text)
            }
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

    /** Vitesse maximale (dp/s) à laquelle le texte défile chez Jean, choisie à distance par l'appelant. */
    fun listenForCaptionScrollSpeed(callId: String, onDpPerSec: (Double) -> Unit): ListenerRegistration {
        return callDoc(callId).addSnapshotListener { snapshot, _ ->
            val speed = snapshot?.getDouble(FIELD_CAPTION_SCROLL_SPEED)
            if (speed != null) onDpPerSec(speed)
        }
    }

    /**
     * Signale en continu au proche le retard de lecture de Jean par rapport
     * au texte reçu (voir IncomingCallActivity.setupCaptionMode) : 0 quand
     * Jean a tout lu, une valeur croissante (en secondes) tant que le
     * défilement — plafonné à listenForCaptionScrollSpeed — n'a pas rattrapé
     * le texte reçu. Remplace l'ancien indicateur booléen "ça déborde", trop
     * imprécis pour que le proche sache s'il doit ralentir un peu ou beaucoup.
     */
    fun signalCaptionCatchUpLag(callId: String, lagSeconds: Float) {
        callDoc(callId).update(FIELD_CAPTION_CATCHUP_LAG, lagSeconds.toDouble())
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
        private const val FIELD_CALLER_SPEECH = "callerSpeechText"
        private const val FIELD_ALERT_STARTED_AT = "alertStartedAt"
        private const val FIELD_ALERT_DURATION = "alertDurationSeconds"
        private const val FIELD_CAPTION_MODE = "captionModeEnabled"
        private const val FIELD_CAPTION_TEXT_SIZE = "captionTextSize"
        private const val FIELD_FORCE_CONNECT = "forceConnectRequested"
        private const val FIELD_SELF_PREVIEW = "selfPreviewEnabled"
        private const val FIELD_CAPTION_SCROLL_SPEED = "captionMaxScrollSpeedDpPerSec"
        private const val FIELD_CAPTION_CATCHUP_LAG = "captionCatchUpLagSeconds"
        private const val FIELD_CALLEE_ERROR = "calleeErrorMessage"
        private const val FIELD_MIC_MUTED = "tabletMicMuted"

        private const val DEVICE_TOKEN_DOC = "devices/jean_tablet"
        private const val FIELD_FCM_TOKEN = "fcmToken"

        const val STATUS_RINGING = "ringing"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_ENDED = "ended"
    }
}
