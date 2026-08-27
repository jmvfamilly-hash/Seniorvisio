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

    // Force HTTP/1.1 : un WebSocket standard n'est pas compatible avec HTTP/2,
    // qu'OkHttp négocierait sinon par défaut (ALPN) avec ce serveur.
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    @Volatile private var connected = false
    // A-t-on déjà réussi à ouvrir la connexion au moins une fois ? Distingue
    // "n'a jamais pu se connecter" (souci d'authentification/requête) de
    // "s'est coupée en cours d'envoi audio" (souci réseau/timeout) — le
    // diagnostic par étape OkHttp (EventListener) ne remonte pas ce niveau
    // de détail pour un WebSocket (limite connue, jamais implémentée par
    // OkHttp : voir issue square/okhttp#5833), d'où ce suivi manuel.
    @Volatile private var everOpened = false

    fun start(sampleRate: Int) {
        val request = Request.Builder()
            .url("wss://streaming.assemblyai.com/v3/ws?sample_rate=$sampleRate")
            .addHeader("Authorization", apiKey)
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
                if (json.optString("type") != "Turn") return
                val transcript = json.optString("transcript")
                if (transcript.isBlank()) return
                val isFinal = json.optBoolean("end_of_turn", false)
                handler.post { onTranscript(transcript, isFinal) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                // Le corps de la réponse HTTP (si le serveur en a renvoyé un,
                // ex. clé refusée, paramètre invalide) est bien plus parlant
                // que le message d'exception générique d'OkHttp seul — de
                // même que le type exact de l'exception et sa cause racine.
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
                Log.w(TAG, "Échec WebSocket AssemblyAI : $detail", t)
                handler.post { onError?.invoke(detail) }
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

    companion object {
        private const val TAG = "AssemblyAiRealtime"
    }
}
