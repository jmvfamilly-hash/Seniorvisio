package com.seniorvisio.recueil

/**
 * Ce qu'un proche a composé pour Jean, et ce que la tablette en sait.
 *
 * ═══ POURQUOI « RECUEIL » ET PAS « ALBUM » ═══
 *
 * Le premier besoin était photographique. Le suivant ne l'est déjà plus :
 * vidéos, fils d'information ramenés à leur substance, et d'autres formats
 * ensuite. Un type nommé Album aurait rendu chaque ajout un peu faux, puis
 * franchement mensonger — et on aurait fini par le renommer de toute façon,
 * avec plus de code accroché dessus.
 *
 * ═══ CE QUI NE VIT PAS ICI ═══
 *
 * Aucune image, aucune vidéo, aucun octet de contenu. Ce modèle décrit ce
 * qu'il faut pour retrouver, vérifier et afficher — pas le contenu lui-même.
 * C'est ce qui permet au document Firestore correspondant de tenir en
 * quelques centaines d'octets par élément, là où une photo en base64 en pèse
 * cinq cent mille et fait sauter la limite du mébioctet.
 */

/**
 * Ce qu'un élément EST, et donc qui sait l'afficher.
 *
 * L'axe d'extension le plus probable de tout ce chantier : chaque type
 * nouveau apporte un rendu et un vérificateur, et rien d'autre (voir
 * docs/architecture-recueils.md).
 *
 * Les types non pris en charge existent DÉJÀ dans cette énumération, et
 * c'est délibéré : un recueil qui en contient doit pouvoir être lu, décrit et
 * refusé proprement par une tablette qui ne sait pas les afficher — plutôt
 * que de la faire échouer sur une valeur inconnue.
 */
enum class TypeElement(val étiquette: String) {
    PHOTO("photo"),
    VIDEO("vidéo"),
    TEXTE("texte"),
    INCONNU("inconnu");

    companion object {
        /**
         * Un type venu de Firestore, donc écrit ailleurs et possiblement plus
         * récent que cette version de l'application. Une valeur non reconnue
         * devient INCONNU et sera refusée en le disant, jamais une exception.
         */
        fun depuis(valeur: String?): TypeElement =
            entries.firstOrNull { it.étiquette.equals(valeur, ignoreCase = true) } ?: INCONNU
    }
}

/**
 * Comment le contenu arrive : une fois pour toutes, ou renouvelé.
 *
 * Une photo se télécharge et ne bouge plus. Un fil d'information, lui, se
 * rafraîchit — il n'a pas d'octets définitifs. Les deux aboutissent pourtant
 * au même endroit : quelque chose que la tablette sait afficher hors ligne.
 * Le lecteur ne fera pas la différence ; seule l'installation la fait.
 */
enum class NatureElement { FICHIER, FLUX }

/** Où en est la vérification d'un élément SUR CETTE TABLETTE. */
enum class ÉtatElement {
    /** Pas encore téléchargé, ou pas encore vérifié. */
    À_VÉRIFIER,

    /** Téléchargé, réellement décodé, rangé localement : affichable. */
    PRÊT,

    /** Téléchargé mais inaffichable ici. `cause` dit pourquoi. */
    REFUSÉ,
}

/**
 * Un élément d'un recueil.
 *
 * @param id identifiant stable, décidé par le PWA à la composition. Sert de
 *   nom de fichier local et de clé pour les commentaires : il ne doit donc
 *   jamais être réattribué à autre chose.
 * @param source URL de téléchargement (FICHIER) ou adresse du flux (FLUX).
 * @param cause renseignée uniquement en état REFUSÉ, et destinée à être lue
 *   par le proche dans le PWA — donc écrite pour lui, pas pour un journal.
 */
data class Element(
    val id: String,
    val type: TypeElement,
    val nature: NatureElement,
    val source: String,
    val ordre: Int,
    val état: ÉtatElement = ÉtatElement.À_VÉRIFIER,
    val cause: String? = null,
    /**
     * Nom du fichier local une fois rangé. Null tant qu'il ne l'est pas.
     *
     * Un seul champ quel que soit le type : dans tous les cas c'est un
     * fichier rangé sur la tablette et vérifié par le même vérificateur, et
     * lui donner un second nom obligerait chaque lecteur à savoir de quel
     * genre d'élément il s'occupe.
     */
    val fichierLocal: String? = null,
)

/**
 * Un recueil, tel que la tablette le connaît.
 *
 * @param état résumé calculé à partir des éléments — voir [étatGlobal]. Il
 *   n'est pas stocké séparément : deux vérités sur le même fait finissent
 *   toujours par diverger.
 */
data class Recueil(
    val id: String,
    val titre: String,
    val crééPar: String,
    val éléments: List<Element>,
) {
    /** Les seuls éléments que le lecteur acceptera d'afficher. */
    val prêts: List<Element> get() = éléments.filter { it.état == ÉtatElement.PRÊT }.sortedBy { it.ordre }

    /**
     * Ce qu'on peut dire du recueil en un mot, pour le PWA.
     *
     * « incomplet » n'est pas un échec : c'est un recueil utilisable dont une
     * partie n'est pas passée. Le distinguer de « vide » évite de faire
     * renoncer un proche dont neuf photos sur dix fonctionnent.
     */
    fun étatGlobal(): String = when {
        éléments.isEmpty() -> "vide"
        éléments.any { it.état == ÉtatElement.À_VÉRIFIER } -> "installation en cours"
        prêts.isEmpty() -> "aucun élément affichable"
        éléments.any { it.état == ÉtatElement.REFUSÉ } -> "incomplet"
        else -> "installé"
    }
}
