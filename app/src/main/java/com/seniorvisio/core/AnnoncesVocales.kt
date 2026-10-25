package com.seniorvisio.core

import android.content.Context
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Les deux annonces sonores de la pause soignants (voir PauseAppels) : une
 * tonalité suivie d'une phrase dite à voix haute, pour que Jean entende la
 * bascule même sans regarder l'écran.
 *
 * ═══ PAS UNE VRAIE ÉMOTION, UNE APPROXIMATION ═══
 *
 * La synthèse vocale d'Android ne sait pas jouer un ton « triste » ou
 * « joyeux » : c'est la même voix, dont on fait varier la hauteur et le débit
 * pour s'en approcher. Le genre de la voix dépend du moteur de synthèse
 * installé sur l'appareil, hors de notre contrôle.
 */
object AnnoncesVocales {

    enum class Annonce(val texte: String, val hauteur: Float, val débit: Float) {
        APPELS_INTERDITS("Appels interdits", 0.8f, 0.85f),
        APPELS_AUTORISÉS("Appels de nouveau autorisés", 1.3f, 1.15f),
    }

    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun assurerInit(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "Synthèse vocale indisponible (statut $status)")
            }
        }
    }

    /** Tonalité puis phrase, dans cet ordre — la phrase attend que la tonalité soit passée. */
    fun jouer(context: Context, annonce: Annonce) {
        assurerInit(context)
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        }.onFailure { e -> Log.w(TAG, "Tonalité de pause soignants non jouée", e) }

        handler.postDelayed({
            runCatching {
                val moteur = tts ?: return@runCatching
                moteur.language = Locale.FRANCE
                moteur.setPitch(annonce.hauteur)
                moteur.setSpeechRate(annonce.débit)
                moteur.speak(annonce.texte, TextToSpeech.QUEUE_FLUSH, null, annonce.name)
            }.onFailure { e -> Log.w(TAG, "Annonce vocale non lue", e) }
        }, DÉLAI_APRÈS_TONALITÉ_MS)
    }

    private const val TAG = "AnnoncesVocales"
    private const val DÉLAI_APRÈS_TONALITÉ_MS = 900L
}
