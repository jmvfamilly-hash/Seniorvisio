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

    /**
     * Le moteur a-t-il signalé une fin de parole depuis que le texte en cours a
     * été prolongé pour la dernière fois ?
     *
     * Garde-fou contre un découpage abusif. Un texte qui change sans être la
     * suite du précédent signifie presque toujours que le moteur est passé à
     * l'énoncé suivant — mais pas toujours : il lui arrive de se corriger en
     * pleine phrase, « bonjour comment » devenant « bon jour comment ». Exiger
     * qu'une fin de parole soit passée entre les deux sépare les deux cas.
     */
    private var endOfSpeechSeen = false

    /**
     * La dernière ligne figée. Sert à ne pas la figer deux fois : la clôture
     * d'une session peut rendre un texte déjà figé au fil de l'eau, et Jean
     * verrait alors la même phrase deux fois.
     */
    private var lastCommitted = ""

    /**
     * Fige le texte en cours quand il cesse d'évoluer.
     *
     * Sans lui, le dernier énoncé avant un vrai silence ne serait jamais figé :
     * rien ne vient plus le remplacer, et la session ne se termine pas. Il
     * resterait indéfiniment en italique, ni acquis ni effacé.
     */
    private val idleCommit = Runnable {
        if (lastPartial.isEmpty()) return@Runnable
        SpeechTrace.record("APP inactivité", "texte figé faute d'évolution")
        commit(lastPartial)
    }

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

    // --- Mode segmenté, à l'essai -------------------------------------------
    //
    // EXTRA_SEGMENTED_SESSION (Android 13) demande au moteur de rendre ses
    // résultats par segments PENDANT une session, via onSegmentResults — c'est
    // exactement ce que le découpage par le contenu reconstruit à la main. S'il
    // fonctionne, le moteur déclare lui-même ses frontières au lieu qu'on les
    // devine.
    //
    // Mis à l'essai UNE SESSION SUR DEUX plutôt qu'activé d'emblée. Les deux
    // modes se retrouvent ainsi dans la même trace, sur la même voix et dans la
    // même pièce, à quelques secondes d'intervalle : c'est la seule comparaison
    // qui vaille, et c'est celle qu'on s'est imposée pour les moteurs de
    // transcription comme pour ceux de locuteur.
    //
    // Deux raisons de ne rien retirer en attendant. La documentation prévient
    // que « selon l'implémentation, cette valeur peut n'avoir aucun effet » —
    // un extra ignoré ne produit aucune erreur, et le silence ressemblerait à
    // un succès. Et déléguer les frontières revient à s'en remettre au
    // détecteur de voix du moteur, que la trace montre clignotant toutes les
    // six dixièmes de seconde : il pourrait découper bien plus mal que nous.
    private var segmentedRequested = false
    private var sessionSegments = 0

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
        beginListening()
    }

    fun stop() {
        wanted = false
        handler.removeCallbacks(restart)
        handler.removeCallbacks(sessionCap)
        handler.removeCallbacks(idleCommit)
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
        // Une session sur deux en mode segmenté, pour que la trace contienne
        // les deux comportements côte à côte. Décidé avant la construction de
        // l'intention, jamais après : le mode doit figurer dans la ligne de
        // démarrage, faute de quoi on ne saurait pas à quel régime rattacher ce
        // qui suit.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            segmentedRequested = !segmentedRequested
        }

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

            if (segmentedRequested) {
                // La valeur de cet extra est le NOM d'un autre extra, qui
                // définit la fin de la session. Trois sont admis ; deux ne
                // conviennent pas ici — l'un suppose un fichier audio, l'autre
                // s'en remet à la détection de silence que la trace a montrée
                // défaillante. Reste la durée, qui est prévisible.
                //
                // Ironie mesurée : c'est l'extra même qui avait gelé le texte
                // pendant soixante secondes. Mal employé il imposait une durée
                // minimale sans rien rendre en chemin ; ici il borne une
                // session dont les segments tombent au fil de l'eau.
                //
                // Volontairement plus court que la clôture forcée, pour que les
                // deux ne se disputent pas la fin de session — et si le mode
                // segmenté est ignoré, la dégradation est bornée : une session
                // de quinze secondes, que le découpage par le contenu alimente
                // de toute façon.
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    SEGMENTED_SESSION_MS,
                )
            }
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
                "session n°$sessionCount — " +
                    (if (segmentedRequested) "MODE SEGMENTÉ demandé (${SEGMENTED_SESSION_MS} ms) — "
                    else "mode classique — ") +
                    "silence ${SILENCE_COMPLETE_MS}/${SILENCE_POSSIBLY_COMPLETE_MS} ms, hors-ligne demandé",
            )
            firstPartialLogged = false
            peakRmsSinceLog = -120f
            sessionStartedAtMs = android.os.SystemClock.elapsedRealtime()
            sessionPartials = 0
            sessionCommits = 0
            sessionSegments = 0
            sessionFirstTextMs = -1L
            sessionMaxRms = -120f
            sessionSummarised = false
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
                sessionFirstTextMs = delay
                // Le délai entre « le moteur écoute » et le premier mot lisible
                // est la seconde source possible de mots perdus, distincte du
                // temps mort entre sessions — lequel s'est révélé négligeable.
                SpeechTrace.record("APP 1er texte après", "$delay ms d'écoute")
            }
            firstResult(partialResults)?.let {
                sessionPartials++
                handler.removeCallbacks(idleCommit)
                handler.postDelayed(idleCommit, IDLE_COMMIT_MS)
                // La question posée par le diagnostic — le tampon est-il
                // prolongé au lieu d'être remplacé ? — se règle par une mesure
                // plutôt que par une lecture du code. Ce qui est noté ici est
                // la RELATION entre l'ancien texte et le nouveau : le moteur
                // prolonge-t-il sa transcription, la réécrit-il autrement, ou
                // la raccourcit-il ? Le troisième cas est le seul inquiétant.
                // Comparaison sur du texte nettoyé. La trace a montré le
                // piège : « second mot » devenant «  second mot » — une simple
                // espace en tête — était classé comme une réécriture, ce qui
                // aurait fait couper la phrase en deux.
                val previous = lastPartial.trim()
                val current = it.trim()

                // Écho d'une ligne qu'on vient de figer. Le moteur répète
                // volontiers le même texte — la trace en montre plusieurs
                // occurrences consécutives à l'identique — et après une ligne
                // figée par inactivité, cette répétition réapparaîtrait en
                // dessous, en italique, doublant à l'écran ce qui est déjà
                // acquis juste au-dessus.
                if (lastPartial.isEmpty() && current == lastCommitted) {
                    SpeechTrace.record("APP partiel ignoré", "écho de la ligne déjà figée")
                    return@let
                }

                // Prolongé par la fin, mais aussi par le DÉBUT. L'analyse
                // disait « ne prolonge pas ET NE CONTIENT PAS l'ancien
                // tampon » ; je n'en avais retenu que la première moitié.
                //
                // Le moteur préfixe parfois son hypothèse en la re-décodant
                // avec plus de contexte : « second mot » devient « voilà second
                // mot ». Aucun mot de tête ne coïncide alors, l'ancien serait
                // figé, et la version complète le serait à son tour — la même
                // phrase deux fois à l'écran.
                //
                // MAIS « contient » tout court est trop large, et le prendre au
                // pied de la lettre ouvrirait un trou plus grand que celui
                // qu'il bouche. Un tampon de trois lettres — « oui », « non »,
                // « bon » — se retrouve à l'intérieur de presque n'importe quel
                // énoncé suivant ; le figeage ne se déclencherait alors plus
                // JAMAIS après un mot isolé, et ce mot serait perdu à chaque
                // fois. C'est précisément le défaut qu'on répare.
                //
                // Deux formes sont donc distinguées. Le préfixage est sûr et
                // sans condition : l'ancien tampon est un SUFFIXE du nouveau,
                // ce qui décrit exactement le re-décodage avec plus de contexte
                // et n'arrive pas par hasard. L'inclusion au milieu, elle, ne
                // vaut qu'au-delà d'une longueur où la coïncidence cesse d'être
                // vraisemblable.
                val containedInMiddle = previous.length >= MIN_CONTAINED_CHARS &&
                    current.contains(previous)
                val continues = previous.isEmpty() ||
                    current.startsWith(previous) ||
                    current.endsWith(previous) ||
                    containedInMiddle
                val relation = when {
                    previous.isEmpty() -> "premier"
                    current == previous -> "identique"
                    current.startsWith(previous) -> "prolongé (+${current.length - previous.length} car.)"
                    current.endsWith(previous) -> "préfixé (+${current.length - previous.length} car. en tête)"
                    containedInMiddle -> "englobé (+${current.length - previous.length} car. autour)"
                    current.contains(previous) -> "inclus mais trop court (${previous.length} car.) — traité en rupture"
                    previous.startsWith(current) -> "RACCOURCI (-${previous.length - current.length} car.)"
                    else -> "RÉÉCRIT (${previous.length} → ${current.length} car.)"
                }
                SpeechTrace.record("APP tampon partiel", relation)

                // LE CORRECTIF. Un texte qui n'est pas la suite du précédent
                // signifie que le moteur est passé à l'énoncé suivant — et
                // l'ancien doit être figé AVANT d'être remplacé, sinon il
                // disparaît purement et simplement.
                //
                // C'est exactement ce que la trace a montré : « premier mot »
                // écrasé par « second » sans jamais avoir été figé, parce que
                // je n'écrivais une ligne qu'à la fin de la session. Or la
                // session ne se termine pas : le détecteur de voix du moteur
                // clignote toutes les six dixièmes de seconde, il ne voit donc
                // jamais le silence continu qui la clôturerait. Attendre la fin
                // de session était une hypothèse, et elle est fausse sur cet
                // appareil.
                // ═══ Nouvel énoncé, ou correction de la phrase en cours ? ═══
                //
                // La question se tranche sur le CONTENU, plus sur la durée de
                // la pause. La condition précédente exigeait qu'une fin de
                // parole ait été signalée, c'est-à-dire un silence assez long
                // pour que le moteur le déclare — et la trace a montré que
                // celui-ci repart sur une hypothèse fraîche au bout de six
                // dixièmes de seconde, bien avant. Trois segments entiers ont
                // ainsi disparu : « tu as compris funiculaire mais », « mais
                // sinon », « pourquoi pas ». Une voix un peu hachée tombe
                // exactement dans cette zone.
                //
                // Le contenu, lui, sépare nettement les deux cas. Quand le
                // moteur se corrige, il garde le début de sa phrase — c'est
                // une révision, pas un recommencement. Quand il change
                // d'énoncé, plus rien ne coïncide dès le premier mot.
                //
                // Le garde-fou n'est donc pas supprimé mais redéfini : partager
                // un début protège du découpage abusif, ne rien partager
                // déclenche le figeage. Une fin de parole signalée reste un
                // indice supplémentaire de rupture, et lève la protection.
                val shared = sharedWordPrefix(previous, current)
                val selfCorrection = shared > 0 && !endOfSpeechSeen

                if (!continues && !selfCorrection) {
                    SpeechTrace.record(
                        "APP nouvel énoncé",
                        "figé avant remplacement (aucun mot commun en tête)",
                    )
                    commit(lastPartial)
                } else if (!continues) {
                    // LE SEUL CHEMIN DE PERTE QUI SUBSISTE, et il est le prix
                    // du garde-fou : un texte discordant sans fin de parole
                    // préalable est traité comme une correction du moteur, donc
                    // remplacé sans être figé. Si c'était en réalité un nouvel
                    // énoncé, il est perdu.
                    //
                    // Nommé plutôt que laissé invisible : c'est ce qu'il faudra
                    // compter pour savoir si la condition sur la fin de parole
                    // est trop stricte, et c'est la seule mesure qui puisse le
                    // dire.
                    SpeechTrace.record(
                        "APP correction du moteur",
                        "« $previous » → « $current » ($shared mot(s) commun(s) en tête)",
                    )
                }

                lastPartial = it
                endOfSpeechSeen = false
                // Affiché nettoyé : le moteur préfixe volontiers ses résultats
                // d'une espace, visible en tête de chaque phrase en cours. Les
                // lignes figées l'étaient déjà ; celle en cours ne l'était pas,
                // et l'écart se voyait à l'écran.
                onPartial(current)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            // LA MAIN REVIENT À L'APPLICATION.
            SpeechTrace.recordResults("API onResults", results)
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
        /**
         * Un segment est prêt : le moteur déclare lui-même une frontière
         * d'énoncé, ce que le reste de cette classe passe son temps à deviner.
         *
         * Rappel ajouté en Android 13, doté d'une implémentation vide par
         * défaut : le redéfinir ne coûte rien sur les versions antérieures, où
         * il ne sera simplement jamais appelé.
         */
        override fun onSegmentResults(segmentResults: Bundle) {
            sessionSegments++
            SpeechTrace.recordResults("API onSegmentResults", segmentResults)
            val text = firstResult(segmentResults) ?: lastPartial
            if (text.isNotBlank()) {
                SpeechTrace.record("APP segment", "frontière déclarée par le moteur")
                commit(text)
            }
        }

        /**
         * Fin d'une session segmentée. Prend la place d'onResults dans ce mode,
         * sans qu'on sache si les deux se déclenchent ou non — d'où le passage
         * par les mêmes gardes que partout ailleurs, qui tolèrent les deux.
         */
        override fun onEndOfSegmentedSession() {
            listening = false
            SpeechTrace.record("API onEndOfSegmentedSession", "$sessionSegments segment(s) reçu(s)")
            commit(lastPartial)
            endSession("fin de session segmentée, $sessionSegments segment(s)")
            scheduleRestart(0L)
        }

        override fun onEndOfSpeech() {
            SpeechTrace.record("API onEndOfSpeech", "fin de parole détectée")
            // Ne fige rien par lui-même : la trace montre qu'il se déclenche
            // plusieurs fois par énoncé, y compris en pleine phrase. Il arme
            // seulement l'autorisation de figer, que le prochain texte
            // discordant utilisera.
            endOfSpeechSeen = true
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
        if (sessionSummarised) return
        sessionSummarised = true
        val duration = (android.os.SystemClock.elapsedRealtime() - sessionStartedAtMs) / 1000.0
        SpeechTrace.record(
            "APP bilan session n°$sessionCount",
            String.format(
                java.util.Locale.FRANCE,
                "%s — %.1f s, 1er texte %s, %d partiels, %d segment(s), " +
                    "%d ligne(s) figée(s), pic %.1f dB — fin : %s",
                if (segmentedRequested) "SEGMENTÉ" else "classique",
                duration,
                if (sessionFirstTextMs >= 0) "${sessionFirstTextMs} ms" else "JAMAIS",
                sessionPartials, sessionSegments, sessionCommits, sessionMaxRms, outcome,
            ),
        )
    }

    private fun commit(text: String) {
        handler.removeCallbacks(idleCommit)
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
    private fun sharedWordPrefix(first: String, second: String): Int {
        val a = first.split(' ').filter { it.isNotEmpty() }
        val b = second.split(' ').filter { it.isNotEmpty() }
        var shared = 0
        while (shared < a.size && shared < b.size && a[shared].equals(b[shared], ignoreCase = true)) {
            shared++
        }
        return shared
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
         * Longueur au-delà de laquelle retrouver l'ancien tampon au MILIEU du
         * nouveau vaut continuation, et non coïncidence.
         *
         * Douze caractères, soit deux ou trois mots courts. En deçà, l'inclusion
         * ne prouve rien : « oui » est contenu dans une phrase sur deux, et lui
         * accorder valeur de continuation empêcherait à jamais de figer un mot
         * isolé — c'est-à-dire de le conserver. Au-delà, une suite de douze
         * caractères qui se retrouve mot pour mot n'arrive pas par hasard.
         *
         * Le préfixage — l'ancien tampon en SUFFIXE du nouveau — n'est pas
         * soumis à ce seuil : cette forme-là décrit un phénomène précis du
         * moteur, le re-décodage avec plus de contexte, et non une rencontre
         * fortuite de caractères.
         */
        const val MIN_CONTAINED_CHARS = 12

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

        /**
         * Sans évolution du texte pendant ce délai, on le fige.
         *
         * Nécessaire parce que le dernier énoncé avant un vrai silence n'est
         * remplacé par rien : sans cette minuterie il resterait indéfiniment en
         * italique, ni acquis ni effacé — et la session, qui ne se termine pas
         * d'elle-même sur cet appareil, ne viendrait pas le sauver.
         *
         * Deux secondes : au-delà du rythme normal des résultats
         * intermédiaires, mesuré à un toutes les six dixièmes de seconde.
         */
        const val IDLE_COMMIT_MS = 2_000L

        /**
         * Durée d'une session en mode segmenté. Quinze secondes, soit moins que
         * la clôture forcée : les deux mécanismes ne doivent pas se disputer la
         * fin de session, et le moteur doit disposer de sa propre marge.
         */
        const val SEGMENTED_SESSION_MS = 15_000
    }
}
