package com.seniorvisio.core

import android.content.Context
import android.util.Log
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Décide s'il y a de la **voix** dans la pièce, et non simplement du bruit.
 *
 * Ce que ça change : jusqu'ici, une session payante s'ouvrait dès que le
 * niveau sonore dépassait un plancher. Un aspirateur, une porte, des pas, une
 * chaise qu'on tire franchissent ce plancher sans difficulté — et chacun
 * ouvrait une connexion facturée à la durée, sur une tablette qui écoute toute
 * la journée. C'était la principale fuite de coût, et un seuil de niveau, si
 * bien réglé soit-il, ne peut pas la fermer : il mesure une intensité, pas une
 * nature.
 *
 * Ce que ça ne change pas, et qu'il faut savoir : **une télévision produit de
 * la voix.** Ce portier laissera passer un poste laissé allumé, comme le
 * ferait n'importe quel détecteur de parole. C'est précisément pour ce cas-là
 * qu'existe le plafond mensuel (voir AdminConfig.monthlyQuotaHours), qui borne
 * la dépense quoi qu'il arrive.
 *
 * Le réveil de l'écran ne passe PAS par ici : il reste sur le niveau sonore et
 * son curseur de sensibilité (voir RoomPresenceService.handleLevel). Les deux
 * questions sont différentes — « faut-il allumer l'écran » et « faut-il payer
 * pour transcrire » — et les avoir confondues a déjà coûté une régression
 * aujourd'hui même.
 *
 * Le modèle travaille par trames de 512 échantillons à 16 kHz, soit 32 ms.
 * Notre capture ne livre pas des blocs de cette taille : ils sont donc
 * ré-assemblés ici plutôt que d'imposer une taille de lecture au micro, qui
 * dépend de l'appareil.
 */
class RoomVoiceGate(context: Context) {

    private val vad: VadSilero? = try {
        VadSilero(
            context.applicationContext,
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_512,
            // NORMAL et non le mode le plus agressif : le prix d'une erreur
            // n'est pas symétrique. Laisser passer un bruit coûte quelques
            // secondes de connexion ; refuser une voix faible coûte à Jean une
            // phrase qu'il ne lira jamais, et il parle bas.
            mode = Mode.NORMAL,
            // Rémanence courte côté modèle : la vraie rémanence, celle qui
            // évite de couper entre deux phrases, est tenue plus haut (voir
            // RoomPresenceService.TRANSCRIPTION_HOLD_MS).
            silenceDurationMs = 300,
            speechDurationMs = 50,
        )
    } catch (e: Throwable) {
        // Modèle absent, architecture non prévue, mémoire insuffisante : le
        // portier se met en retrait plutôt que d'empêcher la tablette
        // d'écouter. Sans ce filet, un incident au chargement d'un modèle
        // rendrait la transcription de la pièce définitivement muette.
        Log.w(TAG, "Détection de voix indisponible, portier désactivé", e)
        TranscriptionDiagnostics.record("détection de voix indisponible : ${e.message}")
        null
    }

    val available: Boolean get() = vad != null

    private val frame = ShortArray(FRAME_SAMPLES)
    private var filled = 0

    @Volatile private var lastVoiceAtMs = 0L
    @Volatile private var framesSeen = 0
    @Volatile private var framesWithVoice = 0

    /**
     * Absorbe un bloc de son et met à jour le verdict. Sans effet si le modèle
     * n'a pas pu être chargé — [isVoiceActive] rend alors toujours vrai, ce qui
     * revient au comportement d'avant ce portier.
     */
    fun accept(buffer: ShortArray, length: Int) {
        val vad = vad ?: return
        var offset = 0
        while (offset < length) {
            val take = minOf(FRAME_SAMPLES - filled, length - offset)
            System.arraycopy(buffer, offset, frame, filled, take)
            filled += take
            offset += take
            if (filled < FRAME_SAMPLES) return
            filled = 0
            framesSeen++
            val speech = try {
                vad.isSpeech(toBytes(frame))
            } catch (e: Exception) {
                Log.w(TAG, "Trame refusée par la détection de voix", e)
                // En cas de doute, on laisse passer : mieux vaut une session
                // ouverte pour rien qu'une parole de Jean jamais transcrite.
                true
            }
            if (speech) {
                framesWithVoice++
                lastVoiceAtMs = System.currentTimeMillis()
            }
        }
    }

    /** Vrai si de la voix a été entendue assez récemment (voir HANGOVER_MS). */
    fun isVoiceActive(): Boolean {
        if (vad == null) return true
        return lastVoiceAtMs > 0L && System.currentTimeMillis() - lastVoiceAtMs <= HANGOVER_MS
    }

    /**
     * Part du temps jugée vocale depuis la dernière lecture, puis remise à
     * zéro. Publiée au diagnostic : un portier invisible qui se tromperait
     * ferait passer la transcription pour cassée, sans que rien ne désigne le
     * coupable.
     */
    fun consumeVoiceShare(): Int? {
        if (vad == null) return null
        val seen = framesSeen
        val voiced = framesWithVoice
        framesSeen = 0
        framesWithVoice = 0
        if (seen <= 0) return 0
        return voiced * 100 / seen
    }

    fun close() {
        try {
            vad?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Fermeture de la détection de voix", e)
        }
    }

    private fun toBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { out.putShort(it) }
        return bytes
    }

    private companion object {
        const val TAG = "RoomVoiceGate"
        const val FRAME_SAMPLES = 512

        /**
         * Durée pendant laquelle on continue de considérer qu'on est dans une
         * prise de parole après la dernière trame vocale. Couvre les silences
         * entre les mots et les respirations, sans lesquels le portier
         * clignoterait plusieurs fois par phrase.
         */
        const val HANGOVER_MS = 2_000L
    }
}
