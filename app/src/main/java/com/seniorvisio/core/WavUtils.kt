package com.seniorvisio.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Écrit un fichier WAV (PCM 16 bits) à partir d'un buffer brut — format
 * accepté par AssemblyAI, nécessaire pour l'audio qui n'est pas déjà encodé
 * par MediaRecorder (flux micro continu ou piste distante WebRTC, voir
 * AssemblyAiRollingTranscriber).
 */
fun writeWavFile(file: File, pcmData: ByteArray, sampleRate: Int, numChannels: Int = 1) {
    val byteRate = sampleRate * numChannels * 2
    RandomAccessFile(file, "rw").use { raf ->
        raf.setLength(0)
        raf.writeBytes("RIFF")
        raf.write(intToLE(36 + pcmData.size))
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.write(intToLE(16))
        raf.write(shortToLE(1)) // format PCM
        raf.write(shortToLE(numChannels))
        raf.write(intToLE(sampleRate))
        raf.write(intToLE(byteRate))
        raf.write(shortToLE(numChannels * 2))
        raf.write(shortToLE(16))
        raf.writeBytes("data")
        raf.write(intToLE(pcmData.size))
        raf.write(pcmData)
    }
}

/**
 * Convertit un buffer PCM 16 bits entrelacé (plusieurs canaux) en mono par
 * moyenne des canaux — la piste audio distante d'un appel WebRTC peut être
 * stéréo, alors qu'un seul canal suffit pour transcrire la voix du proche.
 */
fun downmixToMono(interleaved: ByteArray, channels: Int): ByteArray {
    if (channels <= 1) return interleaved
    val frameCount = interleaved.size / (2 * channels)
    val out = ByteArray(frameCount * 2)
    for (frame in 0 until frameCount) {
        var sum = 0
        for (ch in 0 until channels) {
            val idx = (frame * channels + ch) * 2
            val sample = ((interleaved[idx + 1].toInt() shl 8) or (interleaved[idx].toInt() and 0xFF)).toShort()
            sum += sample
        }
        val avg = (sum / channels).toShort()
        out[frame * 2] = (avg.toInt() and 0xFF).toByte()
        out[frame * 2 + 1] = ((avg.toInt() shr 8) and 0xFF).toByte()
    }
    return out
}

private fun intToLE(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte(),
)

private fun shortToLE(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
)
