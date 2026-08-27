package com.seniorvisio.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Transcrit un flux audio PCM 16 bits mono en direct via l'API streaming
 * temps réel de Deepgram (WebSocket, wss://api.deepgram.com/v1/listen) —
 * remplace AssemblyAI pour cet usage (voir historique : connexions coupées
 * sans cause identifiable malgré plusieurs correctifs en test réel sur la
 * tablette). AssemblyAI reste utilisé par le labo de comparaison
 * (TranscriptionLabActivity), qui n'a jamais eu ce problème — API par
 * fichier différente de celle-ci, donc non concernée.
 */
class DeepgramRealtimeTranscriber(
    private val apiKey: String,
    private val onTranscript: (text: String, isFinal: Boolean) -> Unit,
    private val onError: ((String) -> Unit)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())

    // Force HTTP/1.1 : un WebSocket standard n'est pas compatible avec HTTP/2,
    // qu'OkHttp négocierait sinon par défaut (ALPN) avec ce serveur.
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var everOpened = false

    fun start(sampleRate: Int) {
        val request = Request.Builder()
            .url(
                "wss://api.deepgram.com/v1/listen" +
                    "?encoding=linear16&sample_rate=$sampleRate&channels=1&language=fr"
            )
            .addHeader("Authorization", "Token $apiKey")
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                everOpened = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (e: Exception) {
                    return
                }
                val transcript = json.optJSONObject("channel")
                    ?.optJSONArray("alternatives")
                    ?.optJSONObject(0)
                    ?.optString("transcript")
                if (transcript.isNullOrBlank()) return
                // speech_final (pause naturelle détectée) plutôt que is_final
                // (simple stabilité d'un fragment) : c'est la frontière qui
                // correspond à "phrase terminée, on l'ajoute à l'historique"
                // côté appelant (voir MainActivity.onRoomTranscript).
                val isFinal = json.optBoolean("speech_final", false)
                handler.post { onTranscript(transcript, isFinal) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                val bodySnippet = try { response?.body?.string()?.take(200) } catch (e: Exception) { null }
                val detail = listOfNotNull(
                    if (everOpened) "connexion déjà ouverte" else "jamais connecté",
                    response?.let { "HTTP ${it.code}" },
                    t.javaClass.simpleName,
                    t.message,
                    t.cause?.let { "cause : ${it.javaClass.simpleName} ${it.message}" },
                    bodySnippet,
                )
                    .joinToString(" — ")
                    .ifBlank { "connexion perdue" }
                Log.w(TAG, "Échec WebSocket Deepgram : $detail", t)
                handler.post { onError?.invoke(detail) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket Deepgram en fermeture : code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
            }
        })
    }

    /** Alimente la connexion en direct avec un fragment audio PCM 16 bits mono. */
    fun feed(pcm: ByteArray) {
        if (!connected) return
        webSocket?.send(ByteString.of(*pcm))
    }

    fun stop() {
        if (connected) {
            webSocket?.send(JSONObject().apply { put("type", "CloseStream") }.toString())
        }
        connected = false
        webSocket?.close(1000, "fin")
        webSocket = null
    }

    companion object {
        private const val TAG = "DeepgramRealtime"
    }
}
