package com.seniorvisio.core

import kotlin.math.exp
import kotlin.math.ln

/**
 * La voix de quelqu'un, réduite à ce qui la distingue : un timbre moyen et une
 * hauteur moyenne (voir VoiceFeatures).
 *
 * Une moyenne sur plusieurs dizaines de trames, et pas une trame isolée. Une
 * seule trame décrit un son — un « a », un « ch » — plus que la personne qui
 * l'émet ; c'est en moyennant sur des phrases entières que ce qui varie d'un
 * mot à l'autre s'annule et que ce qui reste propre à la personne se dégage.
 */
class VoiceSignature(
    val timbre: DoubleArray,
    /** Hauteur moyenne, en logarithme : une octave y vaut le même écart en haut et en bas. */
    val logPitch: Double,
    /** Nombre de trames voisées qui ont servi à la construire. */
    val frames: Int,
) {

    val pitchHz: Double get() = exp(logPitch)

    /**
     * À quel point [other] ressemble à cette signature, de 0 à 1.
     *
     * La hauteur pèse plus que le timbre, et c'est une décision et non un
     * réglage arbitraire : la hauteur est une mesure absolue, en hertz, qui
     * sépare franchement un homme âgé d'une femme ou d'un enfant. Le timbre est
     * une comparaison de formes, plus fine mais plus sensible au bruit de la
     * pièce et à la distance. Le second départage ce que la première laisse
     * indécis — deux voix masculines proches, le cas difficile.
     */
    fun similarityTo(other: VoiceSignature): Double {
        val pitchGap = (other.logPitch - logPitch) / PITCH_TOLERANCE
        val pitchScore = exp(-pitchGap * pitchGap)
        val timbreScore = VoiceFeatures.timbreSimilarity(timbre, other.timbre)
        return PITCH_WEIGHT * pitchScore + (1.0 - PITCH_WEIGHT) * timbreScore
    }

    /**
     * Sérialisée en texte simple : les préférences Android ne savent pas
     * stocker un tableau de nombres, et une chaîne lisible se relit à l'œil
     * quand on cherche à comprendre pourquoi une reconnaissance se comporte
     * mal.
     */
    fun serialise(): String =
        (listOf(frames.toString(), logPitch.toString()) + timbre.map { it.toString() }).joinToString(";")

    companion object {

        /**
         * Écart de hauteur au-delà duquel la ressemblance s'effondre, en
         * logarithme : 0,18 correspond à environ 20 %, soit 110 Hz contre
         * 132 Hz. Une même personne varie moins que ça d'un jour à l'autre ;
         * deux personnes différentes, souvent plus.
         */
        private const val PITCH_TOLERANCE = 0.18

        /** Part de la hauteur dans le verdict, le reste allant au timbre. */
        private const val PITCH_WEIGHT = 0.6

        /**
         * En dessous, la signature n'est pas jugée fiable : une poignée de
         * trames décrit une syllabe, pas une personne. À 64 ms la trame, 200
         * trames font une douzaine de secondes de parole effective — bien plus
         * que la durée d'enregistrement, puisque les silences et les consonnes
         * n'y comptent pas.
         */
        const val MIN_RELIABLE_FRAMES = 200

        fun parse(stored: String?): VoiceSignature? {
            if (stored.isNullOrBlank()) return null
            val parts = stored.split(';')
            if (parts.size != 2 + VoiceFeatures.MFCC_COUNT) return null
            return try {
                VoiceSignature(
                    frames = parts[0].toInt(),
                    logPitch = parts[1].toDouble(),
                    timbre = DoubleArray(VoiceFeatures.MFCC_COUNT) { parts[it + 2].toDouble() },
                )
            } catch (e: NumberFormatException) {
                // Préférence abîmée : on rend null plutôt que de lever. Une
                // signature illisible doit désactiver la reconnaissance, pas
                // empêcher la tablette de démarrer.
                null
            }
        }
    }

    /**
     * Accumule des trames et en tire une signature. Sert aussi bien à
     * l'enregistrement de la voix de Jean qu'à décrire, au vol, la personne en
     * train de parler — ce sont exactement les mêmes gestes, et c'est même
     * indispensable qu'ils le soient : comparer deux grandeurs obtenues de deux
     * façons différentes ne voudrait rien dire.
     */
    class Builder {
        private val timbreSum = DoubleArray(VoiceFeatures.MFCC_COUNT)
        private var logPitchSum = 0.0
        private var count = 0

        val frames: Int get() = count

        fun add(frame: VoiceFeatures.Frame) {
            val pitch = frame.pitchHz ?: return
            for (index in timbreSum.indices) timbreSum[index] += frame.mfcc[index]
            logPitchSum += ln(pitch)
            count++
        }

        fun reset() {
            timbreSum.fill(0.0)
            logPitchSum = 0.0
            count = 0
        }

        /** Null tant qu'il n'y a pas de quoi conclure. */
        fun build(minimumFrames: Int): VoiceSignature? {
            if (count < minimumFrames) return null
            return VoiceSignature(
                timbre = DoubleArray(VoiceFeatures.MFCC_COUNT) { timbreSum[it] / count },
                logPitch = logPitchSum / count,
                frames = count,
            )
        }
    }
}
