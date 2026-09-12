/**
 * Point d'entrée du PWA appelant. Câblage UI uniquement : le contrat
 * CallEngine est dans call-engine.js, l'implémentation WebRTC réelle dans
 * webrtc-engine.js (voir ces fichiers, chargés avant celui-ci dans index.html).
 */

document.getElementById("pwaVersion").textContent = `v. ${window.PWA_VERSION || "?"}`;

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
    const saved = { ...DEFAULT_SETTINGS, ...JSON.parse(raw) };
    // ═══ UN VOLUME À ZÉRO NE SE MÉMORISE JAMAIS ═══
    //
    // Couper le son est un geste d'appel, pas une préférence. Personne ne
    // décide « à l'avenir, Jean n'entendra rien » — on baisse le curseur à
    // fond une fois, pour une raison du moment, et on raccroche.
    //
    // Mémorisé, ce zéro rouvrait l'appel suivant en silence : le document
    // d'appel était créé avec remoteVolume = 0, la tablette obéissait
    // parfaitement, et rien nulle part ne disait pourquoi Jean n'entendait
    // plus rien. Des jours de recherche du côté de la tablette, alors que la
    // consigne de silence partait d'ici.
    //
    // C'est exactement la faute déjà corrigée sur la coupure micro — quelques
    // lignes plus bas, avec le même commentaire — et sur pendingMicMuted côté
    // Android. Une consigne d'appel qui survit à l'appel, trois fois de suite,
    // au même endroit de la même chaîne.
    if (!(saved.volume > 0)) saved.volume = DEFAULT_SETTINGS.volume;
    return saved;
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
  speechTraceToggle: document.getElementById("speechTraceToggle"),
  speechTraceState: document.getElementById("speechTraceState"),
  speechTraceKey: document.getElementById("speechTraceKey"),
  speechTraceDownload: document.getElementById("speechTraceDownload"),
  speechTraceResult: document.getElementById("speechTraceResult"),
  callLogRefresh: document.getElementById("callLogRefresh"),
  callLogText: document.getElementById("callLogText"),
  callLogCopy: document.getElementById("callLogCopy"),
  callLogDownload: document.getElementById("callLogDownload"),
  callLogStatus: document.getElementById("callLogStatus"),
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
  volumeWarning: document.getElementById("volumeWarning"),
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
  dimJeanSpeechToggle: document.getElementById("dimJeanSpeechToggle"),
  roomHandoffToggle: document.getElementById("roomHandoffToggle"),
  handoffReturnSlider: document.getElementById("handoffReturnSlider"),
  speakerEngineSelect: document.getElementById("speakerEngineSelect"),
  thresholdEmbeddedSlider: document.getElementById("thresholdEmbeddedSlider"),
  thresholdPicovoiceSlider: document.getElementById("thresholdPicovoiceSlider"),
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

// Repères encadrant une parole attribuée à Jean (voir JEAN_OPEN/JEAN_CLOSE dans
// RollingCaptionZone.kt). Contrairement au repère de silence, ils ne
// s'affichent pas : ils commandent un style. La réplique doit montrer ce que
// Jean a sous les yeux, et le retrait de ses propres paroles en fait partie.
const JEAN_OPEN = "<jean>";
const JEAN_CLOSE = "</jean>";

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

  // Le texte se lit d'un bout à l'autre, en tenant à jour la seule chose qui
  // change : si l'on est ou non à l'intérieur d'une parole de Jean. Un
  // découpage par repère ne suffirait pas — il y en a maintenant trois sortes,
  // dont deux qui s'apparient.
  // Une fermeture qui arrive avant toute ouverture signifie que la tablette a
  // coupé son tampon au milieu d'une parole de Jean (voir
  // trimTextAlreadyScrolledPast) : elle commençait avant ce qu'il en reste. Le
  // même raisonnement est tenu chez Jean, et les deux doivent aboutir au même
  // rendu — c'est toute la promesse de la réplique.
  const firstOpen = text.indexOf(JEAN_OPEN);
  const firstClose = text.indexOf(JEAN_CLOSE);
  let inJean = firstClose >= 0 && (firstOpen < 0 || firstClose < firstOpen);
  let buffer = "";

  const flush = () => {
    if (!buffer) return;
    if (inJean) {
      const dim = document.createElement("span");
      dim.className = "jean-own-speech";
      dim.textContent = buffer;
      element.appendChild(dim);
    } else {
      element.appendChild(document.createTextNode(buffer));
    }
    buffer = "";
  };

  let index = 0;
  while (index < text.length) {
    if (text.startsWith(JEAN_OPEN, index)) {
      flush();
      inJean = true;
      index += JEAN_OPEN.length;
    } else if (text.startsWith(JEAN_CLOSE, index)) {
      flush();
      inJean = false;
      index += JEAN_CLOSE.length;
    } else if (text.startsWith(SILENCE_MARKER, index)) {
      flush();
      const mark = document.createElement("em");
      mark.className = "silence-mark";
      mark.textContent = SILENCE_MARKER;
      element.appendChild(mark);
      index += SILENCE_MARKER.length;
    } else {
      buffer += text[index];
      index += 1;
    }
  }
  flush();
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
  renderVolumeWarning();
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
  // Identité renseignée sur l'écran d'attente, sinon repli sur l'ancien
  // comportement : nom générique et capture webcam prise à l'ouverture.
  const identity = loadIdentity() || {};
  await engine.startCall(CONFIG.targetDeviceId, identity.name || CONFIG.callerName, {
    remoteVolume: settings.volume / 100,
    captionModeEnabled: settings.captionEnabled,
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
  // Ressemblance exigée pour attribuer une parole à Jean. Un seuil par
  // moteur : leurs scores ne sont pas comparables, et un curseur commun
  // appliquerait à l'un une exigence réglée pour l'autre (voir
  // SpeakerEngineChoice).
  ["thresholdEmbeddedSlider", "jeanVoiceThreshold_embedded"],
  ["thresholdPicovoiceSlider", "jeanVoiceThreshold_picovoice"],
  // Zéro est légitime ici — « pas de retour minuté » — comme pour les
  // plafonds mensuels.
  ["handoffReturnSlider", "roomHandoffReturnMinutes"],
];

// Mêmes réglages d'appareil, mais en tout ou rien.
const ADMIN_TOGGLE_FIELDS = [
  ["roomWakeEnabledToggle", "roomWakeEnabled"],
  ["blockWakeAtNightToggle", "blockWakeAtNight"],
  ["voiceGateToggle", "voiceGateEnabled"],
  ["dimJeanSpeechToggle", "dimJeanSpeech"],
  ["roomHandoffToggle", "roomHandoffEnabled"],
  ["speechTraceToggle", "speechTraceEnabled"],
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
  renderVolumeWarning();
  clearTimeout(volumeDebounce);
  volumeDebounce = setTimeout(() => {
    engine.setRemoteVolume(Number(els.volumeSlider.value) / 100);
  }, 150);
});

/**
 * Dit franchement que le son est coupé chez Jean, quand il l'est.
 *
 * Un curseur à zéro se voit quand on le regarde, et personne ne le regarde :
 * il vit dans le panneau des réglages, qu'on ouvre rarement en pleine
 * conversation. On entend Jean, on croit qu'il nous entend, et rien à l'écran
 * ne dit le contraire.
 *
 * Un volume à zéro mémorisé d'un appel précédent a rendu Jean muet pendant
 * des jours, et la cause a été cherchée partout ailleurs — dans la pile
 * WebRTC, le routage audio, les fils temps réel. Une phrase à l'écran aurait
 * suffi.
 */
function renderVolumeWarning() {
  const muted = Number(els.volumeSlider.value) === 0;
  els.volumeWarning.textContent = muted
    ? "🔇 Le son est coupé chez Jean : il ne vous entend pas. Remontez le curseur."
    : "";
  els.volumeWarning.classList.toggle("hidden", !muted);
}

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
  ["speakerEngineSelect", "speakerEngine"],
];

function applyDeviceSettings(data) {
  deviceSettingsLoaded = true;

  // L'état de la trace, tel que la tablette le rapporte. Affiché plutôt que
  // déduit de la case cochée : la trace s'arrête toute seule au bout de dix
  // minutes, et ce qu'on croit avoir demandé n'est pas ce qui se passe.
  els.speechTraceState.textContent =
    data.speechTraceState || "aucune trace jamais lancée sur cette tablette";

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
    const floor =
      field.startsWith("quotaHours_") || field === "roomHandoffReturnMinutes" ? 0 : 1;
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
  els.dimJeanSpeechToggle.checked = data.dimJeanSpeech !== false;
  // Faux par défaut côté tablette, contrairement aux autres : ce mode
  // change ce que Jean a sous les yeux, il ne s'arme pas tout seul.
  els.roomHandoffToggle.checked = data.roomHandoffEnabled === true;

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

// --- Journal technique de l'appel, en clair -------------------------------
// Sans clé et sans interrupteur, parce qu'il n'y a rien à protéger dedans
// (voir CallTrace côté Android). Affiché à l'écran plutôt que téléchargé : on
// le consulte juste après un appel qui s'est mal passé, souvent debout, et
// ouvrir un fichier texte sur un téléphone pour lire trente lignes est une
// épreuve de plus.
els.callLogRefresh.addEventListener("click", async () => {
  els.callLogText.hidden = false;
  els.callLogText.textContent = "Lecture…";
  els.callLogStatus.textContent = "";
  try {
    const log = await engine.readCallLog(CONFIG.deviceDocId);
    if (!log) {
      els.callLogText.textContent =
        "La tablette n'a encore rien publié ici. Ce journal part au plus tard " +
        "vingt secondes après le premier événement d'appel — si cette ligne " +
        "persiste après un appel, c'est la tablette elle-même qui n'écrit pas.";
      els.callLogCopy.hidden = true;
      els.callLogDownload.hidden = true;
      return;
    }
    const when = log.at?.toDate ? log.at.toDate().toLocaleString("fr-FR") : "date inconnue";
    els.callLogText.textContent = `Publié le ${when}\n\n${log.text}`;
    // Les deux gestes de sortie n'apparaissent qu'une fois qu'il y a quelque
    // chose à sortir : un bouton qui ne peut rien faire est un bouton qu'on
    // touche quand même, puis qu'on croit cassé.
    els.callLogCopy.hidden = false;
    els.callLogDownload.hidden = false;
  } catch (e) {
    console.warn("[app] Journal technique illisible :", e);
    els.callLogText.textContent = "Lecture impossible : " + e.message;
    els.callLogCopy.hidden = true;
    els.callLogDownload.hidden = true;
  }
});

els.callLogCopy.addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(els.callLogText.textContent);
    els.callLogStatus.textContent = "Journal copié — collez-le où vous voulez.";
  } catch (e) {
    // Le presse-papiers est refusé hors contexte sécurisé, et sur certains
    // navigateurs mobiles quand le geste n'est pas reconnu comme direct. On
    // sélectionne alors le texte pour que la copie manuelle demande un seul
    // geste au lieu de trois.
    const range = document.createRange();
    range.selectNodeContents(els.callLogText);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    els.callLogStatus.textContent =
      "Copie automatique refusée par le navigateur : le texte est sélectionné, " +
      "il ne reste qu'à le copier.";
  }
});

els.callLogDownload.addEventListener("click", () => {
  const blob = new Blob([els.callLogText.textContent], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  // Horodaté : on en compare souvent deux, celui d'un appel qui marche et
  // celui d'un appel qui ne marche pas, et deux fichiers du même nom se
  // recouvrent sans prévenir.
  const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, "-");
  link.download = `seniorvisio-journal-appel-${stamp}.txt`;
  link.click();
  URL.revokeObjectURL(url);
  els.callLogStatus.textContent = "Journal enregistré.";
});

// --- Trace de reconnaissance : clé de lecture et récupération --------------
//
// La clé reste dans CE navigateur. Elle n'est ni envoyée à Firestore — où les
// règles laissent lire quiconque connaît l'adresse d'un document — ni livrée
// avec le site, où elle se lirait dans le JavaScript publié.

const TRACE_KEY_STORAGE = "seniorvisio.speechTraceKey";

els.speechTraceKey.value = localStorage.getItem(TRACE_KEY_STORAGE) || "";
els.speechTraceKey.addEventListener("change", () => {
  localStorage.setItem(TRACE_KEY_STORAGE, els.speechTraceKey.value.trim());
});

/**
 * Déchiffre un morceau « vecteur:contenu », les deux en Base64.
 *
 * La clé est la même chaîne que le secret de compilation, réduite par SHA-256
 * exactement comme du côté Android : c'est ce qui permet aux deux bouts de
 * s'entendre sans échanger autre chose que le mot de passe.
 */
async function decryptTraceChunk(chunk, keyText) {
  const [ivPart, dataPart] = chunk.split(":");
  const fromBase64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(keyText));
  const key = await crypto.subtle.importKey("raw", digest, "AES-GCM", false, ["decrypt"]);
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: fromBase64(ivPart), tagLength: 128 },
    key,
    fromBase64(dataPart)
  );
  return new TextDecoder().decode(plain);
}

els.speechTraceDownload.addEventListener("click", async () => {
  const keyText = els.speechTraceKey.value.trim();
  if (!keyText) {
    els.speechTraceResult.textContent = "Collez d'abord la clé de lecture.";
    return;
  }
  els.speechTraceResult.textContent = "Lecture…";
  try {
    const chunks = await engine.readTraceChunks(CONFIG.deviceDocId);
    if (!chunks.length) {
      // On renvoie à l'état publié juste au-dessus plutôt que de laisser
      // conclure. « Rien à télécharger » a des causes opposées — la tablette
      // n'a jamais reçu la commande, elle enregistre encore, ou elle a refusé
      // de publier faute de clé — et c'est l'état, lui, qui les distingue.
      els.speechTraceResult.textContent =
        "Rien à télécharger pour l'instant. Voyez l'état affiché au-dessus : " +
        "il dit si la tablette enregistre, a publié, ou n'a rien reçu.";
      return;
    }
    const parts = [];
    for (const chunk of chunks) parts.push(await decryptTraceChunk(chunk, keyText));
    const text = parts.join("");

    const url = URL.createObjectURL(new Blob([text], { type: "text/plain;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "seniorvisio-trace.txt";
    link.click();
    URL.revokeObjectURL(url);
    els.speechTraceResult.textContent = `Trace récupérée : ${text.length} caractères.`;
  } catch (e) {
    // Une clé fausse ne produit pas un texte faux : le mode GCM vérifie
    // l'authenticité et refuse. Le message le dit, plutôt que de laisser
    // croire à une trace corrompue.
    console.warn("[app] Déchiffrement de la trace impossible :", e);
    els.speechTraceResult.textContent =
      "Déchiffrement impossible — clé incorrecte, ou trace écrite par une autre version.";
  }
});

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
