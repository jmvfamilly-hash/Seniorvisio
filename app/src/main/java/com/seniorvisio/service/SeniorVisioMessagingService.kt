package com.seniorvisio.service

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.seniorvisio.signaling.CallSignalingClient

/**
 * Réveil de l'app par notification push (FCM) en complément de la connexion
 * Firestore permanente de CallListenerService : celle-ci peut être suspendue
 * par Android une fois l'écran éteint depuis un moment (Doze), alors qu'un
 * message FCM en priorité haute (voir functions/index.js) est le seul
 * mécanisme qu'Android garantit de faire percer cette mise en veille — sans
 * ça, un appel entrant ne réveillait la tablette de façon fiable que si
 * l'écran restait allumé en permanence.
 *
 * Payload volontairement minimal (callId + nom, pas la photo) : FCM limite
 * chaque message à 4 Ko, largement dépassé par une photo encodée en base64 ;
 * le reste de l'écran d'appel (photo, vidéo...) suit son circuit habituel
 * une fois l'appel décroché.
 */
class SeniorVisioMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CallSignalingClient().registerDeviceToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["type"] != TYPE_INCOMING_CALL) return
        val callId = message.data["callId"] ?: return
        val callerName = message.data["callerName"] ?: "un proche"
        val intent = Intent(this, IncomingCallService::class.java).apply {
            putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
            putExtra(IncomingCallService.EXTRA_CALLER_NAME, callerName)
        }
        startForegroundService(intent)
    }

    companion object {
        private const val TYPE_INCOMING_CALL = "incoming_call"
    }
}
