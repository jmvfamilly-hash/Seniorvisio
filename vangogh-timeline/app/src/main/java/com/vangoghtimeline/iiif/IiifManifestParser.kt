package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.DatePrecision
import com.vangoghtimeline.model.IiifRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Lit un manifeste **IIIF Presentation API 3.0** (et tolère les usages de la 2.x) et en tire une [Artwork].
 *
 * La date exacte vient de **`navDate`**, la propriété IIIF prévue pour situer une ressource dans le temps
 * (`xsd:dateTime`, ex. `1888-10-01T00:00:00Z`). À défaut, d'une entrée `metadata` dont l'étiquette évoque une date
 * (`Date`, `Created`, `Datation`…). Une entrée `metadata` « Date precision » / « Précision de la date »
 * (`day`|`month`|`year`, ou `jour`|`mois`|`année`) indique que la date n'est pas connue au jour près.
 */
object IiifManifestParser {

    class ParseException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, manifestUrl: String, languages: List<String> = listOf("fr", "en")): Artwork {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject ?: throw ParseException("le manifeste n'est pas un objet JSON")
        } catch (e: ParseException) {
            throw e
        } catch (e: Exception) {
            throw ParseException("JSON invalide : ${e.message}")
        }

        val id = root.string("id") ?: root.string("@id") ?: manifestUrl
        val title = localized(root["label"], languages) ?: throw ParseException("manifeste sans `label`")
        val metadata = metadata(root["metadata"], languages)

        val precision = metadata.firstOrNull { (k, _) -> k.contains("precision") || k.contains("précision") }
            ?.second?.let(::precisionOf)
        val date = root.string("navDate")?.let { ArtworkDate.parseIso(it, precision) }
            ?: metadata.firstOrNull { (k, _) -> DATE_KEYS.any { k.contains(it) } && !k.contains("precision") && !k.contains("précision") }
                ?.second?.let { ArtworkDate.parseIso(it, precision) }
            ?: throw ParseException("pas de date de création (`navDate` ou métadonnée « date »)")

        val canvas = root["items"].array()?.firstOrNull().obj()
        val body = canvas?.get("items").array()?.firstOrNull().obj()
            ?.get("items").array()?.firstOrNull().obj()?.get("body").let { it.obj() ?: it.array()?.firstOrNull().obj() }
        val service = body?.get("service").array()?.firstOrNull().obj()
            ?: body?.get("service").obj()
        val serviceId = service?.let { it.string("id") ?: it.string("@id") }
        val thumb = thumbnailId(root["thumbnail"]) ?: thumbnailId(canvas?.get("thumbnail"))

        return Artwork(
            id = id,
            title = title,
            date = date,
            place = metadata.firstOrNull { (k, _) -> PLACE_KEYS.any { k.contains(it) } }?.second,
            medium = metadata.firstOrNull { (k, _) -> MEDIUM_KEYS.any { k.contains(it) } }?.second,
            iiif = IiifRef(
                manifestUrl = manifestUrl,
                imageServiceId = serviceId,
                thumbnailUrl = thumb,
                canvasWidth = canvas?.get("width").intValue(),
                canvasHeight = canvas?.get("height").intValue(),
            ),
        )
    }

    private val DATE_KEYS = listOf("date", "created", "datation", "creation", "création")
    private val PLACE_KEYS = listOf("place", "lieu", "location", "made in")
    private val MEDIUM_KEYS = listOf("medium", "technique", "matériau", "material")

    private fun precisionOf(value: String): DatePrecision? = when (value.trim().lowercase()) {
        "day", "jour", "exact" -> DatePrecision.DAY
        "month", "mois" -> DatePrecision.MONTH
        "year", "année", "annee" -> DatePrecision.YEAR
        else -> null
    }

    /** `metadata` : liste de { label, value }, tous deux des « cartes de langues ». Étiquettes en minuscules. */
    private fun metadata(el: JsonElement?, languages: List<String>): List<Pair<String, String>> =
        el.array().orEmpty().mapNotNull { entry ->
            val o = entry.obj() ?: return@mapNotNull null
            val label = localized(o["label"], languages)?.lowercase() ?: return@mapNotNull null
            val value = localized(o["value"], languages) ?: return@mapNotNull null
            label to value
        }

    /**
     * Texte localisé : `{"fr": ["Nuit étoilée"], "en": [...]}` (v3), `"texte"` ou `[{"@value": …, "@language": …}]` (v2).
     * On préfère les langues demandées, puis `none`, puis la première venue.
     */
    internal fun localized(el: JsonElement?, languages: List<String>): String? = when (el) {
        null -> null
        is JsonPrimitive -> el.contentOrNull
        is JsonArray -> el.firstNotNullOfOrNull { e ->
            val o = e.obj()
            if (o != null && (o["@language"].str() in languages)) o.string("@value") else null
        } ?: el.firstNotNullOfOrNull { e -> localized(e, languages) }
        is JsonObject -> if (el.containsKey("@value")) el.string("@value") else {
            (languages + "none" + el.keys).firstNotNullOfOrNull { lang ->
                el[lang].array()?.firstOrNull().str()
            }
        }
    }

    private fun thumbnailId(el: JsonElement?): String? =
        (el.array()?.firstOrNull() ?: el).let { it.obj()?.let { o -> o.string("id") ?: o.string("@id") } ?: it.str() }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.array(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonElement?.intValue(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonObject.string(key: String): String? = this[key].str()
}
