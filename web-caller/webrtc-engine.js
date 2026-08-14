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
    this._captionOverflowCb = null;
    this._tabletCaptionCb = null;
    this._transcriptHistory = [];
    this._silenceTimer = null;
    this._silenceActive = false;

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
  /** callback(remainingSeconds, totalSeconds) — progression du décompte vu côté tablette. */
  onCountdown(callback) { this._countdownCb = callback; }
  /** callback({liveText, isFinal, confidence, history}) — miroir local de ce que Jean va voir/entendre. */
  onTranscript(callback) { this._transcriptCb = callback; }
  /** callback(silent: boolean) — aucun son détecté depuis quelques secondes pendant que le micro écoute. */
  onSilenceDetected(callback) { this._silenceCb = callback; }
  /** callback(overflowing: boolean) — le texte affiché déborde de l'espace visible côté tablette : ralentir le débit. */
  onCaptionOverflow(callback) { this._captionOverflowCb = callback; }
  /** callback(text: string) — texte exact affiché en ce moment sur la tablette, quelle que soit sa source. */
  onTabletCaption(callback) { this._tabletCaptionCb = callback; }

  async startCall(targetId, callerName) {
    if (!this._available) {
      console.error("[RealCallEngine] Firebase non configuré (voir firebase-config.js), appel impossible.");
      this._endedCb && this._endedCb();
      return;
    }

    const iceServers = [
      { urls: "stun:stun.l.google.com:19302" },
      { urls: "stun:stun1.l.google.com:19302" },
    ];
    this._pc = new RTCPeerConnection({ iceServers });

    this._localStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
    document.getElementById("localVideo").srcObject = this._localStream;
    this._localStream.getTracks().forEach((track) => this._pc.addTrack(track, this._localStream));

    const remoteStream = new MediaStream();
    document.getElementById("remoteVideo").srcObject = remoteStream;
    this._pc.ontrack = (event) => {
      event.streams[0].getTracks().forEach((track) => remoteStream.addTrack(track));
    };

    this._callDocRef = this._db.collection("calls").doc();
    const callerCandidates = this._callDocRef.collection("callerCandidates");
    const calleeCandidates = this._callDocRef.collection("calleeCandidates");

    this._pc.onicecandidate = (event) => {
      if (event.candidate) callerCandidates.add(event.candidate.toJSON());
    };

    const photoPromise = this._captureCallerPhoto();

    const offer = await this._pc.createOffer();
    await this._pc.setLocalDescription(offer);
    const callerPhotoBase64 = await photoPromise;

    await this._callDocRef.set({
      callerName: callerName || "Un proche",
      status: "ringing",
      offerSdp: offer.sdp,
      callerPhotoBase64: callerPhotoBase64 || null,
      captionModeEnabled: false,
      captionTextSize: 56,
      createdAt: firebase.firestore.FieldValue.serverTimestamp(),
    });

    this._unsubscribeCallDoc = this._callDocRef.onSnapshot((snapshot) => {
      const data = snapshot.data();
      if (!data) return;
      if (data.answerSdp && this._pc && !this._pc.currentRemoteDescription) {
        this._pc.setRemoteDescription(new RTCSessionDescription({ type: "answer", sdp: data.answerSdp }));
      }
      if (data.alertStartedAt && data.alertDurationSeconds) {
        this._startCountdownDisplay(data.alertStartedAt.toMillis(), data.alertDurationSeconds);
      }
      if (typeof data.captionOverflowing === "boolean") {
        this._captionOverflowCb && this._captionOverflowCb(data.captionOverflowing);
      }
      if (typeof data.tabletDisplayedCaption === "string") {
        this._tabletCaptionCb && this._tabletCaptionCb(data.tabletDisplayedCaption);
      }
      if (data.status === "connected") this._connectedCb && this._connectedCb();
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

      const now = Date.now();
      if (text && now - lastSent > 500 && this._callDocRef) {
        lastSent = now;
        this._callDocRef.update({ callerSpeechText: text }).catch(() => {});
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
    recognition.onerror = (e) => console.warn("[RealCallEngine] Reconnaissance vocale :", e.error);
    recognition.onend = () => {
      // L'API s'arrête parfois seule après un silence : on la relance tant que l'appel est actif.
      if (this._pc && this._recognition === recognition) {
        try { recognition.start(); } catch (_) {}
      }
    };

    try { recognition.start(); } catch (_) {}
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
