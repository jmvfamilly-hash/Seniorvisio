package com.seniorvisio.core

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transcrit un flux audio continu par tranches successives — l'API
 * AssemblyAI ne traite que des fichiers, jamais un flux en direct. Chaque
 * tranche est envoyée dès qu'elle est complète pendant que la suivante
 * commence déjà à s'accumuler (le micro ou la piste distante ne s'arrêtent
 * jamais), pour un flux de sous-titres aussi régulier que possible plutôt
 * que d'attendre la réponse réseau avant de continuer à écouter.
 *
 * Le réseau peut répondre dans le désordre d'une tranche à l'autre : les
 * résultats sont donc réordonnés (par numéro de séquence) avant d'être
 * remontés via [onUtterance], sinon la lecture apparaîtrait mélangée.
 *
 * Utilisé à la fois pour les sous-titres de la pièce (voir MainActivity,
 * source : MicPcmStreamer) et les sous-titres d'appel (voir
 * WebRtcCallEngine, source : la piste audio distante WebRTC) — même
 * mécanisme, seule la source du son change.
 */
class AssemblyAiRollingTranscriber(
    private val apiKey: String,
    private val cacheDir: File,
    private val chunkDurationMs: Long = 8000L,
    private val onUtterance: (SpeakerUtterance) -> Unit,
    private val onError: ((String) -> Unit)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var buffer = ByteArrayOutputStream()
    @Volatile private var sampleRate = 16000
    @Volatile private var running = false

    private val nextSequence = AtomicInteger(0)
    private var nextToEmit = 0
    private val pendingResults = sortedMapOf<Int, AssemblyAiResult>()
    private var cutRunnable: Runnable? = null

    fun start() {
        running = true
        nextSequence.set(0)
        nextToEmit = 0
        pendingResults.clear()
        synchronized(lock) { buffer.reset() }
        scheduleNextCut()
    }

    fun stop() {
        running = false
        cutRunnable?.let { handler.removeCallbacks(it) }
        cutRunnable = null
    }

    /** Alimente le tampon courant avec un fragment audio PCM 16 bits (mono attendu). */
    fun feed(pcm: ByteArray, sampleRateHz: Int) {
        if (!running) return
        sampleRate = sampleRateHz
        synchronized(lock) { buffer.write(pcm) }
    }

    private fun scheduleNextCut() {
        val runnable = Runnable {
            cutCurrentChunk()
            if (running) scheduleNextCut()
        }
        cutRunnable = runnable
        handler.postDelayed(runnable, chunkDurationMs)
    }

    private fun cutCurrentChunk() {
        val data = synchronized(lock) {
            val bytes = buffer.toByteArray()
            buffer.reset()
            bytes
        }
        if (data.isEmpty()) return
        val sequence = nextSequence.getAndIncrement()
        val file = File(cacheDir, "assemblyai_rolling_${sequence}_${System.currentTimeMillis()}.wav")
        writeWavFile(file, data, sampleRate)
        Thread {
            try {
                val result = AssemblyAiClient(apiKey).transcribe(file)
                handler.post { deliverResult(sequence, result) }
            } catch (e: Exception) {
                handler.post { onError?.invoke(e.message ?: "erreur inconnue") }
            } finally {
                file.delete()
            }
        }.start()
    }

    private fun deliverResult(sequence: Int, result: AssemblyAiResult) {
        pendingResults[sequence] = result
        while (pendingResults.containsKey(nextToEmit)) {
            val ready = pendingResults.remove(nextToEmit) ?: break
            nextToEmit++
            if (ready.utterances.isNotEmpty()) {
                ready.utterances.forEach { onUtterance(it) }
            } else if (ready.fullText.isNotBlank()) {
                // Tranche trop courte pour que l'API distingue des locuteurs :
                // affiche quand même le texte, sans nom de locuteur.
                onUtterance(SpeakerUtterance(speaker = "", text = ready.fullText))
            }
        }
    }
}
