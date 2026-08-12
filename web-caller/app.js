/**
 * Point d'entrée du PWA appelant. Architecture volontairement identique
 * en esprit à la partie Android : une interface CallEngine abstraite,
 * une implémentation concrète interchangeable derrière.
 *
 * TODO : remplacer StubCallEngine par une vraie implémentation
 * (Stream Video JS SDK, Agora Web SDK, ou signaling WebRTC maison)
 * une fois le choix du SDK managé validé côté Android.
 */

// --- Interface abstraite (contrat) ---
class CallEngine {
  async startCall(targetId) { throw new Error("not implemented"); }
  async cancelCall() { throw new Error("not implemented"); }
  onBlocked(callback) { throw new Error("not implemented"); }
  onConnected(callback) { throw new Error("not implemented"); }
  onEnded(callback) { throw new Error("not implemented"); }
}

// --- Implémentation provisoire, à remplacer par le vrai SDK ---
class StubCallEngine extends CallEngine {
  constructor() {
    super();
    this._blockedCb = null;
    this._connectedCb = null;
    this._endedCb = null;
  }

  async startCall(targetId) {
    console.log(`[StubCallEngine] Démarrage appel vers ${targetId}`);
    // TODO: initier le signaling réel ici (ex: client.call('default', targetId))
  }

  async cancelCall() {
    console.log("[StubCallEngine] Appel annulé côté appelant");
  }

  onBlocked(callback) { this._blockedCb = callback; }
  onConnected(callback) { this._connectedCb = callback; }
  onEnded(callback) { this._endedCb = callback; }
}

// --- Paramètres, alignés avec AdminConfig côté Android ---
const CONFIG = {
  targetDeviceId: "jean-tablette-01", // TODO: à rendre configurable (ex: sélection contact)
};

// --- Câblage UI ---
const engine = new StubCallEngine();

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
  await engine.startCall(CONFIG.targetDeviceId);
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
