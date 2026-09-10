package com.seniorvisio.core

/**
 * Ce que doit savoir faire un moteur de reconnaissance de locuteur : apprendre
 * une voix, puis dire si c'est elle qui parle. Rien d'autre.
 *
 * Les deux moments sont dans la même interface, et c'est nécessaire : une
 * signature n'a de sens que pour le moteur qui l'a produite. Les séparer
 * aurait permis d'apprendre avec l'un et de filtrer avec l'autre — c'est-à-dire
 * de comparer une voix à une empreinte qui ne la décrit pas, sans que rien à
 * l'écran ne le signale.
 */
interface SpeakerRecogniser {

    val engine: SpeakerEngineChoice

    // ─── Apprentissage ───────────────────────────────────────────────────

    /**
     * Absorbe du son destiné à constituer la signature, et rend l'avancement
     * de 0 à 1.
     *
     * L'avancement mesure la **quantité de voix effectivement entendue**, pas
     * le temps écoulé. C'est ce qui compte : vingt secondes pendant lesquelles
     * Jean se tait n'apprennent rien, et une minuterie aurait conclu que
     * c'était fait. Un garde-fou de durée existe tout de même chez l'appelant,
     * pour qu'un apprentissage ne puisse pas durer indéfiniment.
     */
    fun enroll(buffer: ShortArray, length: Int): Float

    /**
     * Clôt l'apprentissage et rend la signature sérialisée, ou null si ce qui a
     * été entendu ne suffit pas. Null n'est pas un échec technique mais un
     * refus délibéré : mieux vaut ne rien enregistrer qu'une signature bâtie
     * sur trois syllabes, qui ne reconnaîtrait personne et ferait chercher la
     * panne ailleurs.
     */
    fun finishEnrollment(): String?

    // ─── Reconnaissance ──────────────────────────────────────────────────

    /** Absorbe un bloc de son de la pièce et met à jour le verdict. */
    fun accept(buffer: ShortArray, length: Int, nowMs: Long)

    /**
     * Vrai si la prise de parole en cours est attribuée à Jean, faux si elle
     * l'est à quelqu'un d'autre, **null si rien ne permet de trancher** — et il
     * faut alors afficher normalement.
     *
     * Le troisième cas n'est pas une commodité : le doute doit profiter à la
     * lisibilité. Afficher en clair une phrase de Jean ne coûte que de la
     * place, l'atténuer à tort lui retire ce qu'il a besoin de lire.
     */
    fun currentSpeakerIsJean(): Boolean?

    /** Dernière ressemblance mesurée, en pourcentage. C'est sur elle que le seuil se règle. */
    fun lastSimilarityPercent(): Int?

    /** Part des prises de parole attribuées à Jean depuis la dernière lecture, puis remise à zéro. */
    fun consumeJeanSharePercent(): Int?

    /** Libère ce qui doit l'être (modèle natif, mémoire). */
    fun close()
}
