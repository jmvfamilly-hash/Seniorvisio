package com.seniorvisio.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Troisième moteur de transcription : celui d'Android lui-même.
 *
 * Contrairement aux deux autres (voir com.seniorvisio.core.SpeechRecognizer),
 * celui-ci ne se nourrit pas d'un flux audio qu'on lui donne — **il écoute le
 * micro lui-même**, et c'est une contrainte de l'API, pas un choix. Il ne peut
 * donc pas implémenter l'interface commune, et il ne peut pas transcrire un
 * appel : le son d'un appel arrive par WebRTC, jamais par le micro. Il ne sert
 * que pour ce qui se dit dans la pièce.
 *
 * Ce qui a une deuxième conséquence, moins évidente : puisqu'il prend le micro,
 * notre propre capture doit le lui laisser. C'est donc lui qui signale aussi la
 * présence de parole pour le réveil de l'écran (voir onSpeechDetected et
 * RoomPresenceService) — sans quoi choisir ce moteur éteindrait silencieusement
 * la fonction principale de la tablette.
 *
 * Reconnaissance sur l'appareil demandée explicitement. Une tablette qui écoute
 * une chambre du matin au soir n'a pas à en envoyer le son chez un tiers, et
 * mieux vaut un moteur qui refuse de démarrer faute de modèle français
 * installé — cas signalé dans le diagnostic — qu'un moteur qui marche en
 * expédiant discrètement la pièce sur le réseau.
 *
 * ═══ Ce que la campagne de mesures a corrigé ici ═══
 *
 * Une application d'essai a été construite pour observer CE moteur seul, avec
 * un journal de chaque appel dans les deux sens. Six conclusions en sont
 * sorties, toutes contraires à ce que ce fichier supposait, et toutes portées
 * ci-dessous. Les suppositions étaient les miennes ; les mesures ont été
 * faites sur la tablette elle-même, en français, dans une vraie pièce.
 *
 * 1. **La session ne se termine pas toute seule DANS CETTE CONFIGURATION.** Le
 *    détecteur de voix du moteur clignote toutes les six dixièmes de seconde,
 *    et aucune des sessions mesurées ne s'est close d'elle-même.
 *
 *    CORRECTION, et elle est importante. Une mesure ultérieure, moteur livré
 *    à lui-même — sans aucune consigne de durée de silence et sans plafond —
 *    montre le contraire : il clôt sa session spontanément, quelques
 *    millisecondes après sa dernière fin de parole. Ce n'est donc pas le
 *    moteur qui refuse de conclure, c'est notre configuration qui l'en
 *    empêche, et le coupable le plus probable est la consigne de silence que
 *    nous lui imposons.
 *
 *    Ce qui suit reste en place parce que ce fichier N'A PAS ENCORE ÉTÉ
 *    mesuré sans ces consignes, et qu'un mécanisme qui écrit du texte ne se
 *    démonte pas sur une mesure faite ailleurs. Mais la question est
 *    rouverte : si le moteur sait conclure seul, presque tout ce qui suit
 *    devient superflu.
 *
 * 2. **onResults ne rend souvent aucun texte** — trois fois sur trois dans les
 *    sessions mesurées. Le texte utile est TOUJOURS venu des partiels. Un
 *    moteur dont on n'écoute que le résultat final n'écrit rien.
 *
 * 3. **Un partiel qui ne prolonge pas le précédent est un nouvel énoncé**, et
 *    l'ancien doit être figé avant d'être remplacé, sans quoi il disparaît de
 *    l'écran sans laisser de trace. C'est la cause mesurée des mots perdus.
 *
 * 4. Mais **le moteur se corrige aussi en cours de phrase**, et ces
 *    corrections-là ne doivent PAS couper la ligne. Les deux cas se
 *    distinguent au contenu, jamais à la durée de la pause : une correction
 *    garde le début de la phrase, un nouvel énoncé ne partage rien.
 *
 * 5. **Le mode segmenté d'Android 13 n'apporte rien** et dégrade. Essayé,
 *    mesuré, écarté — voir plus bas.
 *
 * 6. **Chaque relance coûte de deux à huit secondes de surdité**, pendant
 *    lesquelles le moteur signale pourtant de la parole et ne rend que du
 *    vide. Ce fichier détruisait et reconstruisait le moteur à chaque relance,
 *    ce qui ne pouvait qu'aggraver ce délai.
 */
class AndroidSpeechSession(
    private val context: Context,
    private val onText: (text: String, isFinal: Boolean) -> Unit,
    /** Appelé dès que le moteur entend quelqu'un parler : c'est ce qui réveille l'écran. */
    private val onSpeechDetected: () -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
) {

    /** Vers l'appelant et vers le journal partagé (voir TranscriptionDiagnostics). */
    private fun diagnose(message: String) {
        TranscriptionDiagnostics.record(message)
        onDiagnostic(message)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val adminConfig = AdminConfig(context)

    @Volatile private var lastLevelDb = 0f
    @Volatile private var peakLevelDb = 0f

    /** Dernier niveau observé, en décibels relatifs à ce moteur. Pour le diagnostic. */
    fun lastLevelDb(): Float = lastLevelDb

    /**
     * Le plus fort niveau depuis la dernière lecture, puis remis à zéro —
     * même raison que pour l'autre mécanisme d'écoute (voir
     * RoomPresenceService.consumePeakRms) : entre deux signes de vie il se
     * passe cinq minutes, et l'instant précis où l'on regarde a toutes les
     * chances d'être un instant de silence.
     */
    fun consumePeakLevelDb(): Float {
        val peak = peakLevelDb
        peakLevelDb = 0f
        return peak
    }

    /**
     * Le seuil réglé par l'administrateur, reporté sur l'échelle de ce moteur.
     *
     * Les bornes RMS sont celles du curseur côté administration : ce qui est
     * transposé, c'est la position du curseur dans sa course, pas une valeur
     * physique. Les bornes en décibels encadrent ce que ce moteur produit en
     * pratique — autour de zéro dans une pièce calme, une dizaine sur une voix
     * proche.
     */
    fun wakeThresholdDb(): Float {
        val raw = adminConfig.roomWakeSensitivityThreshold.toFloat()
        val fraction = ((raw - RMS_SCALE_MIN) / (RMS_SCALE_MAX - RMS_SCALE_MIN)).coerceIn(0f, 1f)
        return DB_SCALE_MIN + fraction * (DB_SCALE_MAX - DB_SCALE_MIN)
    }

    /** Vrai entre start() et stop() : distingue un arrêt voulu d'une fin d'énoncé. */
    private var wanted = false
    private var consecutiveErrors = 0
    private var reportedEngine = false

    /**
     * Le dernier partiel reçu, pas encore figé à l'écran.
     *
     * Il existe parce que la conclusion n°3 impose de pouvoir figer l'ancien
     * texte AVANT de le remplacer. Sans ce souvenir, un nouvel énoncé écrase
     * simplement le précédent dans la zone d'affichage et le mot dit une
     * seconde plus tôt n'a jamais été écrit nulle part.
     */
    private var pendingText = ""

    /**
     * La dernière ligne figée, pour ne pas l'écrire deux fois.
     *
     * Le moteur répète volontiers un texte qu'il vient de rendre. Après un
     * figeage, cette répétition réapparaîtrait juste en dessous de la ligne
     * acquise — la même phrase deux fois sous les yeux de Jean.
     */
    private var lastCommitted = ""

    fun isRunning(): Boolean = wanted

    /**
     * Démarre l'écoute continue. Le moteur d'Android, lui, ne sait écouter
     * qu'un énoncé à la fois : il s'arrête à chaque silence, et c'est à nous de
     * le relancer indéfiniment. D'où la boucle ci-dessous, qui est le prix à
     * payer pour une écoute permanente avec cette API.
     */
    fun start() {
        if (wanted) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            diagnose("reconnaissance Android : permission micro refusée")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            diagnose("reconnaissance Android indisponible sur cette tablette")
            return
        }
        wanted = true
        consecutiveErrors = 0
        // Une écoute qui reprend après un appel repart d'une page blanche : le
        // souvenir de la dernière ligne figée ferait taire, comme un écho, une
        // phrase réellement redite un quart d'heure plus tard.
        pendingText = ""
        lastCommitted = ""
        // Les sons de début et de fin d'énoncé sont traités ailleurs : ce
        // n'est pas l'écoute qui décide du volume des alertes, c'est
        // l'absence d'appel (voir AlertVolume, MainActivity et
        // IncomingCallActivity). Les lier à cette session revenait à les
        // remonter en pleine conversation, où le moteur est justement arrêté.
        UsageStats.noteTranscriptionStart(UsageStats.ENGINE_ANDROID)
        handler.post { listen() }
    }

    fun stop() {
        if (wanted) UsageStats.noteTranscriptionStop()
        wanted = false
        handler.removeCallbacksAndMessages(null)
        handler.post {
            // Ce qui était en cours de dictée au moment de l'arrêt est figé
            // plutôt que jeté : un appel entrant coupe l'écoute, et la phrase
            // commencée juste avant n'a aucune raison de s'évaporer de
            // l'écran. Sur le fil du gestionnaire, comme tout le reste de
            // l'état de cette classe — stop() peut venir d'ailleurs.
            commitPending()
            releaseRecognizer()
        }
    }

    private fun listen() {
        if (!wanted) return

        // ═══ Le moteur est RÉUTILISÉ d'une écoute à l'autre ═══
        //
        // Conclusion n°6. Ce code détruisait puis reconstruisait le moteur à
        // chaque relance, et les relances sont incessantes : dans une pièce
        // calme, le moteur rend un « silence » puis s'arrête, en boucle toute
        // la journée. Or la mesure montre que la reprise coûte déjà de deux à
        // huit secondes pendant lesquelles le moteur signale de la parole et
        // ne rend que du vide — reconstruire l'objet à chaque fois ne pouvait
        // que s'y ajouter.
        //
        // Ce délai compte parce qu'il tombe exactement au mauvais moment. La
        // pièce de Jean est silencieuse la plus grande partie du temps ; le
        // moteur y passe donc sa journée en fin d'écoute et en reprise, et les
        // premiers mots d'une phrase — ceux qui disent à qui l'on parle et de
        // quoi — sont précisément ceux qui ont le plus de chances de tomber
        // dans ce trou.
        //
        // Le moteur n'est donc reconstruit que s'il n'existe pas, ou s'il a
        // refusé de démarrer.
        val instance = recognizer ?: try {
            createRecognizer().also { it.setRecognitionListener(listener) }
        } catch (e: Exception) {
            Log.w(TAG, "Création du moteur Android impossible", e)
            diagnose("reconnaissance Android : ${e.message}")
            wanted = false
            return
        }
        recognizer = instance

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            // Le texte doit s'afficher au fil de la phrase, comme pour les deux
            // autres moteurs : sans ça, rien n'apparaît avant le silence final.
            //
            // Et depuis la campagne de mesures, on sait que ce n'est pas un
            // confort mais la seule source de texte qui vaille : le résultat
            // final est revenu VIDE trois fois sur trois (conclusion n°2).
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // ═══ Ce qu'on ne met PAS, et pourquoi ═══
            //
            // Pas de EXTRA_SEGMENTED_SESSION (conclusion n°5). Il a été essayé
            // sur l'application de mesure, une session sur deux, sur la même
            // voix : il fonctionne, et il découpe moins bien. Ses frontières
            // ne sont que le détecteur de voix du moteur, celui qui clignote
            // toutes les six dixièmes de seconde — quatre segments sur cinq
            // arrivent vides, et les autres coupent au milieu d'un groupe de
            // mots (« il y a des » puis « problème » sur deux lignes). À
            // découpage moyen identique, la régularité est deux fois et demie
            // pire. Il coûterait en plus un plafond de session, dont on vient
            // justement de se débarrasser.
            //
            // Pas de EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS non plus. Cet
            // extra signifie « n'arrête pas d'enregistrer avant ce délai », et
            // non « attends au moins ce temps avant de rendre » : posé à une
            // valeur élevée, il gèle tout le texte pendant ce temps. Mesuré.
        }
        try {
            instance.startListening(intent)
        } catch (e: Exception) {
            // Un moteur réutilisé peut refuser de repartir. Dans ce cas
            // seulement il est jeté, et la relance suivante en construira un
            // neuf — c'est le comportement d'avant, désormais réservé au cas
            // où il est réellement nécessaire.
            Log.w(TAG, "Démarrage de l'écoute Android impossible", e)
            releaseRecognizer()
            scheduleRestart()
        }
    }

    private fun releaseRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    /**
     * Sur Android 12 et au-delà, un constructeur garantit la reconnaissance sur
     * l'appareil. En dessous, EXTRA_PREFER_OFFLINE n'est qu'une préférence que
     * le moteur peut ignorer — c'est dit dans le diagnostic plutôt que laissé
     * à supposer.
     */
    private fun createRecognizer(): SpeechRecognizer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!reportedEngine) {
                reportedEngine = true
                diagnose("reconnaissance Android sur l'appareil")
            }
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        if (!reportedEngine) {
            reportedEngine = true
            diagnose("reconnaissance Android (hors ligne demandée, non garantie sur cette version)")
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            consecutiveErrors = 0
        }

        override fun onBeginningOfSpeech() {
            // Volontairement sans effet sur le réveil. C'est ici que le réveil
            // était déclenché, et c'était le défaut : ce signal est la
            // détection de parole de Google, qui n'a pas de seuil réglable et
            // se déclenche sur un bruit de clavier à deux mètres. Le curseur
            // de sensibilité de l'écran d'administration ne servait alors
            // strictement à rien dans ce mode — il ne commandait que l'autre
            // mécanisme d'écoute.
        }

        /**
         * Le réveil passe par là, comme sur l'autre mécanisme d'écoute (voir
         * RoomPresenceService.handleLevel) : un niveau sonore comparé à un
         * seuil réglable, et rien d'autre.
         *
         * L'unité n'est pas la même — ce moteur donne des décibels relatifs,
         * notre capture donne une valeur efficace sur 16 bits — et aucune
         * conversion honnête n'existe entre les deux. Le seuil réglé est donc
         * reporté en proportion de sa propre échelle (voir wakeThresholdDb) :
         * un curseur à mi-course reste à mi-course dans les deux modes, ce qui
         * est ce qu'on attend d'un curseur, à défaut d'être une mesure.
         */
        override fun onRmsChanged(rmsdB: Float) {
            lastLevelDb = rmsdB
            if (rmsdB > peakLevelDb) peakLevelDb = rmsdB
            if (rmsdB >= wakeThresholdDb()) onSpeechDetected()
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { handlePartial(it) }
        }

        override fun onResults(results: Bundle?) {
            // Le résultat final, quand il existe, remplace le tampon : c'est la
            // meilleure version du même énoncé. Quand il n'existe pas — le cas
            // ordinaire, conclusion n°2 — c'est le tampon qui est figé, sans
            // quoi la phrase que Jean vient de dire serait perdue à la relance.
            val text = firstResult(results)
            if (text != null) commit(text) else commitPending()
            // Fin d'énoncé, pas fin d'écoute : on relance aussitôt.
            scheduleRestart(immediate = true)
        }

        override fun onError(error: Int) {
            // Quel que soit le motif, ce qui était dit avant l'erreur a été
            // dit. Une erreur tardive était l'un des chemins par lesquels une
            // phrase disparaissait sans jamais avoir été écrite.
            commitPending()
            when (error) {
                // Silence ou phrase incomprise : le cas ordinaire d'une pièce
                // vide. On relance sans compter ça comme un échec, sinon la
                // moindre heure de calme épuiserait le compteur.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(immediate = true)

                // Sans modèle français installé, insister ne sert à rien : ça
                // se règle sur la tablette, dans les paramètres de Google.
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    diagnose("reconnaissance Android : permission micro refusée")
                    wanted = false
                    UsageStats.noteTranscriptionStop()
                }

                else -> {
                    // Le moteur réutilisé est jeté ici, et seulement ici. Une
                    // erreur autre qu'un silence signifie qu'il est peut-être
                    // dans un état dont il ne sortira pas ; la relance en
                    // construira un neuf.
                    //
                    // Ce cas couvre notamment « moteur occupé », que la
                    // réutilisation peut provoquer si l'on redemande à écouter
                    // avant que la session précédente soit close. Sans cette
                    // remise à neuf, l'erreur se répéterait à l'identique
                    // jusqu'à épuiser le compteur et éteindre l'écoute pour de
                    // bon — l'économie d'une reconstruction ne vaut pas une
                    // transcription morte.
                    releaseRecognizer()
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        diagnose("reconnaissance Android en échec répété (code $error), écoute arrêtée")
                        wanted = false
                        UsageStats.noteTranscriptionStop()
                        return
                    }
                    scheduleRestart()
                }
            }
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Décide, à chaque partiel, si le moteur continue sa phrase ou en a
     * commencé une autre — et fige l'ancienne dans le second cas.
     *
     * ═══ Pourquoi le contenu, et non la durée du silence ═══
     *
     * La première version de ce garde-fou attendait qu'une fin de parole ait
     * été signalée, c'est-à-dire un silence assez long pour que le moteur le
     * déclare. Les mesures l'ont démenti : le moteur repart sur une hypothèse
     * fraîche après six dixièmes de seconde, bien avant de déclarer quoi que
     * ce soit. Une voix un peu hachée tombe exactement dans cette zone — et
     * c'est celle de Jean.
     *
     * La question se tranche donc sur le texte. Quand le moteur se corrige, il
     * garde le début de sa phrase ; quand il change d'énoncé, plus rien ne
     * coïncide dès le premier mot. Vérifié sur les sept cas de la trace de
     * mesure : les trois pertes réelles ne partageaient aucun mot de tête, les
     * quatre auto-corrections en partageaient toutes.
     */
    private fun handlePartial(raw: String) {
        // Le moteur préfixe volontiers ses résultats d'une espace. Les lignes
        // figées étaient nettoyées, celle en cours ne l'était pas : l'écart se
        // voyait à l'écran.
        val current = raw.trim()
        if (current.isEmpty()) return

        val previous = pendingText

        // Écho d'une ligne qu'on vient de figer, à ne pas réafficher en
        // dessous d'elle-même.
        if (previous.isEmpty() && current == lastCommitted) return

        // Prolongé par la fin, mais aussi par le début : le moteur re-décode
        // parfois son hypothèse avec plus de contexte et la rallonge par
        // devant — « second mot » devient « voilà second mot ». Sans ce cas,
        // l'ancien texte serait figé puis la version complète figée à son
        // tour : la même phrase deux fois, l'une tronquée.
        //
        // L'inclusion au milieu, en revanche, ne vaut qu'au-delà d'une
        // longueur où la coïncidence cesse d'être vraisemblable. Un tampon de
        // trois lettres — « oui », « non », « bon » — se retrouve dans presque
        // n'importe quel énoncé suivant ; l'accepter sans condition
        // empêcherait à jamais de figer un mot isolé, c'est-à-dire de le
        // conserver. Ce serait rouvrir, par la correction, le trou qu'elle
        // bouche.
        val continues = previous.isEmpty() ||
            current.startsWith(previous) ||
            current.endsWith(previous) ||
            (previous.length >= MIN_CONTAINED_CHARS && current.contains(previous))

        if (!continues && sharedWordPrefix(previous, current) == 0) {
            commit(previous)
        }

        pendingText = current
        onText(current, false)
        armIdleCommit()
    }

    /**
     * Fige le tampon faute d'évolution.
     *
     * Nécessaire tant que la session ne se termine pas d'elle-même
     * (conclusion n°1, et sa correction) : sans ce garde-temps, une phrase
     * suivie d'un vrai silence resterait indéfiniment « en cours », jamais
     * acquise, et disparaîtrait au premier mot suivant.
     */
    private fun armIdleCommit() {
        handler.removeCallbacks(idleCommit)
        handler.postDelayed(idleCommit, IDLE_COMMIT_MS)
    }

    private val idleCommit = Runnable { commitPending() }

    private fun commitPending() {
        if (pendingText.isNotEmpty()) commit(pendingText)
    }

    private fun commit(text: String) {
        handler.removeCallbacks(idleCommit)
        val phrase = text.trim()
        pendingText = ""
        if (phrase.isEmpty() || phrase == lastCommitted) return
        lastCommitted = phrase
        onText(phrase, true)
    }

    /** Nombre de mots identiques en tête, casse ignorée. */
    private fun sharedWordPrefix(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val left = a.split(' ').filter { it.isNotEmpty() }
        val right = b.split(' ').filter { it.isNotEmpty() }
        var shared = 0
        while (shared < left.size && shared < right.size &&
            left[shared].equals(right[shared], ignoreCase = true)
        ) {
            shared++
        }
        return shared
    }

    /**
     * Relance après un délai qui grandit avec les échecs consécutifs. Relancer
     * sans répit un moteur qui refuse de démarrer le ferait rejeter par le
     * système (et, sur certaines tablettes, émettrait un bip à chaque essai —
     * inacceptable dans une chambre).
     */
    private fun scheduleRestart(immediate: Boolean = false) {
        if (!wanted) return
        val delay = if (immediate) RESTART_DELAY_MS
        else (RESTART_DELAY_MS shl consecutiveErrors.coerceAtMost(6)).coerceAtMost(MAX_RESTART_DELAY_MS)
        handler.postDelayed({ listen() }, delay)
    }

    private companion object {
        const val TAG = "AndroidSpeechSession"
        const val LANGUAGE = "fr-FR"
        const val RESTART_DELAY_MS = 300L
        const val MAX_RESTART_DELAY_MS = 30_000L
        const val MAX_CONSECUTIVE_ERRORS = 8

        /**
         * Silence au terme duquel le tampon est figé sans attendre le moteur.
         *
         * Deux secondes : assez pour laisser passer les hésitations d'une
         * phrase — le moteur lui-même repart sur une hypothèse fraîche après
         * six dixièmes de seconde — et assez court pour qu'une phrase finie
         * s'inscrive pendant que Jean respire, et non au mot suivant.
         */
        const val IDLE_COMMIT_MS = 2_000L

        /**
         * Longueur au-delà de laquelle retrouver l'ancien tampon au MILIEU du
         * nouveau vaut continuation, et non coïncidence. Douze caractères,
         * soit deux ou trois mots courts : en deçà, l'inclusion ne prouve
         * rien.
         */
        const val MIN_CONTAINED_CHARS = 12

        /** Bornes du curseur de sensibilité, côté administration (voir index.html). */
        const val RMS_SCALE_MIN = 500f
        const val RMS_SCALE_MAX = 15_000f

        /** Ce que ce moteur produit en pratique : ~0 dans une pièce calme, ~10 sur une voix proche. */
        const val DB_SCALE_MIN = 0f
        const val DB_SCALE_MAX = 9f
    }
}
