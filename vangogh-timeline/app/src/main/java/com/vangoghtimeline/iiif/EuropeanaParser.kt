package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.RightsCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Œuvres de Van Gogh trouvées par la **Europeana Search API** (`/record/v2/search.json`).
 *
 * Chaque résultat (`items[]`) porte : `id` (`/{jeu de données}/{notice}`), `title[]`, `dcCreator[]`, `year[]`, `edmPreview[]`
 * (vignette Europeana) et `dataProvider[]` (le musée). Le **manifeste IIIF** de la notice se déduit de l'`id` :
 * `https://iiif.europeana.eu/presentation/{jeu}/{notice}/manifest` ; le visualiseur le lit (Presentation 2 ou 3) pour trouver le
 * service d'image du musée, et ouvre l'œuvre en zoom profond. En attendant, la carte affiche la vignette `edmPreview`.
 *
 * Limite : l'API ne donne que l'ANNÉE (`year`) → [com.vangoghtimeline.model.DatePrecision.YEAR].
 * Pur Kotlin : testé sur la JVM avec une réponse type.
 */
object EuropeanaParser {
    /** Clé de démonstration publique d'Europeana : volume limité, à remplacer par une clé personnelle (gratuite) en production. */
    const val DEMO_KEY = "api2demo"

    fun searchUrl(query: ArtworkQuery = ArtworkQuery.VAN_GOGH, key: String = DEMO_KEY): String =
        "https://api.europeana.eu/record/v2/search.json?wskey=$key" +
            "&query=who%3A%28%22" + java.net.URLEncoder.encode(query.artistName, "UTF-8") + "%22%29" +
            "&qf=TYPE%3AIMAGE&media=true&thumbnail=true&rows=100&profile=standard"

    /**
     * Recherches à essayer dans l'ordre : le nom exact ; le nom sans accents (les notices portent souvent « Joaquin Sorolla ») ;
     * le nom de famille seul (« Sorolla y Bastida, Joaquín »). Les doublons d'adresse sont retirés.
     */
    fun searchVariants(query: ArtworkQuery, key: String = DEMO_KEY): List<String> = variants(query, key).map { it.first }

    /** Même liste, avec pour chaque adresse : vrai si c'est la recherche par nom de famille seul (analyse stricte du créateur). */
    fun variants(query: ArtworkQuery, key: String = DEMO_KEY): List<Pair<String, Boolean>> {
        fun url(who: String) = "https://api.europeana.eu/record/v2/search.json?wskey=$key&query=who%3A%28" + who + "%29" +
            "&qf=TYPE%3AIMAGE&media=true&thumbnail=true&rows=100&profile=standard"
        fun quoted(name: String) = "%22" + java.net.URLEncoder.encode(name, "UTF-8") + "%22"
        val parts = query.artistName.trim().split(Regex("\\s+"))
        val last = parts.last()
        val first = parts.dropLast(1).joinToString(" ")
        val list = ArrayList<Pair<String, Boolean>>()
        list += searchUrl(query, key) to false
        list += url(quoted(query.asciiName())) to false
        if (first.isNotEmpty()) {
            // « Nom, Prénom » : la forme des catalogues (« Sargent, John Singer »)
            list += url(quoted("$last, $first")) to false
            // nom ET prénom, sans guillemets : tolère l'ordre et les mots intercalés ; créateur exigé (homonymes)
            list += url("%28" + java.net.URLEncoder.encode(last, "UTF-8") + "%20AND%20" + java.net.URLEncoder.encode(first.split(' ').first(), "UTF-8") + "%29") to true
        }
        list += url(java.net.URLEncoder.encode(query.match, "UTF-8")) to true
        return list.distinctBy { it.first }
    }

    /** Recherche par fournisseur de données (ex. « Museo Sorolla ») : toutes les notices image de ce musée. */
    fun dataProviderUrl(provider: String, key: String = DEMO_KEY): String =
        "https://api.europeana.eu/record/v2/search.json?wskey=$key&query=*" +
            "&qf=DATA_PROVIDER%3A%22" + java.net.URLEncoder.encode(provider, "UTF-8") + "%22" +
            "&qf=TYPE%3AIMAGE&media=true&thumbnail=true&rows=100&profile=standard"

    fun manifestUrlOf(recordId: String): String = "https://iiif.europeana.eu/presentation/" + recordId.trim('/') + "/manifest"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param strictCreator exige un créateur déclaré (`dcCreator`) qui désigne l'artiste : pour la recherche par nom de famille seul,
     *   qui ramène aussi des homonymes (botanistes…) et des notices sans créateur.
     */
    fun parse(text: String, query: ArtworkQuery = ArtworkQuery.VAN_GOGH, tally: Tally? = null, strictCreator: Boolean = false): List<Artwork> {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
        val items = root?.get("items") as? JsonArray ?: run { tally?.shape = JsonReading.describeShape(text); return emptyList() }
        tally?.let { it.raw += items.size }
        val artworks = items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.count { c -> c == '/' } >= 2 } ?: return@mapNotNull null
            val creators = strings(o["dcCreator"])
            if (creators.isEmpty() && strictCreator) { tally?.drop("sans créateur déclaré"); return@mapNotNull null }
            if (creators.isNotEmpty() && creators.none(query::matchesCreator)) { tally?.drop("d'un autre créateur"); return@mapNotNull null }
            val title = strings(o["title"]).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
            // année de secours : la période (`edmTimespanLabel`, ex. « 1880 - 1890 » → 1885), sinon la moitié de la période d'activité (voir dateOf)
            val year = strings(o["year"]).firstNotNullOfOrNull { Regex("""\b(\d{4})\b""").find(it)?.groupValues?.get(1)?.toInt() }
                ?: timespanYear(o)
            val date = dateOf(year, query, tally) ?: return@mapNotNull null
            val noPreview = (o["previewNoDistribute"] as? JsonPrimitive)?.contentOrNull == "true"
            // « previewNoDistribute » : le fournisseur interdit de redistribuer l'aperçu → pas de vignette (l'œuvre reste consultable)
            val preview = if (noPreview) null else strings(o["edmPreview"]).firstOrNull()
            val museum = strings(o["dataProvider"]).firstOrNull().orEmpty()
            val rightsUrl = strings(o["rights"]).firstOrNull()
            val rights = RightsCatalog.fromRightsUrl(rightsUrl, attribution = museum.ifEmpty { null }?.let { "$it, via Europeana" })
                .let { if (noPreview) it.copy(conditions = it.conditions + " Aperçu non redistribuable : vignette masquée.") else it }
            Artwork(
                id = "europeana-" + id.trim('/').replace('/', '_'),
                title = title.trim(),
                date = date,
                iiif = IiifRef(manifestUrl = manifestUrlOf(id), thumbnailUrl = preview),
                provider = if (museum.isEmpty()) "Europeana" else "Europeana · $museum",
                rights = rights,
            )
        }
        if (tally != null) { tally.kept += artworks.size; if (artworks.isEmpty()) tally.shape = JsonReading.describeShape(text) }
        return artworks
    }

    /** Toutes les chaînes d'un élément JSON (liste, objet de langues…), à plat. */
    private fun allStrings(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.flatMap { allStrings(it) }
        is JsonObject -> e.values.flatMap { allStrings(it) }
        is JsonPrimitive -> listOfNotNull(e.takeIf { it.isString }?.contentOrNull)
        else -> emptyList()
    }

    /** Année de secours d'une notice : milieu des années trouvées dans `edmTimespanLabel` (écart d'au plus 50 ans), sinon rien. */
    internal fun timespanYear(o: JsonObject): Int? {
        val years = (allStrings(o["edmTimespanLabel"]) + allStrings(o["edmTimespanLabelLangAware"]))
            .flatMap { Regex("""(?<!\d)(\d{4})(?!\d)""").findAll(it).map { m -> m.groupValues[1].toInt() }.toList() }
            .filter { it in 1000..2100 }
        if (years.isEmpty() || years.max() - years.min() > 50) return null
        return (years.min() + years.max()) / 2
    }

    private fun strings(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(e.takeIf { it.isString }?.contentOrNull)
        else -> emptyList()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
