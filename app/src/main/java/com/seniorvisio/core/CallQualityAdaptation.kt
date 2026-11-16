package com.seniorvisio.core

/**
 * Décide, à partir du débit disponible et du temps de réponse mesurés
 * pendant un appel, jusqu'où réduire ce qui est envoyé — jamais le son ni
 * les sous-titres, qui coûtent presque rien et comptent le plus.
 *
 * ═══ QUATRE PALIERS, UN SEUL SENS DE LECTURE ═══
 *
 * - [Palier.NORMAL] : rien ne change.
 * - [Palier.SANS_VIDEO_PROCHE] : la vidéo envoyée par le proche n'apporte
 *   plus rien à ce débit ; remplacée par sa photo, déjà affichée à la
 *   sonnerie (voir IncomingCallActivity.imageCallerPhoto) — aucune image à
 *   recharger, juste à rendre à nouveau visible.
 * - [Palier.VIDEO_JEAN_MINI] : la vidéo de Jean tombe à une définition
 *   minuscule — pas coupée, juste assez pour voir qu'il est bien là, dans
 *   son fauteuil.
 * - [Palier.AUDIO_SEUL] : plus aucune vidéo dans aucun sens. Le son et les
 *   sous-titres, eux, ne changent jamais à aucun palier.
 *
 * ═══ POURQUOI UNE HYSTÉRÈSE, ET ASYMÉTRIQUE ═══
 *
 * Le débit observé chez Jean le 16 novembre oscille en quelques secondes
 * entre quelques dizaines et plusieurs milliers de kb/s (voir les deux
 * journaux d'appel de ce jour-là, repris ci-dessous dans [Profils]).
 * Réagir à chaque mesure ferait clignoter l'appel sans cesse. Descendre
 * doit donc rester rapide — Jean ne doit pas attendre trente secondes
 * devant une image qui rame avant qu'elle se simplifie — remonter doit au
 * contraire se mériter : une embellie de deux secondes ne garantit rien,
 * la reprendre pour la perdre aussitôt serait pire que de patienter.
 *
 * ═══ CE QUE ÇA NE FAIT PAS ═══
 *
 * N'envoie ni ne reçoit rien : c'est une fonction pure, appelée à chaque
 * nouveau relevé réseau, qui ne fait que répondre « quel palier appliquer
 * maintenant ». Appliquer ce palier (couper une piste, réduire une
 * définition, afficher une photo) est la responsabilité de l'appelant —
 * voir WebRtcCallEngine.
 */
class CallQualityAdaptation(
    private val seuils: Seuils,
    private val hystérèseDescenteMs: Long = 3_000L,
    private val hystérèseMontéeMs: Long = 12_000L,
) {
    enum class Palier(val niveau: Int) {
        NORMAL(0),
        SANS_VIDEO_PROCHE(1),
        VIDEO_JEAN_MINI(2),
        AUDIO_SEUL(3),
    }

    data class Seuils(
        val dispoSansVideoProcheKbps: Int,
        val dispoVideoJeanMiniKbps: Int,
        val dispoAudioSeulKbps: Int,
    )

    private var palierAppliqué = Palier.NORMAL
    private var palierCandidat = Palier.NORMAL
    private var candidatDepuisMs = 0L

    /**
     * Un relevé RTT extrême (plusieurs secondes) prime sur le débit annoncé :
     * on a constaté chez Jean un débit resté bloqué à 84 kb/s — un plafond
     * d'estimation, pas une vraie mesure — pendant que le temps de réponse
     * grimpait, lui, jusqu'à 40 secondes. Le débit seul aurait caché cette
     * aggravation.
     */
    private fun palierSouhaité(dispoKbps: Int, rttMs: Int): Palier = when {
        rttMs >= RTT_CRITIQUE_MS -> Palier.AUDIO_SEUL
        dispoKbps <= seuils.dispoAudioSeulKbps -> Palier.AUDIO_SEUL
        dispoKbps <= seuils.dispoVideoJeanMiniKbps -> Palier.VIDEO_JEAN_MINI
        dispoKbps <= seuils.dispoSansVideoProcheKbps -> Palier.SANS_VIDEO_PROCHE
        else -> Palier.NORMAL
    }

    /** @return le palier à appliquer maintenant — peut rester inchangé tant que l'hystérèse n'a pas élapsé. */
    fun évaluer(dispoKbps: Int, rttMs: Int, horodatageMs: Long): Palier {
        val souhaité = palierSouhaité(dispoKbps, rttMs)
        if (souhaité != palierCandidat) {
            palierCandidat = souhaité
            candidatDepuisMs = horodatageMs
        }
        val descend = palierCandidat.niveau > palierAppliqué.niveau
        val hystérèse = if (descend) hystérèseDescenteMs else hystérèseMontéeMs
        if (palierCandidat != palierAppliqué && horodatageMs - candidatDepuisMs >= hystérèse) {
            palierAppliqué = palierCandidat
        }
        return palierAppliqué
    }

    /** Remis à neuf à chaque nouvel appel : un palier ne doit jamais survivre au suivant. */
    fun réinitialiser() {
        palierAppliqué = Palier.NORMAL
        palierCandidat = Palier.NORMAL
        candidatDepuisMs = 0L
    }

    companion object {
        private const val RTT_CRITIQUE_MS = 3_000

        fun seuilsDepuis(config: AdminConfig) = Seuils(
            dispoSansVideoProcheKbps = config.seuilSansVideoProcheKbps,
            dispoVideoJeanMiniKbps = config.seuilVideoJeanMiniKbps,
            dispoAudioSeulKbps = config.seuilAudioSeulKbps,
        )
    }

    /**
     * Profils de réseau simulé, rejoués à la place des vraies statistiques
     * WebRTC pendant un appel (voir AdminConfig.simulationRéseauProfil et son
     * garde-fou « jamais en production »).
     *
     * Les deux profils ci-dessous ne sont pas inventés : ce sont les débits
     * et temps de réponse RÉELLEMENT mesurés et journalisés chez Jean le
     * 16 novembre 2026, sur deux appels distincts — l'un qui s'effondre puis
     * se rétablit avant de couper net, l'autre qui ne décolle quasiment
     * jamais d'un plafond bas avant de couper après une remontée brève.
     * Les rejouer permet d'éprouver les quatre paliers sans attendre qu'un
     * aussi mauvais réseau se reproduise.
     */
    object Profils {

        /** Un point du profil : à quel instant depuis le début de l'appel, quel débit et quel RTT. */
        data class Point(val depuisMs: Long, val dispoKbps: Int, val rttMs: Int)

        /**
         * Appel du 16/11 à 19h26 (callId=RJdvNjGQu7AGYni8BUM5) : bon débit,
         * effondrement sévère (RTT jusqu'à 5,9 s, débit sous 90 kb/s), puis
         * vraie reprise (débit remonté à plusieurs Mb/s) — et malgré cette
         * reprise, coupure nette et définitive quelques dizaines de secondes
         * plus tard.
         */
        val APPEL_JEAN_1 = listOf(
            Point(0, 1744, 60),
            Point(15_000, 230, 57),
            Point(30_000, 85, 133),
            Point(48_000, 84, 2_434),
            Point(66_000, 84, 5_886),
            Point(81_000, 86, 441),
            Point(96_000, 248, 78),
            Point(111_000, 695, 55),
            Point(126_000, 2_080, 73),
            Point(141_000, 6_036, 83),
            Point(156_000, 2_803, 54),
            Point(171_000, 0, 20_000),
        )

        /**
         * Appel du 16/11 à 19h37 (callId=AUtxsE70RwPFB8U4jFrI) : débit
         * presque toujours bloqué entre 84 et 250 kb/s, jamais vraiment bon,
         * avec un trou très profond en fin d'appel (RTT jusqu'à 40 s) avant
         * une coupure qui, cette fois, ne se rétablit pas.
         */
        val APPEL_JEAN_2 = listOf(
            Point(0, 702, 80),
            Point(15_000, 86, 406),
            Point(30_000, 84, 146),
            Point(45_000, 84, 830),
            Point(66_000, 84, 68),
            Point(111_000, 90, 129),
            Point(126_000, 106, 75),
            Point(141_000, 85, 175),
            Point(171_000, 94, 143),
            Point(186_000, 200, 77),
            Point(216_000, 664, 55),
            Point(246_000, 84, 146),
            Point(276_000, 530, 530),
            Point(306_000, 388, 388),
            Point(336_000, 4_685, 4_685),
            Point(366_000, 209, 428),
            Point(396_000, 162, 85),
            Point(426_000, 7_532, 7_532),
            Point(441_000, 18_759, 18_759),
            Point(456_000, 8_391, 8_391),
            Point(471_000, 19_068, 19_068),
            Point(486_000, 40_451, 40_451),
            Point(501_000, 26_403, 26_403),
            Point(516_000, 0, 20_000),
        )

        fun parNom(nom: String): List<Point>? = when (nom) {
            "jean_appel_1" -> APPEL_JEAN_1
            "jean_appel_2" -> APPEL_JEAN_2
            else -> null
        }

        /**
         * Le point du profil applicable à cet instant depuis le début de
         * l'appel — celui dont le repère est le plus proche sans le
         * dépasser, pour que les mesures restent stables entre deux points
         * au lieu de sauter d'une valeur à l'autre sans transition.
         */
        fun àInstant(profil: List<Point>, depuisMs: Long): Point =
            profil.lastOrNull { it.depuisMs <= depuisMs } ?: profil.first()
    }
}
