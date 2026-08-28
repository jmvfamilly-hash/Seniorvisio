/**
 * Contrat abstrait du moteur d'appel côté PWA — pendant JS de
 * core/CallEngine.kt côté Android. Permet de garder l'implémentation WebRTC
 * (webrtc-engine.js) interchangeable et l'UI (app.js) indépendante du
 * transport.
 */
class CallEngine {
  /** initialSettings (optionnel) : réglages à appliquer dès la création de l'appel (voir app.js, "Mémoriser ces réglages"). */
  async startCall(targetId, callerName, initialSettings) { throw new Error("not implemented"); }
  async cancelCall() { throw new Error("not implemented"); }
  /** Réveille la tablette et active les sous-titres de la pièce, sans appel vidéo. */
  async activateRoomCaptions() { throw new Error("not implemented"); }
  onBlocked(callback) { throw new Error("not implemented"); }
  onConnected(callback) { throw new Error("not implemented"); }
  onEnded(callback) { throw new Error("not implemented"); }
  /** callback(message: string) — l'appel n'a pas pu démarrer (caméra/micro inaccessible, etc.). */
  onError(callback) { throw new Error("not implemented"); }
}
