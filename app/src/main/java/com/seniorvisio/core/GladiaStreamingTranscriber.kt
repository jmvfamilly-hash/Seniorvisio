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

    /**
     * Volatile, et c'est indispensable ici alors que ça ne l'était pas pour
     * AssemblyAI. Là-bas, la connexion est créée dans start(), donc AVANT que
     * le fil qui vide la file d'attente ne soit démarré — et démarrer un fil
     * garantit à lui seul qu'il voie tout ce qui précède. Ici la connexion
     * n'existe qu'après un aller-retour HTTP, sur un troisième fil, sans
     * aucun lien de synchronisation avec celui qui envoie le son : sans
     * volatile, ce dernier peut ne jamais voir la connexion apparaître et
     * jeter tout le son en silence, indéfiniment.
     */
    @Volatile private var webSocket: WebSocket? = null
    private val pendingAudio = java.io.ByteArrayOutputStream()

    /** Vrai dès stop() : empêche une connexion encore en vol de s'ouvrir dans le vide. */
    @Volatile private var stopped = false

    // --- De quoi savoir ce qui se passe quand rien ne s'affiche -------------
    // Une session qui s'ouvre et reste muette a au moins quatre causes très
    // différentes : le son ne part pas, il part mais le service se tait, le
    // service répond une erreur, ou il répond des transcriptions dans une
    // forme que ce fichier ne sait pas lire. Vues de l'écran de Jean, elles
    // sont rigoureusement identiques — et ne se corrigent pas du tout de la
    // même façon. Ces quatre compteurs les séparent.
    @Volatile private var chunksSent = 0
    @Volatile private var messagesReceived = 0
    @Volatile private var transcriptsRead = 0
    private var reportedFirstChunk = false
    private var reportedFirstMessage = false

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
                // Publié dans le diagnostic consultable à distance, et pas
                // seulement dans le journal système auquel personne n'a accès :
                // « la session ne s'ouvre pas » et « elle s'ouvre mais reste
                // muette » se ressemblent exactement vues de l'écran de Jean,
                // et ne se corrigent pas du tout de la même façon.
                TranscriptionDiagnostics.record("Gladia : session ouverte")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                messagesReceived++
                Log.d(TAG, "Message Gladia : ${text.take(LOG_EXCERPT)}")
                // Le tout premier message est publié tel quel dans le
                // diagnostic. C'est ce qui permet de constater la forme réelle
                // au lieu de la supposer : la documentation de référence n'est
                // pas toujours atteignable, et une forme devinée qui ne
                // correspond pas se traduit par un écran vide, sans erreur.
                if (!reportedFirstMessage) {
                    reportedFirstMessage = true
                    TranscriptionDiagnostics.record("Gladia 1er message : ${text.take(DIAGNOSTIC_EXCERPT)}")
                }
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    // Tout ce qui ressemble à un refus est remonté. Sans ça, un
                    // service qui explique poliment ce qui ne va pas — format
                    // audio refusé, session expirée, quota dépassé — voyait son
                    // message jeté au motif qu'il n'était pas une
                    // transcription.
                    if (type.contains("error", ignoreCase = true) || json.has("error")) {
                        TranscriptionDiagnostics.record("Gladia refuse : ${text.take(DIAGNOSTIC_EXCERPT)}")
                        onError("Gladia : ${text.take(DIAGNOSTIC_EXCERPT)}")
                        return
                    }
                    if (type != "transcript") return
                    val data = json.optJSONObject("data") ?: return
                    val utterance = data.optJSONObject("utterance")?.optString("text").orEmpty()
                    // is_final distingue les versions successives d'un même
                    // énoncé de celle qui le clôt — exactement ce dont la zone
                    // d'affichage a besoin (voir RollingCaptionZone.submit).
                    if (utterance.isNotBlank()) {
                        transcriptsRead++
                        onText(utterance, data.optBoolean("is_final", false))
                    }
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

    /**
     * Le son est mis en réserve dès le premier bloc, y compris avant que la
     * connexion existe.
     *
     * Il était jusqu'ici purement et simplement jeté tant que la connexion
     * n'était pas ouverte — soit pendant tout l'aller-retour HTTP qui la
     * négocie. Quelques centaines de millisecondes à chaque ouverture, et la
     * session se rouvre à chaque silence un peu franc pour ne pas facturer le
     * vide : c'est le début de chaque prise de parole qui disparaissait.
     *
     * La réserve d'avant-connexion est plafonnée et ne garde que le son le
     * plus récent : si la connexion n'arrive jamais, mieux vaut perdre du son
     * ancien que remplir la mémoire d'une tablette allumée en permanence.
     */
    override fun accept(pcm16: ByteArray, sampleRate: Int, channels: Int) {
        val converted = Pcm16.toBytes(Pcm16.toMono16k(pcm16, sampleRate, channels))
        val chunk = synchronized(pendingAudio) {
            pendingAudio.write(converted)
            if (webSocket == null) {
                if (pendingAudio.size() > PRE_CONNECT_MAX_BYTES) {
                    val kept = pendingAudio.toByteArray()
                    pendingAudio.reset()
                    pendingAudio.write(kept, kept.size - PRE_CONNECT_MAX_BYTES, PRE_CONNECT_MAX_BYTES)
                }
                return
            }
            if (pendingAudio.size() < MIN_CHUNK_BYTES) return
            val bytes = pendingAudio.toByteArray()
            pendingAudio.reset()
            bytes
        }
        webSocket?.send(Buffer().write(chunk).readByteString())
        chunksSent++
        if (!reportedFirstChunk) {
            reportedFirstChunk = true
            TranscriptionDiagnostics.record("Gladia : audio envoyé")
        }
    }

    override fun stop() {
        stopped = true
        // Le bilan de la session, lisible à distance après coup. C'est lui qui
        // répond à « rien ne s'affiche, pourquoi » : zéro envoi accuse la
        // chaîne audio, des envois sans message accusent le service, des
        // messages sans transcription accusent la lecture faite ici.
        TranscriptionDiagnostics.record(
            "Gladia bilan : $chunksSent envois, $messagesReceived messages, $transcriptsRead transcriptions"
        )
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

        /**
         * Deux secondes à 16 kHz mono 16 bits : de quoi couvrir la
         * négociation de session sans perdre le début d'une phrase, et pas
         * plus, faute de quoi une connexion qui n'aboutit jamais ferait
         * gonfler la mémoire sans fin.
         */
        const val PRE_CONNECT_MAX_BYTES = 64_000

        /** Assez pour reconnaître une forme de message, assez peu pour ne pas noyer l'écran. */
        const val DIAGNOSTIC_EXCERPT = 180
        const val LOG_EXCERPT = 400
    }
}
