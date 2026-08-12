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

    const offer = await this._pc.createOffer();
    await this._pc.setLocalDescription(offer);

    await this._callDocRef.set({
      callerName: callerName || "Un proche",
      status: "ringing",
      offerSdp: offer.sdp,
      createdAt: firebase.firestore.FieldValue.serverTimestamp(),
    });

    this._unsubscribeCallDoc = this._callDocRef.onSnapshot((snapshot) => {
      const data = snapshot.data();
      if (!data) return;
      if (data.answerSdp && this._pc && !this._pc.currentRemoteDescription) {
        this._pc.setRemoteDescription(new RTCSessionDescription({ type: "answer", sdp: data.answerSdp }));
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
   * Transcrit en direct la voix du proche (micro local) et envoie le texte
   * dans Firestore, pour le mode "sous-titres géants" côté tablette (voir
   * core/WebRtcCallEngine.kt). Non supporté par Safari/iOS : l'appel vidéo
   * fonctionne quand même, seuls les sous-titres restent vides.
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

    let lastSent = 0;
    recognition.onresult = (event) => {
      let text = "";
      for (let i = event.resultIndex; i < event.results.length; i++) {
        text += event.results[i][0].transcript;
      }
      const now = Date.now();
      if (text && now - lastSent > 500 && this._callDocRef) {
        lastSent = now;
        this._callDocRef.update({ callerSpeechText: text }).catch(() => {});
      }
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

  _stopCaptioning() {
    if (this._recognition) {
      const recognition = this._recognition;
      this._recognition = null;
      recognition.onend = null;
      try { recognition.stop(); } catch (_) {}
    }
  }

  /** Résumé lisible des métriques temps réel (niveau audio, gigue, pertes, fps vidéo). */
  async getStatsSummary() {
    if (!this._pc) return "";
    const stats = await this._pc.getStats();
    let audioLine = "";
    let videoLine = "";
    stats.forEach((report) => {
      if (report.type === "inbound-rtp" && report.kind === "audio") {
        audioLine = `🔊 niveau=${report.audioLevel ?? "?"} gigue=${report.jitter ?? "?"}s pertes=${report.packetsLost ?? "?"}`;
      }
      if (report.type === "inbound-rtp" && report.kind === "video") {
        videoLine = `🎥 ${report.frameWidth ?? "?"}x${report.frameHeight ?? "?"}@${Math.round(report.framesPerSecond ?? 0)}fps pertes=${report.packetsLost ?? "?"}`;
      }
    });
    return [audioLine, videoLine].filter(Boolean).join("\n");
  }

  async cancelCall() {
    if (this._callDocRef) {
      await this._callDocRef.update({ status: "ended" }).catch(() => {});
    }
    this._teardown();
  }

  _teardown() {
    this._stopCaptioning();
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
