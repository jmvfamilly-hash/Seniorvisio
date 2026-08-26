package com.seniorvisio.core

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Transcrit un flux audio PCM 16 bits mono en direct via l'API streaming
 * temps réel d'AssemblyAI (WebSocket, wss://streaming.assemblyai.com/v3/ws) :
 * le texte arrive au fil de l'eau, avec un délai de l'ordre de la seconde —
 * contrairement à l'API par fichier (AssemblyAiClient), qui traite par
 * tranches de plusieurs secondes et fournit en échange l'identification des
 * locuteurs. Choix fait ici en faveur de la fluidité (constaté en test réel :
 * le délai cumulé par tranches était trop gênant pour Jean) plutôt que de
 * savoir qui parle.
 */
class AssemblyAiRealtimeTranscriber(
    private val apiKey: String,
    private val onTranscript: (text: String, isFinal: Boolean) -> Unit,
    private val onError: ((String) -> Unit)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    @Volatile private var connected = false

    fun start(sampleRate: Int) {
        val request = Request.Builder()
            .url("wss://streaming.assemblyai.com/v3/ws?sample_rate=$sampleRate")
            .addHeader("Authorization", apiKey)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (e: Exception) {
                    return
                }
                if (json.optString("type") != "Turn") return
                val transcript = json.optString("transcript")
                if (transcript.isBlank()) return
                val isFinal = json.optBoolean("end_of_turn", false)
                handler.post { onTranscript(transcript, isFinal) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                handler.post { onError?.invoke(t.message ?: "connexion perdue") }
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
            webSocket?.send(JSONObject().apply { put("type", "Terminate") }.toString())
        }
        connected = false
        webSocket?.close(1000, "fin")
        webSocket = null
    }
}
