package com.seniorvisio.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration
import com.seniorvisio.signaling.CallSignalingClient
import com.seniorvisio.signaling.RemoteIceCandidate
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

    private var callerCandidatesListener: ListenerRegistration? = null
    private var callId: String? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

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

    fun attachRenderers(local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        local.init(eglBase.eglBaseContext, null)
        local.setMirror(true)
        remote.init(eglBase.eglBaseContext, null)
        localRenderer = local
        remoteRenderer = remote
        localVideoTrack?.addSink(local)
        remoteVideoTrack?.addSink(remote)
    }

    // ---- internals ----

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
        callerCandidatesListener?.remove()
        callerCandidatesListener = null
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
        localRenderer = null
        remoteRenderer = null
        peerConnection?.close()
        peerConnection = null
        localVideoTrack = null
        remoteVideoTrack = null
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
