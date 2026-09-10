package com.seniorvisio.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ce qui, dans un instant de son, caractérise une voix plutôt qu'un mot.
 *
 * Deux mesures, choisies parce qu'elles disent qui parle et non ce qui est dit :
 *
 *  - le **timbre**, sous la forme classique de coefficients cepstraux sur une
 *    échelle mel. Ils décrivent la forme du conduit vocal — sa longueur, ses
 *    résonances — laquelle ne change pas d'une phrase à l'autre chez une même
 *    personne ;
 *  - la **hauteur**, c'est-à-dire la fréquence de vibration des cordes vocales.
 *    Grossière, mais c'est de loin le repère le plus discriminant dont on
 *    dispose : un homme âgé tourne autour de 110 Hz, une femme autour de 200,
 *    un enfant plus haut encore.
 *
 * Calculé dans l'application, sans modèle ni bibliothèque. Un réseau de
 * neurones d'empreinte vocale ferait beaucoup mieux, en particulier sur deux
 * voix masculines proches — c'est le cas difficile, et il est fréquent dans une
 * famille. Le choix ici est assumé : pas d'octet supplémentaire dans une
 * application qui pèse déjà lourd, aucune dépendance à un modèle à télécharger,
 * et des mathématiques vérifiables. Le jour où la justesse ne suffira pas, seul
 * ce fichier est à remplacer — tout ce qui s'appuie dessus reste en place.
 *
 * Tout est en 16 kHz mono, la cadence à laquelle la pièce est déjà captée
 * (voir RoomPresenceService.SAMPLE_RATE_HZ).
 */
object VoiceFeatures {

    /**
     * 1024 échantillons, soit 64 ms. Assez long pour contenir plusieurs
     * périodes d'une voix grave — il en faut au moins deux pour en mesurer la
     * hauteur, et une voix d'homme âgé à 100 Hz en fait une toutes les 10 ms —
     * et assez court pour que le conduit vocal n'ait pas eu le temps de changer
     * de forme au milieu. Puissance de deux, ce qu'exige la transformée.
     */
    const val FRAME_SAMPLES = 1024

    /** Combien de coefficients de timbre sont retenus. */
    const val MFCC_COUNT = 12

    private const val SAMPLE_RATE_HZ = 16_000
    private const val MEL_FILTERS = 24
    private const val MEL_LOW_HZ = 80.0
    private const val MEL_HIGH_HZ = 7_600.0

    /**
     * Bornes de la recherche de hauteur : 70 Hz à 250 Hz. En dessous, plus
     * aucune voix humaine ; au-dessus, on trouverait surtout la première
     * harmonique d'une voix grave, ce qui la ferait passer pour une voix aiguë.
     */
    private const val MIN_PITCH_HZ = 70.0
    private const val MAX_PITCH_HZ = 250.0

    /**
     * En dessous de cette corrélation, la trame n'est pas jugée voisée : une
     * consonne, un souffle, un bruit. On ne lui attribue pas de hauteur, et on
     * ne s'en sert pas pour reconnaître quelqu'un — le bruit d'une chaise n'a
     * pas d'identité.
     */
    private const val VOICING_MIN_CORRELATION = 0.30

    /** Fenêtre de Hamming, calculée une fois : elle ne dépend que de la taille. */
    private val window = DoubleArray(FRAME_SAMPLES) {
        0.54 - 0.46 * cos(2.0 * PI * it / (FRAME_SAMPLES - 1))
    }

    /** Bornes des filtres mel, en numéros de raies du spectre. */
    private val filterBins: IntArray = run {
        val low = toMel(MEL_LOW_HZ)
        val high = toMel(MEL_HIGH_HZ)
        IntArray(MEL_FILTERS + 2) { index ->
            val hz = fromMel(low + (high - low) * index / (MEL_FILTERS + 1))
            (hz * FRAME_SAMPLES / SAMPLE_RATE_HZ).toInt().coerceIn(0, FRAME_SAMPLES / 2)
        }
    }

    /**
     * Ce qu'on retient d'une trame : son timbre, et sa hauteur si elle en a une.
     */
    class Frame(val mfcc: DoubleArray, val pitchHz: Double?)

    /**
     * Analyse une trame de [FRAME_SAMPLES] échantillons. Rend null si la trame
     * n'est pas voisée — c'est-à-dire s'il n'y a personne en train de parler
     * dedans, auquel cas il n'y a rien à attribuer à qui que ce soit.
     */
    fun analyse(samples: ShortArray): Frame? {
        val windowed = DoubleArray(FRAME_SAMPLES) { samples[it].toDouble() * window[it] }
        val pitch = detectPitch(windowed) ?: return null
        return Frame(mfccOf(windowed), pitch)
    }

    /**
     * Hauteur de la voix par autocorrélation : on cherche le décalage pour
     * lequel le signal ressemble le plus à lui-même. Chez une voix, ce décalage
     * est la période de vibration des cordes vocales.
     *
     * Méthode ancienne et peu coûteuse. Son défaut connu est de confondre
     * parfois une fréquence avec son double ou sa moitié ; les bornes de
     * recherche limitent la casse, et une erreur isolée se noie dans la moyenne
     * de plusieurs dizaines de trames.
     */
    private fun detectPitch(windowed: DoubleArray): Double? {
        var energy = 0.0
        for (value in windowed) energy += value * value
        if (energy <= 0.0) return null

        val minLag = (SAMPLE_RATE_HZ / MAX_PITCH_HZ).toInt()
        val maxLag = (SAMPLE_RATE_HZ / MIN_PITCH_HZ).toInt().coerceAtMost(FRAME_SAMPLES - 1)
        var bestLag = 0
        var bestCorrelation = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until FRAME_SAMPLES - lag) sum += windowed[i] * windowed[i + lag]
            val correlation = sum / energy
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestLag = lag
            }
        }
        if (bestLag == 0 || bestCorrelation < VOICING_MIN_CORRELATION) return null
        return SAMPLE_RATE_HZ.toDouble() / bestLag
    }

    /**
     * Le timbre : énergie du spectre répartie sur des filtres espacés selon
     * l'oreille, passée au logarithme puis décorrélée.
     *
     * Le tout premier coefficient est écarté : il ne porte que le volume
     * global. Le garder ferait dépendre l'identité d'une voix de la distance à
     * laquelle on parle, ce qui n'a aucun sens ici — Jean assis dans son
     * fauteuil et un proche debout près de la tablette ne sont pas à la même
     * distance, et c'est justement le genre de faux indice qui rendrait le
     * filtre inutilisable.
     */
    private fun mfccOf(windowed: DoubleArray): DoubleArray {
        val power = powerSpectrum(windowed)
        val logEnergies = DoubleArray(MEL_FILTERS) { filter ->
            val low = filterBins[filter]
            val centre = filterBins[filter + 1]
            val high = filterBins[filter + 2]
            var sum = 0.0
            for (bin in low until centre) {
                sum += power[bin] * (bin - low).toDouble() / (centre - low).coerceAtLeast(1)
            }
            for (bin in centre until high) {
                sum += power[bin] * (high - bin).toDouble() / (high - centre).coerceAtLeast(1)
            }
            ln(sum + 1e-10)
        }
        return DoubleArray(MFCC_COUNT) { index ->
            val order = index + 1
            var sum = 0.0
            for (filter in 0 until MEL_FILTERS) {
                sum += logEnergies[filter] * cos(PI * order * (filter + 0.5) / MEL_FILTERS)
            }
            sum
        }
    }

    private fun powerSpectrum(windowed: DoubleArray): DoubleArray {
        val real = windowed.copyOf()
        val imaginary = DoubleArray(FRAME_SAMPLES)
        fft(real, imaginary)
        return DoubleArray(FRAME_SAMPLES / 2 + 1) { real[it] * real[it] + imaginary[it] * imaginary[it] }
    }

    /**
     * Transformée de Fourier rapide, en place, sur un tableau de taille
     * puissance de deux. Écrite ici plutôt qu'empruntée : c'est une trentaine
     * de lignes parfaitement standard, et la seule alternative aurait été
     * d'ajouter une bibliothèque entière pour cette unique fonction.
     */
    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var target = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (target and bit != 0) {
                target = target xor bit
                bit = bit shr 1
            }
            target = target or bit
            if (index < target) {
                val tempReal = real[index]; real[index] = real[target]; real[target] = tempReal
                val tempImaginary = imaginary[index]; imaginary[index] = imaginary[target]; imaginary[target] = tempImaginary
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            var start = 0
            while (start < size) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val low = start + offset
                    val high = low + length / 2
                    val productReal = real[high] * twiddleReal - imaginary[high] * twiddleImaginary
                    val productImaginary = real[high] * twiddleImaginary + imaginary[high] * twiddleReal
                    real[high] = real[low] - productReal
                    imaginary[high] = imaginary[low] - productImaginary
                    real[low] += productReal
                    imaginary[low] += productImaginary
                    val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextReal
                }
                start += length
            }
            length = length shl 1
        }
    }

    private fun toMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
    private fun fromMel(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    /** Similarité cosinus, ramenée de [-1, 1] à [0, 1]. */
    fun timbreSimilarity(first: DoubleArray, second: DoubleArray): Double {
        var dot = 0.0
        var firstNorm = 0.0
        var secondNorm = 0.0
        for (index in first.indices) {
            dot += first[index] * second[index]
            firstNorm += first[index] * first[index]
            secondNorm += second[index] * second[index]
        }
        val denominator = sqrt(firstNorm) * sqrt(secondNorm)
        if (denominator <= 0.0) return 0.0
        return ((dot / denominator) + 1.0) / 2.0
    }
}
