package com.seniorvisio.core

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Quatrième moteur : le service de transcription en direct de Gladia.
 *
 * Alternative à AssemblyAI, pas son remplaçant : les deux se règlent
 * séparément pour la pièce et pour les appels (voir AdminConfig), ce qui est
 * la seule façon de les comparer honnêtement — même voix, même pièce, à
 * quelques minutes d'intervalle.
 *
 * Deux temps, contrairement à AssemblyAI qui se connecte directement :
 *
 *  1. Une requête HTTP POST porte la configuration (langue, format audio) et
 *     la clé d'API, et rend une URL de WebSocket déjà authentifiée.
 *  2. Le son part sur cette URL.
 *
 * Ce premier temps est un appel réseau bloquant : il se fait donc sur un fil
 * séparé, jamais sur celui qui demande le démarrage — lequel est le fil
 * principal, et bloquer une seconde là gèlerait l'écran de Jean.
 *
 * Son envoyé en **binaire brut**, alors que Gladia accepte aussi du base64
 * dans du JSON. Le base64 coûte un tiers d'octets en plus, sur une tablette
 * qui transcrit des heures par jour ; et c'est déjà la forme employée pour
 * AssemblyAI, donc un mécanisme de moins à tenir. Le choix mérite d'être
 * noté parce que le piège existe en sens inverse : AssemblyAI, lui, exigeait
 * du base64 en v2 et le REFUSE en v3, ce qui a coûté une version entière
 * silencieusement muette.
 */
class GladiaStreamingTranscriber(private val apiKey: String) : SpeechRecognizer {

    override val engine = TranscriptionEngineChoice.GLADIA

    private var webSocket: WebSocket? = null
    private val pendingAudio = java.io.ByteArrayOutputStream()

    /** Vrai dès stop() : empêche une connexion encore en vol de s'ouvrir dans le vide. */
    @Volatile private var stopped = false

    private val client = OkHttpClient.Builder()
        // Connexion longue durée : pas de délai de lecture, sans quoi OkHttp
        // couperait au premier silence prolongé entre deux phrases.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun start(onText: (text: String, isFinal: Boolean) -> Unit, onError: (String) -> Unit) {
        stopped = false
        Thread {
            val url = try {
                requestSessionUrl()
            } catch (e: Exception) {
                Log.w(TAG, "Ouverture de session Gladia impossible", e)
                onError("Gladia : ${e.message}")
                return@Thread
            } ?: run {
                onError("Gladia : réponse d'ouverture de session inexploitable")
                return@Thread
            }
            // Arrêt demandé pendant que la requête était en vol : ne pas
            // ouvrir une connexion que plus personne n'écoute — elle est
            // facturée à la durée.
            if (stopped) return@Thread
            openSocket(url, onText, onError)
        }.apply { name = "SeniorVisio-Gladia"; start() }
    }

    /**
     * Négocie la session et rend l'URL de WebSocket. Null si la réponse n'a
     * pas la forme attendue — cas traité comme une erreur plutôt que par une
     * exception, pour que le message affiché dise ce qui manque.
     */
    private fun requestSessionUrl(): String? {
        val config = JSONObject()
            .put("encoding", "wav/pcm")
            .put("sample_rate", Pcm16.TARGET_SAMPLE_RATE_HZ)
            .put("bit_depth", 16)
            .put("channels", 1)
            // Langue figée et non détectée : personne ne parle autre chose
            // dans cette chambre, et laisser un moteur hésiter entre plusieurs
            // langues sur une voix âgée et peu forte ne peut que nuire.
            .put(
                "language_config",
                JSONObject()
                    .put("languages", org.json.JSONArray().put(LANGUAGE))
                    .put("code_switching", false)
            )

        val request = Request.Builder()
            .url(SESSION_URL)
            .addHeader("x-gladia-key", apiKey)
            .post(config.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} ${body.take(200)}")
            }
            return JSONObject(body).optString("url").takeIf { it.isNotBlank() }
        }
    }

    private fun openSocket(
        url: String,
        onText: (text: String, isFinal: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connexion Gladia temps réel établie")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") != "transcript") return
                    val data = json.optJSONObject("data") ?: return
                    val utterance = data.optJSONObject("utterance")?.optString("text").orEmpty()
                    // is_final distingue les versions successives d'un même
                    // énoncé de celle qui le clôt — exactement ce dont la zone
                    // d'affichage a besoin (voir RollingCaptionZone.submit).
                    if (utterance.isNotBlank()) onText(utterance, data.optBoolean("is_final", false))
                } catch (e: Exception) {
                    Log.w(TAG, "Message Gladia illisible : $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { " (HTTP ${it.code})" } ?: ""
                Log.w(TAG, "Connexion Gladia perdue$detail", t)
                onError("Gladia : connexion perdue$detail — ${t.message}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })
    }

    override fun accept(pcm16: ByteArray, sampleRate: Int, channels: Int) {
        val socket = webSocket ?: return
        val converted = Pcm16.toBytes(Pcm16.toMono16k(pcm16, sampleRate, channels))
        val chunk = synchronized(pendingAudio) {
            pendingAudio.write(converted)
            if (pendingAudio.size() < MIN_CHUNK_BYTES) return
            val bytes = pendingAudio.toByteArray()
            pendingAudio.reset()
            bytes
        }
        socket.send(Buffer().write(chunk).readByteString())
    }

    override fun stop() {
        stopped = true
        // Annonce la fin plutôt que de couper net : sans elle, le service
        // garde la session ouverte le temps de son propre délai d'expiration,
        // et la facture jusque-là.
        webSocket?.send(JSONObject().put("type", "stop_recording").toString())
        webSocket?.close(1000, null)
        webSocket = null
        synchronized(pendingAudio) { pendingAudio.reset() }
    }

    private companion object {
        const val TAG = "GladiaStreaming"
        const val SESSION_URL = "https://api.gladia.io/v2/live"
        const val LANGUAGE = "fr"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * 100 ms à 16 kHz mono 16 bits. Même valeur que pour AssemblyAI, et
         * pour la même raison : les blocs livrés par WebRTC font une dizaine
         * de millisecondes, bien trop courts pour être envoyés tels quels.
         */
        const val MIN_CHUNK_BYTES = 3_200
    }
}
