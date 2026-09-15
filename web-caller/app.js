/**
 * Point d'entrée du PWA appelant. Câblage UI uniquement : le contrat
 * CallEngine est dans call-engine.js, l'implémentation WebRTC réelle dans
 * webrtc-engine.js (voir ces fichiers, chargés avant celui-ci dans index.html).
 */

// ═══════════════════════════════════════════════════════════════════════════
// TROIS GARDE-FOUS, POSÉS AVANT TOUT LE RESTE
//
// Ce fichier câble une soixantaine de commandes, les unes après les autres,
// au chargement de la page. En JavaScript, une seule exception dans ce
// câblage interrompt TOUT ce qui suit — silencieusement pour qui regarde
// l'écran : la page s'affiche normalement, les boutons sont là, et la moitié
// d'entre eux ne répond plus.
//
// Ce n'est pas une crainte théorique. Les commandes du panneau ⚙️ de la
// famille — « même pièce », « écrire ce qui se dit dans la pièce » — étaient
// câblées aux lignes 1592 et 1626, c'est-à-dire DERRIÈRE tout le panneau
// d'administration ajouté au fil de la semaine. Chaque outil de diagnostic
// que j'ajoutais à la fin du fichier se plaçait entre Jean et les commandes
// que sa famille utilise.
//
// Et une cause suffisait à déclencher ça sans qu'aucun code soit fautif : le
// navigateur du proche pouvait servir un index.html en cache avec un app.js
// à jour. Un identifiant absent, `document.getElementById` rend null, et la
// première commande qui s'y accroche fait tomber toutes les suivantes.
//
//   elementMalPresent — recense ce qui manque au lieu de rendre null en
//     silence ;
//   on()             — câble une commande, et si elle est absente le signale
//     et passe à la suivante au lieu d'interrompre la page ;
//   bloc()           — isole un morceau de câblage : ce qui échoue dedans
//     n'emporte plus ce qui vient après.
//
// Aucun des trois ne répare quoi que ce soit. Ils font que la panne se voie,
// et qu'elle reste locale. C'est la même règle que CallTrace.guard côté
// tablette, et elle vient de la même leçon, répétée quatre fois cette
// semaine : un échec muet coûte plus cher que la panne qu'il cache.
// ═══════════════════════════════════════════════════════════════════════════

const élémentsManquants = [];
const blocsEnPanne = [];

/** Comme getElementById, mais retient ce qui manque au lieu de l'oublier. */
function el(id) {
  const trouvé = document.getElementById(id);
  if (!trouvé) élémentsManquants.push(id);
  return trouvé;
}

/**
 * Câble une commande. Si l'élément n'existe pas, le dit et continue.
 *
 * La clé est celle de `els`, pas l'identifiant HTML : c'est `els` qui sait
 * déjà faire la correspondance, et la faire deux fois la ferait diverger.
 */
function on(clé, événement, gestionnaire) {
  const cible = els[clé];
  if (!cible) {
    blocsEnPanne.push(`commande « ${clé} » absente de la page`);
    console.error(`[Câblage] Élément introuvable : ${clé}`);
    return;
  }
  cible.addEventListener(événement, gestionnaire);
}

/** Isole un morceau de câblage : ce qui échoue dedans n'emporte pas la suite. */
function bloc(nom, action) {
  try {
    action();
  } catch (e) {
    blocsEnPanne.push(`${nom} : ${e.message}`);
    console.error(`[Câblage] ${nom} a échoué :`, e);
  }
}

/**
 * Dit à l'écran que la page est incomplète, et ce qu'il faut faire.
 *
 * Sans ça, une page à moitié câblée est indiscernable d'une page qui marche
 * jusqu'à ce qu'on touche la commande morte — au pire moment, c'est-à-dire
 * pendant l'appel.
 */
/**
 * Le bandeau qui coiffe la page, hors du flux.
 *
 * ═══ POURQUOI UN CONTENEUR EN POSITION FIXE ═══
 *
 * `body` est une boîte flexible qui centre `#app`. Un bandeau simplement
 * ajouté au début du corps devient donc un ÉLÉMENT FLEXIBLE de plus, posé
 * À CÔTÉ du contenu : il s'affichait sur la gauche et rétrécissait toute la
 * page. Une position fixe le retire complètement du flux — il ne peut plus
 * disputer sa place à quoi que ce soit.
 *
 * Un seul conteneur pour tous les bandeaux, et non un par message : il peut
 * y en avoir deux à la fois (banc d'essai + consigne refusée), et deux
 * éléments fixes indépendants se poseraient l'un sur l'autre.
 *
 * Le contenu est ensuite décalé de la hauteur réelle du conteneur, mesurée
 * et non devinée : elle dépend du nombre de bandeaux et de la largeur de
 * l'écran, où un même texte tient sur une ou trois lignes.
 */
function conteneurDesBandeaux() {
  let conteneur = document.getElementById("banners");
  if (!conteneur) {
    conteneur = document.createElement("div");
    conteneur.id = "banners";
    document.body.prepend(conteneur);
  }
  return conteneur;
}

function ajusterDecalageDesBandeaux() {
  const conteneur = document.getElementById("banners");
  const hauteur = conteneur ? conteneur.offsetHeight : 0;
  document.body.style.paddingTop = hauteur ? `${hauteur}px` : "";
}

// La hauteur change avec la largeur de l'écran (un texte qui passait sur une
// ligne en passe sur trois en portrait) et à la rotation du téléphone.
window.addEventListener("resize", ajusterDecalageDesBandeaux);

function signalerPageIncomplète() {
  if (!élémentsManquants.length && !blocsEnPanne.length) return;
  const bandeau = document.createElement("p");
  bandeau.className = "wiring-warning";
  bandeau.textContent =
    "⚠️ Cette page n'est pas complètement chargée : certaines commandes ne " +
    "répondront pas. Fermez l'onglet et rouvrez-le pour recharger.";
  conteneurDesBandeaux().append(bandeau);
  ajusterDecalageDesBandeaux();
  console.error("[Câblage] Éléments absents :", élémentsManquants);
  console.error("[Câblage] Blocs en panne :", blocsEnPanne);
}

bloc("version affichée", () => {
  el("pwaVersion").textContent = `v. ${window.PWA_VERSION || "?"}`;
});

/**
 * Dit, en permanence et en grand, qu'on est sur le banc d'essai.
 *
 * ═══ POURQUOI UN BANDEAU ET PAS UNE MENTION DISCRÈTE ═══
 *
 * Les deux PWA sont identiques au pixel près. La seule chose qui les
 * distingue est l'adresse, que personne ne relit après avoir mis un raccourci
 * sur son écran d'accueil. Or les deux confusions possibles coûtent cher :
 * régler la tablette de Jean en croyant essayer, ou chercher une demi-heure
 * pourquoi un appel n'aboutit pas sur une tablette qui n'écoute pas la même
 * boîte aux lettres.
 *
 * Rien n'est ajouté côté production : chez Jean, l'absence de bandeau EST
 * l'information. Un bandeau « production » finirait par ne plus se lire, et
 * son absence un jour de panne ne se remarquerait pas.
 */
bloc("bandeau d'environnement", () => {
  const env = window.SENIORVISIO_ENV;
  if (!env || env.name === "production") return;
  const bandeau = document.createElement("p");
  bandeau.className = "env-banner";
  bandeau.textContent =
    `🧪 BANC D'ESSAI — cette page appelle la tablette de test (${env.deviceDocId}), ` +
    "pas celle de Jean.";
  conteneurDesBandeaux().append(bandeau);
  ajusterDecalageDesBandeaux();
  document.title = `[TEST] ${document.title}`;
});

// --- Paramètres, alignés avec AdminConfig côté Android ---
const CONFIG = {
  targetDeviceId: "jean-tablette-01", // non utilisé par le signaling Firestore (un seul foyer), gardé pour usage futur multi-tablette
  // Document d'état de la tablette dans Firestore : signe de vie, batterie,
  // version installée, et réglages de transcription pilotés d'ici. Doit rester
  // identique à DEVICE_DOC_PATH dans core/DeviceStatusReporter.kt.
  // Repli explicite sur la production si environment.js manque : ce fichier
  // est ajouté au déploiement, et une page mise en cache avant son arrivée
  // doit continuer de fonctionner (voir environment.js).
  deviceDocId: (window.SENIORVISIO_ENV && window.SENIORVISIO_ENV.deviceDocId) || "jean_tablet",
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
  speechTraceToggle: el("speechTraceToggle"),
  speechTraceState: el("speechTraceState"),
  speechTraceKey: el("speechTraceKey"),
  speechTraceDownload: el("speechTraceDownload"),
  speechTraceResult: el("speechTraceResult"),
  callLogRefresh: el("callLogRefresh"),
  callLogText: el("callLogText"),
  callLogCopy: el("callLogCopy"),
  callLogDownload: el("callLogDownload"),
  callLogStatus: el("callLogStatus"),
  idle: el("stateIdle"),
  calling: el("stateCalling"),
  blocked: el("stateBlocked"),
  connected: el("stateConnected"),
  callButton: el("callButton"),
  cancelButton: el("cancelButton"),
  forceConnectButton: el("forceConnectButton"),
  retryButton: el("retryButton"),
  blockedMessage: el("blockedMessage"),
  hangupButton: el("hangupButton"),
  paneVideo: el("paneVideo"),
  paneSettings: el("paneSettings"),
  paneSlideshow: el("paneSlideshow"),
  switchCameraButton: el("switchCameraButton"),
  openSettingsButton: el("openSettingsButton"),
  openSlideshowButton: el("openSlideshowButton"),
  rememberSettingsButton: el("rememberSettingsButton"),
  callStats: el("callStats"),
  volumeSlider: el("volumeSlider"),
  volumeWarning: el("volumeWarning"),
  captionToggle: el("captionToggle"),
  captionStatus: el("captionStatus"),
  tabletMicMuteToggle: el("tabletMicMuteToggle"),
  slideshowInput: el("slideshowInput"),
  slideshowNav: el("slideshowNav"),
  slideshowPrevButton: el("slideshowPrevButton"),
  slideshowNextButton: el("slideshowNextButton"),
  slideshowCounter: el("slideshowCounter"),
  slideshowStopButton: el("slideshowStopButton"),
  slideshowRememberToggle: el("slideshowRememberToggle"),
  sameRoomToggle: el("sameRoomToggle"),
  sameRoomStatus: el("sameRoomStatus"),
  slideshowStatus: el("slideshowStatus"),
  selfPreviewToggle: el("selfPreviewToggle"),
  scrollSpeedSlider: el("scrollSpeedSlider"),
  captionLinesSlider: el("captionLinesSlider"),
  captionClearDelaySlider: el("captionClearDelaySlider"),
  micToRoomControl: el("micToRoomControl"),
  micToRoomToggle: el("micToRoomToggle"),
  micToRoomStatus: el("micToRoomStatus"),
  openAdminIdleButton: el("openAdminIdleButton"),
  openAdminCallButton: el("openAdminCallButton"),
  adminOverlay: el("adminOverlay"),
  adminLock: el("adminLock"),
  adminPanel: el("adminPanel"),
  adminPinInput: el("adminPinInput"),
  adminUnlockButton: el("adminUnlockButton"),
  adminCancelButton: el("adminCancelButton"),
  adminCloseButton: el("adminCloseButton"),
  adminLockStatus: el("adminLockStatus"),
  micToRoomBanner: el("micToRoomBanner"),
  micToRoomBackButton: el("micToRoomBackButton"),
  callingHint: el("callingHint"),
  countdownFill: el("countdownFill"),
  countdownText: el("countdownText"),
  identityName: el("identityName"),
  identityPhotoInput: el("identityPhotoInput"),
  identityPhotoPreview: el("identityPhotoPreview"),
  identityRights: el("identityRights"),
  saveIdentityButton: el("saveIdentityButton"),
  identityStatus: el("identityStatus"),
  roomEngineSelect: el("roomEngineSelect"),
  callEngineSelect: el("callEngineSelect"),
  voskModelSelect: el("voskModelSelect"),
  engineStatus: el("engineStatus"),
  roomWakeEnabledToggle: el("roomWakeEnabledToggle"),
  roomWakeThresholdSlider: el("roomWakeThresholdSlider"),
  blockWakeAtNightToggle: el("blockWakeAtNightToggle"),
  roomListeningStatus: el("roomListeningStatus"),
  deviceHealth: el("deviceHealth"),
  transcriptionDiagnostic: el("transcriptionDiagnostic"),
  paidUsage: el("paidUsage"),
  voiceGateToggle: el("voiceGateToggle"),
  dimJeanSpeechToggle: el("dimJeanSpeechToggle"),
  roomHandoffToggle: el("roomHandoffToggle"),
  handoffReturnSlider: el("handoffReturnSlider"),
  speakerEngineSelect: el("speakerEngineSelect"),
  thresholdEmbeddedSlider: el("thresholdEmbeddedSlider"),
  thresholdPicovoiceSlider: el("thresholdPicovoiceSlider"),
  quotaAssemblyaiSlider: el("quotaAssemblyaiSlider"),
  quotaGladiaSlider: el("quotaGladiaSlider"),
  refreshUsageButton: el("refreshUsageButton"),
  usageSummary: el("usageSummary"),
  usageDays: el("usageDays"),
  restartAppButton: el("restartAppButton"),
  rebootDeviceButton: el("rebootDeviceButton"),
  commandStatus: el("commandStatus"),
  captionOverflowIndicator: el("captionOverflowIndicator"),
  // Réplique de l'écran de Jean (voir applyScreenLayout / applyScreenState).
  jeanScreen: el("jeanScreen"),
  jeanSlideshow: el("jeanSlideshow"),
  jeanRoomText: el("jeanRoomText"),
  jeanCallText: el("jeanCallText"),
  jeanRoomBox: el("jeanRoomBox"),
  jeanCallBox: el("jeanCallBox"),
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

/**
 * Met le libellé du bouton en accord avec ce que l'appui va faire.
 *
 * Nommer la caméra VERS laquelle on bascule, et non celle qui filme : un
 * bouton qui affiche l'état courant se lit comme un bouton qui affiche son
 * action, et on appuie en croyant faire l'inverse de ce qui se produit.
 */
function renderCameraButton() {
  if (!els.switchCameraButton) return;
  const versArrière = engine.currentFacingMode() !== "environment";
  els.switchCameraButton.textContent = versArrière ? "🔄 Caméra arrière" : "🔄 Caméra avant";
}

on("switchCameraButton", "click", async () => {
  // Désarmé le temps de la bascule : ouvrir une caméra prend un instant, et
  // deux appuis coup sur coup lanceraient deux demandes concurrentes sur le
  // même objectif — la seconde échouerait, et l'échec porterait sur une
  // bascule que personne n'a demandée.
  els.switchCameraButton.disabled = true;
  try {
    await engine.switchCamera();
  } catch (e) {
    // Cas le plus courant, et parfaitement normal : un appareil qui n'a
    // qu'une caméra. Le dire franchement plutôt que laisser un bouton sans
    // effet — c'est la règle de tout ce fichier depuis les dix catch muets.
    afficherConsigneRefusée("changement de caméra", (e && (e.name || e.message)) || "impossible");
  } finally {
    renderCameraButton();
    els.switchCameraButton.disabled = false;
  }
});

on("openSettingsButton", "click", () => showPane("paneSettings"));
on("openSlideshowButton", "click", () => showPane("paneSlideshow"));
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

  // Le premier texte d'appel publié par la tablette confirme que le moteur a
  // fini de démarrer (voir renderCaptionStatus).
  if (state.callText && !captionTextSeen) {
    captionTextSeen = true;
    renderCaptionStatus();
  }

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

on("callButton", "click", async () => {
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
  // L'attente de la transcription recommence à chaque appel : le moteur est
  // arrêté entre deux appels (voir renderCaptionStatus).
  captionTextSeen = false;
  renderCaptionStatus();
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

on("rememberSettingsButton", "click", () => {
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

/**
 * Dit où en est la transcription, parce qu'elle met du temps à démarrer.
 *
 * ═══ POURQUOI CETTE LIGNE EXISTE ═══
 *
 * Cocher la case ne fait pas apparaître du texte : elle démarre un moteur de
 * reconnaissance vocale sur la tablette — chargement du modèle ou poignée de
 * main avec le service distant — ce qui prend plusieurs secondes.
 *
 * Pendant ce temps, rien ne changeait à l'écran. Le proche en concluait que
 * sa case n'avait rien fait et la décochait, ce qui ARRÊTE le moteur : le
 * geste censé corriger le symptôme le provoquait. « Le toggle ne semble pas
 * marcher » n'était pas une panne, c'était une attente que personne
 * n'annonçait.
 *
 * Et le « ✅ » n'est pas une supposition sur le temps écoulé : il attend que
 * du texte arrive VRAIMENT chez Jean — c'est la tablette qui publie ce
 * qu'elle affiche (voir applyScreenState). Une confirmation posée sur une
 * minuterie aurait menti le jour où le moteur échoue, c'est-à-dire le seul
 * jour où elle compte.
 */
function renderCaptionStatus() {
  if (!els.captionStatus) return;
  if (!els.captionToggle.checked) {
    els.captionStatus.textContent = "";
    return;
  }
  els.captionStatus.textContent = captionTextSeen
    ? "✅ Vos paroles s'affichent chez Jean."
    : "⏳ Démarrage de la transcription… le texte apparaîtra dans quelques secondes.";
}

/** Vrai dès que la tablette a publié du texte d'appel pour l'appel en cours. */
let captionTextSeen = false;

on("captionToggle", "change", () => {
  // Remis à zéro à chaque bascule : rallumer la transcription relance
  // l'attente, et afficher « ✅ » hérité de la fois précédente ferait croire
  // que le texte arrive déjà.
  captionTextSeen = false;
  renderCaptionStatus();
  engine.setCaptionMode(els.captionToggle.checked);
});

on("selfPreviewToggle", "change", () => {
  engine.setSelfPreviewMode(els.selfPreviewToggle.checked);
});

// Coupe le micro de la tablette (voir WebRtcCallEngine.listenForMicMute côté
// Android). Sert de test décisif pour localiser un écho : s'il disparaît en
// cochant cette case, il vient de la tablette ; s'il persiste, il vient de ce
// téléphone-ci. Volontairement non mémorisé d'un appel à l'autre : Jean se
// retrouverait muet sans que personne ne comprenne pourquoi.
on("tabletMicMuteToggle", "change", () => {
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

/**
 * Dit au proche qu'une de ses consignes n'est pas arrivée chez Jean.
 *
 * Le bandeau est construit ici plutôt que déclaré dans index.html, et c'est
 * délibéré : il sert précisément dans les situations où la page peut être
 * incomplète. Un bandeau d'alerte qui dépend de la présence d'un élément
 * dans le HTML est un bandeau qui manque le jour où il servirait.
 */
function afficherConsigneRefusée(nom, cause) {
  let bandeau = document.getElementById("commandWarning");
  if (!bandeau) {
    bandeau = document.createElement("p");
    bandeau.id = "commandWarning";
    bandeau.className = "wiring-warning";
    conteneurDesBandeaux().append(bandeau);
  }
  bandeau.textContent =
    `⚠️ « ${nom} » n'est pas arrivé chez Jean (${cause}). Réessayez ; si cela ` +
    `se reproduit, raccrochez et rappelez.`;
  bandeau.hidden = false;
  ajusterDecalageDesBandeaux();
  clearTimeout(afficherConsigneRefusée.minuterie);
  afficherConsigneRefusée.minuterie = setTimeout(() => {
    bandeau.hidden = true;
    // Le décalage se reprend en même temps que le bandeau s'efface : sans ça,
    // une bande vide resterait réservée en haut de la page pour le reste de
    // l'appel.
    ajusterDecalageDesBandeaux();
  }, 8000);
}

engine.onCommandRejected(afficherConsigneRefusée);

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
  on(elementKey, "change", () => {
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
  on(elementKey, "input", () => {
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
on("volumeSlider", "input", () => {
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

on("forceConnectButton", "click", () => {
  els.forceConnectButton.disabled = true;
  engine.forceConnect();
});

on("cancelButton", "click", async () => {
  await engine.cancelCall();
  showState("idle");
});

on("retryButton", "click", () => showState("idle"));


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

on("openAdminIdleButton", "click", openAdmin);
on("openAdminCallButton", "click", openAdmin);
on("adminUnlockButton", "click", tryUnlockAdmin);
on("adminCancelButton", "click", closeAdmin);
on("adminCloseButton", "click", closeAdmin);
on("adminPinInput", "keydown", (event) => {
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
  // Lecture et affichage séparés, et la cause exacte reportée à l'écran.
  //
  // « Lecture de l'usage impossible » ne disait rien : ni si la lecture avait
  // échoué ou l'affichage, ni pourquoi. Or les causes sont opposées — un refus
  // Firestore se corrige dans les règles, une erreur d'affichage dans le code,
  // et une absence de données ne se corrige pas du tout. C'est le même catch
  // muet qui a coûté une semaine sur le volume.
  let days;
  try {
    days = await engine.readUsageDays(CONFIG.deviceDocId, 8);
  } catch (e) {
    console.warn("[app] Lecture de l'usage refusée :", e);
    els.usageSummary.textContent =
      `Lecture refusée par Firestore (${e.code || e.name || "?"}) : ${e.message || e}`;
    els.refreshUsageButton.disabled = false;
    return;
  }
  try {
    renderUsageSummary(days);
    renderUsageDays(days);
  } catch (e) {
    console.warn("[app] Affichage de l'usage impossible :", e);
    els.usageSummary.textContent =
      `${days.length} journée(s) lue(s), mais affichage impossible : ${e.message || e}`;
  } finally {
    els.refreshUsageButton.disabled = false;
  }
}

on("refreshUsageButton", "click", loadUsage);

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

on("restartAppButton", "click", () =>
  sendCommand("restart-app", "Relancer l'application sur la tablette de Jean ?")
);
on("rebootDeviceButton", "click", () =>
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

let lastDeviceData = null;

function applyDeviceSettings(data) {
  deviceSettingsLoaded = true;
  // Retenu pour que la fraîcheur du signe de vie puisse vieillir toute seule
  // entre deux publications de la tablette (voir renderDeviceHealth).
  lastDeviceData = data;

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
  // EN DERNIER, ET PROTÉGÉ. Ce bloc était appelé en tête et sans garde : la
  // moindre erreur dedans — un élément absent parce que le navigateur a gardé
  // l'ancien index.html en cache tout en chargeant le nouveau app.js — coupait
  // l'affichage de TOUS les réglages en dessous. Une nouveauté ne doit pas
  // pouvoir emporter du code qui marchait.
  try {
    renderDeviceHealth(data);
  } catch (e) {
    console.warn("[app] État de la tablette non affichable :", e);
  }
}

/**
 * L'état de la tablette, à lire avant tout réglage.
 *
 * ═══ Pourquoi le signe de vie passe en premier ═══
 *
 * Tous ces chiffres étaient déjà publiés par la tablette toutes les cinq
 * minutes. Aucun n'était affiché : `lastHeartbeatAt` servait uniquement à
 * savoir s'il existait, jamais à dire QUAND. On pouvait donc régler pendant
 * des minutes une tablette éteinte, débranchée ou plantée, sans que rien ne
 * l'indique — et conclure que les réglages ne marchent pas.
 *
 * La fraîcheur du signe de vie est donc la première ligne, et la seule qui
 * change de couleur. Le reste ne veut rien dire si elle est rouge : une
 * révision, une batterie ou un état d'écoute vieux d'une heure décrivent une
 * tablette qui n'existe plus.
 *
 * Les seuils découlent de la cadence réelle (cinq minutes, voir
 * CallListenerService.HEARTBEAT_INTERVAL_MS) : sous douze minutes, un signe a
 * pu être manqué sans que rien n'aille mal ; au-delà de trente, deux se sont
 * perdus de suite et ce n'est plus un hasard.
 */
function renderDeviceHealth(data) {
  if (!els.deviceHealth) {
    // Le cas se produit quand le navigateur a gardé l'ancien index.html en
    // cache tout en chargeant le nouveau app.js — les deux fichiers n'ont
    // aucune raison d'expirer ensemble. Dit clairement, parce qu'un bloc
    // absent ressemble sinon à une fonctionnalité qui n'a pas été livrée.
    console.warn(
      "[app] Bloc « État de la tablette » absent de la page : index.html est " +
      "probablement une version en cache. Rechargez en forçant (Ctrl+Maj+R, " +
      "ou vider les données du site sur téléphone)."
    );
    return;
  }
  const vu = data.lastHeartbeatAt?.toDate ? data.lastHeartbeatAt.toDate() : null;
  const minutes = vu ? Math.round((Date.now() - vu.getTime()) / 60000) : null;

  let classe = "health-dead";
  let vie = "aucun signe de vie reçu";
  if (minutes !== null) {
    vie = minutes < 1 ? "à l'instant" : `il y a ${minutes} min`;
    if (minutes < 12) classe = "health-ok";
    else if (minutes < 30) classe = "health-warn";
    else {
      classe = "health-dead";
      vie = `il y a ${minutes} min — la tablette ne répond plus`;
    }
  }

  const compagnes = data.companionApps
    ? Object.entries(data.companionApps).map(([nom, version]) =>
        `${nom.split(".").pop()} ${version}`).join(", ")
    : "—";

  const lignes = [
    ["Signe de vie", `<span class="${classe}">${vie}</span>`],
    ["Version installée", data.appVersion || "—"],
    ["Batterie", typeof data.batteryPercent === "number" && data.batteryPercent >= 0
      ? `${data.batteryPercent} %` : "—"],
    ["Écoute de la pièce", data.roomListening || "—"],
    ["Modèle embarqué", data.voskModelState || "—"],
    ["Applications tierces", compagnes],
  ];
  els.deviceHealth.innerHTML =
    "<dl>" + lignes.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join("") + "</dl>";
}

// Le « il y a X min » vieillit tout seul : sans ce rafraîchissement, il
// resterait figé à sa valeur d'affichage tant que la tablette ne republie
// rien — c'est-à-dire précisément quand elle est en panne, le seul moment où
// cette ligne compte.
setInterval(() => {
  bloc("rafraîchissement du signe de vie", () => {
    if (lastDeviceData && !els.adminOverlay.classList.contains("hidden")) {
      renderDeviceHealth(lastDeviceData);
    }
  });
}, 30000);

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
  on(elementKey, "change", () => {
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
on("callLogRefresh", "click", async () => {
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

on("callLogCopy", "click", async () => {
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

on("callLogDownload", "click", () => {
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

bloc("clé de trace chiffrée", () => {
  els.speechTraceKey.value = localStorage.getItem(TRACE_KEY_STORAGE) || "";
});
on("speechTraceKey", "change", () => {
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

on("speechTraceDownload", "click", async () => {
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

// Sous garde : c'est la dernière instruction de haut niveau avant le câblage
// de l'identité et du diaporama. Une erreur ici les emportait tous les deux.
bloc("écoute des réglages de la tablette", () => {
  engine.watchDeviceSettings(CONFIG.deviceDocId, applyDeviceSettings);
});

// --- Identité de l'appelant ---
// Photo retenue en mémoire tant qu'elle n'est pas enregistrée : le
// redimensionnement est asynchrone, on ne peut pas le refaire au moment du clic.
let pendingIdentityPhoto = null;

const IDENTITY_JUST_SAVED_KEY = "seniorvisio_identity_just_saved";

bloc("identité de l'appelant", function restoreIdentity() {
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
});

on("identityPhotoInput", "change", async () => {
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

on("saveIdentityButton", "click", () => {
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

/**
 * Budget d'une photo une fois encodée, en octets.
 *
 * ═══ POURQUOI LA GALERIE NE S'AFFICHAIT PAS CHEZ JEAN ═══
 *
 * Un document Firestore ne peut pas dépasser un mébioctet, et la photo du
 * diaporama voyage DANS le document d'appel — à côté des deux SDP, de la
 * photo d'identité de l'appelant et des textes affichés à l'écran de Jean.
 * Une photo de 1280 px encodée en base64 pèse couramment 400 à 700 Ko : avec
 * tout le reste, le document passait par-dessus la limite, et Firestore
 * refusait l'écriture.
 *
 * Le refus tombait dans un `.catch(() => {})`, et `pushCurrentSlide`
 * affichait la photo localement sans attendre le résultat de l'envoi. D'où
 * le symptôme exact rapporté : la photo est là du côté du proche, et Jean ne
 * voit rien.
 *
 * 500 Ko laisse de la place pour tout le reste du document sans réduire les
 * photos au point que la mémoire d'une réunion de famille devienne illisible
 * sur un écran de tablette.
 */
const SLIDESHOW_PHOTO_MAX_BYTES = 500_000;

/** Paliers essayés dans l'ordre jusqu'à tenir dans le budget ci-dessus. */
const SLIDESHOW_FALLBACKS = [
  { côté: SLIDESHOW_PHOTO_MAX_SIDE, qualité: SLIDESHOW_PHOTO_QUALITY },
  { côté: 1024, qualité: 0.68 },
  { côté: 800, qualité: 0.62 },
  { côté: 640, qualité: 0.55 },
];

/**
 * Encode une photo en restant sous le budget, en réduisant par paliers.
 *
 * Réduire vaut mieux qu'échouer : une photo un peu moins fine reste une
 * photo que Jean regarde, alors qu'une photo refusée n'est rien du tout. Et
 * le dernier palier est renvoyé même s'il dépasse encore — l'envoi dira
 * alors franchement qu'il n'est pas passé, au lieu de faire disparaître la
 * photo en silence comme avant.
 */
/**
 * Dit POURQUOI aucune photo n'est passée, au lieu de le taire.
 *
 * ═══ « AUCUNE DE CES PHOTOS N'A PU ÊTRE LUE » NE SUFFIT PAS ═══
 *
 * Ce message a été affiché en usage réel, et il ne permettait rien : ni de
 * savoir quel fichier, ni de quel format, ni si le décodage avait échoué ou
 * si le fichier était vide. Le détail partait dans la console du téléphone,
 * c'est-à-dire nulle part.
 *
 * C'est le même défaut de silence que les dix consignes avalées — dans du
 * code que je venais d'écrire, et après l'avoir corrigé partout ailleurs.
 *
 * Deux causes expliquent presque tous les cas où TOUTES les photos échouent,
 * et elles se reconnaissent au type et à la taille du fichier :
 *
 *   - le format HEIC/HEIF, celui des photos « haute efficacité » des
 *     téléphones récents. Chrome sur Android ne sait pas le décoder ; iOS, lui,
 *     convertit en JPEG tout seul à la sélection. D'où un échec TOTAL et non
 *     partiel, qui dépend du téléphone et pas des photos ;
 *   - un fichier de taille nulle, ce que renvoient les galeries qui gardent
 *     les photos dans le nuage sans les avoir téléchargées.
 */
function expliquerLeRefus(refusées, total) {
  if (!refusées.length) return "Aucune photo lisible dans cette sélection.";

  const type = (r) => (r.type || "type inconnu").toLowerCase();
  const heic = refusées.filter((r) => /hei[cf]/.test(type(r)) || /\.hei[cf]$/i.test(r.nom));
  const vides = refusées.filter((r) => r.taille === 0);
  const première = refusées[0];

  let message = `Aucune des ${total} photo(s) n'a pu être lue. ` +
    `Exemple : « ${première.nom} » (${type(première)}, ` +
    `${Math.round((première.taille || 0) / 1024)} Ko).`;

  if (heic.length === refusées.length) {
    message +=
      " Ce sont des photos au format HEIC, que ce navigateur ne sait pas ouvrir. " +
      "Dans les réglages photo du téléphone, choisissez le format « Compatible » " +
      "ou « JPEG » plutôt que « Haute efficacité », ou envoyez une capture d'écran.";
  } else if (vides.length === refusées.length) {
    message +=
      " Ces fichiers sont vides : ce sont probablement des photos encore dans le " +
      "nuage. Ouvrez-les une fois dans la galerie pour les télécharger, puis " +
      "réessayez.";
  }
  return message;
}

async function encoderSousLaLimite(file) {
  let dernière = null;
  for (const palier of SLIDESHOW_FALLBACKS) {
    dernière = await resizeToBase64(file, palier.côté, palier.qualité);
    if (dernière.length <= SLIDESHOW_PHOTO_MAX_BYTES) {
      return { base64: dernière, réduite: palier !== SLIDESHOW_FALLBACKS[0] };
    }
  }
  return { base64: dernière, réduite: true };
}

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
async function pushCurrentSlide() {
  if (!slideshowPhotos.length) return;
  const photo = slideshowPhotos[slideshowIndex];
  // La réplique locale n'est posée qu'APRÈS confirmation de l'envoi. Elle
  // prétend montrer ce que Jean a sous les yeux : l'afficher sans attendre
  // en faisait un mensonge, et c'est ce mensonge qui a fait chercher la
  // panne du diaporama du mauvais côté.
  const passée = await engine.setSlideshowPhoto(photo);
  els.jeanSlideshow.classList.toggle("hidden", !passée);
  if (passée) els.jeanSlideshow.src = `data:image/jpeg;base64,${photo}`;
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

on("slideshowInput", "change", async () => {
  const files = Array.from(els.slideshowInput.files || []);
  if (!files.length) return;
  els.slideshowStatus.textContent = `Préparation de ${files.length} photo(s)…`;

  const prepared = [];
  const refusées = [];
  let réduites = 0;
  for (const file of files) {
    try {
      const ajustée = await encoderSousLaLimite(file);
      if (ajustée.réduite) réduites++;
      prepared.push(ajustée.base64);
    } catch (e) {
      // Une photo illisible (format exotique, fichier corrompu) ne doit pas
      // faire échouer toute la sélection — mais elle doit DIRE pourquoi.
      refusées.push({ nom: file.name, type: file.type, taille: file.size, cause: e });
      console.warn("[Diaporama] Photo ignorée :", file.name, file.type, file.size, e);
    }
  }

  if (!prepared.length) {
    els.slideshowStatus.textContent = expliquerLeRefus(refusées, files.length);
    return;
  }

  const tooMany = prepared.length > SLIDESHOW_MAX_PHOTOS;
  slideshowPhotos = prepared.slice(0, SLIDESHOW_MAX_PHOTOS);
  slideshowIndex = 0;
  renderSlideshowState();
  pushCurrentSlide();
  const allégées = réduites > 0 ? ` ${réduites} allégée(s) pour passer chez Jean.` : "";
  els.slideshowStatus.textContent = (tooMany
    ? `${SLIDESHOW_MAX_PHOTOS} premières photos retenues (limite de cet appareil).`
    : `${slideshowPhotos.length} photo(s) prête(s).`) + allégées;

  if (els.slideshowRememberToggle.checked) saveSlideshow();
});

on("slideshowPrevButton", "click", () => showSlide(slideshowIndex - 1));
on("slideshowNextButton", "click", () => showSlide(slideshowIndex + 1));

// Termine le diaporama : la vidéo du proche réapparaît chez Jean, la sélection
// de photos reste en place pour pouvoir relancer sans tout re-choisir.
on("slideshowStopButton", "click", () => {
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

on("slideshowRememberToggle", "change", () => {
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
on("sameRoomToggle", "change", () => {
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

on("micToRoomToggle", "change", () => setMicToRoom(els.micToRoomToggle.checked));
on("micToRoomBackButton", "click", () => setMicToRoom(false));

on("hangupButton", "click", async () => {
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
engine.onConnected(() => {
  showState("connected");
  // Seulement maintenant : la demande initiale ne précise aucun facingMode,
  // c'est le navigateur qui a choisi, et on ne sait ce qu'il a pris qu'une
  // fois la piste ouverte (voir currentFacingMode).
  renderCameraButton();
});
engine.onEnded(() => showState("idle"));
engine.onError((message) => {
  alert(message);
  showState("idle");
});

// Dernière instruction du fichier, et c'est voulu : à ce point, tout le
// câblage a été tenté. Ce qui a manqué ou échoué en chemin a été retenu au
// lieu d'interrompre la page, et c'est ici qu'on le dit — une fois, en clair,
// au proche qui est devant l'écran.
signalerPageIncomplète();
