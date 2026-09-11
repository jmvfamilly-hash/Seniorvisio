package com.papyrus

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

    /**
     * LE SEUL MODE LIVRÉ. Les deux autres sont des instruments de mesure.
     *
     * ═══ Pourquoi ni l'un ni l'autre des deux précédents ═══
     *
     * L'API pure n'écrit rien avant la toute fin de la session : elle mesure
     * bien le comportement du moteur, mais laisse un écran vide pendant qu'on
     * parle. Le mode événements s'appuie sur les signaux de début et de fin de
     * parole, dont la trace montre qu'ils ne se déclenchent pas de façon
     * fiable sur un monologue — un seul début de parole sur seize secondes et
     * demie de parole continue, aucune fin intermédiaire. Un découpage assis
     * dessus manque tout ce qui dure.
     *
     * ═══ Ce que fait celui-ci ═══
     *
     * Il ne demande RIEN au moteur sur le séquencement — aucune consigne de
     * silence, aucun plafond — et il ne lui demande rien non plus sur le
     * découpage : ni la fin ni le début de parole ne déclenchent quoi que ce
     * soit. Les résultats intermédiaires sont la seule source, et c'est la
     * stabilité de leur texte qui décide ce qui est acquis (voir
     * WordStabiliser).
     *
     * Le moteur reste ainsi ce qu'il sait faire — transcrire — et le rythme
     * appartient entièrement à l'application, ce qui est la condition pour
     * qu'il soit régulier.
     */
    SEQUENCE("séquence"),
}
