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
 * Les deux régimes de séquencement, choisis au bouton et jamais mélangés.
 *
 * Une session d'essai n'en applique qu'un seul, du début à la fin. C'est la
 * correction d'une alternance automatique qui s'est révélée fausse : les deux
 * régimes n'ont aucune raison de durer aussi longtemps l'un que l'autre, et
 * alterner une session sur deux aurait donné quelques secondes au premier
 * contre deux minutes au second — 4 % de la parole contre 96 %. Le déséquilibre
 * venait de la question même que l'expérience pose.
 */
enum class SequencingMode(val label: String) {
    /**
     * OBSERVATION PURE. Le moteur fait tout, nous ne faisons que regarder.
     *
     * Aucune consigne de silence, AUCUN PLAFOND, aucun stopListening de notre
     * part, aucun figeage déclenché par une fin ou un début de parole. La
     * phrase est écrite quand le moteur rend son onResults, et la session
     * relancée quand il l'a close lui-même.
     *
     * Le plafond de secours a été retiré volontairement. Il rendait
     * l'observation impure : une session close par nous au bout d'une minute
     * ne dit rien de ce que le moteur aurait fait. Si la session ne finit
     * jamais, c'est LE résultat, et la trace le montrera par son silence même
     * — aucune ligne de clôture, aucune relance, une session qui court.
     *
     * Les résultats intermédiaires restent demandés. C'est de l'observation,
     * pas une intervention : ils ne changent pas le séquencement, ils le
     * rendent visible.
     */
    API_PURE("API pure"),

    /** Notre découpage : consignes de silence, plafond, figeage sur la séquence fin puis début. */
    EVENTS("événements"),
}

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
    /** Régime appliqué pendant toute la vie de cet objet. Changer de mode en construit un neuf. */
    private val mode: SequencingMode,
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

    /**
     * Le moteur a-t-il signalé une fin de parole depuis la dernière ligne
     * figée ?
     *
     * La moitié de la condition de frontière. Un début de parole seul ne
     * prouve rien : le moteur en émet cent vingt en deux minutes, dont deux
     * en pleine phrase, et figer sur chacun couperait celle-ci en deux. C'est
     * la SÉQUENCE fin de parole puis début de parole qui marque une frontière,
     * et elle ne s'est jamais trompée sur la trace mesurée.
     */
    private var endOfSpeechSeen = false

    /** Vrai en mode API pure : raccourci de lecture pour tout ce qui suit. */
    private val pureApi get() = mode == SequencingMode.API_PURE

    /** Instant du dernier onEndOfSpeech, pour mesurer l'attente du moteur avant sa clôture. */
    private var lastEndOfSpeechAtMs = 0L

    /**
     * La dernière ligne figée. Sert à ne pas la figer deux fois : la clôture
     * d'une session peut rendre un texte déjà figé au fil de l'eau, et Jean
     * verrait alors la même phrase deux fois.
     */
    private var lastCommitted = ""

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

    /** Lignes réellement figées pendant cette session. */
    private var sessionCommits = 0

    /**
     * Délai entre « le moteur écoute » et le premier texte lisible de CETTE
     * session, en millisecondes. -1 tant qu'aucun texte n'est venu.
     *
     * Mesuré par session et publié au bilan, parce que la question ouverte n'a
     * de réponse que là. Six secondes ont été observées au tout premier usage,
     * ce qui s'explique par un modèle qui se charge. Mais la même trace montre
     * que TOUTES les sessions sont tuées par la clôture forcée à vingt
     * secondes, jamais par une fin naturelle — et si ce délai se reproduisait à
     * chaque session, ce plafond nous rendrait sourds six secondes toutes les
     * vingt. Un tiers du temps d'écoute perdu par une décision qui était censée
     * protéger.
     *
     * Une colonne dans le bilan tranche entre les deux : un délai élevé sur la
     * seule première session accuse le chargement du modèle, un délai élevé
     * partout accuse le plafond.
     */
    private var sessionFirstTextMs = -1L

    // --- Mode segmenté : essayé, mesuré, écarté -----------------------------
    //
    // EXTRA_SEGMENTED_SESSION (Android 13) demandait au moteur de déclarer
    // lui-même ses frontières d'énoncé, au lieu qu'on les devine au contenu.
    // Il a été mis à l'essai une session sur deux, pour que les deux régimes
    // se retrouvent dans la même trace, sur la même voix et à quelques
    // secondes d'intervalle — la seule comparaison qui vaille.
    //
    // LA RÉPONSE EST NON, et elle est mesurée. Le mode fonctionne bien sur ce
    // moteur : cinquante-quatre segments reçus, la question du support est
    // close. Mais quarante-deux d'entre eux arrivent VIDES — quatre sur cinq.
    // La période est celle du détecteur de voix du moteur, six dixièmes de
    // seconde : ce ne sont pas des frontières d'énoncé, c'est son clignotement
    // rendu tel quel.
    //
    // Le dégât se lit en clair dans la trace. « il y a des problèmes » y est
    // coupé en deux lignes, à 121,8 s puis 123,1 s, au milieu du groupe
    // nominal — là où le découpage par le contenu aurait vu un simple
    // prolongement. À nombre de mots par ligne identique (6,3 contre 6,2),
    // l'écart-type passe de 2,3 à 5,9 : le mode segmenté produit à la fois des
    // mots isolés et des pavés de vingt-trois mots. Il ne découpe pas plus
    // finement, il découpe au hasard.
    //
    // Il coûtait en outre un plafond de session, dont on vient de se
    // débarrasser pour la raison exposée sous MAX_SESSION_MS.

    /**
     * Le bilan a-t-il déjà été émis pour cette session ?
     *
     * La clôture forcée en émettait un, puis le onResults qu'elle provoque en
     * émettait un second : deux bilans pour une session, aux chiffres
     * légèrement différents, ce qui donnait l'impression de deux sessions
     * imbriquées.
     */
    private var sessionSummarised = false

    fun start() {
        if (wanted) return
        wanted = true
        SpeechTrace.record("APP start()", "écoute demandée")
        describeEngine()
        // ═══ Pourquoi la première écoute attend ═══
        //
        // Changer de régime détruit ce gestionnaire et en construit un autre.
        // La destruction de l'ancien moteur et la création du nouveau
        // tombaient alors dans la même image, et le service de reconnaissance
        // n'avait pas fini de se refermer quand on lui redemandait une
        // instance — d'où la connexion perdue immédiatement, sept fois de
        // suite, observée en rev17.
        //
        // La reconstruction sur erreur, ajoutée juste au-dessus, suffirait à
        // s'en relever en une demi-seconde. Ce délai évite d'avoir à s'en
        // relever : il ne coûte qu'un tiers de seconde, une seule fois par
        // bascule, et il n'écrit aucune erreur dans la trace — ce qui compte
        // sur un instrument de mesure, où chaque ligne fausse se paie plus
        // tard en temps de lecture.
        handler.postDelayed({ if (wanted) beginListening() }, FIRST_LISTEN_DELAY_MS)
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

            // Consignes de durée de silence, posées SEULEMENT quand c'est nous
            // qui séquençons. En mode API elles sont tues : lui dicter quand se
            // taire, c'est déjà lui retirer la décision qu'on veut précisément
            // lui laisser prendre, et l'expérience ne prouverait plus rien.
            //
            // Le moteur les traite de toute façon comme des souhaits et non
            // comme des ordres — la documentation le dit, et les appareils le
            // confirment. Là où elles sont suivies, elles rallongent la session
            // et espacent donc les coutures.
            if (!pureApi) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_COMPLETE_MS)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SILENCE_POSSIBLY_COMPLETE_MS,
                )
            }
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
                "session n°$sessionCount — " +
                    (if (pureApi) "OBSERVATION PURE DE L'API : aucune consigne de silence, " +
                        "AUCUN PLAFOND, figeage sur onResults seul"
                    else "séquencement par les événements : silence " +
                        "${SILENCE_COMPLETE_MS}/${SILENCE_POSSIBLY_COMPLETE_MS} ms, " +
                        "plafond ${MAX_SESSION_MS / 1000} s") +
                    ", hors-ligne demandé",
            )
            firstPartialLogged = false
            peakRmsSinceLog = -120f
            sessionStartedAtMs = android.os.SystemClock.elapsedRealtime()
            sessionPartials = 0
            sessionCommits = 0
            sessionFirstTextMs = -1L
            sessionMaxRms = -120f
            sessionSummarised = false
            lastEndOfSpeechAtMs = 0L
            handler.removeCallbacks(sessionCap)
            // Rien à armer en observation pure : aucune minuterie de notre part
            // ne doit pouvoir interrompre le moteur, sans quoi ce qu'on mesure
            // n'est plus lui mais nous.
            if (!pureApi) handler.postDelayed(sessionCap, MAX_SESSION_MS)
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
                sessionFirstTextMs = delay
                // Le délai entre « le moteur écoute » et le premier mot lisible
                // est la seconde source possible de mots perdus, distincte du
                // temps mort entre sessions — lequel s'est révélé négligeable.
                SpeechTrace.record("APP 1er texte après", "$delay ms d'écoute")
            }
            firstResult(partialResults)?.let {
                sessionPartials++
                // Le moteur préfixe volontiers ses résultats d'une espace :
                // « second mot » arrive en «  second mot ». Nettoyé ici une
                // fois pour toutes, faute de quoi l'écart se verrait à
                // l'écran et fausserait toute comparaison de textes.
                val current = it.trim()
                if (current.isEmpty()) return@let

                // ═══ LE TAMPON EST REMPLACÉ, JAMAIS FIGÉ ICI ═══
                //
                // C'est tout ce que fait désormais ce rappel. Les partiels
                // sont faits pour se remplacer les uns les autres — la trace
                // le montre sans exception — et aucun d'eux ne dit s'il est
                // le dernier. Leur demander de trancher revenait à deviner.
                //
                // La décision appartient maintenant aux deux événements du
                // moteur, qui la portent réellement : voir onEndOfSpeech et
                // onBeginningOfSpeech.
                //
                // La RELATION avec le texte précédent reste journalisée. Elle
                // ne commande plus rien, mais c'est elle qui a permis de
                // comprendre le fonctionnement du moteur, et c'est encore par
                // elle qu'on verra si un autre appareil se comporte
                // autrement.
                val previous = lastPartial.trim()
                SpeechTrace.record(
                    "APP tampon partiel",
                    when {
                        previous.isEmpty() -> "premier"
                        current == previous -> "identique"
                        current.startsWith(previous) -> "prolongé (+${current.length - previous.length} car.)"
                        previous.startsWith(current) -> "RACCOURCI (-${previous.length - current.length} car.)"
                        else -> "réécrit (${previous.length} → ${current.length} car.)"
                    },
                )
                lastPartial = current
                onPartial(current)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            // LA MAIN REVIENT À L'APPLICATION.
            SpeechTrace.recordResults("API onResults", results)
            // Spontané ou arraché ? En mode API c'est TOUTE la question, et
            // sans cette distinction la trace montrerait un onResults sans
            // dire s'il vient du moteur ou de notre stopListening — deux
            // conclusions opposées sous la même ligne.
            SpeechTrace.record(
                "APP origine de la clôture",
                if (!pureApi) "mode événements : notre plafond a pu intervenir"
                else "SPONTANÉE : le moteur a clos de lui-même" +
                    (if (lastEndOfSpeechAtMs > 0)
                        ", ${android.os.SystemClock.elapsedRealtime() - lastEndOfSpeechAtMs} ms " +
                            "après la dernière fin de parole"
                    else ""),
            )
            // « l'API ne rend rien » et « la session n'a rien produit » sont
            // deux choses différentes depuis que les lignes sont figées en
            // cours de route. Le libellé précédent les confondait, et laissait
            // croire à une session stérile alors qu'elle avait produit huit
            // lignes.
            endSession(
                if (firstResult(results) != null) "l'API rend un texte final"
                else "l'API ne rend aucun texte final"
            )
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
                    // RECONSTRUCTION, et c'est le correctif de la panne
                    // observée en rev17 : sept démarrages sur sept refusés en
                    // moins de quinze millisecondes, avec l'erreur 11.
                    //
                    // Cette branche comptait l'échec et reprogrammait une
                    // relance — sur la MÊME instance. Or l'erreur 11 est
                    // « connexion au service perdue » : l'objet est mort, et
                    // le relancer rend la même erreur indéfiniment. La
                    // temporisation grandissait jusqu'à huit secondes sans
                    // jamais rien réparer, et vingt-trois secondes de parole
                    // sont passées sans qu'un seul flux audio soit ouvert —
                    // le pic de −120 dB du bilan n'était pas un silence, mais
                    // l'absence de toute capture.
                    //
                    // Mon propre commentaire, deux branches plus haut, disait
                    // déjà « l'instance ne sort pas seule de cet état » à
                    // propos du moteur occupé. La bonne règle est plus large :
                    // une erreur qu'on ne sait pas nommer est précisément le
                    // cas où l'on ignore si l'instance est encore saine.
                    // Reconstruire coûte une poignée de millisecondes ;
                    // s'abstenir a coûté toute la session d'essai.
                    commit(lastPartial)
                    consecutiveErrors++
                    destroyRecognizer()
                    onDiagnostic("Moteur reconstruit (${describeError(error)})")
                    scheduleRestart(backoffMs())
                }
            }
        }

        // onSegmentResults et onEndOfSegmentedSession ne sont volontairement
        // PAS redéfinis. Ils l'ont été le temps de l'essai, et la mesure a
        // tranché contre le mode segmenté — voir le bloc en tête de classe.
        // Les laisser en place sans demander le mode reviendrait à garder du
        // code que rien n'appelle, et à faire croire à un mécanisme actif.

        // Le moteur signale la fin de la parole avant de rendre son résultat.
        // Rien à faire ici : onResults ou onError suit immédiatement, et agir
        // aux deux endroits relancerait deux sessions concurrentes.
        /**
         * Le moteur a fini de décoder sa bouffée. NE FIGE RIEN ENCORE.
         *
         * Il arrive qu'un dernier partiel suive cet événement — la « vidange »
         * de la phrase qui s'achève. Sur les huit cas observés il était sept
         * fois identique au précédent et n'a jamais rien ajouté, mais c'est un
         * appareil et une trace : attendre coûte moins cher que de trancher
         * sur huit observations.
         *
         * Cet événement arme donc simplement l'autorisation de figer, que le
         * prochain début de parole consommera.
         */
        override fun onEndOfSpeech() {
            lastEndOfSpeechAtMs = android.os.SystemClock.elapsedRealtime()
            SpeechTrace.record("API onEndOfSpeech", "fin de parole détectée")
            // Armée dans les deux régimes, mais consommée seulement par le
            // nôtre : en mode API l'instant sert uniquement à mesurer combien
            // de temps le moteur attend ensuite avant de clore.
            endOfSpeechSeen = true
        }

        /**
         * L'attaque de la phrase suivante. C'EST LUI QUI FIGE la précédente.
         *
         * ═══ Pourquoi ces deux événements, et plus aucune minuterie ═══
         *
         * Les partiels sont faits pour se remplacer les uns les autres, et
         * aucun ne dit qu'il est le dernier. Toute tentative de le deviner —
         * par la durée d'un silence, puis par la comparaison des contenus — a
         * échoué sur un point ou sur un autre : la minuterie faisait attendre
         * deux secondes une phrase dite en trois cents millisecondes, et la
         * comparaison de contenus prenait des auto-corrections du moteur pour
         * de nouvelles phrases (elle a produit les fragments « déjà » et
         * « bon déjà il »).
         *
         * Le moteur, lui, sait. Sur la trace mesurée, les vingt-quatre
         * ruptures se répartissent sans une seule erreur : les quinze
         * précédées d'un début de parole sont de vraies nouvelles phrases, les
         * neuf autres sont toutes des auto-corrections — « CAD » → « cadet »,
         * « il n'y a pas » → « il y a pas ». Deux fausses alertes seulement
         * sur deux cent treize continuations.
         *
         * LA FIN DE PAROLE EST EXIGÉE avant de figer. Sans elle, les deux
         * débuts qui tombent en pleine phrase couperaient celle-ci en deux ;
         * avec elle, ils sont sans effet. Un début de parole seul ne prouve
         * rien — c'est la séquence fin PUIS début qui fait une frontière.
         */
        override fun onBeginningOfSpeech() {
            SpeechTrace.record("API onBeginningOfSpeech", "début de parole détecté")
            // En mode API on ne touche à rien : c'est onResults qui figera,
            // s'il vient. Toute intervention ici retirerait au moteur la
            // décision qu'on veut lui laisser prendre, et l'expérience ne
            // prouverait plus rien.
            if (pureApi) return
            if (lastPartial.isEmpty() || !endOfSpeechSeen) return
            SpeechTrace.record("APP frontière", "fin de parole puis début : phrase close")
            commit(lastPartial)
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
        if (sessionSummarised) return
        sessionSummarised = true
        val duration = (android.os.SystemClock.elapsedRealtime() - sessionStartedAtMs) / 1000.0
        SpeechTrace.record(
            "APP bilan session n°$sessionCount",
            String.format(
                java.util.Locale.FRANCE,
                "%s — %.1f s, 1er texte %s, %d partiels, " +
                    "%d ligne(s) figée(s), pic %.1f dB, %s — fin : %s",
                if (pureApi) "API PURE" else "ÉVÉNEMENTS",
                duration,
                if (sessionFirstTextMs >= 0) "${sessionFirstTextMs} ms" else "JAMAIS",
                sessionPartials, sessionCommits, sessionMaxRms,
                // Combien de temps le moteur a laissé passer entre sa dernière
                // fin de parole et la clôture de la session. C'est la mesure
                // qui dira si son silence de clôture est atteignable, et à
                // quel prix en attente.
                if (lastEndOfSpeechAtMs > 0)
                    "clôture ${android.os.SystemClock.elapsedRealtime() - lastEndOfSpeechAtMs} ms " +
                        "après la dernière fin de parole"
                else "aucune fin de parole signalée",
                outcome,
            ),
        )
    }

    private fun commit(text: String) {
        endOfSpeechSeen = false
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
        if (trimmed == lastCommitted) {
            SpeechTrace.record("APP commit", "IGNORÉ, déjà figé : « $trimmed »")
            return
        }
        lastCommitted = trimmed
        sessionCommits++
        SpeechTrace.record("APP commit", "ligne figée : « $trimmed »")
        onFinal(trimmed)
    }

    /**
     * Combien de mots les deux textes partagent depuis leur début.
     *
     * C'est la mesure qui sépare une correction du moteur d'un changement
     * d'énoncé, et elle est plus sûre que la durée de la pause : une révision
     * garde le début de sa phrase, un nouvel énoncé ne partage rien.
     *
     * Comparaison insensible à la casse : le moteur capitalise parfois le
     * premier mot d'une phrase après coup, et un « Bonjour » succédant à
     * « bonjour » passerait sinon pour une rupture.
     */
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
        // Valeurs littérales plutôt que constantes : celles-ci datent d'Android
        // 12 et 13, au-delà du minimum visé, et une constante d'API récente
        // référencée ici ferait broncher l'analyse statique pour un simple
        // libellé. Les noms de plate-forme sont cités en clair à la place.
        ERROR_TOO_MANY_REQUESTS -> "trop de demandes (ERROR_TOO_MANY_REQUESTS)"
        ERROR_SERVER_DISCONNECTED -> "connexion au service perdue (ERROR_SERVER_DISCONNECTED)"
        ERROR_LANGUAGE_NOT_SUPPORTED -> "langue non prise en charge — modèle français à installer"
        ERROR_LANGUAGE_UNAVAILABLE -> "langue indisponible — modèle français à télécharger"
        else -> "erreur $error"
    }

    private companion object {
        const val TAG = "ContinuousSpeech"
        const val LANGUAGE = "fr-FR"

        /** Laisse le service se refermer avant qu'on lui redemande une instance. */
        const val FIRST_LISTEN_DELAY_MS = 300L

        // Codes d'erreur de SpeechRecognizer postérieurs au minimum visé.
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13

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
         *
         * ═══ Passé de vingt secondes à deux minutes, et voici pourquoi ═══
         *
         * Vingt secondes coûtaient cher, et la trace le chiffre. Après chaque
         * relance, le moteur reste de deux à huit secondes sans rendre un mot,
         * alors même qu'il signale de la parole et qu'on l'entend : il se
         * réchauffe. Sur les sessions où quelqu'un parlait vraiment pendant ce
         * délai, cela fait dix-neuf secondes et demie perdues sur cent
         * trente-cinq — un septième de la parole, jamais transcrite.
         *
         * Or les sept sessions mesurées sont mortes du plafond ou d'une fin
         * segmentée, aucune d'une fin de parole naturelle. Le plafond n'était
         * donc pas un filet : c'était le mécanisme ORDINAIRE de fin de
         * session, et il fabriquait une couture toutes les vingt secondes.
         *
         * CE QUI RESTE VRAI, ET CE QUI NE L'EST PLUS. Une mesure ultérieure,
         * moteur livré à lui-même — sans consigne de silence ni plafond —
         * montre qu'il clôt sa session spontanément, quelques millisecondes
         * après sa dernière fin de parole. Il sait donc conclure.
         *
         * Ce qu'on ignore encore, c'est s'il le sait AVEC nos consignes de
         * silence : le régime « événements » n'a jamais pu être observé, sa
         * première mesure ayant échoué sur une panne de reconstruction du
         * moteur. Le plafond reste donc en place, comme filet et non comme
         * mécanisme — mais il n'est plus exclu qu'il ne serve plus à rien.
         *
         * Il ne coûtait rien tant que le texte n'était figé qu'à la fin d'une
         * session — le raccourcir était même la façon de voir du texte
         * s'inscrire. Depuis que les lignes se figent au fil de l'eau, sur
         * rupture de contenu et sur inactivité, une session longue n'a plus
         * aucun inconvénient : le texte sort pareil, avec six fois moins de
         * trous.
         *
         * Deux minutes et non « jamais », parce que la raison d'être du filet
         * tient toujours : un moteur bloqué doit finir par être secoué. Six
         * fois moins de coutures est un gain mesurable ; supprimer le filet
         * serait un pari.
         */
        const val MAX_SESSION_MS = 120_000L

        /** Une mesure de niveau par quart de seconde : assez pour distinguer un silence d'une voix faible, sans noyer la trace. */
        const val RMS_SAMPLE_MS = 250L


    }
}
