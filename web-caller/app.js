/**
 * Point d'entrée du PWA appelant. Câblage UI uniquement : le contrat
 * CallEngine est dans call-engine.js, l'implémentation WebRTC réelle dans
 * webrtc-engine.js (voir ces fichiers, chargés avant celui-ci dans index.html).
 */

// --- Paramètres, alignés avec AdminConfig côté Android ---
const CONFIG = {
  targetDeviceId: "jean-tablette-01", // non utilisé par le signaling Firestore (un seul foyer), gardé pour usage futur multi-tablette
  callerName: "Un proche",
};

// --- Câblage UI ---
const engine = new RealCallEngine(FIREBASE_CONFIG);

const els = {
  idle: document.getElementById("stateIdle"),
  calling: document.getElementById("stateCalling"),
  blocked: document.getElementById("stateBlocked"),
  connected: document.getElementById("stateConnected"),
  callButton: document.getElementById("callButton"),
  cancelButton: document.getElementById("cancelButton"),
  retryButton: document.getElementById("retryButton"),
  hangupButton: document.getElementById("hangupButton"),
};

function showState(name) {
  ["idle", "calling", "blocked", "connected"].forEach((s) => {
    els[s].classList.toggle("hidden", s !== name);
  });
}

els.callButton.addEventListener("click", async () => {
  showState("calling");
  await engine.startCall(CONFIG.targetDeviceId, CONFIG.callerName);
});

els.cancelButton.addEventListener("click", async () => {
  await engine.cancelCall();
  showState("idle");
});

els.retryButton.addEventListener("click", () => showState("idle"));

els.hangupButton.addEventListener("click", async () => {
  await engine.cancelCall();
  showState("idle");
});

engine.onBlocked(() => showState("blocked"));
engine.onConnected(() => showState("connected"));
engine.onEnded(() => showState("idle"));
