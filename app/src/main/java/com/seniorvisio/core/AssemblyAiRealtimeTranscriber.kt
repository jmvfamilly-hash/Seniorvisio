package com.seniorvisio.core

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
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
 * Protocole "Universal-Streaming" (v3), qui a remplacé l'ancienne API temps
 * réel v2 (endpoint différent, messages différents — voir le guide de
 * migration d'AssemblyAI) : la v2 utilisée dans une première version de ce
 * fichier ne recevait jamais aucune transcription (rien ne s'affichait ni
 * pour les appels, ni pour la pièce), confirmé en cherchant la documentation
 * à jour au lieu de deviner davantage.
 */
class AssemblyAiRealtimeTranscriber(private val apiKey: String) {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        // Connexion longue durée (toute la durée de l'appel) : pas de délai
        // de lecture, sans quoi OkHttp couperait la connexion au premier
        // silence prolongé entre deux phrases.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * onText(text, isFinal) : isFinal distingue un texte encore provisoire
     * (revu au mot près pendant que la phrase continue) d'un texte
     * définitivement figé — utile pour accumuler un historique (voir
     * RoomTranscriptionActivity) sans y empiler chaque révision
     * intermédiaire de la même phrase. Les sous-titres d'appel (voir
     * WebRtcCallEngine), eux, ignorent cette distinction : ils affichent
     * simplement le dernier texte reçu, quel qu'il soit.
     */
    fun start(onText: (String, Boolean) -> Unit, onError: (String) -> Unit = {}) {
        val request = Request.Builder()
            .url("$REALTIME_URL?sample_rate=$TARGET_SAMPLE_RATE_HZ&encoding=pcm_s16le")
            .addHeader("Authorization", apiKey)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connexion AssemblyAI temps réel établie")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        // "transcript" ne contient que les mots déjà
                        // confirmés par le modèle — end_of_turn distingue un
                        // tour de parole encore en cours (peut être révisé)
                        // d'un tour définitivement clos.
                        "Turn" -> {
                            val transcript = json.optString("transcript")
                            if (transcript.isNotBlank()) onText(transcript, json.optBoolean("end_of_turn", false))
                        }
                        "Begin" -> Log.i(TAG, "Session AssemblyAI démarrée : ${json.optString("id")}")
                        "Termination" -> Log.i(TAG, "Session AssemblyAI terminée")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Message AssemblyAI illisible : $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { " (HTTP ${it.code})" } ?: ""
                Log.w(TAG, "Connexion AssemblyAI temps réel perdue$detail", t)
                onError("connexion perdue$detail : ${t.message}")
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
        // Trames binaires brutes, pas du JSON/base64 (protocole v3) : l'API
        // v2 précédente encodait l'audio en base64 dans un message texte,
        // ce que v3 n'accepte plus.
        socket.send(Buffer().write(converted).readByteString())
    }

    fun stop() {
        webSocket?.send(JSONObject().put("type", "Terminate").toString())
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
        private const val REALTIME_URL = "wss://streaming.assemblyai.com/v3/ws"
        private const val TARGET_SAMPLE_RATE_HZ = 16_000
    }
}
