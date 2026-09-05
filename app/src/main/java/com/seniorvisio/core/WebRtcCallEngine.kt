package com.seniorvisio.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration
import com.seniorvisio.signaling.CallSignalingClient
import com.seniorvisio.signaling.RemoteIceCandidate
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
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
import java.nio.ByteBuffer

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
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var micMuteListener: ListenerRegistration? = null
    private var slideshowListener: ListenerRegistration? = null

    private var callerCandidatesListener: ListenerRegistration? = null
    private var callId: String? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var volumeListener: ListenerRegistration? = null
    private var captionModeListener: ListenerRegistration? = null
    private var captionTextSizeListener: ListenerRegistration? = null
    private var captionScrollSpeedListener: ListenerRegistration? = null
    private var selfPreviewListener: ListenerRegistration? = null
    private var forceConnectListener: ListenerRegistration? = null
    private var remoteEndedListener: ListenerRegistration? = null
    private var connectionLostCb: (() -> Unit)? = null
    private val autoHangupHandler = Handler(Looper.getMainLooper())
    private var autoHangupRunnable: Runnable? = null

    // ---- Transcription temps réel (voir listenForCaptions/setCaptionsActive) ----
    private var transcriptionOnText: ((text: String, isFinal: Boolean) -> Unit)? = null
    private var transcriber: AssemblyAiRealtimeTranscriber? = null
    private var captionsActive = false
    private var remoteAudioSinkAttached = false
    private var hasReportedFirstAudio = false
    /**
     * Consigne de coupure du micro reçue avant même que la piste audio existe
     * (le mode soignant l'écrit dès la création de l'appel, voir
     * web-caller/app.js). Sans ce report, la piste était créée active dans
     * answer() puis coupée quelques centaines de millisecondes plus tard, à
     * l'arrivée de l'instantané Firestore : assez pour un bref larsen quand le
     * téléphone du soignant est à quelques centimètres de la tablette.
     */
    private var pendingMicMuted: Boolean = false
    private var pendingVolume: Double = 1.0
    private var currentVolume: Double = 1.0
    private var volumeRampRunnable: Runnable? = null
    private val volumeHandler = Handler(Looper.getMainLooper())
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
        // Abandonner en silence ici a coûté cher : appelée avant que l'offre du
        // proche ne soit reçue (connexion immédiate du mode soignant), cette
        // méthode ne faisait rien du tout, sans le moindre message. Résultat, la
        // tablette se croyait en communication, le proche restait devant un
        // décompte sans fin, et la transcription — qui passe par Firestore et
        // non par WebRTC — continuait de fonctionner, masquant complètement le
        // problème. L'appelant doit attendre onReady (voir
        // IncomingCallActivity), mais si le cas se represente, il laisse
        // désormais une trace.
        val pc = peerConnection
        if (pc == null) {
            Log.e(TAG, "answer() appelée avant que la connexion WebRTC ne soit prête : appel ignoré")
            return
        }
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

    /**
     * Relaie la cause exacte d'un échec de préparation d'appel (voir
     * IncomingCallActivity) dans le document Firestore de l'appel, pour
     * qu'elle soit visible depuis la console Firebase et depuis l'écran du
     * proche (voir web-caller/webrtc-engine.js) — sans ça, seul un accès
     * physique à la tablette (adb logcat) pouvait révéler pourquoi l'appel
     * raccrochait aussitôt.
     */
    fun reportPreparationError(message: String) {
        val id = callId ?: return
        signaling.reportCalleeError(id, message)
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

    /**
     * Enregistre le texte transcrit à afficher en "sous-titres géants". Ne
     * dépend plus de la reconnaissance vocale du navigateur de l'appelant
     * (absente sur Safari/iOS, présente mais privée de son sur Android/
     * Chrome car le micro est accaparé par l'appel WebRTC lui-même) : la
     * tablette transcrit maintenant elle-même le son déjà reçu par l'appel
     * (voir attachTranscriptionSink), avec AssemblyAI — indépendant de
     * l'appareil ou du navigateur utilisé pour appeler.
     */
    fun listenForCaptions(onText: (text: String, isFinal: Boolean) -> Unit) {
        transcriptionOnText = onText
    }

    /**
     * Démarre/arrête la transcription temps réel selon que le proche a activé
     * les sous-titres depuis le PWA (voir listenForCaptionMode) : AssemblyAI
     * est un service payant à l'usage, contrairement à la reconnaissance
     * vocale du navigateur qu'il remplace — inutile de le faire tourner
     * pendant tout l'appel si personne ne regarde le texte.
     */
    fun setCaptionsActive(active: Boolean) {
        if (captionsActive == active) return
        captionsActive = active
        if (!active) {
            transcriber?.stop()
            transcriber = null
        }
    }

    /**
     * Relié à la piste audio distante dès qu'elle est disponible (voir
     * onTrack) : reçoit en continu le son déjà reçu par l'appel WebRTC, sous
     * forme de PCM brut, et le transmet à AssemblyAI tant que les sous-titres
     * sont actifs (voir setCaptionsActive). Le transcripteur n'est créé qu'au
     * premier bloc audio reçu, pour connaître la fréquence d'échantillonnage
     * réelle plutôt que de la deviner à l'avance.
     */
    private fun attachTranscriptionSink(track: AudioTrack) {
        if (remoteAudioSinkAttached) return
        remoteAudioSinkAttached = true
        track.addSink(object : AudioTrackSink {
            override fun onData(
                audioData: ByteBuffer,
                bitsPerSample: Int,
                sampleRate: Int,
                numberOfChannels: Int,
                numberOfFrames: Int,
                absoluteCaptureTimestampMs: Long,
            ) {
                // Confirme, une seule fois, que ce sink reçoit bien de l'audio
                // — sans outil pour consulter le journal système sur la
                // tablette, impossible autrement de savoir si AudioTrackSink
                // fonctionne ici comme prévu ou reste silencieux.
                if (!hasReportedFirstAudio) {
                    hasReportedFirstAudio = true
                    callId?.let {
                        signaling.reportCaptionDebug(it, "audio reçu (${sampleRate}Hz, ${numberOfChannels}ch)")
                    }
                }
                if (!captionsActive) return
                val onText = transcriptionOnText ?: return
                val apiKey = AdminConfig(context).assemblyAiApiKey
                if (apiKey.isBlank()) {
                    callId?.let { signaling.reportCaptionDebug(it, "clé API AssemblyAI absente") }
                    return
                }
                val instance = transcriber ?: AssemblyAiRealtimeTranscriber(apiKey).also {
                    transcriber = it
                    it.start(onText = onText) { message ->
                        Log.w(TAG, "AssemblyAI temps réel : $message")
                        callId?.let { id -> signaling.reportCaptionDebug(id, "AssemblyAI : $message") }
                    }
                }
                // AudioTrackSink fournit un ByteBuffer (potentiellement direct,
                // en lecture seule) — on en extrait une copie en ByteArray, le
                // format attendu par le transcripteur.
                val bytes = ByteArray(audioData.remaining())
                audioData.duplicate().get(bytes)
                instance.sendAudio(bytes, sampleRate, numberOfChannels)
            }
        })
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
     * l'appel (pas le volume système de la tablette). La transition se fait
     * en douceur (~1,2s) plutôt qu'un saut instantané, pour éviter un effet
     * de surprise côté Jean si le proche change le réglage en pleine
     * conversation (recommandation ergonomique).
     */
    fun listenForRemoteVolumeControl() {
        val id = callId ?: return
        volumeListener = signaling.listenForRemoteVolume(id) { volume ->
            pendingVolume = volume
            rampVolumeTo(volume)
        }
    }

    /**
     * Écoute les photos d'un diaporama commenté à distance par le proche : il
     * les fait défiler depuis le PWA, Jean n'a rien à manipuler et regarde
     * simplement, pendant que la voix du proche est retranscrite en dessous
     * (voir listenForCaptions).
     *
     * Reçoit null (ou une chaîne vide) quand le diaporama se termine : l'écran
     * doit alors revenir à la vidéo.
     */
    fun listenForSlideshowPhoto(onPhoto: (String?) -> Unit) {
        val id = callId ?: return
        slideshowListener = signaling.listenForSlideshowPhoto(id, onPhoto)
    }

    /**
     * Coupe/rétablit à distance le micro de la tablette, sur demande du proche
     * depuis le PWA.
     *
     * Ajouté d'abord comme test décisif pour localiser un écho : dans un appel
     * à deux, si l'écho que le proche entend disparaît quand ce micro est
     * coupé, il vient forcément de la tablette (son haut-parleur qui reboucle
     * dedans) ; s'il persiste, il ne peut venir que du téléphone appelant.
     * Aucune autre hypothèse à départager ensuite.
     *
     * Utile aussi en soi : le proche peut couper un bruit de fond gênant chez
     * Jean (télévision, aspirateur...) sans rien lui demander.
     */
    fun listenForMicMute() {
        val id = callId ?: return
        micMuteListener = signaling.listenForMicMute(id) { muted ->
            pendingMicMuted = muted
            // Mémorisé même quand la piste n'existe pas encore : startLocalMedia
            // l'appliquera à sa création (voir pendingMicMuted).
            localAudioTrack?.setEnabled(!muted)
        }
    }

    /** Écoute l'activation à distance de l'aperçu de sa propre caméra affiché à Jean (masqué par défaut). */
    fun listenForSelfPreviewMode(onEnabled: (Boolean) -> Unit) {
        val id = callId ?: return
        selfPreviewListener = signaling.listenForSelfPreviewMode(id, onEnabled)
    }

    /** Écoute la vitesse maximale de défilement des sous-titres choisie à distance par le proche. */
    fun listenForCaptionScrollSpeed(onDpPerSec: (Float) -> Unit) {
        val id = callId ?: return
        captionScrollSpeedListener = signaling.listenForCaptionScrollSpeed(id) { speed -> onDpPerSec(speed.toFloat()) }
    }

    /**
     * Publie ce que Jean a réellement sous les yeux dans chacune de ses deux
     * zones de texte, et l'avance prise par le proche sur sa lecture, pour
     * que le PWA affiche exactement la même chose au même instant (voir
     * IncomingCallActivity et web-caller/app.js).
     */
    fun publishScreenState(roomText: String?, callText: String?, lagSeconds: Float) {
        val id = callId ?: return
        signaling.publishScreenState(id, roomText, callText, lagSeconds)
    }

    /**
     * Écoute la demande du proche de connecter l'appel immédiatement, sans
     * attendre la fin du décompte (bouton "Se connecter maintenant" côté PWA).
     */
    fun listenForForceConnect(onForce: () -> Unit) {
        val id = callId ?: return
        forceConnectListener = signaling.listenForForceConnect(id, onForce)
    }

    /**
     * Écoute la fin d'appel déclenchée à distance par le proche (PWA), pour
     * que la tablette se referme aussi — sans ça, un raccroché côté proche
     * laissait la communication tourner indéfiniment côté tablette.
     */
    fun listenForRemoteHangup(onHangup: () -> Unit) {
        val id = callId ?: return
        remoteEndedListener = signaling.listenForRemoteEnded(id, onHangup)
    }

    /**
     * Détecte une perte de connexion que personne n'a signalée explicitement
     * (Wi-Fi coupé, navigateur du proche qui plante ou se ferme brutalement,
     * appli tuée en arrière-plan...) : sans ça, ni la tablette ni le PWA ne
     * savent que l'appel est terminé, la caméra/le micro restent engagés
     * indéfiniment côté tablette — jusqu'à ce que Jean raccroche à la main,
     * ou, s'il ne le fait pas, jusqu'à un redémarrage de la tablette (voir
     * cleanup()). Voir onIceConnectionChange ci-dessous.
     */
    fun onConnectionLost(callback: () -> Unit) {
        connectionLostCb = callback
    }

    private fun scheduleAutoHangupOnIceFailure() {
        if (autoHangupRunnable != null) return
        val runnable = Runnable {
            autoHangupRunnable = null
            hangUp()
            connectionLostCb?.invoke()
        }
        autoHangupRunnable = runnable
        autoHangupHandler.postDelayed(runnable, ICE_FAILURE_GRACE_MS)
    }

    /** Une brève déconnexion ICE se résout souvent seule (reprise Wi-Fi...) : n'agit qu'après le délai de grâce. */
    private fun cancelScheduledAutoHangup() {
        autoHangupRunnable?.let { autoHangupHandler.removeCallbacks(it) }
        autoHangupRunnable = null
    }

    // ---- internals ----

    private fun rampVolumeTo(target: Double) {
        val track = remoteAudioTrack
        volumeRampRunnable?.let { volumeHandler.removeCallbacks(it) }
        if (track == null) {
            currentVolume = target
            return
        }
        val start = currentVolume
        val steps = 20
        val stepDelayMs = 60L
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                step++
                val value = start + (target - start) * (step.toFloat() / steps)
                track.setVolume(value)
                currentVolume = value
                if (step < steps) volumeHandler.postDelayed(this, stepDelayMs)
            }
        }
        volumeRampRunnable = runnable
        volumeHandler.post(runnable)
    }

    /**
     * Force le haut-parleur principal (et le mode audio "communication") :
     * sans ça, Android route par défaut l'audio d'appel vers le petit
     * écouteur destiné à être collé à l'oreille, quasi inaudible ici.
     *
     * Fixe aussi le volume système de l'appel à un niveau déterminé (voir
     * [pinSystemVolume]) : c'est ce volume qui multiplie en dernier le gain réglé
     * à distance ([listenForRemoteVolumeControl]) — s'il reste au choix de Jean
     * (boutons physiques), il peut annuler l'effet du curseur du proche. Pendant
     * l'appel, seul ce curseur doit faire foi (voir aussi IncomingCallActivity,
     * qui bloque les boutons physiques).
     */
    private fun configureAudioForCall() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        savedAudioMode = audioManager.mode
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
        savedCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        pinSystemVolume()
    }

    /**
     * Fixe le volume système de l'appel à un niveau confortable — volontairement
     * PAS au maximum, contrairement à la première version.
     *
     * Constaté en test réel : à fond, le haut-parleur de la tablette rejouait la
     * voix du proche assez fort pour que le micro de la tablette la recapte et la
     * lui renvoie — un écho franc de sa propre voix, indépendant de son appareil
     * (iPad comme Android, avec ou sans casque). Confirmé sans ambiguïté en
     * coupant le micro de la tablette à distance : l'écho disparaissait.
     *
     * Un haut-parleur poussé au maximum sature et déforme le son ; l'annulation
     * d'écho, qui compare ce qui est capté à ce qui a été joué, ne reconnaît plus
     * ce qu'elle doit soustraire et laisse passer l'écho. Garder de la marge lui
     * redonne une chance de faire son travail.
     *
     * Jean entend toujours largement assez fort : le proche dispose en plus du
     * curseur de volume à distance, qui monte jusqu'à 200 % — soit nettement
     * au-delà de ce que donnait l'ancien réglage à fond.
     */
    fun pinSystemVolume() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val level = Math.round(max * SYSTEM_VOLUME_RATIO).coerceIn(1, max)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, level, 0)
    }

    private fun restoreAudio() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        savedAudioMode?.let { audioManager.mode = it }
        audioManager.isSpeakerphoneOn = savedSpeakerphoneOn
        savedCallVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0) }
        savedAudioMode = null
        savedCallVolume = null
    }

    /**
     * Volontairement sans module audio explicite : forcer un
     * JavaAudioDeviceModule avec setUseHardwareAcousticEchoCanceler(true) a
     * fait APPARAÎTRE un écho là où il n'y en avait pas (constaté en test
     * réel côté appelant, correctif retiré aussitôt). Le piège est la
     * sémantique de ce réglage : demander l'annulation d'écho MATÉRIELLE
     * désactive du même coup celle, logicielle, de WebRTC (AEC3) — pour
     * éviter un double traitement. Sur un appareil dont l'annulation
     * matérielle est mal calibrée pour le haut-parleur (fréquent), c'est
     * remplacer un bon filtre par un mauvais. Laisser la bibliothèque
     * choisir donne ici un bien meilleur résultat.
     */
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
                } else if (track is AudioTrack) {
                    remoteAudioTrack = track
                    track.setVolume(pendingVolume)
                    currentVolume = pendingVolume
                    attachTranscriptionSink(track)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> scheduleAutoHangupOnIceFailure()
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> cancelScheduledAutoHangup()
                    else -> {}
                }
            }
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
        localAudioTrack = audioTrack
        // Coupé avant même d'être ajouté à la connexion si la consigne est déjà
        // arrivée (mode soignant) : rien ne doit sortir du micro de la tablette,
        // pas même le temps d'un instantané Firestore.
        audioTrack.setEnabled(!pendingMicMuted)

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
        cancelScheduledAutoHangup()
        restoreAudio()
        transcriber?.stop()
        transcriber = null
        transcriptionOnText = null
        captionsActive = false
        remoteAudioSinkAttached = false
        hasReportedFirstAudio = false
        micMuteListener?.remove()
        micMuteListener = null
        slideshowListener?.remove()
        slideshowListener = null
        callerCandidatesListener?.remove()
        callerCandidatesListener = null
        volumeListener?.remove()
        volumeListener = null
        captionModeListener?.remove()
        captionModeListener = null
        captionTextSizeListener?.remove()
        captionTextSizeListener = null
        captionScrollSpeedListener?.remove()
        captionScrollSpeedListener = null
        selfPreviewListener?.remove()
        selfPreviewListener = null
        forceConnectListener?.remove()
        forceConnectListener = null
        remoteEndedListener?.remove()
        remoteEndedListener = null
        volumeRampRunnable?.let { volumeHandler.removeCallbacks(it) }
        volumeRampRunnable = null
        pendingVolume = 1.0
        currentVolume = 1.0

        // La libération effective (caméra, GL, connexion WebRTC) se fait sur
        // un thread à part, pas ici : videoCapturer.stopCapture() est un
        // appel bloquant côté WebRTC (attend l'arrêt réel du thread de
        // capture), explicitement documenté comme à ne jamais appeler depuis
        // le thread principal — sous peine de geler l'interface. Repéré en
        // test réel : l'écran restait figé côté Jean après un raccroché en
        // pleine conversation (caméra activement en train de capturer),
        // alors qu'un appel bloqué avant connexion (caméra jamais démarrée)
        // ne posait aucun souci. cleanup() elle-même reste appelée depuis le
        // thread UI (bouton Raccrocher, onDestroy...), donc les champs sont
        // capturés puis remis à null immédiatement ici pour que l'état de
        // l'engine soit cohérent dès le retour de cleanup(), sans attendre
        // la fin de la libération en arrière-plan.
        val capturerToRelease = videoCapturer
        val textureHelperToRelease = surfaceTextureHelper
        val localRendererToRelease = localRenderer
        val remoteRendererToRelease = remoteRenderer
        val peerConnectionToRelease = peerConnection
        val factoryToRelease = peerConnectionFactory
        videoCapturer = null
        surfaceTextureHelper = null
        localRenderer = null
        remoteRenderer = null
        peerConnection = null
        localVideoTrack = null
        localAudioTrack = null
        remoteVideoTrack = null
        remoteAudioTrack = null
        pendingRemoteCandidates.clear()
        peerConnectionFactory = null

        Thread {
            // Chaque étape est isolée dans son propre try/catch : une erreur
            // sur l'une d'elles (état caméra inattendu, etc.) ne doit jamais
            // empêcher les suivantes de s'exécuter. Avant ce garde-fou, un
            // unique stopCapture() en échec (seule InterruptedException était
            // attrapée) court-circuitait tout le reste — y compris la
            // libération de la factory WebRTC et du contexte EGL juste en
            // dessous, qui restaient alors en mémoire pour le reste de la vie
            // du processus (CallListenerService étant un foreground service
            // permanent, le processus ne redémarre jamais tout seul) : la
            // caméra restait bloquée jusqu'à un redémarrage de la tablette.
            capturerToRelease?.let {
                try {
                    it.stopCapture()
                } catch (e: Exception) {
                }
                try {
                    it.dispose()
                } catch (e: Exception) {
                }
            }
            try {
                textureHelperToRelease?.dispose()
            } catch (e: Exception) {
            }
            try {
                localRendererToRelease?.release()
            } catch (e: Exception) {
            }
            try {
                remoteRendererToRelease?.release()
            } catch (e: Exception) {
            }
            try {
                peerConnectionToRelease?.close()
            } catch (e: Exception) {
            }
            try {
                factoryToRelease?.dispose()
            } catch (e: Exception) {
            }
            try {
                eglBase.release()
            } catch (e: Exception) {
            }
        }.start()
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

    companion object {
        private const val TAG = "WebRtcCallEngine"

        /**
         * Une brève déconnexion ICE se résout souvent seule (quelques
         * secondes de coupure Wi-Fi...) : ce délai laisse une chance de
         * reprendre avant de considérer l'appel définitivement perdu.
         */
        private const val ICE_FAILURE_GRACE_MS = 8000L

        /**
         * Fraction du volume système maximal utilisée pendant un appel (voir
         * pinSystemVolume). Compromis entre "Jean entend bien" et "le micro de
         * la tablette ne recapte pas le haut-parleur" — c'est le second point
         * qui a causé un écho franc côté proche tant que ce réglage était à 1.0.
         */
        private const val SYSTEM_VOLUME_RATIO = 0.7f
    }
}
