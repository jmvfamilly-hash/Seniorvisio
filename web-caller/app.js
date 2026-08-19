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
  forceConnectButton: document.getElementById("forceConnectButton"),
  retryButton: document.getElementById("retryButton"),
  hangupButton: document.getElementById("hangupButton"),
  callStats: document.getElementById("callStats"),
  volumeSlider: document.getElementById("volumeSlider"),
  captionToggle: document.getElementById("captionToggle"),
  selfPreviewToggle: document.getElementById("selfPreviewToggle"),
  textSizeSlider: document.getElementById("textSizeSlider"),
  scrollSpeedSlider: document.getElementById("scrollSpeedSlider"),
  callingHint: document.getElementById("callingHint"),
  countdownFill: document.getElementById("countdownFill"),
  countdownText: document.getElementById("countdownText"),
  transcriptCurrent: document.getElementById("transcriptCurrent"),
  transcriptHistory: document.getElementById("transcriptHistory"),
  silenceIndicator: document.getElementById("silenceIndicator"),
  captionOverflowIndicator: document.getElementById("captionOverflowIndicator"),
};

let statsInterval = null;

function showState(name) {
  ["idle", "calling", "blocked", "connected"].forEach((s) => {
    els[s].classList.toggle("hidden", s !== name);
  });

  if (statsInterval) {
    clearInterval(statsInterval);
    statsInterval = null;
  }
  if (name === "connected") {
    statsInterval = setInterval(async () => {
      els.callStats.textContent = await engine.getStatsSummary();
    }, 2000);
  }
}

els.callButton.addEventListener("click", async () => {
  els.volumeSlider.value = 100;
  els.captionToggle.checked = false;
  els.selfPreviewToggle.checked = false;
  els.textSizeSlider.value = 56;
  els.scrollSpeedSlider.value = 50;
  els.callingHint.textContent = "Connexion à sa tablette…";
  els.countdownFill.style.width = "0%";
  els.countdownText.textContent = "";
  els.transcriptCurrent.textContent = "";
  els.transcriptHistory.innerHTML = "";
  els.silenceIndicator.classList.add("hidden");
  els.captionOverflowIndicator.classList.add("hidden");
  els.forceConnectButton.disabled = false;
  showState("calling");
  await engine.startCall(CONFIG.targetDeviceId, CONFIG.callerName);
});

engine.onCountdown((remaining, total) => {
  els.callingHint.textContent = "L'alerte s'affiche sur sa tablette…";
  els.countdownFill.style.width = `${Math.round((remaining / total) * 100)}%`;
  els.countdownText.textContent =
    remaining > 0 ? `${remaining}s avant connexion automatique` : "Connexion en cours…";
});

els.captionToggle.addEventListener("change", () => {
  engine.setCaptionMode(els.captionToggle.checked);
});

els.selfPreviewToggle.addEventListener("change", () => {
  engine.setSelfPreviewMode(els.selfPreviewToggle.checked);
});

/** Classe CSS selon la confiance de reconnaissance (repère visuel des passages mal transcrits). */
function confidenceClass(confidence) {
  if (confidence == null) return "";
  if (confidence < 0.5) return "low-confidence";
  if (confidence < 0.75) return "mid-confidence";
  return "";
}

engine.onTranscript(({ liveText, isFinal, confidence, history }) => {
  els.transcriptCurrent.textContent = liveText || "…";
  els.transcriptCurrent.className = "transcript-current" + (isFinal ? " " + confidenceClass(confidence) : "");

  els.transcriptHistory.innerHTML = "";
  history.slice(0, -1).forEach((entry) => {
    const li = document.createElement("li");
    li.textContent = entry.text;
    li.className = confidenceClass(entry.confidence);
    els.transcriptHistory.appendChild(li);
  });
});

engine.onSilenceDetected((silent) => {
  els.silenceIndicator.classList.toggle("hidden", !silent);
});

engine.onCaptionCatchUpLag((lagSeconds) => {
  const lagging = lagSeconds > 0.3; // en dessous, pas perceptible pour Jean
  if (lagging) {
    els.captionOverflowIndicator.textContent =
      `⏳ Jean a environ ${lagSeconds.toFixed(1)}s de retard sur ta voix, ralentis un peu`;
  }
  els.captionOverflowIndicator.classList.toggle("hidden", !lagging);
});

let textSizeDebounce = null;
els.textSizeSlider.addEventListener("input", () => {
  clearTimeout(textSizeDebounce);
  textSizeDebounce = setTimeout(() => {
    engine.setCaptionTextSize(Number(els.textSizeSlider.value));
  }, 150);
});

let scrollSpeedDebounce = null;
els.scrollSpeedSlider.addEventListener("input", () => {
  clearTimeout(scrollSpeedDebounce);
  scrollSpeedDebounce = setTimeout(() => {
    engine.setCaptionScrollSpeed(Number(els.scrollSpeedSlider.value));
  }, 150);
});

let volumeDebounce = null;
els.volumeSlider.addEventListener("input", () => {
  clearTimeout(volumeDebounce);
  volumeDebounce = setTimeout(() => {
    engine.setRemoteVolume(Number(els.volumeSlider.value) / 100);
  }, 150);
});

els.forceConnectButton.addEventListener("click", () => {
  els.forceConnectButton.disabled = true;
  engine.forceConnect();
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
