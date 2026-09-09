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
  // Document d'état de la tablette dans Firestore : signe de vie, batterie,
  // version installée, et réglages de transcription pilotés d'ici. Doit rester
  // identique à DEVICE_DOC_PATH dans core/DeviceStatusReporter.kt.
  deviceDocId: "jean_tablet",
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
  };
}

function applySettingsToUi(settings) {
  els.volumeSlider.value = settings.volume;
  els.captionToggle.checked = settings.captionEnabled;
  els.selfPreviewToggle.checked = settings.selfPreview;
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
  blockedMessage: document.getElementById("blockedMessage"),
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
  slideshowStopButton: document.getElementById("slideshowStopButton"),
  slideshowRememberToggle: document.getElementById("slideshowRememberToggle"),
  sameRoomToggle: document.getElementById("sameRoomToggle"),
  sameRoomStatus: document.getElementById("sameRoomStatus"),
  slideshowStatus: document.getElementById("slideshowStatus"),
  selfPreviewToggle: document.getElementById("selfPreviewToggle"),
  scrollSpeedSlider: document.getElementById("scrollSpeedSlider"),
  captionLinesSlider: document.getElementById("captionLinesSlider"),
  captionClearDelaySlider: document.getElementById("captionClearDelaySlider"),
  micToRoomControl: document.getElementById("micToRoomControl"),
  micToRoomToggle: document.getElementById("micToRoomToggle"),
  micToRoomStatus: document.getElementById("micToRoomStatus"),
  openAdminIdleButton: document.getElementById("openAdminIdleButton"),
  openAdminCallButton: document.getElementById("openAdminCallButton"),
  adminOverlay: document.getElementById("adminOverlay"),
  adminLock: document.getElementById("adminLock"),
  adminPanel: document.getElementById("adminPanel"),
  adminPinInput: document.getElementById("adminPinInput"),
  adminUnlockButton: document.getElementById("adminUnlockButton"),
  adminCancelButton: document.getElementById("adminCancelButton"),
  adminCloseButton: document.getElementById("adminCloseButton"),
  adminLockStatus: document.getElementById("adminLockStatus"),
  micToRoomBanner: document.getElementById("micToRoomBanner"),
  micToRoomBackButton: document.getElementById("micToRoomBackButton"),
  callingHint: document.getElementById("callingHint"),
  countdownFill: document.getElementById("countdownFill"),
  countdownText: document.getElementById("countdownText"),
  identityName: document.getElementById("identityName"),
  identityPhotoInput: document.getElementById("identityPhotoInput"),
  identityPhotoPreview: document.getElementById("identityPhotoPreview"),
  identityRights: document.getElementById("identityRights"),
  saveIdentityButton: document.getElementById("saveIdentityButton"),
  identityStatus: document.getElementById("identityStatus"),
  roomEngineSelect: document.getElementById("roomEngineSelect"),
  callEngineSelect: document.getElementById("callEngineSelect"),
  voskModelSelect: document.getElementById("voskModelSelect"),
  engineStatus: document.getElementById("engineStatus"),
  roomWakeEnabledToggle: document.getElementById("roomWakeEnabledToggle"),
  roomWakeThresholdSlider: document.getElementById("roomWakeThresholdSlider"),
  blockWakeAtNightToggle: document.getElementById("blockWakeAtNightToggle"),
  roomListeningStatus: document.getElementById("roomListeningStatus"),
  transcriptionDiagnostic: document.getElementById("transcriptionDiagnostic"),
  paidUsage: document.getElementById("paidUsage"),
  voiceGateToggle: document.getElementById("voiceGateToggle"),
  quotaAssemblyaiSlider: document.getElementById("quotaAssemblyaiSlider"),
  quotaGladiaSlider: document.getElementById("quotaGladiaSlider"),
  refreshUsageButton: document.getElementById("refreshUsageButton"),
  usageSummary: document.getElementById("usageSummary"),
  usageDays: document.getElementById("usageDays"),
  restartAppButton: document.getElementById("restartAppButton"),
  rebootDeviceButton: document.getElementById("rebootDeviceButton"),
  commandStatus: document.getElementById("commandStatus"),
  captionOverflowIndicator: document.getElementById("captionOverflowIndicator"),
  // Réplique de l'écran de Jean (voir applyScreenLayout / applyScreenState).
  jeanScreen: document.getElementById("jeanScreen"),
  jeanSlideshow: document.getElementById("jeanSlideshow"),
  jeanRoomText: document.getElementById("jeanRoomText"),
  jeanCallText: document.getElementById("jeanCallText"),
  jeanRoomBox: document.getElementById("jeanRoomBox"),
  jeanCallBox: document.getElementById("jeanCallBox"),
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

  // La date, la météo et le moment de la journée ne sont plus répliqués :
  // l'écran de Jean cesse de les afficher dès qu'un appel se présente (voir
  // IncomingCallActivity), et une réplique qui montre ce qu'il n'a pas sous
  // les yeux ne réplique rien. La tablette continue de les publier — son
  // écran d'accueil les affiche toujours — simplement, cet écran-ci les
  // ignore.
  //
  // Le réordonnancement des zones disparaît avec elles : les deux pavés de
  // texte sont passés sous la vidéo et portent chacun son étiquette, il n'y
  // avait plus que la zone d'information à réordonner, seule dans son
  // conteneur.

  applyCaptionGeometry(layout);
}

/**
 * Donne aux deux pavés de texte la géométrie réelle mesurée chez Jean : autant
 * de caractères par ligne, autant de lignes.
 *
 * L'unité `ch` vaut la largeur du chiffre zéro de la fonte courante : en fonte
 * à chasse fixe — celle imposée à ces pavés — c'est exactement la largeur d'un
 * caractère quelconque, donc `Nch` tient N caractères, ni plus ni moins. C'est
 * ce qui fait couper les lignes aux mêmes endroits que sur la tablette.
 *
 * Sans valeur publiée (tablette d'une version antérieure), on ne force rien :
 * le pavé reprend la largeur disponible, ce qui reste lisible même si les
 * coupures ne correspondent plus.
 */
function applyCaptionGeometry(layout) {
  const chars = Number(layout.captionCharsPerLine);
  const lines = Number(layout.captionLines);
  [els.jeanRoomText, els.jeanCallText].forEach((element) => {
    element.style.width = Number.isFinite(chars) && chars > 0 ? `${chars}ch` : "";
    // Une hauteur fixée en lignes, et le débordement masqué comme chez Jean :
    // sa zone ne grandit pas non plus, elle fait défiler.
    element.style.height = Number.isFinite(lines) && lines > 0 ? `${lines * 1.35}em` : "";
  });
}


// Repère de silence inséré par la tablette dans le fil de la parole (voir
// SILENCE_MARKER dans RollingCaptionZone.kt). Doit rester identique des deux
// côtés : c'est la seule chose qui les relie.
const SILENCE_MARKER = "<silence>";

/**
 * Pose le texte de Jean dans la réplique, les marques de silence dans le même
 * style que chez lui — plus petites et en italique. La réplique est censée
 * montrer ce qu'il voit, et le rythme de la parole en fait partie.
 *
 * Construit nœud par nœud plutôt qu'en innerHTML : ce texte sort d'un moteur
 * de reconnaissance vocale, personne ne garantit qu'il ne contiendra jamais
 * quelque chose ressemblant à une balise.
 */
function renderJeanText(element, text) {
  element.textContent = "";
  if (!text) return;
  text.split(SILENCE_MARKER).forEach((part, index) => {
    if (index > 0) {
      const mark = document.createElement("em");
      mark.className = "silence-mark";
      mark.textContent = SILENCE_MARKER;
      element.appendChild(mark);
    }
    if (part) element.appendChild(document.createTextNode(part));
  });
}

/**
 * Affiche dans la réplique exactement le texte que Jean a sous les yeux, au
 * moment où il l'a — pas ce que le proche vient de dire, qui a toujours de
 * l'avance (voir PacedCaptionZone côté tablette).
 */
function applyScreenState(state) {
  const setCaption = (box, textElement, text) => {
    renderJeanText(textElement, text);
    // La fin du texte, pas le début : la zone de Jean défile et lui montre ce
    // qu'il est en train de lire. Afficher le haut du tampon montrerait ce
    // qu'il a lu il y a une minute.
    textElement.scrollTop = textElement.scrollHeight;
    // Un pavé vide disparaît, exactement comme la zone chez Jean : garder un
    // cadre vide donnerait à croire que quelque chose est affiché là-bas.
    box.classList.toggle("hidden", !text);
  };
  setCaption(els.jeanRoomBox, els.jeanRoomText, state.roomText);
  setCaption(els.jeanCallBox, els.jeanCallText, state.callText);

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
  els.micToRoomControl.classList.remove("hidden");
  els.micToRoomToggle.checked = false;
  els.micToRoomBanner.classList.add("hidden");
  els.micToRoomStatus.textContent = "";
  els.captionOverflowIndicator.classList.add("hidden");
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
      selfPreviewEnabled: false,
      // Ni décompte, ni photo, ni caméra : voir le commentaire de CAREGIVER_MODE.
      forceConnect: true,
      skipPhoto: true,
      audioOnly: true,
      // Scanner le QR code de la tablette, c'est être debout devant elle :
      // le mode "même pièce" est acquis par construction, sans rien à cocher
      // ni à détecter. C'est le second des deux chemins vers ce mode, l'autre
      // étant la case des réglages.
      sameRoomMode: true,
    });
  } else {
    // Identité renseignée sur l'écran d'attente, sinon repli sur l'ancien
    // comportement : nom générique et capture webcam prise à l'ouverture.
    const identity = loadIdentity() || {};
    await engine.startCall(CONFIG.targetDeviceId, identity.name || CONFIG.callerName, {
      remoteVolume: settings.volume / 100,
      captionModeEnabled: settings.captionEnabled,
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

// Sans cet abonnement, les messages de diagnostic publiés par la tablette
// étaient reçus dans le document d'appel puis jetés : une transcription muette
// n'avait donc aucune explication nulle part. C'est ici qu'on apprend si le son
// arrive, quel moteur a été retenu, et pourquoi il n'écrit rien.
// Diagnostic de l'appel en cours : conservé ici, affiché dans le panneau
// d'administration et non sur l'écran vidéo. Il arrive souvent alors que le
// panneau est fermé — d'où la mémorisation, sans quoi l'information la plus
// utile serait précisément celle qu'on aurait manquée en ne regardant pas au
// bon moment.
let lastCallDiagnostic = "";

engine.onCaptionDebug((message) => {
  lastCallDiagnostic = message;
  renderTranscriptionDiagnostic();
});

engine.onScreenState(applyScreenState);
engine.onScreenLayout(applyScreenLayout);

// Ergonomie de lecture : réglages d'ADMINISTRATEUR et non d'appel. Ils
// décrivent la façon dont Jean lit, qui ne change pas selon qui l'appelle, et
// ils doivent valoir aussi pour ce qui se dit dans la pièce — c'est-à-dire
// l'essentiel des journées de la tablette, où personne n'est au bout du fil.
// Ils passent donc par le document d'appareil et s'appliquent tout de suite
// (voir DeviceStatusReporter, et applyCaptionErgonomics côté tablette).
//
// Les avoir laissés à chaque appelant faisait varier l'écran de Jean d'un
// appel à l'autre sans que personne ne sache pourquoi.
const ADMIN_SLIDER_FIELDS = [
  ["captionLinesSlider", "captionVisibleLines"],
  ["scrollSpeedSlider", "captionScrollSpeedDp"],
  ["captionClearDelaySlider", "captionClearDelaySeconds"],
  ["roomWakeThresholdSlider", "roomWakeThreshold"],
  // Plafonds mensuels des services payants. Le champ porte le nom du moteur
  // pour que l'ajout d'un troisième service n'oblige pas à inventer une
  // nouvelle convention (voir DeviceStatusReporter, FIELD_QUOTA_PREFIX).
  ["quotaAssemblyaiSlider", "quotaHours_assemblyai"],
  ["quotaGladiaSlider", "quotaHours_gladia"],
];

// Mêmes réglages d'appareil, mais en tout ou rien.
const ADMIN_TOGGLE_FIELDS = [
  ["roomWakeEnabledToggle", "roomWakeEnabled"],
  ["blockWakeAtNightToggle", "blockWakeAtNight"],
  ["voiceGateToggle", "voiceGateEnabled"],
];

for (const [elementKey, field] of ADMIN_TOGGLE_FIELDS) {
  els[elementKey].addEventListener("change", () => {
    if (!deviceSettingsLoaded) return;
    engine
      .setDeviceSetting(CONFIG.deviceDocId, field, els[elementKey].checked)
      .catch((e) => console.warn("[app] Réglage tablette non transmis :", e));
  });
}

// --- Valeur numérique à côté de chaque curseur ----------------------------
// Un curseur seul ne dit pas où il est. « Efface après ce temps sans parole »
// à mi-course, c'est 30 secondes ou 12 ? Impossible à savoir, donc impossible
// de reproduire un réglage, de le dire à quelqu'un au téléphone, ou de
// constater qu'on vient de le changer par mégarde.
//
// Fait pour tous les curseurs de la page d'un coup, plutôt que curseur par
// curseur : celui qu'on ajoutera demain sera affiché sans qu'on y pense.
// L'unité vient de l'attribut data-unit posé dans le HTML.
function renderSliderValue(input) {
  const output = input.nextElementSibling;
  if (!output || !output.classList.contains("slider-value")) return;
  output.textContent = `${input.value}${input.dataset.unit || ""}`;
}

function refreshAllSliderValues() {
  document.querySelectorAll('input[type="range"]').forEach(renderSliderValue);
}

document.querySelectorAll('input[type="range"]').forEach((input) => {
  const output = document.createElement("span");
  output.className = "slider-value";
  input.insertAdjacentElement("afterend", output);
  input.addEventListener("input", () => renderSliderValue(input));
  renderSliderValue(input);
});

const adminSliderDebounce = {};
for (const [elementKey, field] of ADMIN_SLIDER_FIELDS) {
  els[elementKey].addEventListener("input", () => {
    if (!deviceSettingsLoaded) return;
    clearTimeout(adminSliderDebounce[field]);
    // Le curseur produit une écriture par pixel parcouru : sans ce délai, un
    // seul geste coûterait des dizaines d'écritures Firestore et autant de
    // relectures sur la tablette.
    adminSliderDebounce[field] = setTimeout(() => {
      engine
        .setDeviceSetting(CONFIG.deviceDocId, field, Number(els[elementKey].value))
        .catch((e) => console.warn("[app] Réglage tablette non transmis :", e));
    }, 250);
  });
}

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


// --- Administration ------------------------------------------------------
// Deux catégories d'utilisateur, une seule application.
//
// L'utilisateur standard appelle Jean, voit ce qu'il a sous les yeux, décide
// si ses paroles s'écrivent, peut faire écouter la pièce, règle le volume, dit
// qu'il est dans la même pièce, et gère ses photos. Rien de tout cela ne peut
// abîmer durablement la tablette : ces réglages vivent dans le document
// d'appel et meurent en raccrochant.
//
// L'administrateur voit exactement le même écran — c'est lui aussi quelqu'un
// qui appelle — et bascule vers les réglages de la tablette par un code. Ces
// réglages-là passent par le document d'appareil et s'appliquent tout de
// suite, appel ou pas, pour tout le monde.
//
// Le code est celui de l'écran admin de la tablette : elle en publie
// l'empreinte avec son signe de vie (voir DeviceStatusReporter.
// adminPinFingerprint). Un seul code à retenir, changé au même endroit.
//
// Ce n'est pas une barrière de sécurité et il ne faut pas lui faire dire
// autre chose : les règles Firestore de ce projet laissent écrire quiconque
// connaît l'adresse, et quatre chiffres se retrouvent instantanément à partir
// de leur empreinte. C'est un garde-fou contre la fausse manœuvre d'un proche
// qui explore l'application, rien de plus.
const ADMIN_UNLOCKED_KEY = "seniorvisio_admin_unlocked";

// Empreinte publiée par la tablette. Null tant qu'elle n'a pas donné signe de
// vie, ou si elle tourne encore une version qui ne la publie pas.
let adminPinFingerprint = null;

/** Déverrouillage retenu le temps de l'onglet seulement, jamais au-delà. */
function isAdminUnlocked() {
  try {
    return sessionStorage.getItem(ADMIN_UNLOCKED_KEY) === "1";
  } catch (e) {
    return false;
  }
}

function rememberAdminUnlocked() {
  try {
    sessionStorage.setItem(ADMIN_UNLOCKED_KEY, "1");
  } catch (e) {
    // Navigation privée, stockage refusé : sans effet, le code sera
    // simplement redemandé à la prochaine ouverture du panneau.
  }
}

async function sha256Hex(text) {
  const bytes = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Les deux sources de diagnostic dans un seul bloc, côté administration.
 *
 * L'une vient du signe de vie de la tablette — toutes les cinq minutes, valable
 * hors appel — l'autre du document de l'appel en cours, immédiate mais
 * limitée à sa durée. Les afficher au même endroit évite d'avoir à savoir
 * laquelle regarder ; les distinguer évite de croire qu'un message d'il y a
 * cinq minutes décrit l'appel en cours.
 */
let lastDeviceDiagnostic = "";

function renderTranscriptionDiagnostic() {
  const lines = [];
  if (lastCallDiagnostic) lines.push(`🛠️ Appel en cours : ${lastCallDiagnostic}`);
  if (lastDeviceDiagnostic) lines.push(`🛠️ Tablette : ${lastDeviceDiagnostic}`);
  els.transcriptionDiagnostic.textContent = lines.join("\n");
}

function openAdmin() {
  els.adminOverlay.classList.remove("hidden");
  const unlocked = isAdminUnlocked();
  els.adminLock.classList.toggle("hidden", unlocked);
  els.adminPanel.classList.toggle("hidden", !unlocked);
  els.adminLockStatus.textContent = "";
  els.adminPinInput.value = "";
  if (unlocked) loadUsage();
  else els.adminPinInput.focus();
}

function closeAdmin() {
  els.adminOverlay.classList.add("hidden");
}

async function tryUnlockAdmin() {
  const typed = els.adminPinInput.value.trim();
  if (!typed) return;

  if (!adminPinFingerprint) {
    // Sans empreinte publiée, impossible de vérifier quoi que ce soit. Refuser
    // plutôt que d'ouvrir : mieux vaut un panneau inaccessible le temps que la
    // tablette se manifeste qu'un panneau qui s'ouvre sans contrôle.
    els.adminLockStatus.textContent =
      "La tablette n'a pas encore donné signe de vie : code invérifiable pour l'instant.";
    return;
  }

  els.adminUnlockButton.disabled = true;
  try {
    if ((await sha256Hex(typed)) !== adminPinFingerprint) {
      els.adminLockStatus.textContent = "Code incorrect.";
      els.adminPinInput.value = "";
      return;
    }
    rememberAdminUnlocked();
    els.adminLock.classList.add("hidden");
    els.adminPanel.classList.remove("hidden");
    loadUsage();
  } catch (e) {
    // crypto.subtle n'existe qu'en contexte sécurisé (HTTPS ou localhost). Le
    // PWA est servi en HTTPS, mais le dire explicitement évite de chercher
    // longtemps si quelqu'un l'ouvre un jour autrement.
    console.warn("[app] Vérification du code impossible :", e);
    els.adminLockStatus.textContent =
      "Vérification impossible sur cette page (connexion non sécurisée ?).";
  } finally {
    els.adminUnlockButton.disabled = false;
  }
}

els.openAdminIdleButton.addEventListener("click", openAdmin);
els.openAdminCallButton.addEventListener("click", openAdmin);
els.adminUnlockButton.addEventListener("click", tryUnlockAdmin);
els.adminCancelButton.addEventListener("click", closeAdmin);
els.adminCloseButton.addEventListener("click", closeAdmin);
els.adminPinInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") tryUnlockAdmin();
});


// --- Utilisation et commandes ---------------------------------------------
// Ce que la tablette a réellement fait de ses journées, publié par elle
// (voir UsageStats et DeviceStatusReporter.publishUsage). Chargé à la
// demande plutôt qu'écouté en permanence : ces documents ne changent qu'au
// signe de vie, toutes les cinq minutes.

const SLOTS_PER_DAY = 96;

function formatDuration(seconds) {
  const total = Math.max(0, Math.round(Number(seconds) || 0));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (hours > 0) return `${hours} h ${String(minutes).padStart(2, "0")}`;
  if (minutes > 0) return `${minutes} min`;
  return `${total} s`;
}

/**
 * Une journée en 96 tranches d'un quart d'heure. L'opacité de chaque tranche
 * dit la proportion de ce quart d'heure passée écran allumé — un total
 * quotidien ne dirait ni à quelles heures il y a de l'activité, ni si elle est
 * groupée ou éparpillée, qui est justement ce qu'on cherche à voir.
 */
function renderDayBar(day) {
  const slots = Array.isArray(day.awakeSlots) ? day.awakeSlots : [];
  const bar = document.createElement("div");
  bar.className = "usage-bar";
  for (let i = 0; i < SLOTS_PER_DAY; i++) {
    const cell = document.createElement("div");
    cell.className = "usage-slot";
    // 900 secondes = un quart d'heure entier éveillé.
    const share = Math.min(1, (Number(slots[i]) || 0) / 900);
    cell.style.opacity = share === 0 ? 0.08 : 0.25 + share * 0.75;
    bar.appendChild(cell);
  }
  return bar;
}

function renderUsageDays(days) {
  els.usageDays.textContent = "";
  days.forEach((day) => {
    const block = document.createElement("div");
    block.className = "usage-day";

    const label = document.createElement("div");
    label.className = "usage-day-label";
    const left = document.createElement("span");
    left.textContent = day.date;
    const right = document.createElement("span");
    const callCount = Array.isArray(day.calls) ? day.calls.length : 0;
    right.textContent = `éveil ${formatDuration(day.awakeSeconds)} · ${callCount} appel${callCount > 1 ? "s" : ""}`;
    label.append(left, right);

    const hours = document.createElement("div");
    hours.className = "usage-hours";
    ["0 h", "6 h", "12 h", "18 h", "24 h"].forEach((h) => {
      const span = document.createElement("span");
      span.textContent = h;
      hours.appendChild(span);
    });

    block.append(label, renderDayBar(day), hours);

    // Le détail des appels : l'heure et la durée, ce qui permet de rapprocher
    // une plage d'éveil d'un appel plutôt que d'une visite.
    if (callCount > 0) {
      const list = document.createElement("div");
      list.className = "usage-day-label";
      const detail = document.createElement("span");
      detail.textContent = day.calls
        .map((c) => `${String(c.h).padStart(2, "0")}:${String(c.m).padStart(2, "0")} (${formatDuration(c.d)})`)
        .join(" · ");
      list.appendChild(detail);
      block.appendChild(list);
    }

    els.usageDays.appendChild(block);
  });
}

function renderUsageSummary(days) {
  els.usageSummary.textContent = "";
  if (days.length === 0) {
    const empty = document.createElement("p");
    empty.className = "hint";
    empty.textContent = "Aucune journée publiée pour l'instant.";
    els.usageSummary.appendChild(empty);
    return;
  }

  const today = days[0];
  const week = days.slice(0, 7);
  const sum = (field) => week.reduce((total, d) => total + (Number(d[field]) || 0), 0);
  const engineSum = (name) =>
    week.reduce((total, d) => total + (Number((d.engineSeconds || {})[name]) || 0), 0);

  const billableEquivalent = sum("billableEquivalentSeconds");
  const actuallyPaid = engineSum("assemblyai");

  const box = document.createElement("div");
  box.className = "usage-figures";
  const lines = [
    `<strong>Aujourd'hui</strong> — éveil ${formatDuration(today.awakeSeconds)}, sommeil ${formatDuration(today.asleepSeconds)}`,
    `<strong>Moyenne par jour sur ${week.length} jour${week.length > 1 ? "s" : ""}</strong> — éveil ${formatDuration(sum("awakeSeconds") / week.length)}`,
    `<strong>Transcription (${week.length} j)</strong> — embarqué ${formatDuration(engineSum("vosk"))}, Android ${formatDuration(engineSum("android"))}, AssemblyAI ${formatDuration(actuallyPaid)}`,
    `<strong>Facturé par AssemblyAI</strong> — ${formatDuration(actuallyPaid)}`,
    `<strong>Ce qu'il aurait facturé pour tout</strong> — ${formatDuration(billableEquivalent)}`,
    `<span class="usage-saving">Économisé — ${formatDuration(billableEquivalent - actuallyPaid)}</span>`,
  ];
  // Construit ligne par ligne avec un balisage fixe : seules les durées, que
  // nous calculons nous-mêmes, varient — aucun texte venu de la tablette n'est
  // interprété ici.
  lines.forEach((html) => {
    const p = document.createElement("p");
    p.style.margin = "0";
    p.innerHTML = html;
    els.usageSummary.appendChild(p);
  });
  els.usageSummary.appendChild(box);
}

async function loadUsage() {
  els.refreshUsageButton.disabled = true;
  try {
    const days = await engine.readUsageDays(CONFIG.deviceDocId, 8);
    renderUsageSummary(days);
    renderUsageDays(days);
  } catch (e) {
    console.warn("[app] Lecture de l'usage impossible :", e);
    els.usageSummary.textContent = "Lecture de l'usage impossible.";
  } finally {
    els.refreshUsageButton.disabled = false;
  }
}

els.refreshUsageButton.addEventListener("click", loadUsage);

async function sendCommand(command, confirmation) {
  if (!window.confirm(confirmation)) return;
  els.commandStatus.textContent = "Commande envoyée, en attente de la tablette…";
  try {
    await engine.sendDeviceCommand(CONFIG.deviceDocId, command);
    // La tablette écoute son document en permanence : elle exécute dès qu'elle
    // reçoit, sans attendre le prochain signe de vie.
    els.commandStatus.textContent =
      "Commande transmise. La tablette met quelques secondes à repartir.";
  } catch (e) {
    console.warn("[app] Commande non transmise :", e);
    els.commandStatus.textContent = "Commande non transmise (réseau ?).";
  }
}

els.restartAppButton.addEventListener("click", () =>
  sendCommand("restart-app", "Relancer l'application sur la tablette de Jean ?")
);
els.rebootDeviceButton.addEventListener("click", () =>
  sendCommand("reboot", "Redémarrer complètement la tablette de Jean ? Elle sera indisponible une minute ou deux.")
);

// --- Moteur de transcription de la tablette, réglé à distance ---
// Sur l'écran d'accueil et non dans les réglages d'appel : le moteur de la
// pièce écoute toute la journée, il doit pouvoir être changé sans avoir à
// déranger Jean par un appel. La tablette relit ce réglage à chaque bloc de
// son (voir TranscriptionEngine.feed), la bascule prend donc effet en pleine
// phrase — ce qui est exactement ce qu'il faut pour comparer deux moteurs sur
// la même voix.

// Ce que la tablette a réellement enregistré, pour ne pas écraser un réglage
// venu d'ailleurs par le simple fait d'afficher la page (les <select>
// démarrent sur leur première option, qui n'est pas forcément la vraie).
let deviceSettingsLoaded = false;

const ENGINE_SELECT_FIELDS = [
  ["roomEngineSelect", "roomTranscriptionEngine"],
  ["callEngineSelect", "callTranscriptionEngine"],
  ["voskModelSelect", "voskModelSize"],
];

function applyDeviceSettings(data) {
  deviceSettingsLoaded = true;

  // Empreinte du code d'accès, publiée par la tablette avec son signe de vie
  // (voir DeviceStatusReporter.adminPinFingerprint). Elle arrive par le même
  // canal que les réglages : un seul abonnement au document d'appareil.
  adminPinFingerprint = data.adminPinFingerprint || null;

  // Les curseurs reflètent ce que la tablette applique réellement, et pas la
  // dernière position touchée sur CE téléphone : plusieurs personnes peuvent
  // administrer, et l'affichage doit dire l'état de la tablette.
  for (const [elementKey, field] of ADMIN_SLIDER_FIELDS) {
    const value = Number(data[field]);
    // Zéro accepté, contrairement aux autres curseurs : sur un plafond, il
    // veut dire « sans limite » et non « jamais réglé ». Le refuser ferait
    // revenir le curseur à dix heures à chaque signe de vie, en écrasant
    // silencieusement le choix de l'administrateur.
    const floor = field.startsWith("quotaHours_") ? 0 : 1;
    if (Number.isFinite(value) && value >= floor) els[elementKey].value = value;
  }
  // Affecter .value par programme ne déclenche aucun événement « input » :
  // sans ce rappel, le curseur bougerait en affichant l'ancien nombre, ce qui
  // est pire que de n'en afficher aucun.
  refreshAllSliderValues();
  for (const [elementKey, field] of ADMIN_TOGGLE_FIELDS) {
    // Ces deux-là sont vrais par défaut côté tablette (voir AdminConfig) :
    // un champ absent veut donc dire « jamais réglé d'ici », pas « désactivé ».
    if (typeof data[field] === "boolean") els[elementKey].checked = data[field];
  }
  els.roomWakeEnabledToggle.checked = data.roomWakeEnabled !== false;
  // Vrai par défaut côté tablette : un champ absent veut dire « jamais réglé
  // d'ici », pas « désactivé ».
  els.voiceGateToggle.checked = data.voiceGateEnabled !== false;

  // Le seuil ne se règle pas sans voir le niveau qu'il doit dépasser : la
  // tablette republie avec son signe de vie le pic mesuré depuis le précédent,
  // l'état de la capture et le blocage nocturne (voir
  // DeviceStatusReporter.describeRoomListening).
  els.roomListeningStatus.textContent = data.roomListening
    ? `Écoute de la pièce : ${data.roomListening}`
    : "En attente du premier signe de vie de la tablette…";

  // Le diagnostic de transcription ne partait jusqu'ici que dans le document
  // d'un appel en cours : hors appel — c'est-à-dire quand on règle justement
  // le moteur de la pièce — il n'allait nulle part, et cet écran restait vide
  // sans que rien n'indique pourquoi.
  els.paidUsage.textContent = data.paidUsage ? `💳 Ce mois-ci : ${data.paidUsage}` : "";
  lastDeviceDiagnostic = data.transcriptionDiagnostic || "";
  renderTranscriptionDiagnostic();

  for (const [elementKey, field] of ENGINE_SELECT_FIELDS) {
    const select = els[elementKey];
    const value = data[field];
    // Une valeur inconnue (réglage écrit à la main, ancienne version) est
    // ignorée plutôt qu'imposée au <select>, qui retomberait sinon sur une
    // sélection vide donnant l'impression que rien n'est configuré.
    if (value && [...select.options].some((option) => option.value === value)) {
      select.value = value;
      select.dataset.appliedValue = value;
    }
  }
  renderEngineStatus(data);
}

function renderEngineStatus(data) {
  const modelState = data.voskModelState;
  const heartbeat = data.lastHeartbeatAt;
  if (!modelState) {
    els.engineStatus.textContent = heartbeat
      ? "La tablette n'a pas encore signalé l'état de son modèle embarqué."
      : "En attente du premier signe de vie de la tablette…";
    return;
  }
  // Le téléchargement du grand modèle (1,4 Go) peut durer une heure : sans ce
  // retour, la personne qui vient de le demander, souvent à l'autre bout du
  // pays, n'aurait aucun moyen de distinguer un transfert qui avance d'un
  // échec silencieux.
  els.engineStatus.textContent = `Modèle embarqué : ${modelState}`;
}

async function writeDeviceSetting(field, value, select) {
  const previous = select.dataset.appliedValue;
  select.disabled = true;
  try {
    await engine.setDeviceSetting(CONFIG.deviceDocId, field, value);
    select.dataset.appliedValue = value;
  } catch (e) {
    console.warn("[app] Réglage de transcription non transmis :", e);
    // Remis dans son état précédent : laisser le <select> afficher un choix
    // que la tablette n'a jamais reçu ferait croire la bascule faite.
    if (previous) select.value = previous;
    els.engineStatus.textContent =
      "Réglage non transmis (réseau ?). La tablette garde son moteur actuel.";
  } finally {
    select.disabled = false;
  }
}

for (const [elementKey, field] of ENGINE_SELECT_FIELDS) {
  els[elementKey].addEventListener("change", () => {
    if (!deviceSettingsLoaded) return;
    writeDeviceSetting(field, els[elementKey].value, els[elementKey]);
  });
}

engine.watchDeviceSettings(CONFIG.deviceDocId, applyDeviceSettings);

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
  els.slideshowStopButton.classList.toggle("hidden", !hasPhotos);
  if (!hasPhotos) return;

  els.slideshowCounter.textContent = `${slideshowIndex + 1} / ${slideshowPhotos.length}`;
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
  // Sans objet quand on est déjà dans la pièce : la personne qui parle à Jean,
  // c'est soi, et son micro est justement coupé.
  els.micToRoomControl.classList.toggle("hidden", sameRoom);
  if (sameRoom && els.micToRoomToggle.checked) setMicToRoom(false);
});

// Quelqu'un est entré dans la chambre de Jean et lui parle : la transcription
// écoute la pièce plutôt que la voix de l'appelant, pour que Jean puisse
// suivre cette conversation-là par écrit. Le son continue de circuler dans les
// deux sens — l'appelant peut donc parler avec la personne présente pendant ce
// temps, c'est même tout l'intérêt.
//
// Côté Jean, la zone d'appel n'est pas masquée : elle perd sa source et
// s'efface d'elle-même après le délai habituel, exactement comme après un
// silence. Les deux zones gardent leur place, il retrouve donc toujours
// chaque chose au même endroit.
function setMicToRoom(enabled) {
  els.micToRoomToggle.checked = enabled;
  engine.setMicToRoom(enabled);
  els.micToRoomBanner.classList.toggle("hidden", !enabled);
  els.micToRoomStatus.textContent = enabled
    ? "Jean lit ce qui se dit autour de lui. Vous restez audible et pouvez lui parler, mais vos paroles ne s'écrivent plus."
    : "";
}

els.micToRoomToggle.addEventListener("change", () => setMicToRoom(els.micToRoomToggle.checked));
els.micToRoomBackButton.addEventListener("click", () => setMicToRoom(false));

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

// Deux façons de ne pas aboutir, qui n'ont rien à voir l'une avec l'autre :
// Jean a refusé, ou il était déjà en ligne avec quelqu'un d'autre. Dans le
// second cas il n'a même pas été dérangé — le dire évite de faire croire à un
// refus, et évite surtout de rappeler dans la seconde en pensant à une fausse
// manœuvre.
engine.onBlocked((reason) => {
  els.blockedMessage.textContent = reason === "busy"
    ? "Jean est déjà en communication avec quelqu'un. Réessayez dans quelques minutes."
    : "Jean a bloqué l'appel.";
  showState("blocked");
});
engine.onConnected(() => showState("connected"));
engine.onEnded(() => showState("idle"));
engine.onError((message) => {
  alert(message);
  showState("idle");
});
