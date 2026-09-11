package com.papyrus

/**
 * La chaîne qui sépare le rythme du moteur de celui de la lecture.
 *
 * ═══ Deux horloges, et c'est tout le sujet ═══
 *
 * Le moteur de reconnaissance produit par rafales : plusieurs résultats dans la
 * même milliseconde, puis des secondes de rien. La trace le mesure — 57 % des
 * écarts entre deux résultats sont inférieurs à 50 ms, puis vient un trou de
 * six dixièmes de seconde. Un écran qui suivrait ce rythme sauterait.
 *
 * La lecture, elle, demande une cadence régulière. Une personne âgée qui suit
 * un texte du regard n'a pas à subir les à-coups d'un décodeur.
 *
 * Entre les deux, une file d'attente. Elle absorbe l'écart, et **la règle
 * centrale est qu'elle ne le répercute jamais** : quelle que soit la quantité
 * de texte en attente, le défilement garde exactement la même vitesse. Un stock
 * important se signale par un voyant, jamais par une accélération — accélérer
 * pour rattraper, c'est précisément ce qui rend un texte illisible au moment où
 * il y a le plus à lire.
 *
 * ═══ Pourquoi ce fichier n'importe rien d'Android ═══
 *
 * Aucun import de la plate-forme, aucun Handler, aucune horloge implicite : le
 * temps entre toujours par paramètre. La chaîne se teste donc entièrement sur
 * une machine de développement, en simulant le temps qui passe — ce qui est la
 * seule façon de vérifier un délai de stabilité de 1500 ms sans attendre 1500
 * millisecondes à chaque cas.
 */

/**
 * Transforme les hypothèses volatiles du moteur en mots définitifs.
 *
 * ═══ La règle : un mot inchangé pendant assez longtemps est acquis ═══
 *
 * Le moteur révise ses mots — rarement, mais il le fait : trois réécritures sur
 * cent un résultats dans le pire cas mesuré, toujours de petits écarts (« CAD »
 * devenant « cadet », « Rob » devenant « robot »). Ces révisions doivent être
 * absorbées **avant** que le mot ne parte à l'écran, et non corrigées après
 * coup : un mot déjà lu qui se transforme sous les yeux est exactement ce qu'on
 * cherche à éviter.
 *
 * D'où l'attente. Un mot doit rester identique pendant [stableDelayMs] avant
 * d'être promu. Ce n'est pas un délai d'affichage — la file et le défilement
 * s'en chargent — c'est un délai de **confiance**.
 *
 * ═══ Promotion dans l'ordre, jamais par sauts ═══
 *
 * Le mot d'indice n ne peut être promu que si tous ceux d'avant le sont. Sans
 * cette contrainte, un mot tardif pourrait doubler un mot hésitant qui le
 * précède, et la phrase sortirait dans le désordre.
 */
class WordStabiliser(
    private val stableDelayMs: Long,
    /** Appelé une fois par mot, dans l'ordre, quand il devient définitif. */
    private val onConfirmed: (String) -> Unit,
) {

    private class Candidate(val word: String, val firstSeenAtMs: Long)

    private var candidates: List<Candidate> = emptyList()
    private var confirmedCount = 0

    /** Les mots pas encore promus : c'est eux, et eux seuls, que montre la zone future. */
    fun pendingWords(): List<String> = candidates.drop(confirmedCount).map { it.word }

    /**
     * Un nouveau résultat intermédiaire du moteur.
     *
     * Il REMPLACE l'hypothèse courante, il ne s'y ajoute pas — c'est le
     * fonctionnement du moteur, vérifié sur toutes les traces. La comparaison
     * se fait mot à mot, par position : un mot identique à la même place garde
     * son horodatage d'apparition et continue de mûrir ; un mot différent
     * repart à zéro, et ceux qui le suivent aussi puisqu'ils sont recréés.
     */
    fun submitPartial(text: String, nowMs: Long) {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val next = ArrayList<Candidate>(words.size)
        for ((index, word) in words.withIndex()) {
            val previous = candidates.getOrNull(index)
            // Un mot déjà confirmé ne peut plus changer : on conserve la
            // version partie à l'écran, quoi que le moteur en dise ensuite.
            // C'est l'invariant « rien de ce qui a été lu ne se réécrit ».
            next += when {
                index < confirmedCount -> previous ?: Candidate(word, nowMs)
                previous != null && previous.word == word -> previous
                else -> Candidate(word, nowMs)
            }
        }
        candidates = next
        promoteRipeWords(nowMs)
    }

    /**
     * Fait mûrir le temps sans nouveau résultat du moteur.
     *
     * Indispensable, et facile à oublier : après le dernier mot d'une phrase,
     * plus aucun résultat n'arrive. Sans ce rappel régulier, les derniers mots
     * resteraient éternellement candidats — le silence les empêcherait d'être
     * promus alors que c'est justement le silence qui prouve qu'ils sont
     * définitifs.
     */
    fun tick(nowMs: Long) = promoteRipeWords(nowMs)

    /**
     * Le moteur clôt sa session : tout ce qui reste devient définitif.
     *
     * [finalText] est le résultat final quand il en rend un. La trace montre
     * qu'il est en général identique, caractère pour caractère, au dernier
     * résultat intermédiaire : cette réconciliation ne change donc presque
     * jamais rien à l'écran, et c'est le cas nominal, pas l'exception.
     *
     * Les mots DÉJÀ confirmés ne sont pas retouchés, même si le texte final en
     * diffère. Le reste est publié depuis le texte final s'il existe, depuis
     * les candidats sinon — de sorte qu'aucun mot ne se perde dans l'un ni
     * l'autre cas.
     */
    fun finish(finalText: String?) {
        val finalWords = finalText?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() }.orEmpty()
        val total = maxOf(finalWords.size, candidates.size)
        for (index in confirmedCount until total) {
            val word = finalWords.getOrNull(index) ?: candidates.getOrNull(index)?.word ?: continue
            onConfirmed(word)
        }
        candidates = emptyList()
        confirmedCount = 0
    }

    private fun promoteRipeWords(nowMs: Long) {
        while (confirmedCount < candidates.size &&
            nowMs - candidates[confirmedCount].firstSeenAtMs >= stableDelayMs
        ) {
            onConfirmed(candidates[confirmedCount].word)
            confirmedCount++
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * La réserve de mots acquis, entre le moteur et l'écran.
 *
 * **Sans plafond, et c'est délibéré.** Un monologue de seize secondes produit
 * une quarantaine de mots sans une seule pause détectée par le moteur ; à la
 * cadence de lecture, l'écran a plusieurs dizaines de secondes de retard. Une
 * file bornée jetterait alors les mots les plus anciens, c'est-à-dire ceux qui
 * viennent juste d'être dits — la perte serait invisible et irrattrapable.
 *
 * Le risque d'une file sans limite est connu : elle grandit. Mais elle grandit
 * de quelques octets par mot, et personne ne dicte des heures sans pause. Entre
 * une mémoire qui enfle lentement et du texte perdu en silence, le choix est
 * fait.
 */
class WordQueue {
    private val words = ArrayDeque<String>()

    val backlog: Int get() = words.size

    fun enqueue(word: String) = words.addLast(word)

    /** Le prochain mot à afficher, ou null si la dictée a pris de l'avance sur personne. */
    fun dequeue(): String? = words.removeFirstOrNull()
}

/**
 * Le voyant qui dit « il reste à lire », et qui ne fait que le dire.
 *
 * ═══ Pourquoi une hystérésis ═══
 *
 * Un seuil unique ferait clignoter le voyant dès que la file oscille autour de
 * lui — un mot entre, un mot sort, le voyant s'allume et s'éteint plusieurs
 * fois par seconde. Or un point qui clignote dans le champ de vision est
 * exactement ce qu'on veut éviter chez quelqu'un qui lit.
 *
 * Deux seuils distincts l'en empêchent : il s'allume au-dessus de [enterAt],
 * s'éteint en dessous de [exitAt], et entre les deux il garde son état. La
 * zone morte est le prix d'un voyant calme.
 */
class BacklogIndicator(
    private val enterAt: Int,
    private val exitAt: Int,
) {
    var visible: Boolean = false
        private set

    fun update(backlog: Int) {
        visible = when {
            backlog > enterAt -> true
            backlog < exitAt -> false
            else -> visible
        }
    }
}

/**
 * Les crans de vitesse, en mots par minute.
 *
 * Des paliers et non un curseur continu : un réglage libre demande de viser, se
 * règle par tâtonnement et ne se retrouve pas. Cinq crans nommés se manipulent
 * du bout du doigt et se décrivent au téléphone — « mets-le sur normal » est
 * une consigne qu'un aidant peut donner, « 137 mots par minute » non.
 *
 * Le libellé qualitatif est ce qui s'affiche ; la valeur en mots par minute
 * l'accompagne pour le diagnostic, parce qu'une trace relue plus tard doit
 * pouvoir être rattachée à une cadence précise.
 */
enum class ScrollRate(val label: String, val wordsPerMinute: Int) {
    TRES_LENT("très lent", 80),
    LENT("lent", 120),
    NORMAL("normal", 150),
    RAPIDE("rapide", 200),
    TRES_RAPIDE("très rapide", 260);

    /** Millisecondes entre deux mots. C'est ce que consomme l'horloge de défilement. */
    val intervalMs: Long get() = 60_000L / wordsPerMinute

    /**
     * Le cran suivant, en s'arrêtant aux extrémités plutôt qu'en bouclant.
     *
     * Boucler ferait passer de « très rapide » à « très lent » d'une pression,
     * ce qui est un accident et jamais une intention.
     */
    fun next(): ScrollRate = entries.getOrElse(ordinal + 1) { this }

    fun previous(): ScrollRate = entries.getOrElse(ordinal - 1) { this }

    companion object {
        val DEFAULT = NORMAL

        fun fromNameOrDefault(name: String?): ScrollRate =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Valeurs par défaut de la chaîne, réunies pour être relues d'un coup d'œil. */
object ScrollDefaults {
    /** Attente avant qu'un mot soit tenu pour définitif. */
    const val STABLE_DELAY_MS = 1_500L

    /** Au-delà, le voyant s'allume : environ vingt secondes de lecture en réserve. */
    const val BACKLOG_ENTER = 8

    /** En deçà, il s'éteint. L'écart avec le seuil d'allumage est la zone morte. */
    const val BACKLOG_EXIT = 4

    /**
     * Silence au terme duquel l'écran rappelle qu'il écoute toujours.
     *
     * Distinct du voyant de retard, et il ne faut pas les confondre : celui-ci
     * dit « le moteur écoute, même s'il ne se passe rien », l'autre dit « il y a
     * plus à lire que ce que tu vois ». La trace justifie ce besoin — trois
     * sessions consécutives en « rien compris », neuf secondes chacune, pendant
     * lesquelles l'écran restait parfaitement immobile sans rien expliquer.
     */
    const val SILENCE_HINT_MS = 5_000L
}
