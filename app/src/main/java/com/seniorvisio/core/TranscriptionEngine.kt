package com.seniorvisio.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Le moteur de transcription : une source de son entre, du texte étiqueté par
 * sa source sort. Rien d'autre.
 *
 * C'est la pièce du milieu de la chaîne — source sonore, moteur, couche
 * d'affichage — et la seule qui parle à AssemblyAI. Les deux endroits qui
 * captent du son (le service qui écoute la pièce, le moteur d'appel WebRTC)
 * avaient jusqu'ici chacun leur copie de cette logique : création paresseuse
 * de la session, clé API, remontée d'erreur, arrêt. Les deux ont divergé au
 * moins une fois — le correctif sur la taille des blocs audio n'a été appliqué
 * qu'à l'une d'elles avant d'être remonté d'un cran. Une seule copie
 * maintenant.
 *
 * Une seule session AssemblyAI à la fois, jamais deux. Question de coût
 * — le service est facturé à la durée de connexion — mais surtout de sens :
 * deux textes qui arrivent en même temps de deux sources différentes
 * demanderaient à Jean de choisir lequel lire, ce qui est exactement ce qu'on
 * ne veut pas lui demander. Changer de source ferme la session en cours ; la
 * zone qui perd sa source s'efface alors d'elle-même, comme après un silence.
 */
class TranscriptionEngine(
    private val context: Context,
    /** Texte transcrit, accompagné de la source dont il provient. */
    private val onText: (source: TranscriptionSource, text: String, isFinal: Boolean) -> Unit,
    /**
     * Messages destinés à être montrés à celui qui teste (voir
     * CallSignalingClient.reportCaptionDebug) : arrivée effective du son,
     * clé API manquante, connexion perdue. Sans accès au journal système de la
     * tablette, c'est le seul moyen de savoir où ça bloque.
     */
    private val onDiagnostic: (String) -> Unit = {},
) {

    /**
     * Tout message de diagnostic part vers l'appelant ET dans le journal
     * partagé (voir TranscriptionDiagnostics). Sans le second, ces messages
     * n'existaient que le temps d'un appel : hors appel, ils tombaient dans le
     * vide, alors que c'est précisément là qu'on règle le moteur de la pièce.
     */
    private fun diagnose(message: String) {
        TranscriptionDiagnostics.record(message)
        onDiagnostic(message)
    }

    private var recognizer: SpeechRecognizer? = null
    private var recognizerKind: TranscriptionEngineChoice? = null

    /**
     * La source pour laquelle la session en cours a été ouverte. Retenue à
     * part d'[activeSource], qui peut déjà avoir changé au moment où l'on
     * ferme (voir setActiveSource) : le texte resté en attente appartient à la
     * source d'origine, pas à la nouvelle.
     */
    private var recognizerSource: TranscriptionSource? = null
    @Volatile private var activeSource: TranscriptionSource? = null

    /** Sources dont l'arrivée de son a déjà été signalée, pour ne le dire qu'une fois chacune. */
    private val reportedSources = mutableSetOf<TranscriptionSource>()

    /** Dernier bloc de son contenant autre chose que du silence (voir feed). */
    private var lastSoundAtMs = 0L

    /** Un bloc de son conservé en réserve, avec ce qu'il faut pour le rejouer tel quel. */
    private class Block(
        val pcm16: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val atMs: Long,
    )

    /**
     * Les dernières secondes de son, conservées en permanence — y compris
     * pendant qu'aucune session n'est ouverte — et rejouées en tête dès qu'une
     * session s'ouvre.
     *
     * Sans ça, le début de chaque prise de parole après un silence était
     * perdu, et pas pour une seule raison : le temps que le niveau franchisse
     * le plancher de silence, puis le temps d'établir la connexion et de
     * démarrer la session côté service. Trois ou quatre mots à chaque fois,
     * systématiquement les premiers — c'est-à-dire ceux qui disent de quoi on
     * parle.
     *
     * Deux secondes suffisent largement pour couvrir l'attaque d'une phrase et
     * une poignée de main réseau, et coûtent environ 64 ko de mémoire à 16 kHz
     * en mono. C'est le prix le plus bas auquel on pouvait garder l'économie
     * de connexion sans qu'elle se paie en mots perdus.
     */
    private val preRoll = ArrayDeque<Block>()

    /**
     * Quand la dernière session s'est fermée. Seul le son postérieur est
     * rejoué : rejouer ce qui a déjà été envoyé à la session précédente
     * ferait réapparaître à l'écran des mots déjà lus.
     */
    private var lastSessionEndAtMs = 0L

    /**
     * Choisit la source à transcrire, ou `null` pour ne rien transcrire du
     * tout. Le son des autres sources continue d'arriver mais est ignoré (voir
     * [feed]) : c'est volontaire, ça évite de brancher et débrancher des sinks
     * audio en pleine conversation pour un simple changement d'avis.
     */
    fun setActiveSource(source: TranscriptionSource?) {
        if (activeSource == source) return
        activeSource = source
        // La session en cours écoutait autre chose : on la ferme, la suivante
        // s'ouvrira au premier bloc de la nouvelle source.
        stopSession()
    }

    fun activeSource(): TranscriptionSource? = activeSource

    /**
     * Bloc de son brut (PCM 16 bits) venant de [source]. Ignoré si ce n'est
     * pas la source active — les appelants peuvent donc alimenter le moteur
     * en continu depuis toutes leurs sources sans se soucier de laquelle
     * compte à cet instant.
     */
    fun feed(source: TranscriptionSource, pcm16: ByteArray, sampleRate: Int, channels: Int) {
        if (reportedSources.add(source)) {
            diagnose("son ${label(source)} reçu (${sampleRate}Hz, ${channels}ch)")
        }
        if (source != activeSource) return

        // Le réglage a pu changer à distance depuis l'ouverture de la session
        // (voir DeviceStatusReporter) : on ferme celle en cours pour que la
        // suivante utilise le moteur demandé, sans rien avoir à notifier. La
        // bascule attend que le moteur voulu soit réellement disponible :
        // recognizerKind retient ce qui tourne vraiment, pas ce qui a été
        // demandé, sinon un repli sur AssemblyAI faute de modèle embarqué
        // resterait en place pour toujours — et le comparer à `wanted` sans
        // vérifier la disponibilité rouvrirait une session à chaque bloc de
        // son, soit une reconnexion WebSocket toutes les 100 ms.
        val wanted = resolveEngine(source)
        if (recognizer != null && recognizerKind != wanted && isAvailable(wanted)) stopSession()

        // AssemblyAI facture la durée de connexion, pas les mots : une session
        // laissée ouverte pendant qu'une pièce est vide, ou pendant qu'un
        // proche écoute sans parler, coûte exactement le même prix qu'une
        // conversation. On la ferme donc au bout d'un silence franc et on la
        // rouvre au premier son suivant.
        //
        // La coupure vit ici et non chez l'appelant : le service d'écoute de
        // la pièce avait bien un garde-fou de ce genre, mais un appel n'en
        // avait aucun — la session restait ouverte du décrochage au raccroché,
        // silences compris, c'est-à-dire l'essentiel d'une conversation où
        // l'on écoute autant qu'on parle.
        //
        // Seulement pour le moteur payant : fermer une session embarquée
        // n'économise rien et lui ferait perdre le contexte de la phrase en
        // cours pour rien.
        val now = SystemClock.elapsedRealtime()
        // Mis en réserve AVANT toute décision de couper : c'est précisément le
        // son que les gardes ci-dessous laisseraient tomber qu'il faut pouvoir
        // rejouer (voir preRoll).
        remember(Block(pcm16, sampleRate, channels, now))
        if (levelOf(pcm16) >= SILENCE_LEVEL) lastSoundAtMs = now
        val silent = lastSoundAtMs == 0L || now - lastSoundAtMs > BILLED_SILENCE_MS
        if (silent) {
            if (recognizerKind?.billedByDuration == true) stopSession()
            // Et surtout ne pas en rouvrir une sur du silence : ce serait
            // fermer et rouvrir en boucle, en payant chaque ouverture.
            if (recognizer == null && wanted.billedByDuration) return
        }

        val running = recognizer
        if (running != null) {
            running.accept(pcm16, sampleRate, channels)
            return
        }

        val created = createRecognizerFor(wanted) ?: return
        recognizer = created
        recognizerSource = source
        // Ce que le moteur déclare être, et non ce que son type laisse deviner :
        // une enveloppe s'intercale désormais (voir buffered), et un troisième
        // moteur tomberait silencieusement dans le mauvais cas.
        recognizerKind = created.engine
        // La session, et non la parole : AssemblyAI facture la durée de
        // connexion, pas le nombre de mots (voir UsageStats).
        UsageStats.noteTranscriptionStart(UsageStats.engineFor(created.engine))
        created.start(
            onText = { text, isFinal ->
                // La source peut avoir changé pendant que ce texte arrivait :
                // on l'étiquette avec celle qui l'a réellement produit, pas
                // avec celle qui est active maintenant.
                onText(source, text, isFinal)
            },
            onError = { message ->
                Log.w(TAG, "Transcription ${label(source)} : $message")
                diagnose(message)
            },
        )
        // Le son des dernières secondes part en premier : c'est le début de la
        // phrase, dit pendant que la session était encore fermée. La réserve
        // contient déjà le bloc courant, ajouté plus haut — il ne faut donc
        // surtout pas le renvoyer derrière.
        flushPreRollInto(created)
    }

    /** Conserve le bloc et jette ce qui dépasse la fenêtre (voir preRoll). */
    private fun remember(block: Block) {
        preRoll.addLast(block)
        while (preRoll.size > 1 && block.atMs - preRoll.first().atMs > PRE_ROLL_MS) {
            preRoll.removeFirst()
        }
    }

    /**
     * Rejoue la réserve dans une session qui vient de s'ouvrir, puis la vide :
     * ce son est désormais parti, le renvoyer plus tard le ferait transcrire
     * deux fois.
     *
     * Seuls les blocs postérieurs à la fermeture de la session précédente sont
     * rejoués. Sans cette borne, fermer et rouvrir coup sur coup — un
     * changement de moteur, un changement de source — renverrait du son déjà
     * transcrit, et Jean verrait revenir des mots qu'il vient de lire.
     */
    private fun flushPreRollInto(recognizer: SpeechRecognizer) {
        preRoll.forEach { block ->
            if (block.atMs >= lastSessionEndAtMs) {
                recognizer.accept(block.pcm16, block.sampleRate, block.channels)
            }
        }
        preRoll.clear()
    }

    /** Ferme tout : plus aucune source active, plus aucune session ouverte. */
    fun stop() {
        activeSource = null
        stopSession()
        reportedSources.clear()
    }

    /**
     * Le moteur découle de la source, exactement comme la zone d'affichage :
     * une solution pour la pièce, une pour les appels distants, toutes deux
     * choisies par l'administrateur (voir AdminConfig.roomEngine/callEngine et
     * le panneau d'administration du PWA). Personne d'autre n'en décide : un
     * appelant qui aurait pu basculer sur un service payant le temps de son
     * appel engageait une dépense que ni Jean ni l'administrateur ne voyaient
     * passer.
     *
     * Tant que le modèle embarqué n'est pas prêt — il se télécharge une fois,
     * au premier démarrage — la pièce passe par AssemblyAI plutôt que de
     * rester muette sans explication. Quelques minutes facturées une seule
     * fois valent mieux qu'une fonction qui semble cassée.
     */
    private fun resolveEngine(source: TranscriptionSource): TranscriptionEngineChoice {
        val adminConfig = AdminConfig(context)
        val choice = when (source) {
            TranscriptionSource.ROOM -> adminConfig.roomEngine
            TranscriptionSource.CALL -> adminConfig.callEngine
        }
        if (choice != TranscriptionEngineChoice.AUTO) return choice
        // Tout sur le moteur embarqué : gratuit, hors-ligne, et il ne dépend
        // d'aucun service qui pourrait tomber au mauvais moment.
        return TranscriptionEngineChoice.VOSK
    }

    /**
     * Niveau sonore du bloc, en valeur efficace sur les échantillons 16 bits.
     *
     * Calculé ici plutôt que fourni par l'appelant : les deux sources ne le
     * mesurent pas de la même façon — le service d'écoute de la pièce le
     * connaît déjà, une piste audio WebRTC ne le donne pas du tout — et la
     * décision de couper une session payante doit valoir pour les deux, avec
     * la même règle.
     */
    private fun levelOf(pcm16: ByteArray): Double {
        val samples = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = samples.remaining()
        if (count <= 0) return 0.0
        var sumOfSquares = 0.0
        for (i in 0 until count) {
            val value = samples.get(i).toDouble()
            sumOfSquares += value * value
        }
        return sqrt(sumOfSquares / count)
    }

    /** Le moteur voulu peut-il réellement démarrer maintenant ? */
    private fun isAvailable(wanted: TranscriptionEngineChoice): Boolean = when (wanted) {
        TranscriptionEngineChoice.VOSK -> VoskModelProvider.getModel() != null
        TranscriptionEngineChoice.GLADIA -> AdminConfig(context).gladiaApiKey.isNotBlank()
        // Jamais ici : ce moteur écoute le micro lui-même et ne passe pas par
        // cette chaîne (voir AndroidSpeechSession, RoomPresenceService).
        TranscriptionEngineChoice.ANDROID -> false
        else -> AdminConfig(context).assemblyAiApiKey.isNotBlank()
    }

    /**
     * Tant que le modèle embarqué n'est pas prêt — il se télécharge une fois —
     * on retombe sur AssemblyAI plutôt que de rester muet sans explication.
     * Quelques minutes facturées valent mieux qu'une fonction qui semble
     * cassée, et le repli se voit dans le diagnostic. La bascule vers le
     * modèle embarqué se fera d'elle-même à la session suivante, une fois le
     * téléchargement terminé (voir feed).
     */
    private fun createRecognizerFor(wanted: TranscriptionEngineChoice): SpeechRecognizer? {
        // La reconnaissance d'Android n'écoute que le micro : on ne peut pas
        // lui donner le son d'un appel, qui arrive par WebRTC. Le dire plutôt
        // que de rester muet — un réglage qui ne s'applique pas sans
        // explication, c'est une heure perdue à chercher pourquoi.
        var wanted = wanted
        if (wanted == TranscriptionEngineChoice.ANDROID) {
            diagnose("la reconnaissance Android n'écoute que le micro : impossible sur un appel")
            wanted = if (VoskModelProvider.getModel() != null) TranscriptionEngineChoice.VOSK
            else TranscriptionEngineChoice.ASSEMBLYAI
        }
        if (wanted == TranscriptionEngineChoice.VOSK) {
            if (VoskModelProvider.getModel() != null) return buffered(VoskSpeechRecognizer())
            diagnose("modèle embarqué indisponible (${VoskModelProvider.describeState()}), AssemblyAI en attendant")
        }

        if (wanted == TranscriptionEngineChoice.GLADIA) {
            val gladiaKey = AdminConfig(context).gladiaApiKey
            if (gladiaKey.isBlank()) {
                diagnose("clé API Gladia absente")
                return null
            }
            return buffered(GladiaStreamingTranscriber(gladiaKey))
        }

        val apiKey = AdminConfig(context).assemblyAiApiKey
        if (apiKey.isBlank()) {
            diagnose("clé API AssemblyAI absente")
            return null
        }
        return buffered(AssemblyAiRealtimeTranscriber(apiKey))
    }

    /**
     * Interpose une file d'attente entre le fil qui capte le son et le moteur
     * (voir BufferedSpeechRecognizer).
     *
     * Appliqué ici, à la création, plutôt que dans chaque moteur : ils ont
     * tous le même problème — ré-échantillonnage et écriture réseau exécutés
     * sur un fil temps réel dont le seul travail est de livrer le bloc suivant
     * à l'heure — et la logique d'AssemblyAI a déjà divergé une fois d'avoir
     * été recopiée à deux endroits.
     */
    private fun buffered(recognizer: SpeechRecognizer): SpeechRecognizer =
        BufferedSpeechRecognizer(recognizer, onDiagnostic = { diagnose(it) })

    private fun stopSession() {
        // Le silence qui précédait la fermeture ne doit pas compter comme du
        // silence après la réouverture : sans cette remise à zéro, la session
        // suivante se refermerait au premier bloc reçu.
        lastSoundAtMs = 0L
        // Borne de ce qui sera rejoué à la prochaine ouverture (voir
        // flushPreRollInto) : tout ce qui précède a déjà été envoyé à la
        // session qu'on ferme ici.
        if (recognizer != null) lastSessionEndAtMs = SystemClock.elapsedRealtime()
        if (recognizer != null) {
            UsageStats.noteTranscriptionStop()
            // Clôt le segment resté en attente. Fermer une session ne produit
            // aucun texte final : sans ce signal, la phrase en cours resterait
            // ouverte dans la zone d'affichage et le segment suivant
            // l'écraserait en croyant la corriger (voir
            // RollingCaptionZone.submit). Le risque était théorique tant qu'on
            // ne fermait qu'en fin d'appel ; il ne l'est plus maintenant qu'on
            // ferme à chaque silence.
            recognizerSource?.let { onText(it, "", true) }
        }
        recognizerSource = null
        recognizer?.stop()
        recognizer = null
        recognizerKind = null
    }

    private fun label(source: TranscriptionSource) = when (source) {
        TranscriptionSource.ROOM -> "micro tablette"
        TranscriptionSource.CALL -> "appel"
    }

    companion object {
        /**
         * Silence au-delà duquel une session payante est fermée. Assez long
         * pour qu'une respiration, une hésitation ou un « voilà… » suivi d'une
         * reprise ne coupent pas la connexion — la rouvrir coûte le début de
         * la phrase suivante, le temps de la poignée de main.
         */
        private const val BILLED_SILENCE_MS = 6_000L

        /**
         * En dessous, on considère qu'il n'y a personne qui parle. Volontairement
         * bas : mieux vaut garder la connexion ouverte quelques secondes de trop
         * que couper sur une voix lointaine ou une personne qui parle bas — ce
         * qui est précisément le cas de Jean.
         */
        private const val SILENCE_LEVEL = 300.0

        /**
         * Durée de son gardée en réserve pour être rejouée à l'ouverture d'une
         * session (voir preRoll). Deux secondes couvrent largement l'attaque
         * d'une phrase — toujours plus faible que son milieu, donc sous le
         * plancher de silence pendant un instant — et le temps d'établir une
         * connexion. Environ 64 ko à 16 kHz en mono.
         */
        private const val PRE_ROLL_MS = 2_000L

        private const val TAG = "TranscriptionEngine"
    }
}
