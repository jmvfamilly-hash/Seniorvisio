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

// --- Réglages mémorisés d'un appel à l'autre (volume, taille de texte...) ---
// Stockés dans ce navigateur uniquement (localStorage) : chaque proche qui
// appelle depuis son propre téléphone garde ses propres réglages préférés,
// plutôt qu'un réglage partagé côté tablette qui écraserait les préférences
// des autres appelants.
const SETTINGS_STORAGE_KEY = "seniorvisio_caller_settings";
const DEFAULT_SETTINGS = {
  volume: 100,
  captionEnabled: false,
  selfPreview: false,
  textSize: 56,
  scrollSpeed: 50,
};

// --- Identité de l'appelant, mémorisée dans ce navigateur uniquement ---
// Volontairement séparée des réglages d'appel ci-dessus : elle se renseigne
// sur l'écran d'attente, avant tout appel, alors que les réglages se règlent
// pendant l'appel avec un autre bouton. Chaque proche a la sienne sur son
// propre téléphone — pas de compte à créer, pas d'annuaire partagé à tenir.
//
// La photo est redimensionnée avant d'être mémorisée : une photo brute de
// téléphone dépasserait à elle seule la limite de 1 Mio d'un document
// Firestore une fois encodée en base64.
const IDENTITY_STORAGE_KEY = "seniorvisio_caller_identity";
const IDENTITY_PHOTO_MAX_SIDE = 800;
const IDENTITY_PHOTO_QUALITY = 0.72;

function loadIdentity() {
  try {
    const raw = localStorage.getItem(IDENTITY_STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (e) {
    return null;
  }
}

/**
 * Redimensionne la photo choisie et renvoie du base64 brut (sans préfixe
 * "data:"), format attendu tel quel par la tablette
 * (IncomingCallActivity.showCallerPhoto décode directement en Base64).
 * Proportions conservées : le cadrage final est fait côté tablette, en plein
 * écran.
 */
function resizeToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("Lecture du fichier impossible"));
    reader.onload = () => {
      const image = new Image();
      image.onerror = () => reject(new Error("Ce fichier n'est pas une image lisible"));
      image.onload = () => {
        const scale = Math.min(1, IDENTITY_PHOTO_MAX_SIDE / Math.max(image.width, image.height));
        const canvas = document.createElement("canvas");
        canvas.width = Math.round(image.width * scale);
        canvas.height = Math.round(image.height * scale);
        canvas.getContext("2d").drawImage(image, 0, 0, canvas.width, canvas.height);
        resolve(canvas.toDataURL("image/jpeg", IDENTITY_PHOTO_QUALITY).split(",")[1]);
      };
      image.src = reader.result;
    };
    reader.readAsDataURL(file);
  });
}

function loadSavedSettings() {
  try {
    const raw = localStorage.getItem(SETTINGS_STORAGE_KEY);
    if (!raw) return null;
    return { ...DEFAULT_SETTINGS, ...JSON.parse(raw) };
  } catch (e) {
    return null;
  }
}

function currentSettingsFromUi() {
  return {
    volume: Number(els.volumeSlider.value),
    captionEnabled: els.captionToggle.checked,
    selfPreview: els.selfPreviewToggle.checked,
    textSize: Number(els.textSizeSlider.value),
    scrollSpeed: Number(els.scrollSpeedSlider.value),
  };
}

function applySettingsToUi(settings) {
  els.volumeSlider.value = settings.volume;
  els.captionToggle.checked = settings.captionEnabled;
  els.selfPreviewToggle.checked = settings.selfPreview;
  els.textSizeSlider.value = settings.textSize;
  els.scrollSpeedSlider.value = settings.scrollSpeed;
}

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
  toggleViewButton: document.getElementById("toggleViewButton"),
  rememberSettingsButton: document.getElementById("rememberSettingsButton"),
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
  resetTranscriptionButton: document.getElementById("resetTranscriptionButton"),
  resetTranscriptionStatus: document.getElementById("resetTranscriptionStatus"),
  identityName: document.getElementById("identityName"),
  identityPhotoInput: document.getElementById("identityPhotoInput"),
  identityPhotoPreview: document.getElementById("identityPhotoPreview"),
  identityRights: document.getElementById("identityRights"),
  saveIdentityButton: document.getElementById("saveIdentityButton"),
  identityStatus: document.getElementById("identityStatus"),
  silenceIndicator: document.getElementById("silenceIndicator"),
  captionOverflowIndicator: document.getElementById("captionOverflowIndicator"),
  fullscreenCaptionBanner: document.getElementById("fullscreenCaptionBanner"),
  fullscreenCaption: document.getElementById("fullscreenCaption"),
  fullscreenCaptionLag: document.getElementById("fullscreenCaptionLag"),
};

let statsInterval = null;

/**
 * Onglet "visio" (vidéo quasi plein écran, juste Raccrocher + le bouton pour
 * naviguer vers l'écran de réglages) vs onglet "réglages" (l'écran complet
 * construit jusqu'ici : volume, sous-titres, miroir de transcription…).
 * Basculé via une classe sur <body> (voir style.css), actif par défaut dès
 * la connexion — pendant la conversation elle-même, pas besoin des réglages
 * sous les yeux en permanence.
 */
function setVideoMode(active) {
  document.body.classList.toggle("video-mode", active);
  els.toggleViewButton.textContent = active ? "⚙️ Réglages" : "📹 Vidéo";
}

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
    setVideoMode(true);
  } else {
    setVideoMode(false);
  }
}

els.toggleViewButton.addEventListener("click", () => {
  setVideoMode(!document.body.classList.contains("video-mode"));
});

els.callButton.addEventListener("click", async () => {
  const settings = loadSavedSettings() || DEFAULT_SETTINGS;
  applySettingsToUi(settings);
  els.callingHint.textContent = "Connexion à sa tablette…";
  els.countdownFill.style.width = "0%";
  els.countdownText.textContent = "";
  els.transcriptCurrent.textContent = "";
  els.transcriptHistory.innerHTML = "";
  els.silenceIndicator.classList.add("hidden");
  els.captionOverflowIndicator.classList.add("hidden");
  els.fullscreenCaptionBanner.classList.add("hidden");
  // Désactivé tant que l'appel n'est pas prêt (voir plus bas) : un appui
  // pendant la mise en place (caméra, création de l'offre...) tombait dans
  // le vide côté PWA — le document d'appel n'existait pas encore, la
  // demande de connexion immédiate ne partait jamais — tout en désactivant
  // le bouton, sans plus aucun moyen de relancer la connexion pour cet appel.
  els.forceConnectButton.disabled = true;
  showState("calling");
  // Identité renseignée sur l'écran d'attente, sinon repli sur l'ancien
  // comportement : nom générique et capture webcam prise à l'ouverture.
  const identity = loadIdentity() || {};
  await engine.startCall(CONFIG.targetDeviceId, identity.name || CONFIG.callerName, {
    remoteVolume: settings.volume / 100,
    captionModeEnabled: settings.captionEnabled,
    captionTextSize: settings.textSize,
    captionMaxScrollSpeedDpPerSec: settings.scrollSpeed,
    selfPreviewEnabled: settings.selfPreview,
    callerPhotoBase64: identity.photoBase64 || null,
  });
  els.forceConnectButton.disabled = false;
});

els.rememberSettingsButton.addEventListener("click", () => {
  localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(currentSettingsFromUi()));
  const original = els.rememberSettingsButton.textContent;
  els.rememberSettingsButton.textContent = "✅ Réglages mémorisés";
  setTimeout(() => {
    els.rememberSettingsButton.textContent = original;
  }, 2000);
});

engine.onCountdown((remaining, total) => {
  els.callingHint.textContent = "L'alerte s'affiche sur sa tablette…";
  els.countdownFill.style.width = `${Math.round((remaining / total) * 100)}%`;
  els.countdownText.textContent =
    remaining > 0 ? `${remaining}s avant connexion automatique` : "Connexion en cours…";
});

els.captionToggle.addEventListener("change", () => {
  engine.setCaptionMode(els.captionToggle.checked);
  // Le bandeau plein écran ne montre les sous-titres que si Jean les a
  // effectivement affichés — pas de surimpression fantôme sinon.
  els.fullscreenCaptionBanner.classList.toggle("hidden", !els.captionToggle.checked);
});

engine.onFullscreenCaption(({ text, lagSeconds }) => {
  els.fullscreenCaption.textContent = text || "…";
  const lagging = lagSeconds > 0.3;
  els.fullscreenCaptionLag.textContent = lagging ? `⏳ ${lagSeconds.toFixed(1)}s de retard` : "";
  els.fullscreenCaptionLag.classList.toggle("hidden", !lagging);
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

// --- Identité de l'appelant ---
// Photo retenue en mémoire tant qu'elle n'est pas enregistrée : le
// redimensionnement est asynchrone, on ne peut pas le refaire au moment du clic.
let pendingIdentityPhoto = null;

(function restoreIdentity() {
  const identity = loadIdentity();
  if (!identity) return;
  els.identityName.value = identity.name || "";
  els.identityRights.checked = Boolean(identity.rightsAcceptedAt);
  if (identity.photoBase64) {
    els.identityPhotoPreview.src = `data:image/jpeg;base64,${identity.photoBase64}`;
    els.identityPhotoPreview.classList.remove("hidden");
  }
})();

els.identityPhotoInput.addEventListener("change", async () => {
  const file = els.identityPhotoInput.files && els.identityPhotoInput.files[0];
  if (!file) return;
  els.identityStatus.textContent = "Préparation de la photo…";
  try {
    pendingIdentityPhoto = await resizeToBase64(file);
    els.identityPhotoPreview.src = `data:image/jpeg;base64,${pendingIdentityPhoto}`;
    els.identityPhotoPreview.classList.remove("hidden");
    els.identityStatus.textContent = "Photo prête. Cochez l'attestation puis enregistrez.";
  } catch (e) {
    pendingIdentityPhoto = null;
    els.identityStatus.textContent = e.message;
  }
});

els.saveIdentityButton.addEventListener("click", () => {
  const previous = loadIdentity() || {};
  const photoBase64 = pendingIdentityPhoto || previous.photoBase64 || null;

  // L'attestation n'est exigée que s'il y a effectivement une image : un proche
  // qui ne renseigne que son prénom n'a rien à certifier.
  if (photoBase64 && !els.identityRights.checked) {
    els.identityStatus.textContent =
      "Merci de cocher l'attestation de droit à l'image avant d'enregistrer.";
    return;
  }

  localStorage.setItem(IDENTITY_STORAGE_KEY, JSON.stringify({
    name: els.identityName.value.trim(),
    photoBase64,
    rightsAcceptedAt: photoBase64 ? (previous.rightsAcceptedAt || new Date().toISOString()) : null,
  }));
  pendingIdentityPhoto = null;
  els.identityStatus.textContent = "✅ Enregistré. Jean vous verra ainsi au prochain appel.";
});

// Réinitialisation de l'application de transcription de Google sur la tablette
// (voir DeviceStatusReporter.handleTranscriptionReset côté Android). Seul moyen
// de réparer à distance un réglage déréglé dedans : son interface échappe
// entièrement à Senior Visio. Confirmation obligatoire, l'action efface les
// réglages et les transcriptions conservées.
els.resetTranscriptionButton.addEventListener("click", async () => {
  const confirmed = confirm(
    "Remettre l'application de transcription de la tablette dans son état d'origine ?\n\n" +
    "Ses réglages et les transcriptions qu'elle conserve seront effacés. " +
    "Jean n'a rien à faire."
  );
  if (!confirmed) return;

  els.resetTranscriptionButton.disabled = true;
  els.resetTranscriptionStatus.textContent = "Envoi de la demande…";
  try {
    await engine.requestTranscriptionReset();
    els.resetTranscriptionStatus.textContent =
      "Demande envoyée. La tablette l'applique dès qu'elle est en ligne.";
  } catch (e) {
    els.resetTranscriptionStatus.textContent = `Échec de l'envoi : ${e.message}`;
  } finally {
    els.resetTranscriptionButton.disabled = false;
  }
});

els.hangupButton.addEventListener("click", async () => {
  await engine.cancelCall();
  showState("idle");
});

engine.onBlocked(() => showState("blocked"));
engine.onConnected(() => showState("connected"));
engine.onEnded(() => showState("idle"));
engine.onError((message) => {
  alert(message);
  showState("idle");
});
