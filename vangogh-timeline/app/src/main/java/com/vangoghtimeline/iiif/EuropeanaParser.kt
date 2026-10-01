package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.IiifRef
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
            "&qf=TYPE%3AIMAGE&media=true&thumbnail=true&reusability=open&rows=100&profile=standard"

    /**
     * Recherches à essayer dans l'ordre : le nom exact ; le nom sans accents (les notices portent souvent « Joaquin Sorolla ») ;
     * le nom de famille seul (« Sorolla y Bastida, Joaquín »). Les doublons d'adresse sont retirés.
     */
    fun searchVariants(query: ArtworkQuery, key: String = DEMO_KEY): List<String> {
        fun url(who: String) = "https://api.europeana.eu/record/v2/search.json?wskey=$key&query=who%3A%28" + who + "%29" +
            "&qf=TYPE%3AIMAGE&media=true&thumbnail=true&reusability=open&rows=100&profile=standard"
        fun quoted(name: String) = "%22" + java.net.URLEncoder.encode(name, "UTF-8") + "%22"
        return listOf(
            searchUrl(query, key),
            url(quoted(query.asciiName())),
            url(java.net.URLEncoder.encode(query.match, "UTF-8")),
        ).distinct()
    }

    fun manifestUrlOf(recordId: String): String = "https://iiif.europeana.eu/presentation/" + recordId.trim('/') + "/manifest"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, query: ArtworkQuery = ArtworkQuery.VAN_GOGH): List<Artwork> {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.count { c -> c == '/' } >= 2 } ?: return@mapNotNull null
            val creators = strings(o["dcCreator"])
            if (creators.isNotEmpty() && creators.none(query::matchesCreator)) return@mapNotNull null
            val title = strings(o["title"]).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
            val year = strings(o["year"]).firstNotNullOfOrNull { Regex("""\b(\d{4})\b""").find(it)?.groupValues?.get(1)?.toInt() }
                ?: return@mapNotNull null
            if (year !in query.years) return@mapNotNull null
            val preview = strings(o["edmPreview"]).firstOrNull()
            val museum = strings(o["dataProvider"]).firstOrNull().orEmpty()
            Artwork(
                id = "europeana-" + id.trim('/').replace('/', '_'),
                title = title.trim(),
                date = ArtworkDate.year(year),
                iiif = IiifRef(manifestUrl = manifestUrlOf(id), thumbnailUrl = preview),
                provider = if (museum.isEmpty()) "Europeana" else "Europeana · $museum",
            )
        }
    }

    private fun strings(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(e.takeIf { it.isString }?.contentOrNull)
        else -> emptyList()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
