/**
 * Point d'entrée du PWA appelant. Câblage UI uniquement : le contrat
 * CallEngine est dans call-engine.js, l'implémentation WebRTC réelle dans
 * webrtc-engine.js (voir ces fichiers, chargés avant celui-ci dans index.html).
 */

document.getElementById("pwaVersion").textContent = `v. ${window.PWA_VERSION || "?"}`;

// --- Mode soignant -------------------------------------------------------
// Ouvert en scannant le QR code affiché sur l'écran d'accueil de la tablette
// (voir MainActivity.showCaregiverQrCode). Destiné à quelqu'un qui est DANS la
// pièce avec Jean — soignant, visiteur — et veut lui parler sans hausser la
// voix : sa parole s'écrit en grand sur la tablette.
//
// Tout ce qui a du sens pour un appel venu de l'extérieur est retiré ici :
// pas de décompte (la personne est déjà là), pas de photo d'appelant (Jean la
// voit en vrai), pas de vidéo, pas de réglages — et surtout aucun son côté
// tablette, sans quoi le téléphone du soignant, à quelques centimètres,
// provoquerait un larsen immédiat.
const CAREGIVER_MODE = new URLSearchParams(location.search).has("soignant");
if (CAREGIVER_MODE) document.body.classList.add("caregiver-mode");

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
 *
 * Constaté en usage réel : l'appel suivant échouait après le choix de
 * certaines photos, systématiquement les plus lourdes (JPEG haute résolution
 * tout droit sortis de l'appareil photo, plusieurs mégaoctets). L'ancienne
 * version lisait le fichier en base64 (FileReader.readAsDataURL) AVANT de le
 * décoder en image : pour une photo de 8 Mo, ça veut dire ~11 Mo de texte en
 * mémoire en plus de l'image décodée, ce qui suffit à mettre certains
 * navigateurs mobiles sous pression mémoire au point de couper l'accès
 * caméra/micro du site. createObjectURL référence le fichier directement,
 * sans jamais construire ce texte intermédiaire.
 */
function resizeToBase64(file, maxSide = IDENTITY_PHOTO_MAX_SIDE, quality = IDENTITY_PHOTO_QUALITY) {
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(file);
    const image = new Image();
    const cleanup = () => URL.revokeObjectURL(objectUrl);
    image.onerror = () => {
      cleanup();
      reject(new Error("Ce fichier n'est pas une image lisible"));
    };
    image.onload = () => {
      try {
        const scale = Math.min(1, maxSide / Math.max(image.width, image.height));
        const canvas = document.createElement("canvas");
        canvas.width = Math.round(image.width * scale);
        canvas.height = Math.round(image.height * scale);
        canvas.getContext("2d").drawImage(image, 0, 0, canvas.width, canvas.height);
        resolve(canvas.toDataURL("image/jpeg", quality).split(",")[1]);
      } catch (e) {
        reject(e);
      } finally {
        cleanup();
      }
    };
    image.src = objectUrl;
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
  paneVideo: document.getElementById("paneVideo"),
  paneSettings: document.getElementById("paneSettings"),
  paneSlideshow: document.getElementById("paneSlideshow"),
  openSettingsButton: document.getElementById("openSettingsButton"),
  openSlideshowButton: document.getElementById("openSlideshowButton"),
  rememberSettingsButton: document.getElementById("rememberSettingsButton"),
  callStats: document.getElementById("callStats"),
  volumeSlider: document.getElementById("volumeSlider"),
  captionToggle: document.getElementById("captionToggle"),
  tabletMicMuteToggle: document.getElementById("tabletMicMuteToggle"),
  slideshowInput: document.getElementById("slideshowInput"),
  slideshowNav: document.getElementById("slideshowNav"),
  slideshowPrevButton: document.getElementById("slideshowPrevButton"),
  slideshowNextButton: document.getElementById("slideshowNextButton"),
  slideshowCounter: document.getElementById("slideshowCounter"),
  slideshowPreview: document.getElementById("slideshowPreview"),
  slideshowStopButton: document.getElementById("slideshowStopButton"),
  slideshowRememberToggle: document.getElementById("slideshowRememberToggle"),
  sameRoomToggle: document.getElementById("sameRoomToggle"),
  sameRoomStatus: document.getElementById("sameRoomStatus"),
  slideshowStatus: document.getElementById("slideshowStatus"),
  selfPreviewToggle: document.getElementById("selfPreviewToggle"),
  textSizeSlider: document.getElementById("textSizeSlider"),
  scrollSpeedSlider: document.getElementById("scrollSpeedSlider"),
  callingHint: document.getElementById("callingHint"),
  countdownFill: document.getElementById("countdownFill"),
  countdownText: document.getElementById("countdownText"),
  identityName: document.getElementById("identityName"),
  identityPhotoInput: document.getElementById("identityPhotoInput"),
  identityPhotoPreview: document.getElementById("identityPhotoPreview"),
  identityRights: document.getElementById("identityRights"),
  saveIdentityButton: document.getElementById("saveIdentityButton"),
  identityStatus: document.getElementById("identityStatus"),
  captionOverflowIndicator: document.getElementById("captionOverflowIndicator"),
  captionDebugIndicator: document.getElementById("captionDebugIndicator"),
  // Réplique de l'écran de Jean (voir applyScreenLayout / applyScreenState).
  jeanScreen: document.getElementById("jeanScreen"),
  jeanZones: document.getElementById("jeanZones"),
  jeanSlideshow: document.getElementById("jeanSlideshow"),
  jeanMoment: document.getElementById("jeanMoment"),
  jeanWeather: document.getElementById("jeanWeather"),
  jeanDate: document.getElementById("jeanDate"),
  jeanRoomText: document.getElementById("jeanRoomText"),
  jeanCallText: document.getElementById("jeanCallText"),
};

let statsInterval = null;

/**
 * Trois fenêtres pendant l'appel, une seule visible à la fois : la vidéo (ce
 * que Jean voit), les réglages, et les photos. La vidéo est celle qui
 * s'ouvre à la connexion — pendant la conversation elle-même, ni les
 * réglages ni le choix des photos n'ont à occuper l'écran.
 *
 * Trois vues plein écran plutôt que trois fenêtres de navigateur : le proche
 * appelle depuis son téléphone, où une seconde fenêtre est au mieux un
 * onglet qu'on ne retrouve pas, au pire un popup bloqué — et surtout, quitter
 * la fenêtre qui porte la vidéo mettrait l'appel WebRTC en arrière-plan.
 */
const PANES = ["paneVideo", "paneSettings", "paneSlideshow"];

function showPane(name) {
  PANES.forEach((pane) => els[pane].classList.toggle("hidden", pane !== name));
  document.body.classList.toggle("video-mode", name === "paneVideo");
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
    showPane("paneVideo");
  } else {
    document.body.classList.remove("video-mode");
  }
}

els.openSettingsButton.addEventListener("click", () => showPane("paneSettings"));
els.openSlideshowButton.addEventListener("click", () => showPane("paneSlideshow"));
document.querySelectorAll("[data-back-to-video]").forEach((button) => {
  button.addEventListener("click", () => showPane("paneVideo"));
});

/**
 * Dessine la réplique de l'écran de Jean d'après ce que la tablette en dit :
 * proportions réelles de sa dalle, ordre des zones tel que réglé par l'admin,
 * palette claire ou sombre en cours, contenu de la zone d'information.
 *
 * Tout vient de la tablette (voir CallSignalingClient.publishScreenLayout)
 * plutôt que d'être recalculé ici : le proche peut être dans une autre ville
 * (météo différente) et l'ordre des zones n'existe que côté tablette. Le
 * recalculer donnerait une réplique qui diverge, ce qui viderait de son sens
 * l'idée même de lui montrer ce que Jean voit.
 */
function applyScreenLayout(layout) {
  if (layout.aspectRatio) {
    els.jeanScreen.style.aspectRatio = String(layout.aspectRatio);
  }
  els.jeanScreen.classList.toggle("jean-dark", layout.isDark);
  els.jeanScreen.classList.toggle("jean-light", !layout.isDark);
  els.jeanMoment.textContent = layout.infoMoment;
  els.jeanWeather.textContent = layout.infoWeather;
  els.jeanDate.textContent = layout.infoDate;

  // Réordonne les zones sans les recréer : leur contenu et leur état
  // d'affichage survivent au changement.
  layout.zoneOrder.split(",").forEach((zoneName) => {
    const zone = els.jeanZones.querySelector(`[data-zone="${zoneName}"]`);
    if (zone) els.jeanZones.appendChild(zone);
  });
}

/**
 * Affiche dans la réplique exactement le texte que Jean a sous les yeux, au
 * moment où il l'a — pas ce que le proche vient de dire, qui a toujours de
 * l'avance (voir PacedCaptionZone côté tablette).
 */
function applyScreenState(state) {
  const setZone = (zoneName, textElement, text) => {
    const zone = els.jeanZones.querySelector(`[data-zone="${zoneName}"]`);
    textElement.textContent = text || "";
    if (zone) zone.classList.toggle("hidden", !text);
  };
  setZone("ROOM", els.jeanRoomText, state.roomText);
  setZone("CALL", els.jeanCallText, state.callText);

  // En dessous d'une demi-seconde, l'écart ne se voit pas : le signaler
  // ferait clignoter un avertissement en permanence pendant une conversation
  // parfaitement normale.
  const lagging = state.lagSeconds > 0.5;
  if (lagging) {
    els.captionOverflowIndicator.textContent =
      `⏳ Jean a encore ${state.lagSeconds.toFixed(0)}s de lecture devant lui, laisse-lui le temps`;
  }
  els.captionOverflowIndicator.classList.toggle("hidden", !lagging);
}

els.callButton.addEventListener("click", async () => {
  const settings = loadSavedSettings() || DEFAULT_SETTINGS;
  applySettingsToUi(settings);
  els.callingHint.textContent = "Connexion à sa tablette…";
  els.countdownFill.style.width = "0%";
  els.countdownText.textContent = "";
  // Remis à zéro à chaque appel : un micro resté coupé d'un appel précédent
  // rendrait Jean muet sans que personne ne comprenne pourquoi.
  els.tabletMicMuteToggle.checked = false;
  els.sameRoomToggle.checked = false;
  els.sameRoomStatus.textContent = "";
  els.volumeSlider.disabled = false;
  els.captionOverflowIndicator.classList.add("hidden");
  els.captionDebugIndicator.classList.add("hidden");
  // La réplique de l'écran de Jean repart vide : les textes du dernier appel
  // ne doivent pas réapparaître le temps que la tablette publie les siens.
  applyScreenState({ roomText: null, callText: null, lagSeconds: 0 });
  els.jeanSlideshow.classList.add("hidden");
  // Désactivé tant que l'appel n'est pas prêt (voir plus bas) : un appui
  // pendant la mise en place (caméra, création de l'offre...) tombait dans
  // le vide côté PWA — le document d'appel n'existait pas encore, la
  // demande de connexion immédiate ne partait jamais — tout en désactivant
  // le bouton, sans plus aucun moyen de relancer la connexion pour cet appel.
  els.forceConnectButton.disabled = true;
  showState("calling");
  if (CAREGIVER_MODE) {
    els.callingHint.textContent = "Connexion immédiate…";
    await engine.startCall(CONFIG.targetDeviceId, "Un soignant", {
      // Aucun son chez Jean : le soignant parle de vive voix dans la pièce,
      // la tablette ne fait qu'écrire. Sans ça, larsen immédiat.
      remoteVolume: 0,
      tabletMicMuted: true,
      // Les sous-titres sont toute la raison d'être de ce mode : activés
      // d'office, jamais à cocher.
      captionModeEnabled: true,
      captionTextSize: DEFAULT_SETTINGS.textSize,
      captionMaxScrollSpeedDpPerSec: DEFAULT_SETTINGS.scrollSpeed,
      selfPreviewEnabled: false,
      // Ni décompte, ni photo, ni caméra : voir le commentaire de CAREGIVER_MODE.
      forceConnect: true,
      skipPhoto: true,
      audioOnly: true,
    });
  } else {
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
  }
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
});

els.selfPreviewToggle.addEventListener("change", () => {
  engine.setSelfPreviewMode(els.selfPreviewToggle.checked);
});

// Coupe le micro de la tablette (voir WebRtcCallEngine.listenForMicMute côté
// Android). Sert de test décisif pour localiser un écho : s'il disparaît en
// cochant cette case, il vient de la tablette ; s'il persiste, il vient de ce
// téléphone-ci. Volontairement non mémorisé d'un appel à l'autre : Jean se
// retrouverait muet sans que personne ne comprenne pourquoi.
els.tabletMicMuteToggle.addEventListener("change", () => {
  engine.setTabletMicMuted(els.tabletMicMuteToggle.checked);
});

engine.onScreenState(applyScreenState);
engine.onScreenLayout(applyScreenLayout);

engine.onCaptionDebug((message) => {
  els.captionDebugIndicator.textContent = `🔧 ${message}`;
  els.captionDebugIndicator.classList.remove("hidden");
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

const IDENTITY_JUST_SAVED_KEY = "seniorvisio_identity_just_saved";

(function restoreIdentity() {
  const identity = loadIdentity();
  if (identity) {
    els.identityName.value = identity.name || "";
    els.identityRights.checked = Boolean(identity.rightsAcceptedAt);
    if (identity.photoBase64) {
      els.identityPhotoPreview.src = `data:image/jpeg;base64,${identity.photoBase64}`;
      els.identityPhotoPreview.classList.remove("hidden");
    }
  }

  // Confirmation après le rechargement automatique qui suit l'enregistrement
  // d'une photo (voir plus bas) : sans ce message, la page se contente de se
  // rouvrir sur l'écran d'attente, ce qui peut sembler être un bug plutôt
  // qu'un comportement volontaire.
  if (sessionStorage.getItem(IDENTITY_JUST_SAVED_KEY)) {
    sessionStorage.removeItem(IDENTITY_JUST_SAVED_KEY);
    document.getElementById("identityPanel").open = true;
    els.identityStatus.textContent = "✅ Enregistré. Vous pouvez maintenant appeler Jean.";
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
  const photoJustCaptured = Boolean(pendingIdentityPhoto);
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

  if (!photoJustCaptured) {
    els.identityStatus.textContent = "✅ Enregistré. Jean vous verra ainsi au prochain appel.";
    return;
  }

  // Recharge automatiquement après le choix d'une NOUVELLE photo (pas après
  // un simple changement de prénom) : sur iOS Safari comme sur beaucoup de
  // navigateurs Android, choisir une image via <input type=file> fait
  // passer la page en arrière-plan un instant, et le système invalide
  // ensuite l'accès caméra/micro du site — l'appel suivant échoue
  // immédiatement (getUserMedia refusé) tant que la page n'a pas été
  // rechargée. Constaté en usage réel sur deux appareils différents
  // (Android puis iPad) avant ce correctif. L'identité vient d'être
  // enregistrée dans localStorage, donc rien n'est perdu au rechargement —
  // voir restoreIdentity() pour le message de confirmation qui suit.
  els.identityStatus.textContent = "✅ Enregistré. Réouverture de la page pour que l'appel fonctionne…";
  sessionStorage.setItem(IDENTITY_JUST_SAVED_KEY, "1");
  setTimeout(() => location.reload(), 1200);
});

// --- Diaporama commenté ---------------------------------------------------
// Le proche choisit des photos dans son téléphone et les fait défiler ; Jean
// les voit en grand sans rien manipuler, avec les commentaires du proche en
// sous-titres (voir WebRtcCallEngine.listenForSlideshowPhoto côté Android).
//
// Photos redimensionnées plus grand que la photo d'identité (elles sont
// regardées en plein écran sur une dalle de dix pouces, pas en vignette), mais
// assez compressées pour tenir largement dans un document Firestore, qui n'en
// transporte de toute façon qu'une à la fois.
const SLIDESHOW_STORAGE_KEY = "seniorvisio_slideshow_photos";
const SLIDESHOW_PHOTO_MAX_SIDE = 1280;
const SLIDESHOW_PHOTO_QUALITY = 0.72;
// Le stockage local d'un navigateur est limité (quelques mégaoctets) : au-delà,
// l'enregistrement échoue d'un coup. Mieux vaut une limite claire et annoncée
// qu'un échec incompréhensible au moment de mémoriser.
const SLIDESHOW_MAX_PHOTOS = 15;

let slideshowPhotos = [];
let slideshowIndex = 0;

function renderSlideshowState() {
  const hasPhotos = slideshowPhotos.length > 0;
  els.slideshowNav.classList.toggle("hidden", !hasPhotos);
  els.slideshowPreview.classList.toggle("hidden", !hasPhotos);
  els.slideshowStopButton.classList.toggle("hidden", !hasPhotos);
  if (!hasPhotos) return;

  els.slideshowCounter.textContent = `${slideshowIndex + 1} / ${slideshowPhotos.length}`;
  els.slideshowPreview.src = `data:image/jpeg;base64,${slideshowPhotos[slideshowIndex]}`;
  els.slideshowPrevButton.disabled = slideshowIndex === 0;
  els.slideshowNextButton.disabled = slideshowIndex === slideshowPhotos.length - 1;
}

/**
 * Envoie la photo courante chez Jean (rien si aucun appel n'est en cours), et
 * la pose dans la réplique de son écran : c'est bien ce qu'il a sous les yeux
 * à la place de la vidéo tant que le diaporama tourne.
 */
function pushCurrentSlide() {
  if (!slideshowPhotos.length) return;
  const photo = slideshowPhotos[slideshowIndex];
  engine.setSlideshowPhoto(photo);
  els.jeanSlideshow.src = `data:image/jpeg;base64,${photo}`;
  els.jeanSlideshow.classList.remove("hidden");
}

function showSlide(index) {
  slideshowIndex = Math.max(0, Math.min(index, slideshowPhotos.length - 1));
  renderSlideshowState();
  pushCurrentSlide();
}

(function restoreSlideshow() {
  try {
    const raw = localStorage.getItem(SLIDESHOW_STORAGE_KEY);
    if (!raw) return;
    slideshowPhotos = JSON.parse(raw);
    els.slideshowRememberToggle.checked = true;
    renderSlideshowState();
    els.slideshowStatus.textContent =
      `${slideshowPhotos.length} photo(s) gardée(s) de la dernière fois.`;
  } catch (e) {
    slideshowPhotos = [];
  }
})();

els.slideshowInput.addEventListener("change", async () => {
  const files = Array.from(els.slideshowInput.files || []);
  if (!files.length) return;
  els.slideshowStatus.textContent = `Préparation de ${files.length} photo(s)…`;

  const prepared = [];
  for (const file of files) {
    try {
      prepared.push(await resizeToBase64(file, SLIDESHOW_PHOTO_MAX_SIDE, SLIDESHOW_PHOTO_QUALITY));
    } catch (e) {
      // Une photo illisible (format exotique, fichier corrompu) ne doit pas
      // faire échouer toute la sélection.
      console.warn("[Diaporama] Photo ignorée :", e);
    }
  }

  if (!prepared.length) {
    els.slideshowStatus.textContent = "Aucune de ces photos n'a pu être lue.";
    return;
  }

  const tooMany = prepared.length > SLIDESHOW_MAX_PHOTOS;
  slideshowPhotos = prepared.slice(0, SLIDESHOW_MAX_PHOTOS);
  slideshowIndex = 0;
  renderSlideshowState();
  pushCurrentSlide();
  els.slideshowStatus.textContent = tooMany
    ? `${SLIDESHOW_MAX_PHOTOS} premières photos retenues (limite de cet appareil).`
    : `${slideshowPhotos.length} photo(s) prête(s).`;

  if (els.slideshowRememberToggle.checked) saveSlideshow();
});

els.slideshowPrevButton.addEventListener("click", () => showSlide(slideshowIndex - 1));
els.slideshowNextButton.addEventListener("click", () => showSlide(slideshowIndex + 1));

// Termine le diaporama : la vidéo du proche réapparaît chez Jean, la sélection
// de photos reste en place pour pouvoir relancer sans tout re-choisir.
els.slideshowStopButton.addEventListener("click", () => {
  engine.setSlideshowPhoto(null);
  els.jeanSlideshow.classList.add("hidden");
  els.slideshowStatus.textContent = "Diaporama arrêté, Jean revoit la vidéo.";
});

function saveSlideshow() {
  try {
    localStorage.setItem(SLIDESHOW_STORAGE_KEY, JSON.stringify(slideshowPhotos));
    els.slideshowStatus.textContent = `${slideshowPhotos.length} photo(s) gardée(s) sur cet appareil.`;
  } catch (e) {
    // Quota dépassé : le dire franchement plutôt que de laisser croire que
    // c'est enregistré.
    els.slideshowRememberToggle.checked = false;
    els.slideshowStatus.textContent =
      "Trop de photos pour la mémoire de ce navigateur : elles marchent pour cet appel, mais ne seront pas gardées.";
  }
}

els.slideshowRememberToggle.addEventListener("change", () => {
  if (els.slideshowRememberToggle.checked) {
    saveSlideshow();
  } else {
    localStorage.removeItem(SLIDESHOW_STORAGE_KEY);
    els.slideshowStatus.textContent = "Photos non gardées après cet appel.";
  }
});

// Mode "même pièce" : le proche est à côté de Jean et lui parle de vive voix.
// La tablette ne doit alors ni capter sa voix (elle reviendrait en écho dans
// le téléphone) ni la rejouer avec une seconde de décalage. Le texte, lui,
// continue de s'afficher — c'est même toute la raison d'appeler depuis le
// fauteuil d'à côté.
//
// La tablette applique elle-même la coupure (voir
// WebRtcCallEngine.listenForSameRoomMode) plutôt que de la déduire d'un
// volume à zéro : un curseur remonté par inadvertance ramènerait sinon
// l'écho, sans que rien n'indique pourquoi.
els.sameRoomToggle.addEventListener("change", () => {
  const sameRoom = els.sameRoomToggle.checked;
  engine.setSameRoomMode(sameRoom);
  els.tabletMicMuteToggle.checked = sameRoom;
  engine.setTabletMicMuted(sameRoom);
  els.volumeSlider.disabled = sameRoom;
  els.sameRoomStatus.textContent = sameRoom
    ? "Son de la tablette entièrement coupé. Vos paroles continuent de s'écrire chez Jean."
    : "";
});

els.hangupButton.addEventListener("click", async () => {
  await engine.cancelCall();
  showState("idle");
});

// Le mode soignant réduit l'écran à sa plus simple expression : un bouton pour
// parler, un pour terminer, et la réplique de l'écran de Jean — le seul retour
// qui dit au soignant que sa voix est bien captée et transcrite. Le reste
// (photo, réglages, vidéo) est masqué par la feuille de style ; ici on ne
// change que ce qui doit être formulé autrement.
if (CAREGIVER_MODE) {
  document.querySelector("h1").textContent = "Parler à Jean";
  els.callButton.textContent = "🗣️ Commencer à parler";
  els.hangupButton.textContent = "Terminer";
  // Répété avant ET pendant : c'est le contresens le plus probable, et le
  // réflexe de baisser le téléphone pour s'adresser à la personne revient vite.
  document.getElementById("caregiverIdleHint").textContent =
    "🎤 Parlez dans votre téléphone, comme au téléphone : c'est lui qui vous écoute. La tablette de Jean ne fait qu'écrire, sans aucun son.";
  document.getElementById("caregiverCallHint").textContent =
    "🎤 Gardez le téléphone près de vous et parlez dedans, à voix normale.";
  document.getElementById("mirrorHint").textContent = "👆 Ce que Jean lit en ce moment";
}

engine.onBlocked(() => showState("blocked"));
engine.onConnected(() => showState("connected"));
engine.onEnded(() => showState("idle"));
engine.onError((message) => {
  alert(message);
  showState("idle");
});
