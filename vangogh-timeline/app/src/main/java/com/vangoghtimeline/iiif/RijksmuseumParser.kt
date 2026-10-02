package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.RightsCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Œuvres de Van Gogh du **Rijksmuseum**, via leurs « Data Services » (sans clé), en Linked Art :
 *
 * 1. **Recherche** `https://data.rijksmuseum.nl/search/collection?creator=…&imageAvailable=true` : une page d'identifiants
 *    d'objets (`orderedItems[].id`) et le lien de la page suivante (`next.id`).
 * 2. **Objet** (`https://id.rijksmuseum.nl/{n}`, en `application/ld+json`) : titre (`identified_by`), dates de production
 *    (`produced_by.timespan`), et le lien vers son image `shows[].id` (un *VisualItem*).
 * 3. **VisualItem** : `digitally_shown_by[].id` (un *DigitalObject*).
 * 4. **DigitalObject** : `access_point[].id`, l'URL d'une image IIIF ; on en déduit le service d'image ([IiifImageUrl]).
 *
 * Seul fichier de lecture : pur Kotlin (le réseau est dans [RijksmuseumRepository]), testé sur la JVM avec des réponses types.
 * Les formes lues sont celles du standard Linked Art ; tout champ absent ou inattendu fait ignorer l'œuvre, jamais échouer.
 */
object RijksmuseumParser {
    /** [creator] au format du musée : « Nom, Prénom » (ex. `Gogh, Vincent van`). */
    fun searchUrl(creator: String): String =
        "https://data.rijksmuseum.nl/search/collection?creator=" + java.net.URLEncoder.encode(creator, "UTF-8").replace("+", "%20") +
            "&imageAvailable=true"

    val SEARCH_URL: String = searchUrl("Gogh, Vincent van")

    private val json = Json { ignoreUnknownKeys = true }
    private const val AAT_ENGLISH = "300388277"
    private const val AAT_PRIMARY_NAME = "300404670"

    /** Une page de résultats : identifiants d'objets, et page suivante (ou `null`). */
    class Page(val objectIds: List<String>, val next: String?)

    fun parseSearchPage(text: String): Page {
        val root = obj(text) ?: return Page(emptyList(), null)
        val ids = list(root["orderedItems"]).mapNotNull { it.str("id") }
        val next = list(root["next"]).firstNotNullOfOrNull { it.str("id") }
        return Page(ids, next)
    }

    /** Ce que dit un objet : numéro, titre, date, et l'adresse de son VisualItem. */
    class ObjectInfo(val number: String, val title: String, val date: ArtworkDate, val visualItemUrl: String)

    fun parseObject(text: String): ObjectInfo? {
        val o = obj(text) ?: return null
        val id = o.str("id") ?: return null
        val number = id.trimEnd('/').substringAfterLast('/').ifEmpty { return null }
        val title = pickTitle(list(o["identified_by"])) ?: return null
        val timespan = list((o["produced_by"] as? JsonObject)?.get("timespan")).firstOrNull()
        val date = ProductionDate.of(timespan?.str("begin_of_the_begin"), timespan?.str("end_of_the_end")) ?: return null
        val visual = list(o["shows"]).firstNotNullOfOrNull { it.str("id") } ?: return null
        return ObjectInfo(number, title, date, visual)
    }

    /** Adresses des DigitalObject d'un VisualItem. */
    fun parseVisualItem(text: String): List<String> =
        list(obj(text)?.get("digitally_shown_by")).mapNotNull { it.str("id") }

    /** Service d'image IIIF d'un DigitalObject, déduit de l'URL de son `access_point`. */
    fun parseDigitalObject(text: String): String? =
        list(obj(text)?.get("access_point")).firstNotNullOfOrNull { it.str("id")?.let(IiifImageUrl::serviceBaseOf) }

    fun toArtwork(info: ObjectInfo, serviceId: String): Artwork = Artwork(
        id = "rijks-${info.number}",
        title = info.title,
        date = info.date,
        iiif = IiifRef(manifestUrl = "rijks:${info.number}", imageServiceId = serviceId),
        provider = "Rijksmuseum",
        // politique de données ouvertes du Rijksmuseum : œuvres du domaine public, images publiées en CC0 (non revérifié œuvre par œuvre)
        rights = RightsCatalog.publicDomain("Domaine public — images CC0 (Rijksmuseum)", "https://www.rijksmuseum.nl/en/research/conduct-research/data/policy"),
    )

    /** Anglais d'abord, sinon le terme préféré, sinon le premier nom. */
    private fun pickTitle(identified: List<JsonObject>): String? {
        val names = identified.filter { it.str("type") == "Name" && !it.str("content").isNullOrBlank() }
        fun has(o: JsonObject, key: String, aat: String) = list(o[key]).any { it.str("id")?.endsWith(aat) == true }
        val chosen = names.firstOrNull { has(it, "language", AAT_ENGLISH) }
            ?: names.firstOrNull { has(it, "classified_as", AAT_PRIMARY_NAME) }
            ?: names.firstOrNull()
        return chosen?.str("content")?.trim()
    }

    private fun obj(text: String): JsonObject? = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }

    /** Linked Art donne tantôt un objet, tantôt un tableau pour la même clé : on lit les deux. */
    private fun list(e: JsonElement?): List<JsonObject> = when (e) {
        is JsonArray -> e.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(e)
        else -> emptyList()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/**
 * Date d'une production Linked Art : un intervalle `[début, fin]` (ex. `1888-01-01T00:00:00Z` → `1888-12-31T23:59:59Z`).
 * On ne prétend pas savoir plus que l'intervalle : jour si les deux bornes tombent le même jour, mois si le même mois,
 * année si la même année ; sur plusieurs années, l'année médiane.
 */
object ProductionDate {
    fun of(begin: String?, end: String?): ArtworkDate? {
        val b = begin?.let { ArtworkDate.parseIso(it) } ?: end?.let { ArtworkDate.parseIso(it) } ?: return null
        val e = end?.let { ArtworkDate.parseIso(it) } ?: b
        return when {
            b.year != e.year -> ArtworkDate.year((b.year + e.year) / 2)
            b.month != e.month -> ArtworkDate.year(b.year)
            b.day != e.day -> ArtworkDate.month(b.year, b.month)
            else -> ArtworkDate.exact(b.year, b.month, b.day)
        }
    }
}
