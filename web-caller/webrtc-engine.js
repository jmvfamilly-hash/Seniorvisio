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
    this._recognition = null;
    this._countdownCb = null;
    this._countdownInterval = null;
    this._transcriptCb = null;
    this._silenceCb = null;
    this._captionErrorCb = null;
    this._captionCatchUpLagCb = null;
    this._fullscreenCaptionCb = null;
    this._transcriptHistory = [];
    this._transcriptBuffer = [];
    this._lastLagSeconds = 0;
    this._fullscreenCaptionInterval = null;
    this._silenceTimer = null;
    this._silenceActive = false;
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
  /** callback({liveText, isFinal, confidence, history}) — miroir local de ce que Jean va voir/entendre. */
  onTranscript(callback) { this._transcriptCb = callback; }
  /** callback(silent: boolean) — aucun son détecté depuis quelques secondes pendant que le micro écoute. */
  onSilenceDetected(callback) { this._silenceCb = callback; }
  /**
   * callback(errorCode: string) — la reconnaissance vocale (sous-titres)
   * a signalé une erreur (voir recognition.onerror dans _startCaptioning).
   * Jusqu'ici cette erreur n'était journalisée que dans la console du
   * navigateur, jamais montrée : impossible de savoir pourquoi les
   * sous-titres restaient vides sur certains appareils (Android/Chrome
   * notamment, où le micro est peut-être déjà occupé par l'appel lui-même)
   * sans brancher un débogueur.
   */
  onCaptionError(callback) { this._captionErrorCb = callback; }
  /** callback(lagSeconds: number) — retard de lecture de Jean par rapport au texte reçu (0 = à jour) : ralentir le débit si ça grimpe. */
  onCaptionCatchUpLag(callback) { this._captionCatchUpLagCb = callback; }
  /**
   * callback({text, lagSeconds}) — texte tel qu'il apparaît réellement chez
   * Jean à l'instant présent (retardé du retard de lecture mesuré, voir
   * _startFullscreenCaptionTick), pour l'overlay de l'onglet visio plein
   * écran. Différent de onTranscript, qui montre ce que le proche vient de
   * dire (temps réel, non synchronisé avec ce que Jean a effectivement sous
   * les yeux).
   */
  onFullscreenCaption(callback) { this._fullscreenCaptionCb = callback; }

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
      localStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
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
    const photoPromise = initialSettings.callerPhotoBase64
      ? Promise.resolve(initialSettings.callerPhotoBase64)
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
        selfPreviewEnabled: initialSettings.selfPreviewEnabled ?? false,
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
        this._lastLagSeconds = data.captionCatchUpLagSeconds;
        this._captionCatchUpLagCb && this._captionCatchUpLagCb(data.captionCatchUpLagSeconds);
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

    this._startCaptioning();
    this._startFullscreenCaptionTick();
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
   * Transcrit en direct la voix du proche (micro local) et envoie le texte
   * dans Firestore, pour le mode "sous-titres géants" côté tablette (voir
   * core/WebRtcCallEngine.kt). Non supporté par Safari/iOS : l'appel vidéo
   * fonctionne quand même, seuls les sous-titres restent vides.
   *
   * Miroir local (onTranscript) : montre au proche exactement ce que Jean va
   * recevoir, avec un historique des 3 dernières phrases finalisées et leur
   * confiance de reconnaissance (pour repérer les passages mal transcrits).
   * Détection de silence (onSilenceDetected) : si aucun résultat de
   * reconnaissance n'arrive pendant 5s alors que le micro écoute, on prévient
   * le proche que rien n'est capté (micro coupé, trop loin, etc.).
   */
  _startCaptioning() {
    const SpeechRecognitionCtor = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognitionCtor) {
      console.warn("[RealCallEngine] Reconnaissance vocale non supportée par ce navigateur (sous-titres désactivés).");
      return;
    }

    const recognition = new SpeechRecognitionCtor();
    this._recognition = recognition;
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = "fr-FR";

    this._transcriptHistory = [];
    const SILENCE_MS = 5000;
    const resetSilenceTimer = () => {
      if (this._silenceTimer) clearTimeout(this._silenceTimer);
      if (this._silenceActive) {
        this._silenceActive = false;
        this._silenceCb && this._silenceCb(false);
      }
      this._silenceTimer = setTimeout(() => {
        this._silenceActive = true;
        this._silenceCb && this._silenceCb(true);
      }, SILENCE_MS);
    };
    resetSilenceTimer();

    let lastSent = 0;
    recognition.onresult = (event) => {
      let text = "";
      let hasFinal = false;
      let confidence = null;
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        text += result[0].transcript;
        if (result.isFinal) {
          hasFinal = true;
          confidence = result[0].confidence;
        }
      }

      resetSilenceTimer();

      // Envoi toutes les ~300ms (plutôt que 500ms) : des incréments plus
      // petits et plus fréquents donnent un défilement plus fluide côté
      // tablette (voir IncomingCallActivity.setupCaptionMode) qu'un texte
      // qui avance par gros blocs.
      const now = Date.now();
      if (text && now - lastSent > 300 && this._callDocRef) {
        lastSent = now;
        this._callDocRef.update({ callerSpeechText: text }).catch(() => {});
      }

      // Historique horodaté pour reconstituer ce que Jean voit avec le
      // retard mesuré côté tablette (voir _startFullscreenCaptionTick) —
      // borné à 60s, largement au-delà des retards observés en pratique.
      if (text) {
        this._transcriptBuffer.push({ text, tsMs: now });
        const cutoffMs = now - 60000;
        while (this._transcriptBuffer.length > 1 && this._transcriptBuffer[0].tsMs < cutoffMs) {
          this._transcriptBuffer.shift();
        }
      }

      if (hasFinal && text.trim()) {
        this._transcriptHistory.push({ text: text.trim(), confidence });
        if (this._transcriptHistory.length > 3) this._transcriptHistory.shift();
      }

      this._transcriptCb && this._transcriptCb({
        liveText: text,
        isFinal: hasFinal,
        confidence,
        history: [...this._transcriptHistory],
      });
    };
    recognition.onerror = (e) => {
      console.warn("[RealCallEngine] Reconnaissance vocale :", e.error);
      // "no-speech" est un événement normal (personne ne parle en ce moment,
      // déjà couvert par l'indicateur de silence) — seules les erreurs
      // réelles (micro déjà occupé par l'appel WebRTC, réseau, permission)
      // méritent d'être montrées.
      if (e.error !== "no-speech") this._captionErrorCb && this._captionErrorCb(e.error);
    };
    recognition.onend = () => {
      // L'API s'arrête parfois seule après un silence : on la relance tant que l'appel est actif.
      if (this._pc && this._recognition === recognition) {
        try { recognition.start(); } catch (_) {}
      }
    };

    try { recognition.start(); } catch (_) {}
  }

  /**
   * Reconstitue périodiquement le texte tel qu'il apparaît réellement chez
   * Jean à l'instant présent (voir onFullscreenCaption), en piochant dans
   * l'historique horodaté du texte transcrit (_transcriptBuffer) l'entrée la
   * plus récente antérieure de `_lastLagSeconds` secondes à maintenant —
   * approximation raisonnable de ce que Jean a sous les yeux tant que le
   * retard mesuré reste à peu près stable, sans avoir besoin de faire
   * remonter le texte exact affiché côté tablette.
   */
  _startFullscreenCaptionTick() {
    if (this._fullscreenCaptionInterval) return;
    this._fullscreenCaptionInterval = setInterval(() => {
      if (!this._fullscreenCaptionCb) return;
      const targetTsMs = Date.now() - this._lastLagSeconds * 1000;
      let delayed = "";
      for (let i = this._transcriptBuffer.length - 1; i >= 0; i--) {
        if (this._transcriptBuffer[i].tsMs <= targetTsMs) {
          delayed = this._transcriptBuffer[i].text;
          break;
        }
      }
      if (!delayed && this._transcriptBuffer.length > 0) {
        // Retard plus long que l'historique conservé : montre le plus ancien connu.
        delayed = this._transcriptBuffer[0].text;
      }
      this._fullscreenCaptionCb({ text: delayed, lagSeconds: this._lastLagSeconds });
    }, 250);
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

  /**
   * Demande à la tablette de réinitialiser l'application de transcription de
   * Google (voir DeviceStatusReporter.handleTranscriptionReset côté Android).
   *
   * Seul moyen de réparer à distance un réglage déréglé dedans : son interface
   * échappe à Senior Visio, et rien ne permet d'y imposer une configuration
   * depuis l'extérieur. L'application repart sur les valeurs par défaut de
   * Google, et ses transcriptions conservées sont effacées au passage.
   *
   * S'écrit hors de tout appel, contrairement aux autres réglages à distance :
   * c'est une action de maintenance, pas une commande d'appel. Un horodatage
   * serveur plutôt qu'un booléen, pour que chaque demande soit distincte de la
   * précédente sans avoir à remettre un champ à zéro ensuite.
   */
  async requestTranscriptionReset() {
    if (!this._db) throw new Error("Firebase non configuré");
    await this._db.collection("devices").doc("jean_tablet").set(
      { resetTranscriptionRequestedAt: firebase.firestore.FieldValue.serverTimestamp() },
      { merge: true }
    );
  }

  _stopCaptioning() {
    if (this._recognition) {
      const recognition = this._recognition;
      this._recognition = null;
      recognition.onend = null;
      try { recognition.stop(); } catch (_) {}
    }
    if (this._silenceTimer) {
      clearTimeout(this._silenceTimer);
      this._silenceTimer = null;
    }
    this._silenceActive = false;
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
    this._stopCaptioning();
    if (this._countdownInterval) {
      clearInterval(this._countdownInterval);
      this._countdownInterval = null;
    }
    if (this._fullscreenCaptionInterval) {
      clearInterval(this._fullscreenCaptionInterval);
      this._fullscreenCaptionInterval = null;
    }
    this._transcriptBuffer = [];
    this._lastLagSeconds = 0;
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
