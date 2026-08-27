package com.seniorvisio.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Capture continue du micro en PCM 16 bits mono, pour alimenter
 * [DeepgramRealtimeTranscriber] (sous-titres de la pièce). Contrairement à
 * MediaRecorder (un fichier par prise), une seule session AudioRecord tourne
 * sans interruption : découper le flux en tranches ne demande jamais de
 * relâcher puis rouvrir le micro, donc aucun blanc entre deux tranches.
 */
class MicPcmStreamer(private val onPcm: (ByteArray, Int) -> Unit) {

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val sampleRate = 16000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) return false
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        audioRecord = record
        running = true
        record.startRecording()
        val readThread = Thread {
            val buffer = ByteArray(minBufferSize)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) onPcm(buffer.copyOf(read), sampleRate)
            }
        }
        thread = readThread
        readThread.start()
        return true
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }
}
