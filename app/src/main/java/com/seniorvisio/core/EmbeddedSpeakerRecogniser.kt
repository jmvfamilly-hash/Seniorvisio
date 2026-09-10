package com.seniorvisio.core

/**
 * Le moteur écrit dans l'application : timbre et hauteur de voix (voir
 * VoiceFeatures, VoiceSignature, RoomSpeakerGate).
 *
 * Ce fichier ne fait qu'habiller ce qui existait déjà, pour que les deux
 * moteurs se présentent de la même façon. La logique n'a pas bougé : elle avait
 * été écrite en prévision de ce jour, et c'est ce qui permet d'ajouter un
 * second moteur sans toucher ni au service, ni à l'affichage, ni au PWA.
 *
 * Une différence assumée avec l'ancien comportement : l'avancement de
 * l'apprentissage se mesure désormais en **voix entendue** et non en temps
 * écoulé. Vingt secondes pendant lesquelles Jean se tait n'apprennent rien, et
 * une minuterie aurait pourtant conclu que c'était fait — puis l'enregistrement
 * aurait échoué à la toute fin, après avoir fait parler quelqu'un pour rien.
 */
class EmbeddedSpeakerRecogniser(
    private val reference: VoiceSignature?,
    private val thresholdPercent: Int,
) : SpeakerRecogniser {

    override val engine = SpeakerEngineChoice.EMBEDDED

    private val gate = reference?.let { RoomSpeakerGate(it, thresholdPercent / 100.0) }

    private val enrolment = VoiceSignature.Builder()
    private val enrolmentFrame = ShortArray(VoiceFeatures.FRAME_SAMPLES)
    private var enrolmentFilled = 0

    override fun enroll(buffer: ShortArray, length: Int): Float {
        var offset = 0
        while (offset < length) {
            val take = minOf(VoiceFeatures.FRAME_SAMPLES - enrolmentFilled, length - offset)
            System.arraycopy(buffer, offset, enrolmentFrame, enrolmentFilled, take)
            enrolmentFilled += take
            offset += take
            if (enrolmentFilled < VoiceFeatures.FRAME_SAMPLES) break
            enrolmentFilled = 0
            VoiceFeatures.analyse(enrolmentFrame)?.let { enrolment.add(it) }
        }
        return (enrolment.frames.toFloat() / VoiceSignature.MIN_RELIABLE_FRAMES).coerceAtMost(1f)
    }

    override fun finishEnrollment(): String? =
        enrolment.build(VoiceSignature.MIN_RELIABLE_FRAMES)?.serialise()

    override fun accept(buffer: ShortArray, length: Int, nowMs: Long) {
        gate?.accept(buffer, length, nowMs)
    }

    override fun currentSpeakerIsJean(): Boolean? = gate?.currentSpeakerIsJean()

    override fun lastSimilarityPercent(): Int? = gate?.lastSimilarityPercent()

    override fun consumeJeanSharePercent(): Int? = gate?.consumeJeanSharePercent()

    /** Rien à libérer : tout est en mémoire ordinaire, sans modèle natif. */
    override fun close() = Unit

    /** Combien de tranches de voix ont été entendues, pour l'expliquer en cas de refus. */
    fun enrolledFrames(): Int = enrolment.frames
}
