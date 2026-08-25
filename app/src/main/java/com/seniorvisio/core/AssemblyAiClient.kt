package com.seniorvisio.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Un tour de parole identifié par la diarisation (locuteur A/B/C...). */
data class SpeakerUtterance(val speaker: String, val text: String)

data class AssemblyAiResult(
    val fullText: String,
    val confidence: Double,
    val utterances: List<SpeakerUtterance>,
)

/**
 * Client minimal pour le labo de comparaison de transcription
 * (TranscriptionLabActivity) : upload d'un fichier audio, lancement de la
 * transcription avec diarisation (identification des locuteurs — ce qui
 * permet de distinguer Jean des autres personnes présentes dans la pièce),
 * puis attente du résultat par sondage. N'utilise aucune bibliothèque tierce
 * (HttpURLConnection uniquement), cohérent avec le reste du projet.
 */
class AssemblyAiClient(private val apiKey: String) {

    fun transcribe(audioFile: File): AssemblyAiResult {
        val uploadUrl = uploadAudio(audioFile)
        val transcriptId = requestTranscript(uploadUrl)
        return pollUntilComplete(transcriptId)
    }

    private fun uploadAudio(file: File): String {
        val connection = openConnection("https://api.assemblyai.com/v2/upload", "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        return response.getString("upload_url")
    }

    private fun requestTranscript(audioUrl: String): String {
        val connection = openConnection("https://api.assemblyai.com/v2/transcript", "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().apply {
            put("audio_url", audioUrl)
            put("speaker_labels", true)
            put("language_code", "fr")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        return response.getString("id")
    }

    private fun pollUntilComplete(transcriptId: String): AssemblyAiResult {
        val url = "https://api.assemblyai.com/v2/transcript/$transcriptId"
        while (true) {
            val connection = openConnection(url, "GET")
            val response = JSONObject(connection.inputStream.bufferedReader().readText())
            when (response.getString("status")) {
                "completed" -> return AssemblyAiResult(
                    fullText = response.optString("text"),
                    confidence = response.optDouble("confidence", 0.0),
                    utterances = parseUtterances(response.optJSONArray("utterances")),
                )
                "error" -> throw RuntimeException("AssemblyAI: ${response.optString("error")}")
                else -> Thread.sleep(2000)
            }
        }
    }

    private fun parseUtterances(array: JSONArray?): List<SpeakerUtterance> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            SpeakerUtterance(speaker = "Locuteur ${entry.getString("speaker")}", text = entry.getString("text"))
        }
    }

    private fun openConnection(urlString: String, method: String): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("authorization", apiKey)
        return connection
    }
}
