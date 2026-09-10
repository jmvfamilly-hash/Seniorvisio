package com.seniorvisio.core

/**
 * Dit si c'est Jean qui parle en ce moment dans la pièce.
 *
 * Sert à atténuer ses propres paroles à l'écran : Jean lit ce qu'il n'entend
 * pas, or il sait déjà ce qu'il vient de dire. Ses phrases y prennent la place
 * de celles qu'il a besoin de lire, et les repoussent hors de la zone.
 *
 * ═══ Indépendance totale du portier de voix payant ═══
 *
 * Ce portier-ci n'a AUCUN rapport avec RoomVoiceGate, et c'est délibéré au
 * point de mériter d'être écrit ici. RoomVoiceGate ne s'exécute que si le
 * moteur de la pièce est facturé à la durée et que son interrupteur est armé —
 * c'est-à-dire, dans la configuration courante, jamais. Accrocher la
 * reconnaissance du locuteur à ce portier aurait livré une fonction qui ne
 * s'active pas, et dont l'inactivité n'aurait été ni visible ni explicable.
 *
 * Les deux répondent d'ailleurs à des questions différentes — « faut-il payer
 * pour transcrire » et « qui est en train de parler » — et c'est la troisième
 * fois dans ce projet que deux fonctions distinctes menacent de pendre au même
 * signal. Les deux premières fois ont coûté une régression chacune.
 *
 * ═══ Comment le verdict est rendu ═══
 *
 * Par prise de parole entière, et non trame par trame. Une trame de 64 ms
 * décrit une syllabe, pas une personne : son verdict changerait plusieurs fois
 * par mot. On accumule donc les trames voisées depuis le début de la prise de
 * parole en cours, et c'est cette moyenne qu'on compare. Une pause franche
 * (voir BURST_GAP_MS) referme la prise de parole et en ouvre une autre — c'est
 * là, et seulement là, que le locuteur peut changer d'identité.
 *
 * ═══ Ce qu'il ne saura pas faire ═══
 *
 * Deux personnes qui parlent en même temps : la moyenne penche vers celle qui
 * domine, et l'autre est mal attribuée. Jean enrhumé, ou parlant très bas : la
 * ressemblance chute et ses paroles s'affichent normalement — l'échec va dans
 * le bon sens. Un fils à la voix proche de celle de son père reste le cas
 * difficile, mesuré et assumé (voir VoiceSignature).
 *
 * Le seuil est réglable à distance (voir AdminConfig.jeanVoiceThresholdPercent)
 * parce qu'il dépend de la voix de Jean, de celle de ses proches et de
 * l'acoustique de la pièce — trois choses qu'aucune valeur écrite ici ne peut
 * deviner.
 */
class RoomSpeakerGate(
    private val reference: VoiceSignature,
    /** Ressemblance minimale pour attribuer une prise de parole à Jean, de 0 à 1. */
    private val threshold: Double,
) {

    private val frame = ShortArray(VoiceFeatures.FRAME_SAMPLES)
    private var filled = 0

    /** Ce qui s'accumule depuis le début de la prise de parole en cours. */
    private val burst = VoiceSignature.Builder()
    private var lastVoicedAtMs = 0L

    /** Verdict de la prise de parole en cours, null tant qu'il n'y a pas de quoi conclure. */
    @Volatile private var burstIsJean: Boolean? = null

    /** Dernière ressemblance mesurée, publiée au diagnostic. */
    @Volatile private var lastSimilarity: Double? = null

    @Volatile private var burstsSeen = 0
    @Volatile private var burstsAttributedToJean = 0

    /**
     * Absorbe un bloc de son de la pièce.
     *
     * Ré-assemblé en trames de taille fixe : la capture livre des blocs dont la
     * taille dépend de l'appareil, et l'analyse en exige une précise. Imposer
     * cette taille au microphone serait la mauvaise moitié du compromis — elle
     * casserait la capture sur les appareils qui ne la supportent pas.
     */
    fun accept(buffer: ShortArray, length: Int, nowMs: Long) {
        // Prise de parole précédente close par le silence : le verdict repart
        // de zéro. Sans ça, la personne suivante hériterait de l'identité de la
        // précédente jusqu'à ce que la moyenne se déplace — c'est-à-dire
        // pendant plusieurs phrases.
        if (lastVoicedAtMs != 0L && nowMs - lastVoicedAtMs > BURST_GAP_MS) endBurst()

        var offset = 0
        while (offset < length) {
            val take = minOf(VoiceFeatures.FRAME_SAMPLES - filled, length - offset)
            System.arraycopy(buffer, offset, frame, filled, take)
            filled += take
            offset += take
            if (filled < VoiceFeatures.FRAME_SAMPLES) return
            filled = 0

            // Trame non voisée — une consonne, un souffle, une chaise qu'on
            // tire : elle n'appartient à personne et n'a pas à peser dans la
            // moyenne. Elle ne prolonge pas non plus la prise de parole.
            val analysed = VoiceFeatures.analyse(frame) ?: continue
            burst.add(analysed)
            lastVoicedAtMs = nowMs

            val candidate = burst.build(MIN_FRAMES_FOR_VERDICT) ?: continue
            val similarity = reference.similarityTo(candidate)
            lastSimilarity = similarity
            burstIsJean = similarity >= threshold
        }
    }

    /**
     * Vrai si la prise de parole en cours est attribuée à Jean, faux si elle
     * est attribuée à quelqu'un d'autre, null si rien ne permet de trancher —
     * et il faut alors afficher normalement. Le doute profite toujours à la
     * lisibilité : mieux vaut afficher en clair une phrase de Jean qu'atténuer
     * celle d'un proche.
     */
    fun currentSpeakerIsJean(): Boolean? = burstIsJean

    /** Dernière ressemblance mesurée, en pourcentage, pour le diagnostic. */
    fun lastSimilarityPercent(): Int? = lastSimilarity?.let { (it * 100).toInt() }

    /**
     * Part des prises de parole attribuées à Jean depuis la dernière lecture,
     * puis remise à zéro.
     *
     * Publiée parce qu'un filtre invisible qui se tromperait ferait passer
     * l'écran pour défaillant sans que rien ne le désigne. Cent pour cent
     * signalent un seuil trop bas — tout le monde passe pour Jean ; zéro pour
     * cent alors qu'il a parlé signale l'inverse, ou une signature à refaire.
     */
    fun consumeJeanSharePercent(): Int? {
        val seen = burstsSeen
        val jean = burstsAttributedToJean
        burstsSeen = 0
        burstsAttributedToJean = 0
        if (seen <= 0) return null
        return jean * 100 / seen
    }

    private fun endBurst() {
        if (burst.frames >= MIN_FRAMES_FOR_VERDICT) {
            burstsSeen++
            if (burstIsJean == true) burstsAttributedToJean++
        }
        burst.reset()
        burstIsJean = null
        lastVoicedAtMs = 0L
    }

    private companion object {

        /**
         * Silence qui referme une prise de parole. Deux secondes : une
         * respiration ou une hésitation au milieu d'une phrase ne doit pas
         * remettre l'identité du locuteur en question, mais un échange où l'on
         * se répond change bien de voix à peu près à ce rythme-là.
         */
        const val BURST_GAP_MS = 2_000L

        /**
         * Trames voisées nécessaires avant d'oser un verdict. Trente trames de
         * 64 ms font deux secondes de voix effective, soit quelques mots — en
         * dessous, la moyenne décrit encore la syllabe plutôt que la personne,
         * et le verdict changerait d'un mot à l'autre.
         *
         * Conséquence acceptée : les tout premiers mots d'une prise de parole
         * s'affichent sans attribution, donc en clair. Attendre est préférable
         * à trancher trop tôt, puisqu'une erreur atténuerait un proche.
         */
        const val MIN_FRAMES_FOR_VERDICT = 30
    }
}
