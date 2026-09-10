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

    /**
     * Le dernier texte partiel de la session en cours.
     *
     * Conservé parce qu'une session ne rend pas toujours de résultat final :
     * onResults peut arriver vide, et ERROR_NO_MATCH tomber après plusieurs
     * secondes de dictée parfaitement lisible. Sans cette copie, ce qui avait
     * été affiché disparaissait — la session suivante écrasant le texte partiel
     * par le sien, plus court. C'est ainsi que de la parole se perd, pas
     * seulement de l'affichage.
     */
    private var lastPartial = ""

    private val restart = Runnable {
        SpeechTrace.record("APP relance exécutée", "")
        beginListening()
    }

    /**
     * Referme une session qui dépasse la durée raisonnable. Voir MAX_SESSION_MS.
     */
    private val sessionCap = Runnable {
        if (!listening) return@Runnable
        SpeechTrace.record("APP session trop longue", "clôture forcée après ${MAX_SESSION_MS} ms")
        endSession("clôture forcée")
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Clôture forcée impossible", e)
        }
    }

    // --- Niveau sonore, échantillonné ---------------------------------------
    // Sans lui, une session sans texte a deux explications opposées : la pièce
    // était silencieuse, ou la parole n'a pas été captée. La trace relevée sur
    // l'appareil montre deux sessions entières sans un mot, et rien ne permet
    // aujourd'hui de dire laquelle des deux s'est produite.
    private var lastRmsLogAtMs = 0L
    private var peakRmsSinceLog = -120f
    private var firstPartialLogged = false
    private var readyAtMs = 0L

    // --- Bilan par session --------------------------------------------------
    // Une session s'étale sur des dizaines de lignes de trace. Le bilan les
    // résume en une seule, et c'est celle qu'on lit d'abord : durée, nombre de
    // résultats intermédiaires, pic sonore atteint, et texte produit ou non.
    // Une session sans texte AVEC un pic sonore élevé et une session sans texte
    // dans le silence sont deux diagnostics opposés, et cette ligne les sépare
    // sans avoir à parcourir tout ce qui précède.
    private var sessionStartedAtMs = 0L
    private var sessionPartials = 0
    private var sessionMaxRms = -120f

    fun start() {
        if (wanted) return
        wanted = true
        SpeechTrace.record("APP start()", "écoute demandée")
        describeEngine()
        beginListening()
    }

    fun stop() {
        wanted = false
        handler.removeCallbacks(restart)
        handler.removeCallbacks(sessionCap)
        listening = false
        SpeechTrace.record("APP stop()", "écoute arrêtée")
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
        SpeechTrace.record("APP describeEngine", "Android ${Build.VERSION.SDK_INT}")
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
        // Sorties silencieuses tracées : sans ça, une écoute qui ne redémarre
        // jamais ne laisse aucune trace du tout — ni erreur, ni session. C'est
        // le mode de panne le plus difficile à diagnostiquer, parce que le
        // journal s'arrête net sans rien dire.
        if (!wanted) {
            SpeechTrace.record("APP beginListening", "REFUSÉ : écoute non demandée")
            return
        }
        if (listening) {
            SpeechTrace.record("APP beginListening", "REFUSÉ : une session est déjà ouverte")
            return
        }

        val instance = recognizer ?: createRecognizer() ?: run {
            SpeechTrace.record("APP beginListening", "REFUSÉ : aucun moteur construit")
            return
        }
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_COMPLETE_MS)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_POSSIBLY_COMPLETE_MS,
            )
            // EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS n'est plus posé, et son
            // absence est le correctif principal — voir le commentaire de
            // SILENCE_COMPLETE_MS.
        }

        try {
            listening = true
            sessionCount++
            // LA MAIN PASSE AU MOTEUR. L'écart entre cette ligne et la fin de
            // la session précédente est le temps mort qu'on cherche à mesurer.
            SpeechTrace.record(
                "APP → startListening",
                "session n°$sessionCount — silence ${SILENCE_COMPLETE_MS}/${SILENCE_POSSIBLY_COMPLETE_MS} ms, " +
                    "hors-ligne demandé, aucune durée minimale imposée",
            )
            firstPartialLogged = false
            peakRmsSinceLog = -120f
            sessionStartedAtMs = android.os.SystemClock.elapsedRealtime()
            sessionPartials = 0
            sessionMaxRms = -120f
            handler.removeCallbacks(sessionCap)
            handler.postDelayed(sessionCap, MAX_SESSION_MS)
            instance.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Démarrage de l'écoute impossible", e)
            listening = false
            onDiagnostic("Démarrage refusé : ${e.message}")
            scheduleRestart(backoffMs())
        }
    }

    private fun createRecognizer(): SpeechRecognizer? = try {
        SpeechTrace.record("APP createRecognizer", "construction du moteur")
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
        if (recognizer != null) SpeechTrace.record("APP destroyRecognizer", "moteur détruit")
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
        handler.removeCallbacks(sessionCap)
        if (!wanted) {
            SpeechTrace.record("APP relance", "ABANDONNÉE : écoute non demandée")
            return
        }
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delayMs)
        SpeechTrace.record("APP relance dans", "${delayMs} ms")
        onRestart()
    }

    /** Espacement croissant, borné. Ne compte que les vraies erreurs, pas les silences. */
    private fun backoffMs(): Long =
        (RESTART_DELAY_MS shl consecutiveErrors.coerceAtMost(5)).coerceAtMost(MAX_RESTART_DELAY_MS)

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            // LA MAIN EST AU MOTEUR, et il écoute vraiment : entre
            // startListening et ici, il ne captait pas encore.
            readyAtMs = android.os.SystemClock.elapsedRealtime()
            SpeechTrace.record("API onReadyForSpeech", "le moteur écoute")
            // Une session s'est ouverte pour de bon : la précédente n'a donc
            // pas échoué, quoi qu'ait dit la dernière erreur.
            consecutiveErrors = 0
        }

        override fun onPartialResults(partialResults: Bundle?) {
            SpeechTrace.recordResults("API onPartialResults", partialResults)
            if (!firstPartialLogged && firstResult(partialResults) != null) {
                firstPartialLogged = true
                val delay = android.os.SystemClock.elapsedRealtime() - readyAtMs
                // Le délai entre « le moteur écoute » et le premier mot lisible
                // est la seconde source possible de mots perdus, distincte du
                // temps mort entre sessions — lequel s'est révélé négligeable.
                SpeechTrace.record("APP 1er texte après", "$delay ms d'écoute")
            }
            firstResult(partialResults)?.let {
                sessionPartials++
                // La question posée par le diagnostic — le tampon est-il
                // prolongé au lieu d'être remplacé ? — se règle par une mesure
                // plutôt que par une lecture du code. Ce qui est noté ici est
                // la RELATION entre l'ancien texte et le nouveau : le moteur
                // prolonge-t-il sa transcription, la réécrit-il autrement, ou
                // la raccourcit-il ? Le troisième cas est le seul inquiétant.
                val relation = when {
                    lastPartial.isEmpty() -> "premier"
                    it == lastPartial -> "identique"
                    it.startsWith(lastPartial) -> "prolongé (+${it.length - lastPartial.length} car.)"
                    lastPartial.startsWith(it) -> "RACCOURCI (-${lastPartial.length - it.length} car.)"
                    else -> "RÉÉCRIT (${lastPartial.length} → ${it.length} car.)"
                }
                SpeechTrace.record("APP tampon partiel", "$relation")
                lastPartial = it
                onPartial(it)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            // LA MAIN REVIENT À L'APPLICATION.
            SpeechTrace.recordResults("API onResults", results)
            endSession(if (firstResult(results) != null) "texte rendu" else "AUCUN TEXTE rendu")
            // À défaut de résultat final, le dernier partiel fait foi : il a
            // été affiché, il a donc été lu, et le faire disparaître serait
            // pire que de le figer tel quel.
            commit(firstResult(results) ?: lastPartial)
            // Sans délai : c'est le cas normal, et chaque milliseconde ici est
            // un mot que le moteur n'entend pas. Passer par le fil principal
            // suffit à laisser la session précédente se refermer.
            scheduleRestart(0L)
        }

        override fun onError(error: Int) {
            listening = false
            // LA MAIN REVIENT À L'APPLICATION, sans résultat.
            SpeechTrace.record("API onError", "${describeError(error)} (code $error)")
            endSession("erreur : ${describeError(error)}")
            when (error) {
                // Les deux façons de dire « personne n'a parlé ». Ce sont les
                // erreurs les plus fréquentes en écoute continue, et de loin :
                // les compter comme des pannes ferait grandir la temporisation
                // jusqu'à l'arrêt, dans une pièce simplement silencieuse.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // « Rien compris » arrive aussi APRÈS plusieurs secondes de
                    // dictée déjà affichée. Le partiel est alors tout ce qui
                    // reste de ces mots-là.
                    commit(lastPartial)
                    scheduleRestart(0L)
                }

                // L'instance ne sort pas seule de cet état : la relancer rend
                // la même erreur indéfiniment.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    commit(lastPartial)
                    consecutiveErrors++
                    destroyRecognizer()
                    onDiagnostic("Moteur reconstruit (${describeError(error)})")
                    scheduleRestart(backoffMs())
                }

                else -> {
                    commit(lastPartial)
                    consecutiveErrors++
                    onDiagnostic(describeError(error))
                    scheduleRestart(backoffMs())
                }
            }
        }

        // Le moteur signale la fin de la parole avant de rendre son résultat.
        // Rien à faire ici : onResults ou onError suit immédiatement, et agir
        // aux deux endroits relancerait deux sessions concurrentes.
        override fun onEndOfSpeech() {
            SpeechTrace.record("API onEndOfSpeech", "fin de parole détectée")
        }

        override fun onBeginningOfSpeech() {
            SpeechTrace.record("API onBeginningOfSpeech", "début de parole détecté")
        }
        override fun onRmsChanged(rmsdB: Float) {
            // Échantillonné : ce rappel arrive une dizaine de fois par seconde,
            // et tout journaliser rendrait la trace illisible. C'est le PIC de
            // l'intervalle qui est retenu, pas la dernière valeur : entre deux
            // relevés, ce qui compte est de savoir si quelque chose a été
            // entendu, pas ce qu'il en restait à la fin.
            if (rmsdB > peakRmsSinceLog) peakRmsSinceLog = rmsdB
            if (rmsdB > sessionMaxRms) sessionMaxRms = rmsdB
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastRmsLogAtMs < RMS_SAMPLE_MS) return
            lastRmsLogAtMs = now
            SpeechTrace.record("API onRmsChanged", "pic %.1f dB".format(peakRmsSinceLog))
            peakRmsSinceLog = -120f
        }
        // Ces deux-là sont presque toujours muets, et c'est précisément
        // pourquoi ils sont tracés : le jour où l'un d'eux parle, il faut le
        // savoir plutôt que de le découvrir en relisant la documentation.
        override fun onBufferReceived(buffer: ByteArray?) {
            SpeechTrace.record("API onBufferReceived", "${buffer?.size ?: 0} octets")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            SpeechTrace.record("API onEvent", "type $eventType")
        }
    }

    /**
     * Clôt l'énoncé en cours. Quel que soit le chemin par lequel une session se
     * termine — résultat, silence, erreur — il passe par ici : c'est la seule
     * façon de garantir qu'aucun texte affiché ne disparaisse jamais.
     */
    /**
     * Résume la session qui s'achève, en une ligne lisible seule.
     *
     * Appelé sur TOUS les chemins de fin — résultat, erreur, clôture forcée —
     * pour qu'aucune session ne s'achève sans bilan. Une session manquante dans
     * le récapitulatif signifierait alors quelque chose, au lieu de se
     * confondre avec un oubli d'instrumentation.
     */
    private fun endSession(outcome: String) {
        val duration = (android.os.SystemClock.elapsedRealtime() - sessionStartedAtMs) / 1000.0
        SpeechTrace.record(
            "APP bilan session n°$sessionCount",
            String.format(
                java.util.Locale.FRANCE,
                "%.1f s, %d partiels, pic %.1f dB → %s",
                duration, sessionPartials, sessionMaxRms, outcome,
            ),
        )
    }

    private fun commit(text: String) {
        val hadPartial = lastPartial
        lastPartial = ""
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            // Rien à figer. Noté quand même : si un partiel avait été affiché
            // et se retrouve ici sans être validé, c'est du texte que l'écran
            // a montré puis perdu — et la trace le désigne nommément.
            SpeechTrace.record(
                "APP commit",
                if (hadPartial.isEmpty()) "rien à figer" else "RIEN FIGÉ alors qu'un partiel existait : « $hadPartial »",
            )
            return
        }
        SpeechTrace.record("APP commit", "ligne figée : « $trimmed »")
        onFinal(trimmed)
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
         * Silences au terme desquels le moteur clôt son énoncé.
         *
         * ═══ Ces valeurs sortent d'une mesure, et corrigent une erreur ═══
         *
         * La version précédente demandait dix secondes de silence, et surtout
         * posait EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS à soixante secondes.
         * Cet extra signifie « n'arrête pas d'enregistrer avant ce délai » : le
         * moteur était donc contraint de tenir chaque session une minute
         * entière. La trace relevée sur l'appareil le montre sans ambiguïté —
         * sessions de 60,8 s et 60,6 s avant le moindre résultat.
         *
         * Le raisonnement qui avait conduit là — « plus la session dure, moins
         * il y a de coutures » — était juste à l'envers. Une session longue,
         * c'est un texte figé une minute trop tard, et surtout un résultat
         * partiel qui accumule TOUT ce qui a été dit depuis son début : la même
         * phrase répétée deux fois s'affichait deux fois à la suite, ce qui
         * ressemblait à un défaut de notre tampon alors que la concaténation se
         * faisait à l'intérieur du moteur.
         *
         * Une seconde et demie ferme l'énoncé sur une vraie fin de phrase sans
         * couper une hésitation. La relance ne coûte rien : mesurée à 3 et 9
         * millisecondes sur cet appareil.
         */
        const val SILENCE_COMPLETE_MS = 1_500
        const val SILENCE_POSSIBLY_COMPLETE_MS = 1_000

        /**
         * Au-delà, on referme la session nous-mêmes.
         *
         * Filet contre la pathologie ci-dessus : rien ne garantit qu'un autre
         * appareil, ou une autre version du moteur, respecte les consignes de
         * silence. Une session qui ne se termine jamais est indiscernable d'un
         * moteur en panne, et le texte reste bloqué en attente pendant ce
         * temps. stopListening() et non cancel() : le premier réclame le
         * résultat de ce qui a été entendu, le second le jetterait.
         */
        const val MAX_SESSION_MS = 20_000L

        /** Une mesure de niveau par quart de seconde : assez pour distinguer un silence d'une voix faible, sans noyer la trace. */
        const val RMS_SAMPLE_MS = 250L
    }
}
