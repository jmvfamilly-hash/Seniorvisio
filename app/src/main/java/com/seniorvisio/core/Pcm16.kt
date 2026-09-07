package com.seniorvisio.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mise au format attendu par les moteurs de reconnaissance vocale : mono,
 * 16 bits, 16 kHz. Les deux moteurs en ont besoin — AssemblyAI parce que c'est
 * ce que déclare l'URL de sa session, Vosk parce que ses modèles sont entraînés
 * à cette fréquence — et les sources ne la fournissent pas : le micro de la
 * pièce capture déjà en 16 kHz mono, mais une piste audio WebRTC arrive en
 * 48 kHz, parfois sur deux canaux.
 *
 * Le rééchantillonnage moyenne les échantillons de la fenêtre source au lieu
 * d'en prélever un seul. Prélever un point sur trois — ce que faisait la
 * première version — laisse passer tout ce qui dépasse 8 kHz en le repliant
 * dans la bande utile : le sifflement d'un « s », le claquement d'une porte
 * ressortent transformés en bruit au beau milieu des voyelles. Un modèle en
 * ligne encaisse ; un modèle embarqué de 1,4 Go, à qui l'on demande justement
 * d'être précis, beaucoup moins. La moyenne n'est qu'un filtre passe-bas
 * grossier, mais elle coûte trois additions et supprime l'essentiel du
 * repliement.
 */
object Pcm16 {

    const val TARGET_SAMPLE_RATE_HZ = 16_000

    /** PCM 16 bits entrelacé (petit-boutiste) vers du mono 16 kHz. */
    fun toMono16k(pcm: ByteArray, sourceSampleRate: Int, sourceChannels: Int): ShortArray {
        val channels = sourceChannels.coerceAtLeast(1)
        val frames = pcm.size / 2 / channels
        if (frames <= 0) return ShortArray(0)

        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += input.short.toInt()
            mono[i] = (sum / channels).toShort()
        }

        if (sourceSampleRate == TARGET_SAMPLE_RATE_HZ) return mono

        val ratio = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE_HZ
        val outCount = (mono.size / ratio).toInt()
        if (outCount <= 0) return ShortArray(0)

        val resampled = ShortArray(outCount)
        for (i in 0 until outCount) {
            val from = (i * ratio).toInt()
            val to = (((i + 1) * ratio).toInt()).coerceAtMost(mono.size)
            if (to <= from) {
                // Suréchantillonnage (source sous les 16 kHz) : il n'y a pas de
                // fenêtre à moyenner, on recopie l'échantillon le plus proche.
                resampled[i] = mono[from.coerceAtMost(mono.size - 1)]
                continue
            }
            var sum = 0
            for (s in from until to) sum += mono[s]
            resampled[i] = (sum / (to - from)).toShort()
        }
        return resampled
    }

    fun toBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        shorts.forEach { buffer.putShort(it) }
        return bytes
    }
}
