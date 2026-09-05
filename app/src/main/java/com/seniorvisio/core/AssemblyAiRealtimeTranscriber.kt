package com.seniorvisio.core

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Transcrit en direct le son déjà reçu par l'appel (voir WebRtcCallEngine,
 * qui lui fournit l'audio distant via AudioTrackSink) plutôt que de dépendre
 * de la reconnaissance vocale du navigateur de l'appelant — devenue le point
 * de fragilité du projet : absente sur Safari/iOS, présente mais privée de
 * son sur Android/Chrome (micro accaparé par l'appel WebRTC lui-même). Ici,
 * aucun navigateur n'est impliqué : la tablette transcrit ce qu'elle reçoit
 * déjà, quel que soit l'appareil ou le navigateur de la personne qui appelle.
 *
 * Service payant à l'usage (contrairement à la reconnaissance vocale du
 * navigateur, gratuite mais peu fiable) — n'est démarré que lorsque les
 * sous-titres sont effectivement activés (voir WebRtcCallEngine.
 * setCaptionsActive), pas en permanence pendant l'appel.
 *
 * Protocole temps réel v2 d'AssemblyAI (stable de longue date) : à vérifier
 * en priorité lors du premier test réel si la connexion échoue, la
 * documentation ayant pu évoluer sans que ce code ait pu être testé contre
 * le service réel avant déploiement.
 */
class AssemblyAiRealtimeTranscriber(private val apiKey: String) {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        // Connexion longue durée (toute la durée de l'appel) : pas de délai
        // de lecture, sans quoi OkHttp couperait la connexion au premier
        // silence prolongé entre deux phrases.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun start(onText: (String) -> Unit, onError: (String) -> Unit = {}) {
        val request = Request.Builder()
            .url("$REALTIME_URL?sample_rate=$TARGET_SAMPLE_RATE_HZ")
            .addHeader("Authorization", apiKey)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connexion AssemblyAI temps réel établie")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("message_type")) {
                        "PartialTranscript", "FinalTranscript" -> {
                            val transcript = json.optString("text")
                            if (transcript.isNotBlank()) onText(transcript)
                        }
                        "SessionTerminated" -> Log.i(TAG, "Session AssemblyAI terminée")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Message AssemblyAI illisible : $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Connexion AssemblyAI temps réel perdue", t)
                onError("connexion perdue : ${t.message}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })
    }

    /**
     * À appeler pour chaque bloc audio reçu de WebRTC (voir
     * WebRtcCallEngine.attachTranscriptionSink). Convertit vers le format
     * attendu par AssemblyAI (PCM 16 bits, mono, 16 kHz) quel que soit le
     * format réel livré par WebRTC — sa fréquence d'échantillonnage interne
     * peut varier selon l'appareil/codec, mais n'est jamais garantie être
     * déjà 16 kHz mono.
     *
     * Ré-échantillonnage volontairement simple (plus proche voisin, sans
     * filtre anti-repliement) : suffisant pour de la parole, à améliorer
     * seulement si la qualité de transcription s'avère insuffisante en usage
     * réel.
     */
    fun sendAudio(pcm16: ByteArray, sourceSampleRate: Int, sourceChannels: Int) {
        val socket = webSocket ?: return
        val converted = resampleToMono16k(pcm16, sourceSampleRate, sourceChannels.coerceAtLeast(1))
        val base64Audio = Base64.encodeToString(converted, Base64.NO_WRAP)
        socket.send(JSONObject().put("audio_data", base64Audio).toString())
    }

    fun stop() {
        webSocket?.send(JSONObject().put("terminate_session", true).toString())
        webSocket?.close(1000, null)
        webSocket = null
    }

    private fun resampleToMono16k(pcm: ByteArray, sourceSampleRate: Int, sourceChannels: Int): ByteArray {
        val sampleCount = pcm.size / 2 / sourceChannels
        if (sampleCount <= 0) return ByteArray(0)
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            var sum = 0
            for (c in 0 until sourceChannels) sum += input.short.toInt()
            mono[i] = (sum / sourceChannels).toShort()
        }

        if (sourceSampleRate == TARGET_SAMPLE_RATE_HZ) return shortsToBytes(mono)

        val ratio = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE_HZ
        val outCount = (mono.size / ratio).toInt().coerceAtLeast(1)
        val resampled = ShortArray(outCount)
        for (i in 0 until outCount) {
            val srcIndex = (i * ratio).toInt().coerceIn(0, mono.size - 1)
            resampled[i] = mono[srcIndex]
        }
        return shortsToBytes(resampled)
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        shorts.forEach { buffer.putShort(it) }
        return bytes
    }

    companion object {
        private const val TAG = "AssemblyAiRealtime"
        private const val REALTIME_URL = "wss://api.assemblyai.com/v2/realtime/ws"
        private const val TARGET_SAMPLE_RATE_HZ = 16_000
    }
}
