package com.vangoghtimeline.model

/** Précision réelle de la date connue : beaucoup d'œuvres ne sont datées qu'au mois, voire à l'année. */
enum class DatePrecision { DAY, MONTH, YEAR }

/**
 * Date de création. Stockée en champs primitifs (année, mois, jour) plutôt que dans un type de date externe :
 * la classe reste stable pour Compose (pas de recomposition inutile) et n'a aucune dépendance.
 *
 * - [DatePrecision.DAY]   : `year-month-day` est la date exacte.
 * - [DatePrecision.MONTH] : seul `year-month` est connu ; [day] vaut 1 et n'a pas de sens.
 * - [DatePrecision.YEAR]  : seule `year` est connue ; [month] et [day] valent 1.
 */
data class ArtworkDate(
    val year: Int,
    val month: Int = 1,
    val day: Int = 1,
    val precision: DatePrecision = DatePrecision.DAY,
    /** Vrai si la date est INCONNUE et placée à la moitié de la période d'activité de l'artiste (faute de mieux) : à ne pas présenter comme une date réelle. */
    val estimated: Boolean = false,
) {
    init { require(CivilCalendar.isValid(year, month, day)) { "date invalide: $year-$month-$day" } }

    /** Date enregistrée, au jour près (premier du mois / premier janvier si la précision est moindre). */
    val recordedEpochDay: Long get() = CivilCalendar.epochDay(year, month, day)

    /**
     * Position sur l'axe du temps. Une date exacte est placée au jour près ; une date au mois est placée au
     * milieu du mois, une date à l'année au milieu de l'année : on ne prétend pas connaître le jour, et l'erreur
     * maximale est minimale.
     */
    val positionEpochDay: Long
        get() = when (precision) {
            DatePrecision.DAY -> recordedEpochDay
            DatePrecision.MONTH -> CivilCalendar.epochDay(year, month, 15)
            DatePrecision.YEAR -> CivilCalendar.epochDay(year, 7, 2)
        }

    fun iso(): String = when (precision) {
        DatePrecision.DAY -> "%04d-%02d-%02d".format(year, month, day)
        DatePrecision.MONTH -> "%04d-%02d".format(year, month)
        DatePrecision.YEAR -> "%04d".format(year)
    }

    companion object {
        fun exact(year: Int, month: Int, day: Int) = ArtworkDate(year, month, day, DatePrecision.DAY)
        fun month(year: Int, month: Int) = ArtworkDate(year, month, 1, DatePrecision.MONTH)
        fun year(year: Int) = ArtworkDate(year, 1, 1, DatePrecision.YEAR)

        /** Date inconnue, placée à l'année [year] (moitié de la période d'activité) et marquée comme telle. */
        fun estimated(year: Int) = ArtworkDate(year, 1, 1, DatePrecision.YEAR, estimated = true)

        /**
         * `YYYY`, `YYYY-MM`, `YYYY-MM-DD` ou une `xsd:dateTime` (`1888-10-01T00:00:00Z`, le format de `navDate`).
         * @param precision impose la précision (ex. lue dans les métadonnées) ; par défaut, celle du texte.
         */
        fun parseIso(text: String, precision: DatePrecision? = null): ArtworkDate? {
            val m = Regex("""^\s*(-?\d{4})(?:-(\d{2})(?:-(\d{2}))?)?(?:[T ].*)?\s*$""").matchEntire(text) ?: return null
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].ifEmpty { null }?.toInt()
            val d = m.groupValues[3].ifEmpty { null }?.toInt()
            val natural = when {
                d != null -> DatePrecision.DAY
                mo != null -> DatePrecision.MONTH
                else -> DatePrecision.YEAR
            }
            // Une précision imposée ne peut pas être plus fine que ce que le texte contient.
            val p = if (precision == null || precision.ordinal < natural.ordinal) natural else precision
            val month = if (p == DatePrecision.YEAR) 1 else (mo ?: 1)
            val day = if (p == DatePrecision.DAY) (d ?: 1) else 1
            return if (CivilCalendar.isValid(y, month, day)) ArtworkDate(y, month, day, p) else null
        }
    }
}

/**
 * Lien vers les images IIIF d'une œuvre.
 *
 * @property imageServiceId URL de base du service d'image (`…/iiif/2/F482`), sans `/info.json` : c'est elle qui permet
 *   de demander EXACTEMENT la taille de vignette voulue, et d'ouvrir l'œuvre en zoom profond (`infoJsonUrl`).
 * @property thumbnailUrl vignette fournie par le manifeste, utilisée faute de service d'image.
 * @property imageUrl image ordinaire (sans service IIIF) : ouverte comme `static:{url}` ([com.iiifviewer.StaticImageUrl]).
 */
data class IiifRef(
    val manifestUrl: String,
    val imageServiceId: String? = null,
    val thumbnailUrl: String? = null,
    val canvasWidth: Int? = null,
    val canvasHeight: Int? = null,
    /** Image ordinaire (JPEG), pour un musée sans service IIIF : le visualiseur la découpe lui-même en tuiles. */
    val imageUrl: String? = null,
) {
    /** Largeur / hauteur de l'œuvre, pour réserver la bonne place avant le chargement (1,25 par défaut). */
    val aspectRatio: Float
        get() = if (canvasWidth != null && canvasHeight != null && canvasWidth > 0 && canvasHeight > 0)
            canvasWidth.toFloat() / canvasHeight else 1.25f

    val infoJsonUrl: String? get() = imageServiceId?.trimEnd('/')?.plus("/info.json")

    /**
     * `{service}/full/{w},/0/default.jpg` : largeur imposée, hauteur déduite (forme du niveau 1 de l'Image API, acceptée par tous
     * les serveurs). Le serveur redimensionne : on ne télécharge que les pixels utiles. [maxHeightPx] est gardé pour la
     * signature et pour borner la largeur d'une œuvre très haute (portrait) : sa vignette tient alors dans la hauteur demandée.
     */
    fun thumbnailUrlFor(maxWidthPx: Int, maxHeightPx: Int): String? {
        val service = imageServiceId ?: return thumbnailUrl
        val byHeight = (maxHeightPx * aspectRatio).toInt().coerceAtLeast(1)
        val width = minOf(maxWidthPx, byHeight)
        return "${service.trimEnd('/')}/full/$width,/0/default.jpg"
    }

    /** Ce qu'on passe au visualiseur : le manifeste s'il est réel (http), sinon l'`info.json` du service d'image. */
    val viewerUrl: String? get() = manifestUrl.takeIf { it.startsWith("http") } ?: infoJsonUrl ?: imageUrl?.let { "static:$it" }

    /** Vrai si le visualiseur peut ouvrir cette œuvre : un service d'image, ou un manifeste réel (http). */
    val canOpenViewer: Boolean get() = imageServiceId != null || imageUrl != null || manifestUrl.startsWith("http")
}

/** Une œuvre et ses métadonnées, telles que lues dans son manifeste IIIF. */
data class Artwork(
    val id: String,
    val title: String,
    val date: ArtworkDate,
    val place: String? = null,
    val medium: String? = null,
    val iiif: IiifRef,
    /** Musée / agrégateur d'où vient l'œuvre (ex. « Rijksmuseum »), pour le crédit et la déduplication. Vide = inconnu. */
    val provider: String = "",
    /** Licence et conditions de consultation (voir [RightsInfo]) ; `null` = non renseigné. */
    val rights: RightsInfo? = null,
)
