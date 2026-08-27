package com.seniorvisio.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
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
    // Étape la plus avancée atteinte dans la connexion (voir eventListener
    // ci-dessous) : remontée avec le message d'erreur pour savoir si ça
    // casse avant même la connexion TCP, pendant la poignée de main TLS, ou
    // après l'envoi de la requête — sans quoi "Broken pipe" seul ne dit pas
    // à quel stade ça a cassé.
    @Volatile private var lastPhase = "callStart"

    // Force HTTP/1.1 : un WebSocket standard n'est pas compatible avec HTTP/2,
    // qu'OkHttp négocierait sinon par défaut (ALPN) avec ce serveur — le
    // serveur ferme alors la connexion dès la première écriture ("Write
    // error... Broken pipe" constaté en test réel), la requête de mise à
    // niveau WebSocket n'ayant pas de sens sur une connexion HTTP/2.
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) { lastPhase = "callStart" }
            override fun dnsStart(call: Call, domainName: String) { lastPhase = "dnsStart" }
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { lastPhase = "dnsEnd" }
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { lastPhase = "connectStart" }
            override fun secureConnectStart(call: Call) { lastPhase = "secureConnectStart" }
            override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) { lastPhase = "secureConnectEnd" }
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { lastPhase = "connectEnd (protocole $protocol)" }
            override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: java.io.IOException) { lastPhase = "connectFailed" }
            override fun requestHeadersStart(call: Call) { lastPhase = "requestHeadersStart" }
            override fun requestHeadersEnd(call: Call, request: Request) { lastPhase = "requestHeadersEnd" }
            override fun responseHeadersStart(call: Call) { lastPhase = "responseHeadersStart" }
            override fun responseHeadersEnd(call: Call, response: Response) { lastPhase = "responseHeadersEnd (${response.code})" }
        })
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
                // Le corps de la réponse HTTP (si le serveur en a renvoyé un,
                // ex. clé refusée, paramètre invalide) est bien plus parlant
                // que le message d'exception générique d'OkHttp seul.
                val bodySnippet = try { response?.body?.string()?.take(200) } catch (e: Exception) { null }
                val detail = listOfNotNull("étape : $lastPhase", response?.let { "HTTP ${it.code}" }, t.message, bodySnippet)
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
