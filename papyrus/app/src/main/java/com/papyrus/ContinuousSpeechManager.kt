package com.papyrus

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Écoute continue par le moteur de reconnaissance d'Android, hors-ligne.
 *
 * ═══ Le problème que cette classe existe pour étudier ═══
 *
 * L'interface publique d'Android est **modale** : elle écoute un énoncé, le
 * rend, s'arrête, et doit être relancée. Il n'existe aucun mode continu. Toute
 * « écoute continue » est donc une boucle de relances, et le temps mort entre
 * deux sessions est exactement ce qui coupe les phrases et mange les premiers
 * mots.
 *
 * Ce temps mort ne peut pas être supprimé, seulement réduit — d'où le soin
 * apporté ici à chacune de ses causes. Il ne peut pas non plus être masqué par
 * une réserve de son rejouée, comme on le ferait avec un moteur qu'on
 * alimenterait soi-même : ce moteur tient le microphone et ne nous laisse
 * jamais voir le son.
 *
 * ═══ Les quatre pièges, et ce qui est fait pour chacun ═══
 *
 * **Relancer depuis le rappel lui-même** échoue, silencieusement ou par une
 * exception selon les appareils : le moteur n'a pas fini de se ranger. Toute
 * relance passe donc par le fil principal, après le retour du rappel.
 *
 * **ERROR_NO_MATCH et ERROR_SPEECH_TIMEOUT ne sont pas des erreurs** : ce sont
 * les deux façons dont le moteur dit « personne n'a parlé ». Les traiter comme
 * des pannes ferait grandir une temporisation jusqu'à ce que la reconnaissance
 * s'arrête pour de bon, dans une pièce simplement silencieuse.
 *
 * **ERROR_RECOGNIZER_BUSY réclame une reconstruction.** L'instance est dans un
 * état dont elle ne sort pas seule ; la relancer donne la même erreur
 * indéfiniment.
 *
 * **Le moteur doit vivre sur le fil principal.** Sa création comme ses appels y
 * sont faits sans exception — c'est une contrainte de l'interface, pas une
 * précaution.
 *
 * ═══ Ce qu'on ne peut pas éviter ═══
 *
 * Les sons de début et de fin d'écoute (les « bips ») appartiennent au moteur
 * et ne se désactivent pas. Sur une écoute continue, ils reviennent à chaque
 * relance. C'est un fait à constater ici, pas un réglage à trouver.
 */
class ContinuousSpeechManager(
    private val context: Context,
    /** Texte en cours de dictée, révisé au fil des mots. */
    private val onPartial: (String) -> Unit,
    /** Énoncé clos par le moteur. */
    private val onFinal: (String) -> Unit,
    /** Une session vient d'être relancée : c'est là que se produisent les coupures. */
    private val onRestart: () -> Unit,
    /** Ce qui mérite d'être su : moteur choisi, refus, erreurs inhabituelles. */
    private val onDiagnostic: (String) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** L'écoute est-elle voulue ? Distinct de « en cours » : entre deux sessions, elle l'est toujours. */
    private var wanted = false

    /** Une session est ouverte : empêche deux startListening concurrents. */
    private var listening = false

    /** Erreurs consécutives, hors silences. Sert uniquement à espacer les relances. */
    private var consecutiveErrors = 0

    /** Sessions ouvertes depuis le démarrage — le nombre de coutures dans le texte. */
    var sessionCount = 0
        private set

    private val restart = Runnable { beginListening() }

    fun start() {
        if (wanted) return
        wanted = true
        describeEngine()
        beginListening()
    }

    fun stop() {
        wanted = false
        handler.removeCallbacks(restart)
        listening = false
        destroyRecognizer()
    }

    /**
     * Dit quel moteur va réellement travailler.
     *
     * Sans ça, un modèle hors-ligne absent de l'appareil se traduit par une
     * absence de texte — indiscernable d'un microphone muet ou d'une permission
     * refusée. Trois causes, trois corrections, un seul symptôme.
     */
    private fun describeEngine() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onDiagnostic("Aucun moteur de reconnaissance sur cet appareil")
            return
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                onDiagnostic(
                    if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                        "Moteur sur l'appareil, disponible"
                    } else {
                        "Moteur sur l'appareil INDISPONIBLE — modèle de langue à installer"
                    }
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                onDiagnostic("Moteur sur l'appareil (disponibilité non vérifiable avant Android 13)")
            else ->
                onDiagnostic("Hors-ligne demandé mais non garanti avant Android 12")
        }
    }

    private fun beginListening() {
        if (!wanted || listening) return

        val instance = recognizer ?: createRecognizer() ?: return
        recognizer = instance

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            // Sans quoi rien ne s'affiche avant le silence final : l'écran
            // resterait vide pendant qu'on parle, ce qui est le contraire du
            // but.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Consignes de durée de silence. Le moteur les traite comme des
            // souhaits et non comme des ordres — la documentation le dit, et
            // les appareils le confirment. Elles sont posées quand même : là
            // où elles sont suivies, elles rallongent la session et espacent
            // donc les coutures.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_SPEECH_MS)
        }

        try {
            listening = true
            sessionCount++
            instance.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Démarrage de l'écoute impossible", e)
            listening = false
            onDiagnostic("Démarrage refusé : ${e.message}")
            scheduleRestart(backoffMs())
        }
    }

    private fun createRecognizer(): SpeechRecognizer? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }.also { it.setRecognitionListener(listener) }
    } catch (e: Exception) {
        Log.w(TAG, "Création du moteur impossible", e)
        onDiagnostic("Création du moteur impossible : ${e.message}")
        null
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Fermeture du moteur", e)
        }
        recognizer = null
    }

    /**
     * Relance après le retour du rappel en cours, jamais depuis son intérieur :
     * le moteur n'a pas fini de se ranger, et l'appel échoue — sans bruit sur
     * certains appareils, par une exception sur d'autres.
     */
    private fun scheduleRestart(delayMs: Long) {
        if (!wanted) return
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delayMs)
        onRestart()
    }

    /** Espacement croissant, borné. Ne compte que les vraies erreurs, pas les silences. */
    private fun backoffMs(): Long =
        (RESTART_DELAY_MS shl consecutiveErrors.coerceAtMost(5)).coerceAtMost(MAX_RESTART_DELAY_MS)

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            // Une session s'est ouverte pour de bon : la précédente n'a donc
            // pas échoué, quoi qu'ait dit la dernière erreur.
            consecutiveErrors = 0
        }

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            firstResult(results)?.let(onFinal)
            // Sans délai : c'est le cas normal, et chaque milliseconde ici est
            // un mot que le moteur n'entend pas. Passer par le fil principal
            // suffit à laisser la session précédente se refermer.
            scheduleRestart(0L)
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                // Les deux façons de dire « personne n'a parlé ». Ce sont les
                // erreurs les plus fréquentes en écoute continue, et de loin :
                // les compter comme des pannes ferait grandir la temporisation
                // jusqu'à l'arrêt, dans une pièce simplement silencieuse.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(0L)

                // L'instance ne sort pas seule de cet état : la relancer rend
                // la même erreur indéfiniment.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    consecutiveErrors++
                    destroyRecognizer()
                    onDiagnostic("Moteur reconstruit (${describeError(error)})")
                    scheduleRestart(backoffMs())
                }

                else -> {
                    consecutiveErrors++
                    onDiagnostic(describeError(error))
                    scheduleRestart(backoffMs())
                }
            }
        }

        // Le moteur signale la fin de la parole avant de rendre son résultat.
        // Rien à faire ici : onResults ou onError suit immédiatement, et agir
        // aux deux endroits relancerait deux sessions concurrentes.
        override fun onEndOfSpeech() = Unit

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "erreur audio"
        SpeechRecognizer.ERROR_CLIENT -> "erreur côté client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission microphone refusée"
        SpeechRecognizer.ERROR_NETWORK -> "réseau (le moteur n'est pas hors-ligne)"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "délai réseau dépassé"
        SpeechRecognizer.ERROR_NO_MATCH -> "rien compris"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "moteur occupé"
        SpeechRecognizer.ERROR_SERVER -> "erreur serveur"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "silence"
        else -> "erreur $error"
    }

    private companion object {
        const val TAG = "ContinuousSpeech"
        const val LANGUAGE = "fr-FR"

        /** Point de départ de l'espacement entre deux relances après erreur. */
        const val RESTART_DELAY_MS = 250L
        const val MAX_RESTART_DELAY_MS = 8_000L

        /**
         * Silences suggérés au moteur, volontairement longs : plus la session
         * dure, moins il y a de coutures. Suggérés seulement — voir le
         * commentaire à leur pose.
         */
        const val SILENCE_HINT_MS = 10_000
        const val MINIMUM_SPEECH_MS = 60_000
    }
}
