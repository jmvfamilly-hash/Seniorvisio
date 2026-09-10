package com.seniorvisio.core

import android.content.Context
import android.util.Base64
import android.util.Log
import ai.picovoice.eagle.Eagle
import ai.picovoice.eagle.EagleProfile
import ai.picovoice.eagle.EagleProfiler

/**
 * Picovoice Eagle : reconnaissance de locuteur neuronale, exécutée entièrement
 * sur l'appareil.
 *
 * Le seul échange avec l'extérieur est la validation de la clé d'accès. Aucun
 * son ne quitte la tablette — ce qui n'est pas un détail pour un microphone qui
 * écoute une chambre du matin au soir, et c'est la raison principale de
 * préférer ce moteur-ci à un service distant qui serait probablement meilleur
 * encore.
 *
 * ═══ Ce qu'il apporte face au moteur intégré ═══
 *
 * Le moteur intégré compare une hauteur de voix et un timbre moyen. Il sépare
 * très bien une voix de femme ou d'enfant de celle de Jean, et mal une voix
 * masculine proche — un fils, précisément le visiteur le plus fréquent. C'est
 * exactement ce cas qu'un modèle entraîné traite mieux, et c'est la seule
 * raison d'ajouter six mégaoctets à une application qui pèse déjà lourd.
 *
 * ═══ Deux choses à savoir sur la mécanique ═══
 *
 * L'apprentissage rend un **pourcentage de complétude** décidé par le moteur
 * lui-même, et non par nous : il sait ce qui lui manque encore, là où notre
 * minuterie ne pouvait que supposer. Il n'accepte le son que par tranches
 * d'une taille qu'il impose et qu'on lui demande à l'exécution, plutôt que de
 * l'écrire ici en dur — une constante devinée survivrait mal à une mise à jour
 * de la bibliothèque.
 *
 * La reconnaissance, elle, garde son propre état d'un appel à l'autre : le
 * score monte à mesure qu'elle entend. On publie donc le dernier score reçu
 * plutôt qu'une moyenne, qui traînerait derrière un changement d'interlocuteur.
 */
class EagleSpeakerRecogniser private constructor(
    private val eagle: Eagle?,
    private val profiler: EagleProfiler?,
    private val profile: EagleProfile?,
    private val thresholdPercent: Int,
) : SpeakerRecogniser {

    override val engine = SpeakerEngineChoice.PICOVOICE

    /** Tranche de son à la taille exigée par le moteur, remplie au fil des blocs reçus. */
    private val chunk = ShortArray(
        maxOf(eagle?.minProcessSamples ?: 0, profiler?.frameLength ?: 0).coerceAtLeast(1)
    )
    private var filled = 0

    @Volatile private var lastScore: Float? = null
    @Volatile private var speakerIsJean: Boolean? = null
    private var lastVoicedAtMs = 0L
    @Volatile private var burstsSeen = 0
    @Volatile private var burstsAttributedToJean = 0

    override fun enroll(buffer: ShortArray, length: Int): Float {
        val profiler = profiler ?: return 0f
        var percentage = lastEnrollPercentage
        var offset = 0
        while (offset < length) {
            val take = minOf(profiler.frameLength - filled, length - offset)
            System.arraycopy(buffer, offset, chunk, filled, take)
            filled += take
            offset += take
            if (filled < profiler.frameLength) break
            filled = 0
            percentage = try {
                profiler.enroll(chunk.copyOf(profiler.frameLength))
            } catch (e: Exception) {
                // Une tranche refusée — trop de bruit, pas de voix — n'est pas
                // un échec de l'apprentissage : le moteur en demandera d'autres.
                Log.w(TAG, "Tranche refusée à l'apprentissage", e)
                percentage
            }
        }
        lastEnrollPercentage = percentage
        return (percentage / 100f).coerceIn(0f, 1f)
    }

    private var lastEnrollPercentage = 0f

    override fun finishEnrollment(): String? {
        val profiler = profiler ?: return null
        return try {
            profiler.flush()
            val exported = profiler.export()
            // Rangé en texte : les préférences Android ne stockent pas
            // d'octets, et c'est le même chemin que la signature du moteur
            // intégré — un seul mécanisme de stockage pour les deux.
            Base64.encodeToString(exported.bytes, Base64.NO_WRAP).also { exported.delete() }
        } catch (e: Exception) {
            // Complétude insuffisante : le moteur refuse d'exporter, et il a
            // raison de le faire. On ne stocke rien plutôt qu'un profil bancal
            // qui ne reconnaîtrait personne.
            Log.w(TAG, "Apprentissage Eagle incomplet", e)
            null
        }
    }

    override fun accept(buffer: ShortArray, length: Int, nowMs: Long) {
        val eagle = eagle ?: return
        val profile = profile ?: return
        val needed = eagle.minProcessSamples

        // Prise de parole précédente close par le silence : on compte celle qui
        // s'achève avant d'en ouvrir une autre.
        if (lastVoicedAtMs != 0L && nowMs - lastVoicedAtMs > BURST_GAP_MS) {
            burstsSeen++
            if (speakerIsJean == true) burstsAttributedToJean++
            speakerIsJean = null
        }
        lastVoicedAtMs = nowMs

        var offset = 0
        while (offset < length) {
            val take = minOf(needed - filled, length - offset)
            System.arraycopy(buffer, offset, chunk, filled, take)
            filled += take
            offset += take
            if (filled < needed) return
            filled = 0
            val scores = try {
                eagle.process(chunk.copyOf(needed), arrayOf(profile))
            } catch (e: Exception) {
                Log.w(TAG, "Tranche refusée par la reconnaissance", e)
                continue
            }
            val score = scores.firstOrNull() ?: continue
            lastScore = score
            speakerIsJean = score >= thresholdPercent / 100f
        }
    }

    override fun currentSpeakerIsJean(): Boolean? = speakerIsJean

    override fun lastSimilarityPercent(): Int? = lastScore?.let { (it * 100).toInt() }

    override fun consumeJeanSharePercent(): Int? {
        val seen = burstsSeen
        val jean = burstsAttributedToJean
        burstsSeen = 0
        burstsAttributedToJean = 0
        if (seen <= 0) return null
        return jean * 100 / seen
    }

    override fun close() {
        try {
            eagle?.delete()
            profiler?.delete()
            profile?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Fermeture d'Eagle", e)
        }
    }

    companion object {
        private const val TAG = "EagleSpeaker"

        /** Voir RoomSpeakerGate.BURST_GAP_MS : même raisonnement, même valeur. */
        private const val BURST_GAP_MS = 2_000L

        /**
         * Prépare le moteur pour reconnaître, ou pour apprendre.
         *
         * Rend un message d'erreur plutôt qu'une exception : tout ce qui peut
         * empêcher ce moteur de démarrer — clé absente, clé refusée, quota
         * d'activations épuisé, architecture non prévue — doit se lire à
         * distance dans le diagnostic. Sans ça, les quatre causes se
         * présenteraient identiquement, sous la forme d'un écran qui n'atténue
         * rien.
         */
        fun create(
            context: Context,
            accessKey: String,
            storedProfile: String?,
            thresholdPercent: Int,
            forEnrollment: Boolean,
        ): Result {
            if (accessKey.isBlank()) return Result(null, "clé d'accès Picovoice absente")

            val profile = if (forEnrollment) null else {
                if (storedProfile.isNullOrBlank()) return Result(null, "voix de Jean non apprise avec Picovoice")
                try {
                    EagleProfile(Base64.decode(storedProfile, Base64.NO_WRAP))
                } catch (e: Exception) {
                    Log.w(TAG, "Profil Eagle illisible", e)
                    return Result(null, "profil Picovoice illisible, à réapprendre")
                }
            }

            return try {
                if (forEnrollment) {
                    val profiler = EagleProfiler.Builder().setAccessKey(accessKey).build(context)
                    Result(EagleSpeakerRecogniser(null, profiler, null, thresholdPercent), null)
                } else {
                    val eagle = Eagle.Builder().setAccessKey(accessKey).build(context)
                    Result(EagleSpeakerRecogniser(eagle, null, profile, thresholdPercent), null)
                }
            } catch (e: Throwable) {
                // Throwable et non Exception : une architecture sans
                // bibliothèque native lève une UnsatisfiedLinkError, qui n'est
                // pas une Exception. La laisser passer ferait tomber le service
                // d'écoute — c'est-à-dire le réveil de l'écran de Jean — pour un
                // réglage de confort.
                Log.w(TAG, "Picovoice indisponible", e)
                profile?.delete()
                Result(null, "Picovoice indisponible : ${e.message ?: e.javaClass.simpleName}")
            }
        }

        class Result(val recogniser: EagleSpeakerRecogniser?, val error: String?)
    }
}
