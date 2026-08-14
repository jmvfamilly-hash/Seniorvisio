package com.seniorvisio.core

import android.util.Log
import org.json.JSONObject
import org.vosk.Recognizer
import org.webrtc.AudioTrackSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reçoit l'audio décodé du proche directement depuis WebRTC (voir
 * WebRtcCallEngine.onTrack, `remoteAudioTrack.addSink(this)`) et le
 * transcrit sur la tablette avec Vosk — sans dépendre de la reconnaissance
 * vocale du navigateur de l'appelant (Web Speech API, indisponible sur
 * Safari/iOS, dépendante du réseau).
 *
 * [onData] est appelé sur le thread audio interne de WebRTC, pas le thread
 * principal : le travail de reconnaissance (CPU, pas d'I/O bloquant une fois
 * le modèle chargé) peut s'y faire directement, mais [listener] doit lui-même
 * repasser sur le thread UI s'il touche des vues (voir IncomingCallActivity).
 *
 * Un nouveau [Recognizer] par appel : l'état de reconnaissance ne doit pas
 * être réutilisé d'une conversation à l'autre.
 */
class VoskCaptionRecognizer : AudioTrackSink {

    private var recognizer: Recognizer? = null
    private var recognizerSampleRate = -1
    private var listener: ((String) -> Unit)? = null

    fun setOnTextListener(onText: (String) -> Unit) {
        listener = onText
    }

    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long
    ) {
        if (bitsPerSample != 16 || numberOfFrames <= 0) return
        val model = VoskModelProvider.getModel() ?: return

        var current = recognizer
        if (current == null || recognizerSampleRate != sampleRate) {
            current?.close()
            val created = try {
                Recognizer(model, sampleRate.toFloat())
            } catch (e: Exception) {
                Log.w(TAG, "Recognizer Vosk indisponible : ${e.message}")
                return
            }
            recognizer = created
            recognizerSampleRate = sampleRate
            current = created
        }
        // Réassigné en var ci-dessus : un val local force le compilateur à
        // le traiter comme définitivement non-null pour la suite.
        val activeRecognizer = current ?: return

        val mono = toMonoShortArray(audioData, numberOfChannels, numberOfFrames)
        val isFinal = activeRecognizer.acceptWaveForm(mono, mono.size)
        val json = if (isFinal) activeRecognizer.result else activeRecognizer.partialResult
        val text = extractText(json, isFinal)
        if (!text.isNullOrBlank()) listener?.invoke(text)
    }

    /** WebRTC livre du PCM 16 bits entrelacé ; Vosk attend du mono. */
    private fun toMonoShortArray(buffer: ByteBuffer, channels: Int, frames: Int): ShortArray {
        val samples = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val mono = ShortArray(frames)
        if (channels <= 1) {
            samples.get(mono, 0, minOf(frames, samples.remaining()))
        } else {
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) {
                    val idx = i * channels + c
                    if (idx < samples.limit()) sum += samples.get(idx)
                }
                mono[i] = (sum / channels).toShort()
            }
        }
        return mono
    }

    private fun extractText(json: String, isFinal: Boolean): String? = try {
        JSONObject(json).optString(if (isFinal) "text" else "partial")
    } catch (e: Exception) {
        null
    }

    fun release() {
        recognizer?.close()
        recognizer = null
    }

    companion object {
        private const val TAG = "VoskCaptionRecognizer"
    }
}
