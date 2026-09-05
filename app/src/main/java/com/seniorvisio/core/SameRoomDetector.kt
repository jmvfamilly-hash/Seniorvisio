package com.seniorvisio.core

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.cos

/**
 * Détecte que le téléphone de l'appelant se trouve dans la même pièce que la
 * tablette, en écoutant une balise sonore inaudible qu'il émet pendant la
 * sonnerie (voir web-caller/webrtc-engine.js, _startSameRoomBeacon).
 *
 * Pourquoi pas la géolocalisation, comme on pourrait s'y attendre : elle ne
 * sait pas faire. Le GPS ne fonctionne pas en intérieur, et là où il
 * fonctionne sa précision se compte en dizaines de mètres — deux appareils
 * dans la même chambre et deux appareils dans deux appartements voisins
 * rendent exactement les mêmes coordonnées. Le son, lui, ne traverse pas les
 * murs à cette fréquence.
 *
 * Fréquence choisie dans l'ultrason "de proximité" (17,8 kHz) : au-delà de ce
 * que Jean peut entendre — l'audition au-dessus de 15 kHz disparaît
 * pratiquement toujours après cinquante ans — mais dans ce que le
 * haut-parleur d'un téléphone sait encore produire et un microphone capter,
 * contrairement aux vrais ultrasons (>20 kHz) qu'aucun des deux ne reproduit.
 * Et surtout, ces fréquences sont très fortement absorbées par une cloison :
 * c'est justement ce qui en fait un bon témoin de "même pièce" plutôt que de
 * "même logement".
 *
 * Écoute pendant la sonnerie uniquement, et avec son propre microphone : à ce
 * moment-là WebRTC n'a pas encore pris le micro (il ne le prend qu'à
 * l'acceptation de l'appel, voir WebRtcCallEngine.answer) et
 * RoomPresenceService est déjà suspendu (voir IncomingCallActivity). C'est la
 * seule fenêtre de l'appel où le microphone est libre — et elle suffit
 * largement, la question "sommes-nous dans la même pièce" ne se reposant pas
 * en cours de conversation.
 *
 * Capture à 44,1 kHz et non aux 16 kHz du reste de l'application : à 16 kHz,
 * la plus haute fréquence représentable est 8 kHz, très loin de la balise.
 * Capture séparée et de courte durée, plutôt qu'un relèvement de la fréquence
 * de RoomPresenceService : ré-échantillonner 44,1 kHz vers les 16 kHz
 * qu'attend AssemblyAI avec le ré-échantillonneur simple utilisé ici (plus
 * proche voisin, sans filtre anti-repliement, voir
 * AssemblyAiRealtimeTranscriber) replierait justement ces hautes fréquences
 * dans la bande de la parole et dégraderait la transcription — la fonction la
 * plus importante du projet.
 */
class SameRoomDetector(private val onDetected: () -> Unit) {

    @Volatile private var isListening = false
    private var thread: Thread? = null

    fun start() {
        if (isListening) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            Log.w(TAG, "Capture 44,1 kHz non supportée : détection de proximité indisponible")
            return
        }

        val record = try {
            AudioRecord(
                // VOICE_RECOGNITION plutôt que MIC : c'est la source qui
                // applique le moins de traitement (pas de réduction de bruit
                // agressive, pas d'égalisation vocale), or tout traitement
                // pensé pour la voix commence par jeter ce qui est au-dessus
                // de la bande utile — c'est-à-dire exactement la balise.
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission micro refusée : détection de proximité indisponible", e)
            null
        } ?: return

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        isListening = true
        record.startRecording()
        thread = Thread {
            val buffer = ShortArray(WINDOW_SAMPLES)
            var hits = 0
            var windows = 0
            while (isListening) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < buffer.size) continue
                if (isBeaconPresent(buffer)) hits++
                windows++
                if (windows < WINDOWS_BEFORE_DECISION) continue
                if (hits >= HITS_REQUIRED) {
                    isListening = false
                    onDetected()
                    break
                }
                // Fenêtre glissante remise à zéro plutôt que cumulative : un
                // bruit ponctuel accumulé sur toute la sonnerie finirait sinon
                // par atteindre le seuil, alors que la balise, elle, est
                // présente en continu.
                hits = 0
                windows = 0
            }
            try {
                record.stop()
            } catch (_: IllegalStateException) {
                // Déjà arrêté : sans conséquence.
            }
            record.release()
        }.apply { start() }
    }

    fun stop() {
        isListening = false
        thread = null
    }

    /**
     * Compare l'énergie à la fréquence de la balise à celle de deux bandes
     * voisines, plutôt qu'à un seuil absolu : le niveau reçu dépend du modèle
     * de téléphone, de la distance et de l'orientation, mais un bruit large
     * bande (ventilation, froissement, télévision) monte partout à la fois,
     * alors que la balise ne monte qu'à sa propre fréquence. C'est ce
     * contraste, et non le volume, qui la distingue.
     */
    private fun isBeaconPresent(samples: ShortArray): Boolean {
        val beacon = goertzelPower(samples, BEACON_HZ)
        val referenceBelow = goertzelPower(samples, BEACON_HZ - REFERENCE_OFFSET_HZ)
        val referenceAbove = goertzelPower(samples, BEACON_HZ + REFERENCE_OFFSET_HZ)
        val noiseFloor = maxOf(referenceBelow, referenceAbove, MIN_NOISE_FLOOR)
        return beacon > noiseFloor * REQUIRED_CONTRAST
    }

    /**
     * Algorithme de Goertzel : l'énergie à une seule fréquence, pour le coût
     * d'une multiplication et deux additions par échantillon. Une transformée
     * de Fourier complète calculerait ici plusieurs centaines de fréquences
     * dont deux seulement nous intéressent.
     */
    private fun goertzelPower(samples: ShortArray, frequencyHz: Double): Double {
        val length = samples.size
        val bin = Math.round(length * frequencyHz / SAMPLE_RATE_HZ).toInt()
        val coefficient = 2.0 * cos(2.0 * Math.PI * bin / length)
        var previous = 0.0
        var beforePrevious = 0.0
        for (i in 0 until length) {
            val current = samples[i] / 32768.0 + coefficient * previous - beforePrevious
            beforePrevious = previous
            previous = current
        }
        return previous * previous + beforePrevious * beforePrevious -
            coefficient * previous * beforePrevious
    }

    companion object {
        private const val TAG = "SameRoomDetector"
        private const val SAMPLE_RATE_HZ = 44_100
        private const val BEACON_HZ = 17_800.0

        /** Bandes témoins, assez loin de la balise pour ne pas capter sa propre énergie. */
        private const val REFERENCE_OFFSET_HZ = 900.0

        /** ~46 ms de son par fenêtre : assez long pour une bonne résolution en fréquence, assez court pour décider vite. */
        private const val WINDOW_SAMPLES = 2048

        /** Environ une seconde d'écoute avant de trancher. */
        private const val WINDOWS_BEFORE_DECISION = 22
        private const val HITS_REQUIRED = 12

        /**
         * La balise doit dominer les bandes voisines d'un facteur net. Un
         * rapport modeste se produit naturellement dès qu'un bruit a une
         * couleur un peu marquée ; un facteur de cet ordre ne s'obtient
         * pratiquement qu'avec une vraie tonalité pure.
         */
        private const val REQUIRED_CONTRAST = 25.0

        /** Évite qu'un silence quasi parfait ne rende n'importe quel souffle "25 fois plus fort que rien". */
        private const val MIN_NOISE_FLOOR = 1e-4
    }
}
