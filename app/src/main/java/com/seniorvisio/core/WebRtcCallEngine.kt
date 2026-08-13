package com.seniorvisio.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration
import com.seniorvisio.signaling.CallSignalingClient
import com.seniorvisio.signaling.RemoteIceCandidate
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Implémentation WebRTC de [CallEngine]. Le signaling (échange de l'offre,
 * de la réponse et des candidats ICE) passe par [CallSignalingClient]
 * (Firestore) — voir web-caller/webrtc-engine.js pour le pendant navigateur.
 *
 * Séquence côté tablette (rôle "callee") :
 *  1. [prepareIncomingCall] récupère l'offre et fait setRemoteDescription.
 *     Caméra/micro encore éteints à ce stade (appel juste "vu", pas accepté).
 *  2. [answer] active la caméra/micro, crée puis envoie la réponse SDP, et
 *     démarre l'échange des candidats ICE dans les deux sens.
 */
class WebRtcCallEngine(private val context: Context) : CallEngine {

    private val signaling = CallSignalingClient()
    private val eglBase: EglBase = EglBase.create()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    private var callerCandidatesListener: ListenerRegistration? = null
    private var callId: String? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var captionRenderer: SurfaceViewRenderer? = null
    private var speechListener: ListenerRegistration? = null
    private var volumeListener: ListenerRegistration? = null
    private var captionModeListener: ListenerRegistration? = null
    private var captionTextSizeListener: ListenerRegistration? = null
    private var pendingVolume: Double = 1.0
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private var savedAudioMode: Int? = null
    private var savedSpeakerphoneOn: Boolean = false
    private var savedCallVolume: Int? = null

    override var state: CallState = CallState.IDLE
        private set

    override val engineName: String = "WebRTC (signaling Firestore auto-hébergé)"

    override fun prepareIncomingCall(callId: String, onReady: () -> Unit, onError: (Throwable) -> Unit) {
        if (!signaling.isAvailable()) {
            onError(IllegalStateException("Firebase non configuré (google-services.json manquant)"))
            return
        }
        this.callId = callId
        state = CallState.RINGING_SILENT
        ensureFactory()

        signaling.fetchOfferSdp(callId) { sdp ->
            if (sdp == null) {
                onError(IllegalStateException("Offre d'appel introuvable (callId=$callId)"))
                return@fetchOfferSdp
            }
            val pc = createPeerConnection()
            if (pc == null) {
                onError(IllegalStateException("Impossible de créer la connexion WebRTC"))
                return@fetchOfferSdp
            }
            pc.setRemoteDescription(
                SimpleSdpObserver(
                    onSet = { onReady() },
                    onFailure = { onError(IllegalStateException(it)) }
                ),
                SessionDescription(SessionDescription.Type.OFFER, sdp)
            )
        }
    }

    override fun answer() {
        val pc = peerConnection ?: return
        val id = callId ?: return
        state = CallState.CONNECTING
        startLocalMedia(pc)
        pc.createAnswer(SimpleSdpObserver(onCreate = { desc ->
            pc.setLocalDescription(
                SimpleSdpObserver(onSet = {
                    signaling.sendAnswer(id, desc.description)
                    listenForCallerCandidates(id)
                    drainPendingCandidates()
                    state = CallState.ACTIVE
                }),
                desc
            )
        }), MediaConstraints())
    }

    override fun hangUp() {
        callId?.let { signaling.updateStatus(it, CallSignalingClient.STATUS_ENDED) }
        cleanup()
        state = CallState.ENDED
    }

    /** Appelée quand Jean bloque l'appel pendant le décompte (avant connexion). */
    fun blockCall() {
        callId?.let { signaling.updateStatus(it, CallSignalingClient.STATUS_BLOCKED) }
        cleanup()
        state = CallState.ENDED
    }

    /**
     * @param captionRemote petit rendu vidéo utilisé par le mode "sous-titres géants"
     * (voir IncomingCallActivity) — reçoit le même flux que [remote], juste affiché en
     * plus petit pendant que le texte transcrit prend le plus de place à l'écran.
     */
    fun attachRenderers(local: SurfaceViewRenderer, remote: SurfaceViewRenderer, captionRemote: SurfaceViewRenderer) {
        local.init(eglBase.eglBaseContext, null)
        local.setMirror(true)
        remote.init(eglBase.eglBaseContext, null)
        captionRemote.init(eglBase.eglBaseContext, null)
        localRenderer = local
        remoteRenderer = remote
        captionRenderer = captionRemote
        localVideoTrack?.addSink(local)
        remoteVideoTrack?.addSink(remote)
        remoteVideoTrack?.addSink(captionRemote)
    }

    /**
     * Écoute le texte transcrit en direct de la voix de l'appelant (envoyé par
     * webrtc-engine.js via reconnaissance vocale navigateur) pour le mode
     * "sous-titres géants". Ne fait rien si le navigateur appelant ne
     * supporte pas la reconnaissance vocale (ex. Safari/iOS) : aucun texte
     * n'arrivera jamais, l'appel vidéo reste inchangé.
     */
    fun listenForCaptions(onText: (String) -> Unit) {
        val id = callId ?: return
        speechListener = signaling.listenForCallerSpeech(id, onText)
    }

    /**
     * Notifie Firestore que le décompte d'alerte démarre, pour que le PWA
     * appelant en affiche la progression en direct (voir web-caller/app.js).
     */
    fun signalAlertStarted(durationSeconds: Int) {
        val id = callId ?: return
        signaling.startAlertCountdown(id, durationSeconds)
    }

    /**
     * Écoute l'activation/désactivation à distance du mode "sous-titres
     * géants" : la décision revient au proche depuis le PWA (voir
     * web-caller/app.js), pas à un bouton sur la tablette.
     */
    fun listenForCaptionMode(onEnabled: (Boolean) -> Unit) {
        val id = callId ?: return
        captionModeListener = signaling.listenForCaptionMode(id, onEnabled)
    }

    /** Écoute la taille de texte des sous-titres choisie à distance par le proche depuis le PWA. */
    fun listenForCaptionTextSize(onSizeSp: (Float) -> Unit) {
        val id = callId ?: return
        captionTextSizeListener = signaling.listenForCaptionTextSize(id) { size -> onSizeSp(size.toFloat()) }
    }

    /**
     * Applique le niveau de volume choisi à distance par l'appelant depuis le
     * curseur du PWA (voir web-caller/webrtc-engine.js). 1.0 = volume normal,
     * 0.0 = muet, >1.0 = amplifié. Agit uniquement sur le flux audio de
     * l'appel (pas le volume système de la tablette).
     */
    fun listenForRemoteVolumeControl() {
        val id = callId ?: return
        volumeListener = signaling.listenForRemoteVolume(id) { volume ->
            pendingVolume = volume
            remoteAudioTrack?.setVolume(volume)
        }
    }

    /**
     * Résumé lisible des métriques vidéo temps réel de l'appel (résolution,
     * fps, paquets perdus) — pour objectiver la qualité au lieu de se fier au
     * ressenti. Rafraîchi à la demande (voir appelant).
     */
    fun fetchStatsSummary(onResult: (String) -> Unit) {
        val pc = peerConnection
        if (pc == null) {
            onResult("")
            return
        }
        pc.getStats { report ->
            var videoLine = ""
            report.statsMap.values.forEach { stat ->
                if (stat.type == "inbound-rtp" && stat.members["kind"] == "video") {
                    videoLine = "🎥 ${stat.members["frameWidth"]}x${stat.members["frameHeight"]}" +
                        "@${stat.members["framesPerSecond"]}fps pertes=${stat.members["packetsLost"]}"
                }
            }
            onResult(videoLine)
        }
    }

    // ---- internals ----

    /**
     * Force le haut-parleur principal (et le mode audio "communication") :
     * sans ça, Android route par défaut l'audio d'appel vers le petit
     * écouteur destiné à être collé à l'oreille, quasi inaudible ici.
     *
     * Fixe aussi le volume système de l'appel au maximum : c'est ce volume
     * qui multiplie en dernier le gain réglé à distance ([listenForRemoteVolumeControl])
     * — s'il reste au choix de Jean (boutons physiques), il peut annuler l'effet
     * du curseur du proche. Pendant l'appel, seul ce curseur doit faire foi
     * (voir aussi IncomingCallActivity, qui bloque les boutons physiques).
     */
    private fun configureAudioForCall() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        savedAudioMode = audioManager.mode
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
        savedCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        pinSystemVolumeToMax()
    }

    /** Remet le volume système de l'appel au maximum (voir configureAudioForCall). */
    fun pinSystemVolumeToMax() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
    }

    private fun restoreAudio() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        savedAudioMode?.let { audioManager.mode = it }
        audioManager.isSpeakerphoneOn = savedSpeakerphoneOn
        savedCallVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0) }
        savedAudioMode = null
        savedCallVolume = null
    }

    private fun ensureFactory() {
        if (peerConnectionFactory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(): PeerConnection? {
        val factory = peerConnectionFactory ?: return null
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                callId?.let {
                    signaling.addCandidate(
                        it,
                        RemoteIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                    )
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    remoteRenderer?.let { track.addSink(it) }
                    captionRenderer?.let { track.addSink(it) }
                } else if (track is AudioTrack) {
                    remoteAudioTrack = track
                    track.setVolume(pendingVolume)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
        peerConnection = pc
        return pc
    }

    private fun startLocalMedia(pc: PeerConnection) {
        val factory = peerConnectionFactory ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        configureAudioForCall()

        val capturer = createFrontCameraCapturer() ?: return
        videoCapturer = capturer
        val videoSource = factory.createVideoSource(false)
        val helper = SurfaceTextureHelper.create("SeniorVisioCapture", eglBase.eglBaseContext)
        surfaceTextureHelper = helper
        capturer.initialize(helper, context, videoSource.capturerObserver)
        capturer.startCapture(640, 480, 30)

        val videoTrack = factory.createVideoTrack("SVIO_VIDEO", videoSource)
        localVideoTrack = videoTrack
        localRenderer?.let { videoTrack.addSink(it) }

        val audioTrack = factory.createAudioTrack("SVIO_AUDIO", factory.createAudioSource(MediaConstraints()))

        pc.addTrack(videoTrack, listOf("SVIO_STREAM"))
        pc.addTrack(audioTrack, listOf("SVIO_STREAM"))
    }

    private fun createFrontCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val frontCamera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val deviceName = frontCamera ?: enumerator.deviceNames.firstOrNull() ?: return null
        return enumerator.createCapturer(deviceName, null)
    }

    private fun listenForCallerCandidates(id: String) {
        callerCandidatesListener = signaling.listenForCallerCandidates(id) { remote ->
            val candidate = IceCandidate(remote.sdpMid, remote.sdpMLineIndex, remote.candidate)
            val pc = peerConnection
            if (pc?.remoteDescription != null) {
                pc.addIceCandidate(candidate)
            } else {
                pendingRemoteCandidates.add(candidate)
            }
        }
    }

    private fun drainPendingCandidates() {
        val pc = peerConnection ?: return
        pendingRemoteCandidates.forEach { pc.addIceCandidate(it) }
        pendingRemoteCandidates.clear()
    }

    private fun cleanup() {
        restoreAudio()
        callerCandidatesListener?.remove()
        callerCandidatesListener = null
        speechListener?.remove()
        speechListener = null
        volumeListener?.remove()
        volumeListener = null
        captionModeListener?.remove()
        captionModeListener = null
        captionTextSizeListener?.remove()
        captionTextSizeListener = null
        pendingVolume = 1.0
        videoCapturer?.let {
            try {
                it.stopCapture()
            } catch (_: InterruptedException) {
            }
            it.dispose()
        }
        videoCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localRenderer?.release()
        remoteRenderer?.release()
        captionRenderer?.release()
        localRenderer = null
        remoteRenderer = null
        captionRenderer = null
        peerConnection?.close()
        peerConnection = null
        localVideoTrack = null
        remoteVideoTrack = null
        remoteAudioTrack = null
        pendingRemoteCandidates.clear()
    }

    private class SimpleSdpObserver(
        private val onCreate: (SessionDescription) -> Unit = {},
        private val onSet: () -> Unit = {},
        private val onFailure: (String) -> Unit = {}
    ) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = onCreate(desc)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String) = onFailure(error)
        override fun onSetFailure(error: String) = onFailure(error)
    }
}
