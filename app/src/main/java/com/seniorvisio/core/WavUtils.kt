package com.seniorvisio.core

/**
 * Convertit un buffer PCM 16 bits entrelacé (plusieurs canaux) en mono par
 * moyenne des canaux — la piste audio distante d'un appel WebRTC peut être
 * stéréo, alors qu'un seul canal suffit pour transcrire la voix du proche
 * (voir WebRtcCallEngine.attachAssemblyAiSinkIfReady).
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
