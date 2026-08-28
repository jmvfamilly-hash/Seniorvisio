const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

// Document unique où la tablette enregistre son token FCM courant (voir
// CallSignalingClient.registerDeviceToken côté Android). Une seule tablette
// déployée pour l'instant : pas besoin d'une collection par appareil.
const DEVICE_TOKEN_DOC = "devices/jean_tablet";

/**
 * Réveille la tablette par notification push dès qu'un appel apparaît dans
 * Firestore, en complément de l'écoute Firestore permanente déjà en place
 * côté Android (CallListenerService). Cette écoute permanente peut être
 * suspendue par Android une fois l'écran éteint depuis un moment (Doze) ;
 * un message FCM en priorité haute est le seul mécanisme qu'Android garantit
 * de faire percer cette mise en veille, sans avoir à garder l'écran allumé
 * en permanence.
 *
 * Payload volontairement minimal (callId + nom de l'appelant, pas la photo) :
 * FCM limite chaque message à 4 Ko, largement dépassé par une photo encodée
 * en base64.
 */
exports.notifyIncomingCall = onDocumentCreated("calls/{callId}", async (event) => {
  const call = event.data?.data();
  if (!call || call.status !== "ringing") return;

  const deviceSnap = await getFirestore().doc(DEVICE_TOKEN_DOC).get();
  const token = deviceSnap.get("fcmToken");
  if (!token) {
    console.warn("Aucun token FCM enregistré pour la tablette : réveil push impossible pour cet appel.");
    return;
  }

  await getMessaging().send({
    token,
    android: { priority: "high" },
    data: {
      type: "incoming_call",
      callId: event.params.callId,
      callerName: String(call.callerName || "un proche"),
    },
  });
});

/**
 * Réveille la tablette et active les sous-titres de la pièce à la demande
 * du proche depuis le PWA (voir web-caller/webrtc-engine.js,
 * activateRoomCaptions), sans passer par un appel vidéo. Même mécanisme que
 * notifyIncomingCall ci-dessus : un document Firestore seul ne réveillerait
 * pas une tablette dont l'écran est éteint depuis un moment (Doze).
 */
exports.notifyRoomCaptionRequest = onDocumentCreated("roomCaptionRequests/{requestId}", async () => {
  const deviceSnap = await getFirestore().doc(DEVICE_TOKEN_DOC).get();
  const token = deviceSnap.get("fcmToken");
  if (!token) {
    console.warn("Aucun token FCM enregistré pour la tablette : activation à distance des sous-titres impossible.");
    return;
  }

  await getMessaging().send({
    token,
    android: { priority: "high" },
    data: {
      type: "activate_room_captions",
    },
  });
});
