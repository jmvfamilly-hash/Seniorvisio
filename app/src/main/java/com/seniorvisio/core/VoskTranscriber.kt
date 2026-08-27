package com.seniorvisio.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * Transcrit un flux audio PCM 16 bits mono en direct avec Vosk (gratuit,
 * open source, 100% hors-ligne une fois le modèle chargé — voir
 * [VoskModelProvider]) — remplace Deepgram/AssemblyAI pour les sous-titres
 * en usage courant (pièce et appel), qui facturaient chaque minute
 * transcrite. Le labo de comparaison (TranscriptionLabActivity) garde
 * AssemblyAI, API par fichier distincte non concernée par ce changement.
 *
 * Même forme d'API que l'ancien DeepgramRealtimeTranscriber
 * (start/feed/stop, callback (text, isFinal)) pour ne rien changer côté
 * appelants (MainActivity, WebRtcCallEngine).
 */
class VoskTranscriber(
    private val onTranscript: (text: String, isFinal: Boolean) -> Unit,
    private val onError: ((String) -> Unit)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: Recognizer? = null

    fun start(sampleRate: Int) {
        val model = VoskModelProvider.getModel()
        if (model == null) {
            handler.post {
                onError?.invoke("Modèle de reconnaissance vocale hors-ligne pas encore prêt (téléchargement en cours, ou échoué faute de réseau au premier lancement)")
            }
            return
        }
        recognizer = try {
            Recognizer(model, sampleRate.toFloat())
        } catch (e: Exception) {
            Log.w(TAG, "Recognizer Vosk indisponible : ${e.message}")
            handler.post { onError?.invoke("Recognizer Vosk indisponible : ${e.message}") }
            null
        }
    }

    /** Alimente la reconnaissance en direct avec un fragment audio PCM 16 bits mono. */
    fun feed(pcm: ByteArray) {
        val active = recognizer ?: return
        val isFinal = active.acceptWaveForm(pcm, pcm.size)
        val json = if (isFinal) active.result else active.partialResult
        val text = extractText(json, isFinal)
        if (!text.isNullOrBlank()) handler.post { onTranscript(text, isFinal) }
    }

    fun stop() {
        recognizer?.close()
        recognizer = null
    }

    private fun extractText(json: String, isFinal: Boolean): String? = try {
        JSONObject(json).optString(if (isFinal) "text" else "partial")
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "VoskTranscriber"
    }
}
