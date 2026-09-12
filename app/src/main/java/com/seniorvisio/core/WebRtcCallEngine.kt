package com.seniorvisio.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import org.webrtc.RTCStatsCollectorCallback
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
    private var remoteVideoTrack: VideoTrack? = null
    private var micMuteListener: ListenerRegistration? = null
    private var sameRoomListener: ListenerRegistration? = null
    private var slideshowListener: ListenerRegistration? = null

    /**
     * ═══ CES CHAMPS SONT LUS ET ÉCRITS PAR DEUX THREADS DIFFÉRENTS ═══
     *
     * Les consignes du proche arrivent par des instantanés Firestore, donc sur
     * le THREAD PRINCIPAL. Les pistes audio, elles, arrivent par onTrack, qui
     * s'exécute sur le THREAD DE SIGNALISATION DE WEBRTC. Et onTrack décide du
     * son que Jean va entendre en lisant [sameRoomMode] et [pendingVolume],
     * écrits par l'autre thread quelques millisecondes plus tôt.
     *
     * Sans `@Volatile`, rien n'oblige ce thread-là à voir l'écriture de
     * l'autre : il peut travailler sur une valeur périmée, indéfiniment. Le
     * symptôme est exactement celui qui a été constaté — aucun son au début
     * d'un appel, un curseur de volume sans effet, puis le son qui revient
     * sans qu'on sache pourquoi. Un défaut de visibilité mémoire ne se
     * reproduit pas à la demande : c'est sa signature, et c'est ce qui le rend
     * si difficile à attribuer.
     *
     * `@Volatile` établit le lien manquant entre les deux threads. Il ne rend
     * PAS les suites d'opérations atomiques — c'est pourquoi l'application du
     * volume est en plus ramenée sur un seul thread (voir rampVolumeTo et
     * onTrack) : deux écritures concurrentes de setVolume sur la même piste
     * donneraient sinon un niveau final imprévisible.
     */
    @Volatile private var localAudioTrack: AudioTrack? = null
    @Volatile private var remoteAudioTrack: AudioTrack? = null

    /** Voir listenForSameRoomMode : coupe entièrement le son, quel que soit le curseur de volume. */
    @Volatile private var sameRoomMode = false

    private var callerCandidatesListener: ListenerRegistration? = null
    private var callId: String? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var volumeListener: ListenerRegistration? = null
    private var captionModeListener: ListenerRegistration? = null
    private var micToRoomListener: ListenerRegistration? = null
    private var selfPreviewListener: ListenerRegistration? = null
    private var forceConnectListener: ListenerRegistration? = null
    private var remoteEndedListener: ListenerRegistration? = null
    private var connectionLostCb: (() -> Unit)? = null
    private val autoHangupHandler = Handler(Looper.getMainLooper())
    private var autoHangupRunnable: Runnable? = null

    // ---- Chien de garde du flux entrant (voir startMediaWatchdog) ----
    private val mediaWatchdogHandler = Handler(Looper.getMainLooper())
    private var mediaWatchdogRunnable: Runnable? = null
    private var lastInboundBytes = -1L
    private var lastInboundProgressAtMs = 0L
    private var hasEverReceivedMedia = false

    // ---- Transcription temps réel (voir listenForCaptions/setCaptionsActive) ----
    private var transcriptionOnText: ((source: TranscriptionSource, text: String, isFinal: Boolean) -> Unit)? = null
    private var captionsActive = false

    /** Voir setMicToRoom : laquelle des deux pistes audio est transcrite. */
    private var micToRoom = false

    /**
     * Le moteur partagé avec le service d'écoute de la pièce (voir
     * TranscriptionEngine) : les deux pistes audio de l'appel l'alimentent en
     * permanence, il n'en transcrit qu'une à la fois.
     */
    private val transcription by lazy {
        TranscriptionEngine(
            context = context,
            onText = { source, text, isFinal -> transcriptionOnText?.invoke(source, text, isFinal) },
            onDiagnostic = { message -> callId?.let { signaling.reportCaptionDebug(it, message) } },
        )
    }
    /**
     * Consigne de coupure du micro reçue avant même que la piste audio existe
     * (voir web-caller/app.js). Sans ce report, la piste était créée active
     * dans answer() puis coupée quelques centaines de millisecondes plus tard,
     * à l'arrivée de l'instantané Firestore : assez pour un bref larsen quand
     * le téléphone du proche est à quelques centimètres de la tablette.
     *
     * Remis à false par cleanup() : c'est une consigne d'appel, pas un réglage
     * de la tablette. Voir le commentaire là-bas, la distinction a coûté cher.
     */
    @Volatile private var pendingMicMuted: Boolean = false

    /** Consigne du curseur du proche. Voir le commentaire de [remoteAudioTrack] sur les threads. */
    @Volatile private var pendingVolume: Double = 1.0

    /** Niveau réellement appliqué à la piste. Touché seulement sur le thread principal. */
    private var currentVolume: Double = 1.0
    private var volumeRampRunnable: Runnable? = null
    private val volumeHandler = Handler(Looper.getMainLooper())
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private var savedAudioMode: Int? = null
    private var savedSpeakerphoneOn: Boolean = false
    private var savedCallVolume: Int? = null

    /**
     * Le volume média d'avant l'appel. Sauvegardé depuis que le curseur agit
     * aussi sur ce flux : sans ça, un appel laisserait la musique, les vidéos
     * et les alarmes de la tablette au niveau choisi par le proche.
     */
    private var savedMusicVolume: Int? = null

    /** Le focus audio tenu pendant l'appel, à rendre à la fin (voir requestAudioFocus). */
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    /** Dernier relevé des niveaux système, pour ne journaliser que les changements. */
    private var lastSeenVolumes: String? = null

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
        // Version ET permissions dès la première ligne, avant tout le reste.
        //
        // Ce sont les deux choses dont l'absence rend toutes les lignes
        // suivantes ininterprétables : un journal sans la ligne attendue peut
        // vouloir dire « la fonction n'a pas tourné » ou « la tablette est sur
        // une version qui ne l'écrit pas encore », et ces deux lectures
        // conduisent à des recherches opposées. La question ne doit plus jamais
        // se poser.
        CallTrace.record(
            "APPEL préparation",
            "${com.seniorvisio.BuildConfig.BUILD_REV} — callId=$callId · permissions caméra=" +
                "${ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED}" +
                " micro=${ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}",
        )
        ensureFactory()

        signaling.fetchOfferSdp(callId) { sdp ->
            CallTrace.record("APPEL offre", if (sdp == null) "INTROUVABLE" else "reçue (${sdp.length} car.)")
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
        // proche ne soit reçue (bouton « Connexion immédiate » du PWA), cette
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
            CallTrace.record("APPEL answer", "ABANDON : connexion WebRTC pas encore prête")
            return
        }
        val id = callId
        if (id == null) {
            CallTrace.record("APPEL answer", "ABANDON : aucun identifiant d'appel")
            return
        }
        state = CallState.CONNECTING
        CallTrace.record(
            "APPEL answer",
            "consigneVolume=$pendingVolume microCoupé=$pendingMicMuted mêmePièce=$sameRoomMode",
        )
        startLocalMedia(pc)
        pc.createAnswer(SimpleSdpObserver(onCreate = { desc ->
            pc.setLocalDescription(
                SimpleSdpObserver(onSet = {
                    signaling.sendAnswer(id, desc.description)
                    listenForCallerCandidates(id)
                    drainPendingCandidates()
                    startMediaWatchdog()
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
        // La pile d'appel est journalisée, et ce n'est pas du luxe : « l'appel
        // s'est arrêté tout seul » a plusieurs causes possibles — le chien de
        // garde du flux, l'échec ICE, un raccroché du proche, le bouton de la
        // tablette — et elles sont indiscernables une fois l'appel terminé.
        CallTrace.record(
            "APPEL raccroché",
            Throwable().stackTrace.drop(1).take(3).joinToString(" ← ") { "${it.methodName}:${it.lineNumber}" },
        )
        callId?.let { signaling.updateStatus(it, CallSignalingClient.STATUS_ENDED) }
        cleanup()
        state = CallState.ENDED
    }

    /** Appelée quand Jean bloque l'appel pendant le décompte (avant connexion). */
    fun blockCall() {
        CallTrace.record("APPEL bloqué", "refus depuis la tablette")
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
    fun listenForCaptions(onText: (source: TranscriptionSource, text: String, isFinal: Boolean) -> Unit) {
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
        captionsActive = active
        applyTranscriptionSource()
    }

    /**
     * Bascule la transcription du son de l'appel vers le microphone de la
     * tablette, sur demande de l'appelant (voir listenForMicToRoom) : Jean lit
     * alors ce que dit quelqu'un présent dans sa pièce — un soignant, un
     * visiteur — au lieu de ce que dit son correspondant.
     *
     * Seule la SOURCE de la transcription change. Le son continue de circuler
     * dans les deux sens exactement comme avant : c'est ce qui permet à
     * l'appelant de parler avec la personne présente auprès de Jean pendant
     * tout ce temps.
     *
     * Le texte change de zone tout seul, sans que rien ici n'ait à le dire :
     * la couche d'affichage range chaque texte selon sa source (voir
     * TranscriptionSource, HomeZonesController).
     */
    fun setMicToRoom(enabled: Boolean) {
        micToRoom = enabled
        applyTranscriptionSource()
    }

    private fun applyTranscriptionSource() {
        transcription.setActiveSource(
            when {
                !captionsActive -> null
                micToRoom -> TranscriptionSource.ROOM
                else -> TranscriptionSource.CALL
            }
        )
    }

    /**
     * Branche une des deux pistes audio de l'appel sur le moteur de
     * transcription : celle reçue du proche (dès qu'elle arrive, voir onTrack)
     * et celle du microphone de la tablette (créée par startLocalMedia). Les
     * deux sont reliées en permanence et alimentent le moteur en continu ;
     * c'est lui qui ignore celle qui n'est pas active à cet instant (voir
     * TranscriptionEngine.feed). Brancher et débrancher des sinks audio en
     * pleine conversation à chaque changement d'avis serait autrement plus
     * risqué pour un bénéfice nul.
     */
    private fun attachTranscriptionSink(track: AudioTrack, source: TranscriptionSource) {
        val ring = ringFor(source)
        track.addSink(object : AudioTrackSink {
            override fun onData(
                audioData: ByteBuffer,
                bitsPerSample: Int,
                sampleRate: Int,
                numberOfChannels: Int,
                numberOfFrames: Int,
                absoluteCaptureTimestampMs: Long,
            ) {
                // Le format est relevé à chaque bloc plutôt que supposé fixe :
                // il ne change pas en pratique, mais le déduire d'une constante
                // serait une hypothèse invérifiable, et une erreur de fréquence
                // ne se voit PAS — elle produit du texte plausible et faux.
                ring.sampleRate = sampleRate
                ring.channels = numberOfChannels
                // AUCUNE ALLOCATION ICI. Recopie directe dans un tampon
                // préalloué (voir PcmRingBuffer) : ce rappel s'exécute sur le
                // fil qui alimente le haut-parleur, et une pause du
                // ramasse-miettes y tombe sur le fil qui alloue.
                ring.pcm.write(audioData, audioData.remaining())
            }
        })
    }

    /**
     * Le son d'une source, en attente de transcription, avec son format.
     *
     * ═══ UN TAMPON PAR SOURCE, ET C'EST ESSENTIEL ═══
     *
     * Les deux pistes — le microphone de la tablette et la voix du proche —
     * alimentent la transcription en permanence. Les verser dans un tampon
     * commun mélangerait deux flux d'octets qui n'ont ni le même contenu ni
     * forcément le même format, et le moteur recevrait un entrelacement des
     * deux. Le résultat ne planterait pas : il produirait du texte, et ce
     * texte n'aurait aucun rapport avec ce qui a été dit. C'est la pire forme
     * de défaut, celle qui a l'air de marcher.
     *
     * La fréquence et le nombre de canaux voyagent donc AVEC le son, et non à
     * côté. Vosk applique telle quelle la fréquence qu'on lui annonce :
     * déclarer 16 kHz à du 48 kHz lui fait analyser une bande trois fois trop
     * large, et là encore le texte sort — faux.
     */
    private class SourceRing(val source: TranscriptionSource, capacityBytes: Int) {
        val pcm = PcmRingBuffer(capacityBytes)
        @Volatile var sampleRate = 0
        @Volatile var channels = 0
    }

    private val callRing = SourceRing(TranscriptionSource.CALL, RING_CAPACITY_BYTES)
    private val roomRing = SourceRing(TranscriptionSource.ROOM, RING_CAPACITY_BYTES)

    private fun ringFor(source: TranscriptionSource) =
        if (source == TranscriptionSource.CALL) callRing else roomRing

    @Volatile private var transcriptionWorker: Thread? = null
    @Volatile private var transcriptionWorkerRunning = false
    private var lastSlowFeedLoggedAtMs = 0L
    private var lastOverflowLoggedAtMs = 0L

    // Synchronisées toutes les deux : le démarrage est demandé depuis le
    // thread principal (startLocalMedia) ET depuis le thread de signalisation
    // de WebRTC (onTrack). Un simple test de nullité laisserait les deux
    // passer ensemble et créer deux threads, dont l'un ne serait plus jamais
    // arrêté — exactement le genre de fuite qui ne se voit qu'au bout de
    // plusieurs appels.
    @Synchronized
    private fun startTranscriptionWorker() {
        if (transcriptionWorker != null) return
        transcriptionWorkerRunning = true
        transcriptionWorker = Thread {
            // Préalloué une fois, réutilisé jusqu'à la fin de l'appel.
            val readBuffer = ByteArray(READ_CHUNK_BYTES)
            while (transcriptionWorkerRunning) {
                // On ne draine que la source réellement transcrite, et on vide
                // l'autre. Sans ce vidage, revenir sur une source ferait
                // rejouer d'un coup plusieurs secondes de son périmé, que Jean
                // verrait s'écrire comme s'il venait d'être dit.
                val active = transcription.activeSource()
                if (active != TranscriptionSource.CALL) callRing.pcm.clear()
                if (active != TranscriptionSource.ROOM) roomRing.pcm.clear()
                val ring = when (active) {
                    null -> null
                    else -> ringFor(active)
                }
                if (ring == null) {
                    // Rien à transcrire : on n'occupe pas le processeur à
                    // tourner à vide. Le réveil viendra du prochain passage.
                    try {
                        Thread.sleep(IDLE_SLEEP_MS)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                val read = ring.pcm.read(readBuffer, readBuffer.size)
                if (read <= 0) {
                    if (Thread.currentThread().isInterrupted) break
                    continue
                }
                reportOverflowIfAny(ring)
                try {
                    // LA COPIE EST ICI, ET ELLE EST NÉCESSAIRE. La réserve de
                    // pré-roll et la file de BufferedSpeechRecognizer gardent
                    // la RÉFÉRENCE du tableau qu'on leur donne ; leur passer le
                    // tampon réutilisé ferait écraser sous elles du son qu'elles
                    // croient détenir — sans plantage, juste du texte faux.
                    //
                    // Elle est sur ce fil-ci, et par blocs de cent
                    // millisecondes au lieu de dix : dix fois moins
                    // d'allocations, et plus aucune là où elle coûtait.
                    val chunk = readBuffer.copyOf(read)
                    val startedAt = SystemClock.elapsedRealtime()
                    transcription.feed(ring.source, chunk, ring.sampleRate, ring.channels)
                    val tookMs = SystemClock.elapsedRealtime() - startedAt
                    if (tookMs >= SLOW_FEED_MS && startedAt - lastSlowFeedLoggedAtMs >= 1_000L) {
                        lastSlowFeedLoggedAtMs = startedAt
                        CallTrace.record(
                            "APPEL transcription",
                            "moteur lent : $tookMs ms pour ${read / bytesPerMs(ring)} ms de son",
                        )
                    }
                } catch (e: Throwable) {
                    // Une exception du moteur ne doit pas emporter ce thread :
                    // sans lui, plus aucune transcription ne repart de l'appel,
                    // et rien ne le dirait.
                    CallTrace.record(
                        "APPEL transcription",
                        "exception du moteur : ${e.javaClass.simpleName} ${e.message ?: ""}",
                    )
                }
            }
        }.apply {
            // Priorité de fond, explicitement : ce thread partage le processeur
            // avec le rendu audio et vidéo de l'appel, et il n'a aucune raison
            // de leur disputer un cycle. C'est la même décision que le tampon
            // qui écrase plutôt que d'attendre, appliquée à l'ordonnancement.
            priority = Thread.MIN_PRIORITY
            // Pas « SeniorVisio-Transcription » : c'est déjà le nom du fil de
            // BufferedSpeechRecognizer, et deux threads dont les noms ne
            // diffèrent que par un tiret sont indiscernables dans un journal
            // système — exactement là où on les cherche.
            name = "SeniorVisio-AudioQueue"
            isDaemon = true
            start()
        }
    }

    /** Octets par milliseconde de son pour cette source, au moins un. */
    private fun bytesPerMs(ring: SourceRing): Int =
        maxOf(1, ring.sampleRate * ring.channels * 2 / 1000)

    private fun reportOverflowIfAny(ring: SourceRing) {
        val lost = ring.pcm.overwrittenBytes
        if (lost <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOverflowLoggedAtMs < 5_000L) return
        lastOverflowLoggedAtMs = now
        CallTrace.record(
            "APPEL transcription",
            "${lost / bytesPerMs(ring)} ms de son écrasées depuis le début, moteur en retard",
        )
    }

    @Synchronized
    private fun stopTranscriptionWorker() {
        transcriptionWorkerRunning = false
        transcriptionWorker?.interrupt()
        transcriptionWorker = null
        callRing.pcm.clear()
        roomRing.pcm.clear()
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

    /** Écoute la demande de basculer la transcription sur le microphone de la tablette (voir setMicToRoom). */
    fun listenForMicToRoom(onEnabled: (Boolean) -> Unit) {
        val id = callId ?: return
        micToRoomListener = signaling.listenForMicToRoom(id, onEnabled)
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
            CallTrace.guard("APPEL consigne volume", "$volume") {
                pendingVolume = volume
                rampVolumeTo(volume)
            }
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
            CallTrace.guard("APPEL consigne micro", "coupé=$muted mêmePièce=$sameRoomMode") {
                pendingMicMuted = muted
                // Mémorisé même quand la piste n'existe pas encore :
                // startLocalMedia l'appliquera à sa création (voir
                // pendingMicMuted).
                //
                // Et on ne rallume jamais le micro sans vérifier le mode même
                // pièce : décocher la coupure micro pendant un appel depuis le
                // fauteuil d'à côté ramènerait l'écho que ce mode existe
                // précisément pour éviter. Symétrique de listenForSameRoomMode
                // juste en dessous.
                localAudioTrack?.setEnabled(!muted && !sameRoomMode)
            }
        }
    }

    /**
     * Écoute le signalement, par le proche, qu'il se trouve dans la même
     * pièce que Jean (voir web-caller/app.js).
     *
     * Le son de la tablette est alors coupé entièrement : à deux mètres,
     * entendre la voix du proche à la fois de vive voix et par le
     * haut-parleur, avec une seconde de décalage, est bien plus gênant que de
     * ne pas l'entendre du tout — et le micro de la tablette renverrait en
     * prime cette voix au téléphone du proche, en écho.
     *
     * La coupure est appliquée ici, et elle prime sur le curseur de volume
     * (voir rampVolumeTo) : sans ça, un curseur remonté par inadvertance
     * ramènerait l'écho sans que rien n'indique pourquoi. Le texte, lui,
     * continue d'être affiché : c'est même souvent la seule raison d'appeler
     * depuis le fauteuil d'à côté.
     */
    fun listenForSameRoomMode() {
        val id = callId ?: return
        sameRoomListener = signaling.listenForSameRoomMode(id) { enabled ->
            CallTrace.guard(
                "APPEL consigne même pièce",
                "activé=$enabled microCoupé=$pendingMicMuted consigneVolume=$pendingVolume",
            ) {
                sameRoomMode = enabled
                localAudioTrack?.setEnabled(!enabled && !pendingMicMuted)
                rampVolumeTo(pendingVolume)
            }
        }
    }

    /** Écoute l'activation à distance de l'aperçu de sa propre caméra affiché à Jean (masqué par défaut). */
    fun listenForSelfPreviewMode(onEnabled: (Boolean) -> Unit) {
        val id = callId ?: return
        selfPreviewListener = signaling.listenForSelfPreviewMode(id, onEnabled)
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
     * Décrit l'écran de Jean au PWA (proportions, ordre des zones, palette,
     * contenu de la zone d'information) pour qu'il en dessine une réplique
     * fidèle — voir CallSignalingClient.publishScreenLayout.
     */
    fun publishScreenLayout(
        aspectRatio: Double,
        zoneOrder: String,
        isDark: Boolean,
        infoMoment: String?,
        infoWeather: String?,
        infoDate: String?,
        captionCharsPerLine: Int,
        captionLines: Int,
    ) {
        val id = callId ?: return
        signaling.publishScreenLayout(
            id, aspectRatio, zoneOrder, isDark, infoMoment, infoWeather, infoDate,
            captionCharsPerLine, captionLines,
        )
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


    /**
     * Surveille le flux réellement reçu du proche et raccroche tout seul quand
     * il s'arrête. Jean n'a rien à faire : c'est la règle de tout cet écran, et
     * jusqu'ici c'était le seul endroit où elle tombait en défaut.
     *
     * Le symptôme, constaté en usage réel : le proche ferme l'onglet de son
     * navigateur sans raccrocher, l'image se fige chez Jean, et la
     * conversation reste ouverte indéfiniment — micro et caméra engagés — tant
     * que personne ne touche la tablette.
     *
     * Il existait bien une détection, mais fondée sur le seul état ICE (voir
     * onIceConnectionChange). Un onglet fermé ne prévient personne : aucun
     * paquet d'adieu n'est envoyé, et la pile WebRTC peut mettre très
     * longtemps à déclarer le lien mort — parfois jamais, selon le réseau.
     * L'état ICE dit ce que la pile CROIT de la connexion ; le compteur
     * d'octets reçus dit ce qui arrive VRAIMENT. C'est cette seconde mesure
     * qui correspond à ce que Jean voit : une image figée, c'est très
     * exactement un flux qui ne progresse plus.
     *
     * Et comme elle ne mesure que le résultat, elle couvre du même coup tous
     * les autres cas — navigateur qui plante, téléphone éteint ou en mode
     * avion, Wi-Fi coupé, application tuée en arrière-plan — sans avoir à les
     * distinguer ni même à les prévoir.
     *
     * Deux délais plutôt qu'un : avant le premier octet, la connexion est
     * peut-être encore en train de s'établir (échange ICE, traversée de
     * réseau), ce qui prend parfois une vingtaine de secondes sur une liaison
     * médiocre — raccrocher là serait raccrocher sur un appel en train de
     * réussir. Une fois du média reçu, en revanche, une interruption est
     * franchement anormale et le délai se resserre.
     */
    private fun startMediaWatchdog() {
        if (mediaWatchdogRunnable != null) return
        lastInboundBytes = -1L
        hasEverReceivedMedia = false
        lastInboundProgressAtMs = SystemClock.elapsedRealtime()
        val runnable = object : Runnable {
            override fun run() {
                pollInboundBytes()
                watchSystemVolumes()
                mediaWatchdogHandler.postDelayed(this, MEDIA_WATCHDOG_TICK_MS)
            }
        }
        mediaWatchdogRunnable = runnable
        mediaWatchdogHandler.postDelayed(runnable, MEDIA_WATCHDOG_TICK_MS)
    }

    private fun stopMediaWatchdog() {
        mediaWatchdogRunnable?.let { mediaWatchdogHandler.removeCallbacks(it) }
        mediaWatchdogRunnable = null
    }

    /**
     * Relève les niveaux système pendant l'appel, et ne note que ce qui bouge.
     *
     * Tout ce qu'on a corrigé jusqu'ici POSE un niveau. Rien ne vérifiait
     * qu'il tenait. Or « le son revient aléatoirement » décrit précisément un
     * niveau qui ne tient pas : quelque chose le change sous nous, entre deux
     * de nos écritures, sans que rien dans notre code en soit l'auteur.
     *
     * Deux lignes dans la trace — l'une posée par nous, l'autre relevée dix
     * secondes plus tard et différente — et la question est close. C'est le
     * genre de constat qu'aucune correction ne remplace.
     */
    private fun watchSystemVolumes() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val now = "appel=${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}" +
            "/${audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}" +
            " média=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}" +
            "/${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" +
            " mode=${audioManager.mode}"
        if (now == lastSeenVolumes) return
        val avant = lastSeenVolumes
        lastSeenVolumes = now
        CallTrace.record(
            "APPEL niveaux",
            if (avant == null) "à la connexion : $now" else "CHANGÉ SOUS NOUS : $avant → $now",
        )
    }

    private fun pollInboundBytes() {
        val pc = peerConnection ?: return
        pc.getStats(RTCStatsCollectorCallback { report ->
            var total = 0L
            report.statsMap.values.forEach { stats ->
                if (stats.type != "inbound-rtp") return@forEach
                val received = stats.members["bytesReceived"]
                // Le SDK remonte ce compteur en BigInteger (il peut dépasser
                // la taille d'un entier signé sur un très long appel) ; on
                // accepte tout de même n'importe quel Number, la classe exacte
                // n'étant pas garantie d'une version de WebRTC à l'autre.
                total += (received as? Number)?.toLong() ?: 0L
            }
            // getStats répond sur le thread de signalisation WebRTC : on
            // revient sur le thread principal avant de toucher à l'état ou de
            // raccrocher.
            mediaWatchdogHandler.post { onInboundBytes(total) }
        })
    }

    private fun onInboundBytes(total: Long) {
        if (mediaWatchdogRunnable == null) return
        val now = SystemClock.elapsedRealtime()
        if (total > lastInboundBytes) {
            lastInboundBytes = total
            lastInboundProgressAtMs = now
            // Le premier octet seulement : un relevé par tour remplirait la
            // trace de lignes toutes identiques et noierait ce qu'on y cherche.
            // Ce qui compte, c'est QUAND le flux commence et QUAND il s'arrête.
            if (total > 0 && !hasEverReceivedMedia) {
                CallTrace.record("APPEL flux entrant", "premiers octets reçus du proche ($total)")
            }
            if (total > 0) hasEverReceivedMedia = true
            return
        }
        CallTrace.record(
            "APPEL flux entrant",
            "à l'arrêt sur $total octets depuis ${(now - lastInboundProgressAtMs) / 1000} s",
        )
        val allowed = if (hasEverReceivedMedia) MEDIA_STALL_TIMEOUT_MS else MEDIA_START_TIMEOUT_MS
        if (now - lastInboundProgressAtMs < allowed) return

        Log.i(TAG, "Plus rien reçu du proche depuis ${allowed / 1000}s : raccroché automatique")
        CallTrace.record(
            "APPEL raccroché auto",
            "chien de garde : $total octets reçus, aucun progrès depuis ${allowed / 1000} s " +
                "(médiaDéjàReçu=$hasEverReceivedMedia)",
        )
        stopMediaWatchdog()
        hangUp()
        connectionLostCb?.invoke()
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

    /**
     * Pose immédiatement le niveau qui découle de l'état courant, sans rampe.
     *
     * Utilisée quand la piste distante vient d'arriver : il n'y a rien à
     * adoucir puisque rien ne sortait encore du haut-parleur, et surtout il ne
     * faut pas attendre une seconde de rampe avant que Jean entende quoi que
     * ce soit.
     *
     * À n'appeler que depuis le thread principal.
     */
    private fun applyVolumeNow() {
        val target = if (sameRoomMode) 0.0 else pendingVolume
        val track = remoteAudioTrack
        volumeRampRunnable?.let { volumeHandler.removeCallbacks(it) }
        volumeRampRunnable = null
        currentVolume = target
        // Le flux système d'abord : il n'a pas besoin de la piste pour exister,
        // et c'est le chemin qui répond à coup sûr (voir applySystemVolume).
        applySystemVolume(target)
        if (track == null) {
            CallTrace.record("APPEL volume", "niveau $target retenu, aucune piste distante encore")
            return
        }
        track.setVolume(target)
        CallTrace.record("APPEL volume", "niveau $target posé sur la piste distante")
    }

    private fun rampVolumeTo(requested: Double) {
        // Le mode "même pièce" prime sur le curseur : voir listenForSameRoomMode.
        val target = if (sameRoomMode) 0.0 else requested
        val track = remoteAudioTrack
        volumeRampRunnable?.let { volumeHandler.removeCallbacks(it) }
        // Posé d'un coup, et non par paliers comme le gain : le volume système
        // n'a qu'une poignée de crans, les échelonner ne produirait pas une
        // transition douce mais une succession de sauts audibles. La douceur
        // vient du gain de la piste, qui est continu.
        applySystemVolume(target)
        if (track == null) {
            // La consigne n'est pas perdue pour autant : elle reste dans
            // pendingVolume, et applyVolumeNow la posera quand la piste
            // arrivera (voir onTrack). C'est ce rattrapage qui manquait — une
            // consigne arrivée avant la piste ne s'appliquait jamais, et le
            // curseur semblait mort pour le reste de l'appel.
            currentVolume = target
            CallTrace.record(
                "APPEL rampe",
                "demandé=$requested cible=$target — aucune piste distante, en attente",
            )
            return
        }
        CallTrace.record(
            "APPEL rampe",
            "demandé=$requested cible=$target départ=$currentVolume mêmePièce=$sameRoomMode",
        )
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
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            CallTrace.record("APPEL audio système", "ABANDON : AudioManager indisponible")
            return
        }
        savedAudioMode = audioManager.mode
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
        savedCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestAudioFocus(audioManager)
        routeToBuiltinSpeaker(audioManager)
        pinSystemVolume()
        reportAudioRouting(audioManager)
        CallTrace.record(
            "APPEL audio système",
            "mode précédent=$savedAudioMode hautParleurPrécédent=$savedSpeakerphoneOn " +
                "volumeAppelSauvegardé=$savedCallVolume → fixé à " +
                "${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/" +
                "${audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}",
        )
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
        applySystemVolume(if (sameRoomMode) 0.0 else pendingVolume)
    }

    /**
     * Porte la consigne du proche sur le VOLUME SYSTÈME de l'appel, en plus du
     * gain appliqué à la piste.
     *
     * ═══ Pourquoi deux chemins pour un seul curseur ═══
     *
     * AudioTrack.setVolume est un gain logiciel appliqué par la pile WebRTC à
     * la piste distante. Quand il ne prend pas — et rien dans son interface ne
     * permet de le savoir : la méthode ne rend rien, ne lève rien, ne
     * journalise rien — le curseur du proche est muet, sans qu'aucune ligne de
     * code ne s'en aperçoive. C'est exactement ce qui a été constaté, et deux
     * corrections successives sur le seul chemin du gain n'y ont rien changé.
     *
     * Le flux STREAM_VOICE_CALL d'Android, lui, ne peut pas échouer en
     * silence : c'est le même réglage que les boutons physiques de la tablette.
     * Le curseur agit donc désormais sur les deux, et il reste audible même si
     * l'un des deux ne répond pas.
     *
     * ═══ La correspondance, et pourquoi elle s'arrête à cent pour cent ═══
     *
     * Zéro coupe le flux pour de bon. Cent pour cent redonne exactement le
     * niveau fixé jusqu'ici ([SYSTEM_VOLUME_RATIO]), de sorte qu'un appel
     * ordinaire sonne comme avant. Au-delà, le flux ne bouge plus et seul le
     * gain numérique continue de monter : pousser le haut-parleur au maximum
     * le fait saturer, et l'annulation d'écho ne reconnaît alors plus ce
     * qu'elle doit soustraire — c'est la raison même pour laquelle ce plafond
     * de soixante-dix pour cent existe (voir le commentaire ci-dessus).
     */
    private fun applySystemVolume(requested: Double) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val share = requested.coerceIn(0.0, 1.0)
        val voice = setStreamShare(audioManager, AudioManager.STREAM_VOICE_CALL, share)
        // LES DEUX FLUX, ET NON LE SEUL FLUX D'APPEL.
        //
        // STREAM_VOICE_CALL est le flux de la téléphonie. Cette tablette n'a
        // pas de radio cellulaire : rien ne garantit qu'il porte quoi que ce
        // soit, ni même qu'il ait un maximum non nul. Si la couche d'émulation
        // de téléphonie d'Android ne joue pas son rôle, le canal média prend le
        // relais et le son sort quand même.
        //
        // Régler les deux n'a aucun inconvénient : celui qui ne porte rien
        // ignore la consigne, celui qui porte l'applique. Les deux niveaux sont
        // journalisés, ce qui dira lequel des deux travaille réellement —
        // question qu'aucune correction, prise seule, n'aurait tranchée.
        val music = setStreamShare(audioManager, AudioManager.STREAM_MUSIC, share)
        CallTrace.record("APPEL volume système", "consigne=$requested → appel $voice · média $music")
    }

    /** Pose la part demandée du plafond sur un flux. Rend « niveau/max » pour le journal. */
    private fun setStreamShare(audioManager: AudioManager, stream: Int, share: Double): String {
        val max = audioManager.getStreamMaxVolume(stream)
        if (max <= 0) return "indisponible"
        val level = Math.round(max * SYSTEM_VOLUME_RATIO * share).toInt().coerceIn(0, max)
        return try {
            audioManager.setStreamVolume(stream, level, 0)
            "$level/$max"
        } catch (e: SecurityException) {
            // Un flux peut être verrouillé par une politique de l'appareil.
            // Le dire, plutôt que de laisser croire que la consigne est passée.
            "refusé (${e.javaClass.simpleName})"
        }
    }

    /**
     * Demande le focus audio pour la durée de l'appel.
     *
     * ═══ CETTE DEMANDE N'EXISTAIT NULLE PART ═══
     *
     * Aucune ligne de cette application ne demandait le focus audio. C'est une
     * omission, pas un choix : `MODE_IN_COMMUNICATION` suppose que
     * l'application le détient, et sans lui le système reste libre de couper,
     * d'atténuer ou de rerouter la sortie quand un autre composant le prend.
     *
     * Et sur CETTE tablette, un composant le prend et le rend sans arrêt : le
     * service de reconnaissance vocale d'Android, que l'écoute de la pièce
     * relance à chaque silence, toute la journée (voir AlertVolume, dont le
     * commentaire décrit la même relance permanente). L'écoute est censée être
     * suspendue pendant un appel, mais rien ne garantit qu'aucun autre
     * composant du système ne demande le focus entre-temps.
     *
     * UN SON QUI VA ET VIENT SANS LOGIQUE APPARENTE EST LA SIGNATURE EXACTE
     * de cette omission : la perte de focus coupe la sortie sous le niveau
     * réglé, ce qui explique aussi qu'aucun curseur n'y ait jamais rien changé.
     *
     * ═══ Ce qui est demandé, et pourquoi ═══
     *
     * `USAGE_VOICE_COMMUNICATION` décrit ce que c'est vraiment — une
     * conversation, pas de la musique — et c'est cette description que le
     * système utilise pour router le son et arbitrer entre applications.
     *
     * `GAIN_TRANSIENT_EXCLUSIVE` plutôt qu'un simple gain : pendant un appel,
     * une autre application ne doit pas s'atténuer poliment pour continuer
     * par-dessus, elle doit se taire. C'est le comportement d'un appel
     * téléphonique, et c'est celui qu'on attend ici.
     *
     * L'échec n'interrompt RIEN. Un appel sans focus vaut mieux qu'un appel
     * refusé ; on le note et on continue.
     */
    private fun requestAudioFocus(audioManager: AudioManager) {
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = android.media.AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            // Refusé plutôt que différé : un focus qui arriverait après la fin
            // de l'appel ne servirait à personne, et laisserait l'application
            // le détenir sans rien jouer.
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change -> onAudioFocusChanged(change) }, volumeHandler)
            .build()
        audioFocusRequest = request
        val granted = audioManager.requestAudioFocus(request)
        CallTrace.record(
            "APPEL focus",
            when (granted) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "accordé"
                AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "REFUSÉ — un autre composant le détient"
                else -> "réponse inattendue ($granted)"
            },
        )
    }

    /**
     * Note chaque changement de focus, et ne fait rien d'autre.
     *
     * Volontairement passif pour l'instant. Reprendre le focus dès qu'on le
     * perd déclencherait une guerre avec le composant qui vient de le prendre,
     * et surtout rendrait impossible d'établir QUI le prend et QUAND — ce qui
     * est précisément la question. La trace d'abord, la réaction ensuite, une
     * fois qu'on saura contre quoi on réagit.
     */
    private fun onAudioFocusChanged(change: Int) {
        CallTrace.record(
            "APPEL focus",
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> "regagné"
                AudioManager.AUDIOFOCUS_LOSS -> "PERDU définitivement"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "PERDU temporairement"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "perdu, atténuation demandée"
                else -> "changement inattendu ($change)"
            },
        )
    }

    /**
     * Force la sortie sur le haut-parleur intégré.
     *
     * `setSpeakerphoneOn` est déprécié depuis Android 12 et son effet n'y est
     * plus garanti : il agit sur une notion de « haut-parleur » héritée de la
     * téléphonie, que le routage moderne a remplacée par un choix explicite de
     * périphérique. Sur une tablette sous Android 36, s'y fier revient à
     * espérer qu'une API dépréciée fasse encore ce qu'elle promettait.
     *
     * [AudioManager.setCommunicationDevice] nomme le périphérique voulu, et
     * rend un booléen — il dit donc s'il a réussi, ce que l'ancien ne faisait
     * pas. L'ancien reste en repli sous Android 12, et si la sélection échoue.
     */
    private fun routeToBuiltinSpeaker(audioManager: AudioManager) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null && audioManager.setCommunicationDevice(speaker)) {
                CallTrace.record("APPEL routage", "sortie forcée sur le haut-parleur intégré")
                return
            }
            CallTrace.record(
                "APPEL routage",
                "setCommunicationDevice indisponible ou refusé, repli sur setSpeakerphoneOn",
            )
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true
    }

    /**
     * Relève l'état réel du routage audio au début de l'appel.
     *
     * C'est la ligne qui manquait à toutes les corrections précédentes : elles
     * changeaient le réglage sans jamais constater ce que l'appareil en
     * faisait. Si le flux d'appel annonce ici un maximum nul, ou si aucune
     * sortie n'est sélectionnée, la cause du silence est dans cette ligne et
     * nulle part ailleurs.
     */
    private fun reportAudioRouting(audioManager: AudioManager) {
        val sorties = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString(", ") { "${it.type}" }
        } catch (e: Exception) {
            "illisibles"
        }
        val choisie = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type?.toString() ?: "aucune"
        } else {
            "n/a"
        }
        CallTrace.record(
            "APPEL routage",
            "mode=${audioManager.mode} sortieChoisie=$choisie " +
                "maxAppel=${audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)} " +
                "maxMédia=${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} " +
                "sorties=[$sorties]",
        )
    }

    private fun restoreAudio() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // Rendue AVANT le mode : une sortie de communication laissée
        // sélectionnée après l'appel garde le routage détourné pour tout le
        // reste du système, alarmes comprises, jusqu'au redémarrage.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (e: Exception) {
            }
        }
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            CallTrace.record("APPEL focus", "rendu")
        }
        audioFocusRequest = null
        lastSeenVolumes = null
        savedAudioMode?.let { audioManager.mode = it }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = savedSpeakerphoneOn
        savedCallVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0) }
        savedMusicVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        savedAudioMode = null
        savedCallVolume = null
        savedMusicVolume = null
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
                    CallTrace.record("APPEL onTrack", "piste vidéo du proche reçue")
                    remoteRenderer?.let { track.addSink(it) }
                } else if (track is AudioTrack) {
                    remoteAudioTrack = track
                    CallTrace.record(
                        "APPEL onTrack",
                        "piste audio du proche reçue — mêmePièce=$sameRoomMode consigne=$pendingVolume",
                    )
                    // Le niveau n'est PAS posé ici. onTrack s'exécute sur le
                    // thread de signalisation de WebRTC ; rampVolumeTo, lui,
                    // travaille sur le thread principal. Poser le volume des
                    // deux côtés, c'est laisser une rampe en cours écraser
                    // cette valeur — ou l'inverse — selon lequel finit le
                    // dernier. On repasse donc par le seul thread qui a le
                    // droit de toucher au volume, et par le seul chemin qui
                    // sait ce qui prime sur quoi (le mode même pièce).
                    volumeHandler.post { applyVolumeNow() }
                    // Idempotent, et répété ici à dessein : startLocalMedia le
                    // démarre déjà, mais rien dans l'interface de WebRTC ne
                    // garantit l'ordre des deux, et un bloc déposé sans
                    // consommateur resterait en file.
                    startTranscriptionWorker()
                    attachTranscriptionSink(track, TranscriptionSource.CALL)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                CallTrace.record("APPEL ICE", newState?.name ?: "null")
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
        // ═══ PLUS AUCUNE SORTIE SILENCIEUSE SUR CE CHEMIN ═══
        //
        // Les trois `return` ci-dessous ne disaient rien. Or ils décident de
        // TOUT ce qui suit : la configuration audio, le routage vers le
        // haut-parleur, le focus, la piste micro, la piste caméra. Quand l'un
        // d'eux se déclenche, l'appel se connecte quand même — la piste
        // distante arrive, l'image du proche s'affiche — mais la tablette n'a
        // rien configuré de son côté, et rien nulle part ne le dit.
        //
        // C'est exactement la forme de panne qu'on poursuit depuis des jours :
        // pas d'erreur, pas de plantage, juste une moitié de chaîne qui n'a
        // jamais démarré. Une fonction qui abandonne sans le dire est une
        // fonction dont on ne peut pas diagnostiquer l'absence.
        val factory = peerConnectionFactory
        if (factory == null) {
            CallTrace.record("APPEL média local", "ABANDON : aucune fabrique WebRTC")
            return
        }
        val caméra = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        val micro = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (caméra != PackageManager.PERMISSION_GRANTED || micro != PackageManager.PERMISSION_GRANTED) {
            // Android révoque de lui-même les permissions d'une application
            // qu'il juge inutilisée, et met en veille prolongée celles qu'on ne
            // touche pas. Sur une tablette que personne n'ouvre jamais, ce cas
            // n'est pas théorique — et il se manifesterait exactement ainsi :
            // un appel qui se connecte, une image qui arrive, et rien qui
            // parte ni ne sorte du côté de Jean.
            CallTrace.record(
                "APPEL média local",
                "ABANDON : permission refusée — caméra=${caméra == PackageManager.PERMISSION_GRANTED} " +
                    "micro=${micro == PackageManager.PERMISSION_GRANTED}",
            )
            return
        }

        configureAudioForCall()

        val capturer = createFrontCameraCapturer()
        if (capturer == null) {
            // Après configureAudioForCall, donc l'audio système est bien posé —
            // mais on sort AVANT la piste micro, et le proche n'entendra jamais
            // Jean. La caméra occupée par un autre composant suffit à produire
            // ce cas, et il varie d'un appel à l'autre.
            CallTrace.record("APPEL média local", "ABANDON : aucune caméra disponible — AUCUNE PISTE MICRO CRÉÉE")
            return
        }
        videoCapturer = capturer
        val videoSource = factory.createVideoSource(false)
        val helper = SurfaceTextureHelper.create("SeniorVisioCapture", eglBase.eglBaseContext)
        surfaceTextureHelper = helper
        capturer.initialize(helper, context, videoSource.capturerObserver)
        capturer.startCapture(640, 480, 30)

        val videoTrack = factory.createVideoTrack("SVIO_VIDEO", videoSource)
        localVideoTrack = videoTrack
        localRenderer?.let { videoTrack.addSink(it) }

        // Démarré avant la première piste : c'est lui qui consomme la file, et
        // un bloc déposé sans consommateur ne serait transcrit que bien plus
        // tard, quand la file déborderait.
        startTranscriptionWorker()

        val audioTrack = factory.createAudioTrack("SVIO_AUDIO", factory.createAudioSource(MediaConstraints()))
        localAudioTrack = audioTrack
        // Coupé avant même d'être ajouté à la connexion si la consigne est déjà
        // arrivée : rien ne doit sortir du micro de la tablette, pas même le
        // temps d'un instantané Firestore.
        //
        // Les DEUX consignes comptent ici, et la seconde manquait : en mode même
        // pièce, le haut-parleur est bien coupé à sa création (voir plus haut,
        // initialVolume), mais le micro, lui, partait actif et renvoyait au
        // téléphone du proche la voix qu'il venait de prononcer à deux mètres.
        // L'écho ne s'arrêtait qu'à l'arrivée de l'instantané Firestore de
        // listenForSameRoomMode, quelques centaines de millisecondes plus tard.
        audioTrack.setEnabled(!pendingMicMuted && !sameRoomMode)
        CallTrace.record(
            "APPEL micro tablette",
            "piste créée — actif=${!pendingMicMuted && !sameRoomMode} " +
                "(coupéParLeProche=$pendingMicMuted mêmePièce=$sameRoomMode)",
        )
        // Reliée en permanence, mais n'alimente la transcription que si
        // l'appelant a demandé d'écouter la pièce (voir setMicToRoom).
        attachTranscriptionSink(audioTrack, TranscriptionSource.ROOM)

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
        CallTrace.record("APPEL nettoyage", "fin de l'appel, remise à zéro de l'état")
        cancelScheduledAutoHangup()
        stopTranscriptionWorker()
        stopMediaWatchdog()
        restoreAudio()
        transcription.stop()
        transcriptionOnText = null
        captionsActive = false
        micToRoom = false
        micMuteListener?.remove()
        micMuteListener = null
        sameRoomListener?.remove()
        sameRoomListener = null
        sameRoomMode = false
        slideshowListener?.remove()
        slideshowListener = null
        callerCandidatesListener?.remove()
        callerCandidatesListener = null
        volumeListener?.remove()
        volumeListener = null
        captionModeListener?.remove()
        captionModeListener = null
        micToRoomListener?.remove()
        micToRoomListener = null
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
        // Remise à zéro indispensable, et elle manquait : la consigne de coupure
        // du micro est portée par l'engine, pas par l'appel. Le processus de la
        // tablette ne redémarre jamais de lui-même (CallListenerService est un
        // service de premier plan permanent), donc une coupure demandée une
        // seule fois survivait à l'appel, à tous les appels suivants, et jusqu'au
        // prochain redémarrage complet : la piste audio était recréée coupée sans
        // que rien ne l'indique nulle part. Vu de la chambre, le micro de la
        // tablette « ne marchait plus », sans cause visible.
        pendingMicMuted = false

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

        /** Cadence de relevé du compteur d'octets reçus (voir startMediaWatchdog). */
        private const val MEDIA_WATCHDOG_TICK_MS = 3_000L

        /**
         * Silence toléré une fois que du média est arrivé au moins une fois.
         * Assez long pour laisser passer un changement de réseau côté proche
         * (le flux reprend souvent après quelques secondes), assez court pour
         * que Jean ne reste pas devant une image figée.
         */
        private const val MEDIA_STALL_TIMEOUT_MS = 12_000L

        /**
         * Attente avant le tout premier octet : la connexion peut encore être
         * en train de s'établir. Raccrocher trop tôt ferait échouer les appels
         * lents plutôt que de fermer les appels morts.
         */
        private const val MEDIA_START_TIMEOUT_MS = 30_000L

        /**
         * Fraction du volume système maximal utilisée pendant un appel (voir
         * pinSystemVolume). Compromis entre "Jean entend bien" et "le micro de
         * la tablette ne recapte pas le haut-parleur" — c'est le second point
         * qui a causé un écho franc côté proche tant que ce réglage était à 1.0.
         */
        private const val SYSTEM_VOLUME_RATIO = 0.7f

        /**
         * Taille d'un tampon circulaire, par source.
         *
         * Dimensionné au pire cas observable — 48 kHz, deux canaux, seize bits
         * font 192 ko par seconde — pour tenir deux secondes. Assez pour
         * absorber une lenteur passagère du moteur. Pas davantage : au-delà, ce
         * qui serait transcrit appartiendrait à une phrase que Jean a fini
         * d'entendre, et le texte arriverait décalé plutôt que manquant — ce
         * qui est pire, parce que ça ne se voit pas.
         */
        private const val RING_CAPACITY_BYTES = 384_000

        /**
         * Taille d'une bouchée lue par le fil de transcription : environ cent
         * millisecondes au format le plus courant. Dix fois moins d'allocations
         * qu'un bloc de dix millisecondes, sans ajouter de retard perceptible.
         */
        private const val READ_CHUNK_BYTES = 19_200

        /** Pause du fil de transcription quand aucune source n'est active. */
        private const val IDLE_SLEEP_MS = 100L

        /**
         * Au-delà, le moteur est franchement plus lent que le temps réel et la
         * trace le dit. Cinquante millisecondes pour dix millisecondes de son,
         * c'est un facteur cinq : soutenu, il vide la file en quelques
         * secondes et il aurait, avant cette file, tenu le haut-parleur muet
         * tout ce temps.
         */
        private const val SLOW_FEED_MS = 50L
    }
}
