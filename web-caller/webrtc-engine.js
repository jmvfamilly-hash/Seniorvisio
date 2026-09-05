/**
 * Implémentation WebRTC du CallEngine côté PWA appelant. Signaling par
 * Firestore (même schéma que app/.../core/WebRtcCallEngine.kt côté Android) :
 * un document dans `calls` par appel, avec offerSdp/answerSdp et deux
 * sous-collections de candidats ICE (un camp par rôle).
 */
class RealCallEngine extends CallEngine {
  constructor(firebaseConfig) {
    super();
    this._blockedCb = null;
    this._connectedCb = null;
    this._endedCb = null;
    this._errorCb = null;
    this._pc = null;
    this._localStream = null;
    this._callDocRef = null;
    this._unsubscribeCallDoc = null;
    this._unsubscribeCalleeCandidates = null;
    this._countdownCb = null;
    this._countdownInterval = null;
    this._captionCatchUpLagCb = null;
    this._captionDebugCb = null;
    this._lastCaptionDebugMessage = null;
    this._hasNotifiedConnected = false;
    this._hasReportedCalleeError = false;
    // Incrémenté à chaque nouvel appel et à chaque annulation (voir
    // cancelCall) : permet à startCall() de détecter, à chaque point
    // d'attente, qu'il a été annulé entre-temps et d'arrêter proprement
    // plutôt que de continuer en arrière-plan et écraser l'état déjà
    // remis à zéro par cancelCall (voir isStale() dans startCall).
    this._callGeneration = 0;

    try {
      firebase.initializeApp(firebaseConfig);
      this._db = firebase.firestore();
      this._available = true;
    } catch (e) {
      console.error("[RealCallEngine] Firebase non configuré :", e);
      this._available = false;
    }
  }

  onBlocked(callback) { this._blockedCb = callback; }
  onConnected(callback) { this._connectedCb = callback; }
  onEnded(callback) { this._endedCb = callback; }
  /** callback(message: string) — l'appel n'a pas pu démarrer (caméra/micro inaccessible, etc.). */
  onError(callback) { this._errorCb = callback; }
  /** callback(remainingSeconds, totalSeconds) — progression du décompte vu côté tablette. */
  onCountdown(callback) { this._countdownCb = callback; }
  /** callback(lagSeconds: number) — retard de lecture de Jean par rapport au texte reçu (0 = à jour) : ralentir le débit si ça grimpe. */
  onCaptionCatchUpLag(callback) { this._captionCatchUpLagCb = callback; }
  /** callback(message: string) — diagnostic de la transcription temps réel AssemblyAI côté tablette (voir attachTranscriptionSink). */
  onCaptionDebug(callback) { this._captionDebugCb = callback; }

  async startCall(targetId, callerName, initialSettings = {}) {
    // Capturé au tout début : si cancelCall() est appelé pendant que cette
    // fonction attend encore (caméra, création de l'offre, écriture
    // Firestore...), la génération change et isStale() le détecte au
    // prochain point de contrôle ci-dessous — sans ça, l'exécution en cours
    // continuait en arrière-plan après un "Annuler" et finissait quand même
    // par faire sonner Jean (l'appel semblait alors "ignorer" l'annulation
    // tant que la connexion n'avait pas eu lieu).
    const myGeneration = ++this._callGeneration;
    const isStale = () => myGeneration !== this._callGeneration;

    if (!this._available) {
      console.error("[RealCallEngine] Firebase non configuré (voir firebase-config.js), appel impossible.");
      this._endedCb && this._endedCb();
      return;
    }

    const iceServers = [
      { urls: "stun:stun.l.google.com:19302" },
      { urls: "stun:stun1.l.google.com:19302" },
    ];
    const pc = new RTCPeerConnection({ iceServers });
    this._pc = pc;

    // Sans ça, une coupure réseau brutale (Wi-Fi perdu, tablette éteinte...)
    // ne mettait jamais fin à l'appel côté PWA : ni "ended" ni "blocked"
    // n'était jamais écrit dans Firestore, le proche restait bloqué sur
    // l'écran "connecté" indéfiniment. Un bref délai de grâce (l'ICE se
    // rétablit souvent seul après quelques secondes) avant de considérer la
    // connexion définitivement perdue.
    let iceFailureTimer = null;
    pc.oniceconnectionstatechange = () => {
      if (pc.iceConnectionState === "disconnected" || pc.iceConnectionState === "failed") {
        if (iceFailureTimer) return;
        iceFailureTimer = setTimeout(() => {
          iceFailureTimer = null;
          if (isStale()) return;
          this.cancelCall();
        }, 8000);
      } else if (pc.iceConnectionState === "connected" || pc.iceConnectionState === "completed") {
        if (iceFailureTimer) {
          clearTimeout(iceFailureTimer);
          iceFailureTimer = null;
        }
      }
    };

    // Sans ce garde-fou, un refus/échec de la caméra ou du micro (permission
    // refusée pour ce site, caméra déjà utilisée par un autre onglet, pas de
    // caméra...) plantait silencieusement toute la suite : aucun message,
    // l'écran restait bloqué sur "Connexion à sa tablette…" indéfiniment,
    // sans que rien n'indique pourquoi. L'accès caméra/micro d'un site web
    // est une autorisation distincte de celle d'une appli native (WhatsApp,
    // etc.) : les deux peuvent diverger sur un même appareil.
    let localStream;
    try {
      // `audio: true` volontairement, PAS un objet de contraintes explicites :
      // demander explicitement echoCancellation/noiseSuppression a fait
      // APPARAÎTRE un écho sur iPad, qui n'en avait aucun jusque-là (constaté
      // en test réel, correctif retiré aussitôt). Sur iOS, passer un objet de
      // contraintes au lieu de `true` peut faire choisir au système une autre
      // unité de capture audio, sans le traitement de voix (donc sans son
      // annulation d'écho matérielle, excellente par défaut). Ne pas
      // "durcir" ces contraintes sans test réel sur iPad ET Android.
      //
      // audioOnly : mode soignant (voir app.js). Sa caméra n'apporte rien —
      // il est dans la pièce, Jean le voit — et la demander ajouterait une
      // permission à accorder debout, dans l'urgence, avant de pouvoir parler.
      localStream = await navigator.mediaDevices.getUserMedia({
        video: !initialSettings.audioOnly,
        audio: true,
      });
    } catch (e) {
      console.error("[RealCallEngine] Accès caméra/micro refusé ou impossible :", e);
      // e.name distingue des causes très différentes (NotAllowedError :
      // permission refusée : NotReadableError : caméra déjà tenue par autre
      // chose, parfois après un aller-retour vers l'appareil photo du
      // système via un <input type=file> ; NotFoundError : pas de caméra) —
      // affiché explicitement plutôt qu'un seul message générique, pour
      // pouvoir distinguer ces cas la prochaine fois sans avoir à
      // rouvrir la console du téléphone.
      this._errorCb && this._errorCb(
        `Impossible d'accéder à la caméra/au micro de ce navigateur (${e.name || e}). ` +
        "Vérifie l'autorisation accordée à ce site (icône 🔒 ou ⓘ à côté de l'adresse). " +
        "Si l'erreur persiste juste après avoir choisi une photo, recharge la page et réessaie : " +
        "certains téléphones gardent la caméra occupée après être passés par l'appareil photo du système."
      );
      pc.close();
      if (this._pc === pc) this._pc = null;
      return;
    }
    if (isStale()) {
      // Annulé pendant que la caméra se préparait.
      localStream.getTracks().forEach((track) => track.stop());
      pc.close();
      return;
    }
    this._localStream = localStream;
    document.getElementById("localVideo").srcObject = localStream;
    localStream.getTracks().forEach((track) => pc.addTrack(track, localStream));

    const remoteStream = new MediaStream();
    document.getElementById("remoteVideo").srcObject = remoteStream;
    pc.ontrack = (event) => {
      event.streams[0].getTracks().forEach((track) => remoteStream.addTrack(track));
    };

    const callDocRef = this._db.collection("calls").doc();
    const callerCandidates = callDocRef.collection("callerCandidates");
    const calleeCandidates = callDocRef.collection("calleeCandidates");

    pc.onicecandidate = (event) => {
      if (event.candidate) callerCandidates.add(event.candidate.toJSON());
    };

    // Photo choisie par le proche (voir app.js, panneau "Qui appelle ?") si
    // elle existe : nettement plus reconnaissable qu'une capture webcam prise
    // à la volée, souvent sombre et mal cadrée — et c'est elle que Jean voit
    // en plein écran pendant la sonnerie.
    //
    // skipPhoto : mode soignant (voir app.js), où l'appelant est dans la même
    // pièce que Jean — le montrer en photo n'a aucun intérêt, et la capture
    // webcam ferait attendre 1,5 s pour rien avant de renoncer, faute de flux
    // vidéo à photographier.
    const photoPromise = initialSettings.callerPhotoBase64
      ? Promise.resolve(initialSettings.callerPhotoBase64)
      : initialSettings.skipPhoto
        ? Promise.resolve(null)
        : this._captureCallerPhoto();

    // Aucun de ces appels n'était protégé jusqu'ici : une offre WebRTC qui
    // échoue à se créer, ou surtout une écriture Firestore rejetée (constaté
    // en usage réel avec une photo d'appelant précise, cause encore incertaine
    // — champ trop volumineux ? valeur inattendue ?) laissait l'exception
    // partir dans le vide. Contrairement à l'échec caméra/micro juste
    // au-dessus, rien ne prévenait le proche : l'écran restait bloqué sur
    // "Appel en cours" indéfiniment, sans le moindre message. Ce filet
    // remonte enfin l'erreur réelle (e.message) au lieu de la laisser muette
    // — ce qui dira, la prochaine fois que ça se reproduit, ce qui a
    // effectivement échoué.
    try {
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      const callerPhotoBase64 = await photoPromise;

      if (isStale()) {
        // Annulé pendant la préparation de l'offre : le document d'appel n'a
        // pas encore été écrit, Jean ne sonnera jamais.
        localStream.getTracks().forEach((track) => track.stop());
        pc.close();
        return;
      }

      this._callDocRef = callDocRef;
      await callDocRef.set({
        callerName: callerName || "Un proche",
        status: "ringing",
        offerSdp: offer.sdp,
        callerPhotoBase64: callerPhotoBase64 || null,
        // Réglages mémorisés d'un appel précédent (voir app.js, bouton
        // "Mémoriser ces réglages") appliqués dès la sonnerie plutôt que
        // seulement une fois connecté, pour que Jean retrouve directement le
        // confort habituel sans que le proche ait à retoucher chaque curseur.
        remoteVolume: initialSettings.remoteVolume ?? 1,
        captionModeEnabled: initialSettings.captionModeEnabled ?? false,
        captionTextSize: initialSettings.captionTextSize ?? 56,
        captionMaxScrollSpeedDpPerSec: initialSettings.captionMaxScrollSpeedDpPerSec ?? 50,
        captionVisibleLines: initialSettings.captionVisibleLines ?? 2,
        captionClearDelaySeconds: initialSettings.captionClearDelaySeconds ?? 30,
        selfPreviewEnabled: initialSettings.selfPreviewEnabled ?? false,
        // Mode soignant : la tablette se connecte sans attendre son décompte de
        // 30 s (celui-ci a du sens pour un appel venu de l'extérieur, aucun
        // quand la personne est déjà debout à côté de Jean), et son micro est
        // coupé d'emblée pour éviter le larsen avec le téléphone du soignant,
        // à quelques centimètres. Écrits ici plutôt qu'après connexion : la
        // tablette les lit dès le premier instantané Firestore.
        forceConnectRequested: initialSettings.forceConnect ?? false,
        tabletMicMuted: initialSettings.tabletMicMuted ?? false,
        createdAt: firebase.firestore.FieldValue.serverTimestamp(),
      });
    } catch (e) {
      console.error("[RealCallEngine] Échec pendant la préparation de l'appel :", e);
      this._errorCb && this._errorCb(
        `L'appel n'a pas pu démarrer (${e.code || e.name || "erreur"} : ${e.message || e}). ` +
        "Réessaie ; si ça persiste juste après avoir choisi une photo, essaie sans elle pour confirmer."
      );
      localStream.getTracks().forEach((track) => track.stop());
      pc.close();
      if (this._pc === pc) this._pc = null;
      if (this._callDocRef === callDocRef) this._callDocRef = null;
      return;
    }

    if (isStale()) {
      // Annulé pendant l'écriture Firestore : le document existe déjà côté
      // serveur, il faut le clôturer tout de suite pour ne pas laisser
      // sonner la tablette de Jean pour un appel déjà abandonné.
      await callDocRef.update({ status: "ended" }).catch(() => {});
      localStream.getTracks().forEach((track) => track.stop());
      pc.close();
      return;
    }

    this._unsubscribeCallDoc = callDocRef.onSnapshot((snapshot) => {
      const data = snapshot.data();
      if (!data) return;
      if (data.answerSdp && this._pc && !this._pc.currentRemoteDescription) {
        this._pc.setRemoteDescription(new RTCSessionDescription({ type: "answer", sdp: data.answerSdp }));
      }
      if (data.alertStartedAt && data.alertDurationSeconds) {
        this._startCountdownDisplay(data.alertStartedAt.toMillis(), data.alertDurationSeconds);
      }
      if (typeof data.captionCatchUpLagSeconds === "number") {
        this._captionCatchUpLagCb && this._captionCatchUpLagCb(data.captionCatchUpLagSeconds);
      }
      // Diagnostic de la transcription temps réel AssemblyAI côté tablette
      // (voir WebRtcCallEngine.attachTranscriptionSink) : confirme si le son
      // de l'appel atteint bien le transcripteur, et montre les échecs de
      // connexion — tant que ce circuit n'est pas confirmé fiable en usage
      // réel, seul moyen de savoir où ça bloque sans accès au journal
      // système de la tablette.
      if (data.captionDebugMessage && data.captionDebugMessage !== this._lastCaptionDebugMessage) {
        this._lastCaptionDebugMessage = data.captionDebugMessage;
        this._captionDebugCb && this._captionDebugCb(data.captionDebugMessage);
      }
      // Cause exacte d'un échec de préparation d'appel côté tablette (voir
      // WebRtcCallEngine.reportPreparationError, core/WebRtcCallEngine.kt) :
      // jusqu'ici, la tablette raccrochait aussitôt (status "ended") sans la
      // moindre explication — l'écran ici se contentait de repasser en
      // veille, comme un raccroché normal. Affiché une seule fois par appel.
      if (data.calleeErrorMessage && !this._hasReportedCalleeError) {
        this._hasReportedCalleeError = true;
        this._errorCb && this._errorCb(
          `La tablette n'a pas pu préparer l'appel : ${data.calleeErrorMessage}`
        );
      }
      // Ce listener se redéclenche à chaque écriture sur le document d'appel
      // (curseurs, sous-titres, retard de lecture…), pas seulement au
      // passage à "connected" — sans ce garde-fou, onConnected repartait à
      // chaque mise à jour et ramenait le proche sur l'onglet visio même
      // s'il venait de passer sur l'onglet réglages.
      if (data.status === "connected" && !this._hasNotifiedConnected) {
        this._hasNotifiedConnected = true;
        this._connectedCb && this._connectedCb();
      }
      if (data.status === "blocked") {
        this._blockedCb && this._blockedCb();
        this._teardown();
      }
      if (data.status === "ended") {
        this._endedCb && this._endedCb();
        this._teardown();
      }
    });

    this._unsubscribeCalleeCandidates = calleeCandidates.onSnapshot((snapshot) => {
      snapshot.docChanges().forEach((change) => {
        if (change.type === "added" && this._pc) {
          this._pc.addIceCandidate(new RTCIceCandidate(change.doc.data()));
        }
      });
    });

  }

  /**
   * Capture une photo carrée (240x240, JPEG compressé) depuis le flux vidéo
   * local déjà actif, pour affichage immédiat côté tablette (reconnaissance
   * visuelle du proche à la réception de l'appel). Retourne null si la vidéo
   * n'a pas encore de frame disponible (ex. permission caméra refusée) —
   * non bloquant, Jean voit alors juste le nom.
   */
  async _captureCallerPhoto() {
    try {
      const video = document.getElementById("localVideo");
      if (!video.videoWidth) {
        await new Promise((resolve) => {
          const onLoaded = () => { video.removeEventListener("loadeddata", onLoaded); resolve(); };
          video.addEventListener("loadeddata", onLoaded);
          setTimeout(resolve, 1500);
        });
      }
      if (!video.videoWidth) return null;

      const size = 240;
      const side = Math.min(video.videoWidth, video.videoHeight);
      const sx = (video.videoWidth - side) / 2;
      const sy = (video.videoHeight - side) / 2;
      const canvas = document.createElement("canvas");
      canvas.width = size;
      canvas.height = size;
      canvas.getContext("2d").drawImage(video, sx, sy, side, side, 0, 0, size, size);
      return canvas.toDataURL("image/jpeg", 0.6).split(",")[1];
    } catch (e) {
      console.warn("[RealCallEngine] Capture photo impossible :", e);
      return null;
    }
  }

  /**
   * Affiche la progression du décompte de 30s vu côté tablette (voir
   * core/WebRtcCallEngine.signalAlertStarted). Basé sur l'horodatage serveur
   * Firestore plutôt que le moment de réception ici, pour rester correct même
   * si le message a mis du temps à arriver.
   */
  _startCountdownDisplay(startMillis, durationSeconds) {
    if (this._countdownInterval) return;
    const tick = () => {
      const elapsed = (Date.now() - startMillis) / 1000;
      const remaining = Math.max(0, Math.ceil(durationSeconds - elapsed));
      this._countdownCb && this._countdownCb(remaining, durationSeconds);
      if (remaining <= 0) {
        clearInterval(this._countdownInterval);
        this._countdownInterval = null;
      }
    };
    tick();
    this._countdownInterval = setInterval(tick, 250);
  }

  /**
   * Active/désactive à distance le mode "sous-titres géants" côté tablette
   * (voir core/WebRtcCallEngine.kt : listenForCaptionMode). C'est le proche
   * qui décide depuis le PWA, pas un bouton sur la tablette.
   */
  async setCaptionMode(enabled) {
    if (this._callDocRef) {
      await this._callDocRef.update({ captionModeEnabled: enabled }).catch(() => {});
    }
  }

  /** Règle à distance la taille (en sp) du texte des sous-titres géants côté tablette. */
  async setCaptionTextSize(sizeSp) {
    if (this._callDocRef) {
      await this._callDocRef.update({ captionTextSize: sizeSp }).catch(() => {});
    }
  }

  /**
   * Affiche une photo en grand chez Jean, ou termine le diaporama si on
   * passe null (voir WebRtcCallEngine.listenForSlideshowPhoto côté Android).
   *
   * Une seule photo à la fois dans le document d'appel, remplacée à chaque
   * changement : envoyer toute la série d'un coup dépasserait la limite de
   * taille d'un document Firestore dès quelques images.
   */
  async setSlideshowPhoto(photoBase64) {
    if (this._callDocRef) {
      await this._callDocRef.update({ slideshowPhotoBase64: photoBase64 || null }).catch(() => {});
    }
  }

  /**
   * Coupe/rétablit à distance le micro de la tablette (voir
   * WebRtcCallEngine.listenForMicMute côté Android).
   *
   * Ajouté d'abord comme test décisif pour localiser un écho : s'il disparaît
   * quand ce micro est coupé, il vient de la tablette ; s'il persiste, il ne
   * peut venir que d'ici. Utile aussi pour couper un bruit de fond gênant
   * chez Jean sans rien lui demander.
   */
  async setTabletMicMuted(muted) {
    if (this._callDocRef) {
      await this._callDocRef.update({ tabletMicMuted: muted }).catch(() => {});
    }
  }

  /**
   * Active/désactive à distance l'aperçu de sa propre caméra affiché à Jean
   * (petite vignette en haut de son écran, masquée par défaut) — voir
   * core/WebRtcCallEngine.kt : listenForSelfPreviewMode.
   */
  async setSelfPreviewMode(enabled) {
    if (this._callDocRef) {
      await this._callDocRef.update({ selfPreviewEnabled: enabled }).catch(() => {});
    }
  }

  /**
   * Règle à distance la vitesse maximale (en dp/s) à laquelle le texte des
   * sous-titres défile chez Jean (voir IncomingCallActivity.setupCaptionMode
   * côté Android) — plus c'est bas, plus Jean a le temps de lire, au prix
   * d'un retard qui s'accumule si le proche parle vite (voir onCaptionCatchUpLag).
   */
  async setCaptionScrollSpeed(dpPerSec) {
    if (this._callDocRef) {
      await this._callDocRef.update({ captionMaxScrollSpeedDpPerSec: dpPerSec }).catch(() => {});
    }
  }

  /** Règle à distance le nombre de lignes visibles dans le bandeau de sous-titres côté tablette. */
  async setCaptionVisibleLines(lines) {
    if (this._callDocRef) {
      await this._callDocRef.update({ captionVisibleLines: lines }).catch(() => {});
    }
  }

  /**
   * Règle à distance le délai (en secondes) sans nouvelle parole au bout
   * duquel le sous-titre s'efface côté tablette (voir
   * IncomingCallActivity.setupCaptionMode).
   */
  async setCaptionClearDelay(seconds) {
    if (this._callDocRef) {
      await this._callDocRef.update({ captionClearDelaySeconds: seconds }).catch(() => {});
    }
  }

  /** Résumé lisible des métriques vidéo temps réel (résolution, fps, pertes). */
  async getStatsSummary() {
    if (!this._pc) return "";
    const stats = await this._pc.getStats();
    let videoLine = "";
    stats.forEach((report) => {
      if (report.type === "inbound-rtp" && report.kind === "video") {
        videoLine = `🎥 ${report.frameWidth ?? "?"}x${report.frameHeight ?? "?"}@${Math.round(report.framesPerSecond ?? 0)}fps pertes=${report.packetsLost ?? "?"}`;
      }
    });
    return videoLine;
  }

  /**
   * Règle à distance le volume avec lequel Jean entend le proche sur la
   * tablette (voir core/WebRtcCallEngine.kt : AudioTrack.setVolume côté
   * Android). 1 = volume normal, 0 = muet, >1 = amplifié.
   */
  async setRemoteVolume(volume) {
    if (this._callDocRef) {
      await this._callDocRef.update({ remoteVolume: volume }).catch(() => {});
    }
  }

  /**
   * Force la connexion immédiate côté tablette, sans attendre la fin du
   * décompte (bouton "Se connecter maintenant" côté PWA). La tablette
   * écoute ce champ (voir core/WebRtcCallEngine.listenForForceConnect) et
   * saute directement à la vidéo dès qu'il passe à true.
   */
  async forceConnect() {
    if (this._callDocRef) {
      await this._callDocRef.update({ forceConnectRequested: true }).catch(() => {});
    }
  }

  async cancelCall() {
    // Invalide toute exécution de startCall() encore en cours (voir
    // isStale() dans startCall) : sans ça, Annuler pendant la préparation
    // de l'appel (caméra, offre, écriture Firestore) n'avait aucun effet
    // visible tant que la connexion n'avait pas déjà eu lieu — l'exécution
    // en cours continuait en arrière-plan et refaisait sonner Jean.
    this._callGeneration++;
    if (this._callDocRef) {
      await this._callDocRef.update({ status: "ended" }).catch(() => {});
    }
    this._teardown();
  }

  _teardown() {
    if (this._countdownInterval) {
      clearInterval(this._countdownInterval);
      this._countdownInterval = null;
    }
    this._hasNotifiedConnected = false;
    this._hasReportedCalleeError = false;
    if (this._unsubscribeCallDoc) this._unsubscribeCallDoc();
    this._unsubscribeCallDoc = null;
    if (this._unsubscribeCalleeCandidates) this._unsubscribeCalleeCandidates();
    this._unsubscribeCalleeCandidates = null;
    if (this._localStream) this._localStream.getTracks().forEach((track) => track.stop());
    this._localStream = null;
    if (this._pc) this._pc.close();
    this._pc = null;
    this._callDocRef = null;
  }
}
