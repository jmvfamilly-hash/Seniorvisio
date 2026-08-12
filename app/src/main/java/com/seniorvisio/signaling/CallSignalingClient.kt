package com.seniorvisio.signaling

import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

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

    /** Notifie uniquement des appels *nouvellement* mis en sonnerie après l'appel de cette fonction. */
    fun listenForRingingCalls(onIncoming: (callId: String, callerName: String) -> Unit): ListenerRegistration {
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
                        onIncoming(change.document.id, callerName)
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

    fun addCandidate(callId: String, candidate: RemoteIceCandidate) {
        callDoc(callId).collection(CALLEE_CANDIDATES).add(
            mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "candidate" to candidate.candidate
            )
        )
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
        private const val FIELD_OFFER_SDP = "offerSdp"
        private const val FIELD_ANSWER_SDP = "answerSdp"

        const val STATUS_RINGING = "ringing"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_ENDED = "ended"
    }
}
