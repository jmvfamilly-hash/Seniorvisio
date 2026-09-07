package com.seniorvisio.core

import android.util.Log
import org.json.JSONObject
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reconnaissance vocale embarquée sur la tablette (voir SpeechRecognizer),
 * utilisée pour ce qui se dit dans la pièce. Gratuite et hors-ligne une fois
 * le modèle en place (voir VoskModelProvider) — c'est ce qui permet d'écouter
 * la pièce toute la journée sans que ça coûte quoi que ce soit, là où un
 * service facturé à la durée reviendrait à une centaine d'euros par mois.
 *
 * Un moteur neuf par session : l'état de reconnaissance porte le contexte des
 * phrases précédentes et n'a rien à faire d'une conversation à l'autre.
 */
class VoskSpeechRecognizer : SpeechRecognizer {

    private var recognizer: Recognizer? = null
    private var recognizerSampleRate = -1
    private var onText: ((String, Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var reportedMissingModel = false

    /** Un énoncé a commencé et n'a pas encore été clos (voir accept). */
    private var hasOpenSegment = false

    override fun start(onText: (text: String, isFinal: Boolean) -> Unit, onError: (String) -> Unit) {
        this.onText = onText
        this.onError = onError
        reportedMissingModel = false
        hasOpenSegment = false
    }

    override fun accept(pcm16: ByteArray, sampleRate: Int, channels: Int) {
        val emit = onText ?: return
        val model = VoskModelProvider.getModel()
        if (model == null) {
            // Une seule fois par session : ce cas dure le temps d'un
            // téléchargement, pas la peine d'en faire un flot de messages.
            if (!reportedMissingModel) {
                reportedMissingModel = true
                onError?.invoke("modèle Vosk pas encore prêt (${VoskModelProvider.describeState()})")
            }
            return
        }

        // Le moteur est lié à une fréquence d'échantillonnage donnée : si la
        // source change (la pièce capture à 16 kHz, un appel à 48 kHz), il
        // faut en refaire un plutôt que de lui donner du son qu'il
        // interpréterait à la mauvaise vitesse.
        val active = recognizer?.takeIf { recognizerSampleRate == sampleRate } ?: run {
            recognizer?.close()
            val created = try {
                Recognizer(model, sampleRate.toFloat())
            } catch (e: Exception) {
                Log.w(TAG, "Moteur Vosk indisponible", e)
                onError?.invoke("Vosk : ${e.message}")
                recognizer = null
                return
            }
            recognizer = created
            recognizerSampleRate = sampleRate
            created
        }

        val mono = toMonoSamples(pcm16, channels.coerceAtLeast(1))
        if (mono.isEmpty()) return
        val isFinal = active.acceptWaveForm(mono, mono.size)
        val json = if (isFinal) active.result else active.partialResult
        val text = extractText(json, isFinal).orEmpty().trim()

        // Contrairement à AssemblyAI, dont le texte est cumulatif sur toute une
        // prise de parole, Vosk clôt un énoncé à chaque silence et repart d'une
        // chaîne vide. La fin d'énoncé doit donc être signalée telle quelle, y
        // compris quand elle ne ramène aucun mot : c'est elle qui dit à
        // l'affichage de figer ce qui précède avant que le prochain énoncé
        // n'arrive (voir RollingCaptionZone.submit). Sans ce signal, une fin
        // d'énoncé muette laissait le segment précédent ouvert, et le suivant
        // l'écrasait en croyant le corriger.
        if (isFinal) {
            // Mais seulement s'il y avait quelque chose à clore : en silence
            // prolongé, Vosk renvoie des fins d'énoncé vides en série, qui
            // repousseraient indéfiniment l'effacement de la zone.
            if (text.isNotEmpty() || hasOpenSegment) emit(text, true)
            hasOpenSegment = false
        } else if (text.isNotEmpty()) {
            hasOpenSegment = true
            emit(text, false)
        }
    }

    override fun stop() {
        recognizer?.close()
        recognizer = null
        recognizerSampleRate = -1
        hasOpenSegment = false
        onText = null
        onError = null
    }

    /** Vosk attend du mono ; les sources peuvent livrer plusieurs canaux entrelacés. */
    private fun toMonoSamples(pcm16: ByteArray, channels: Int): ShortArray {
        val samples = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = samples.remaining() / channels
        if (frames <= 0) return ShortArray(0)
        val mono = ShortArray(frames)
        if (channels == 1) {
            samples.get(mono, 0, frames)
            return mono
        }
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += samples.get(i * channels + c)
            mono[i] = (sum / channels).toShort()
        }
        return mono
    }

    private fun extractText(json: String, isFinal: Boolean): String? = try {
        JSONObject(json).optString(if (isFinal) "text" else "partial")
    } catch (e: Exception) {
        Log.w(TAG, "Réponse Vosk illisible : $json", e)
        null
    }

    companion object {
        private const val TAG = "VoskSpeechRecognizer"
    }
}
