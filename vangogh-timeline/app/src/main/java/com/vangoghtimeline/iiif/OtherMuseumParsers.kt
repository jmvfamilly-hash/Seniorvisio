package com.vangoghtimeline.iiif

import com.vangoghtimeline.iiif.JsonReading.arr
import com.vangoghtimeline.iiif.JsonReading.int
import com.vangoghtimeline.iiif.JsonReading.bool
import com.vangoghtimeline.iiif.JsonReading.obj
import com.vangoghtimeline.iiif.JsonReading.objOf
import com.vangoghtimeline.iiif.JsonReading.str
import com.vangoghtimeline.iiif.JsonReading.strings
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.IiifRef
import kotlinx.serialization.json.JsonObject

/**
 * The Metropolitan Museum of Art — Open Access API (sans clé), en deux temps : `search` rend des identifiants, puis `objects/{id}` une
 * notice. Pas de service IIIF : on donne l'URL de l'image (`primaryImage`), que le visualiseur découpe lui-même en tuiles
 * ([com.iiifviewer.StaticImageUrl]). Seules les œuvres du domaine public sont gardées.
 */
object MetParser {
    private const val BASE = "https://collectionapi.metmuseum.org/public/collection/v1"

    fun searchUrl(query: ArtworkQuery): String =
        "$BASE/search?hasImages=true&artistOrCulture=true&q=" + java.net.URLEncoder.encode(query.artistName, "UTF-8")

    fun objectUrl(id: Int): String = "$BASE/objects/$id"

    /**
     * Adresses de recherche à essayer dans l'ordre : complète ; `q` seul (au cas où un des filtres serait refusé) ; peintures
     * européennes (`departmentId=11`), la section où se trouvent Van Gogh, Sargent, Renoir…
     */
    fun searchVariants(query: ArtworkQuery): List<String> {
        val q = java.net.URLEncoder.encode(query.artistName, "UTF-8")
        return listOf(searchUrl(query), "$BASE/search?q=$q", "$BASE/search?departmentId=11&q=$q")
    }

    /** Identifiants de la réponse de recherche (`objectIDs` peut être `null` quand il n'y a aucun résultat). */
    fun parseSearch(text: String): List<Int> = obj(text)?.arr("objectIDs").orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }

    fun parseObject(text: String, query: ArtworkQuery): Artwork? {
        val o = obj(text) ?: return null
        val id = o.int("objectID") ?: return null
        if (o.bool("isPublicDomain") != true) return null
        val image = o.str("primaryImage")?.takeIf { it.startsWith("http") } ?: return null
        if (o.str("artistDisplayName")?.let(query::matchesCreator) != true) return null
        val title = o.str("title")?.takeIf { it.isNotBlank() } ?: return null
        val year = yearOf(o.int("objectBeginDate"), o.int("objectEndDate")) ?: return null
        if (year !in query.years) return null
        return Artwork(
            id = "met-$id", title = title.trim(), date = ArtworkDate.year(year), medium = o.str("medium")?.takeIf { it.isNotBlank() },
            iiif = IiifRef(manifestUrl = "met:$id", thumbnailUrl = o.str("primaryImageSmall")?.takeIf { it.startsWith("http") }, imageUrl = image),
            provider = "The Metropolitan Museum of Art",
        )
    }

    /** Intervalle de production → année : celle des deux bornes si elles sont égales, sinon l'année médiane. */
    internal fun yearOf(begin: Int?, end: Int?): Int? = when {
        begin == null && end == null -> null
        begin == null -> end
        end == null -> begin
        else -> (begin + end) / 2
    }?.takeIf { it in 1000..2100 }
}

/**
 * Cleveland Museum of Art — Open Access API (sans clé), une seule requête. Pas de service IIIF : on donne l'image « print » (JPEG de
 * grande taille) que le visualiseur découpe ; la vignette est l'image « web ».
 */
object ClevelandParser {
    fun searchUrl(query: ArtworkQuery): String =
        "https://openaccess-api.clevelandart.org/api/artworks/?cc0=1&has_image=1&limit=100&artists=" +
            java.net.URLEncoder.encode(query.artistName, "UTF-8")

    fun parse(text: String, query: ArtworkQuery): List<Artwork> =
        obj(text)?.arr("data").orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.int("id") ?: return@mapNotNull null
            val title = o.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val creators = o.arr("creators").mapNotNull { (it as? JsonObject)?.str("description") }
            if (creators.none(query::matchesCreator)) return@mapNotNull null
            val images = o.objOf("images")
            val print = images?.objOf("print")?.str("url")?.takeIf { it.startsWith("http") }
            val web = images?.objOf("web")?.str("url")?.takeIf { it.startsWith("http") }
            val image = print ?: web ?: return@mapNotNull null
            val year = MetParser.yearOf(o.int("creation_date_earliest"), o.int("creation_date_latest"))
                ?: o.str("creation_date")?.let { Regex("""\b(1[5-9]\d{2})\b""").find(it)?.groupValues?.get(1)?.toInt() }
                ?: return@mapNotNull null
            if (year !in query.years) return@mapNotNull null
            Artwork(
                id = "cleveland-$id", title = title.trim(), date = ArtworkDate.year(year), medium = o.str("technique")?.takeIf { it.isNotBlank() },
                iiif = IiifRef(
                    manifestUrl = "cleveland:$id", thumbnailUrl = web ?: print, imageUrl = image,
                    canvasWidth = images?.objOf("print")?.int("width") ?: images?.objOf("web")?.int("width"),
                    canvasHeight = images?.objOf("print")?.int("height") ?: images?.objOf("web")?.int("height"),
                ),
                provider = "Cleveland Museum of Art",
            )
        }
}

/**
 * Statens Museum for Kunst (Copenhague) — API ouverte (sans clé). Chaque notice porte un service IIIF natif :
 * `image_iiif_id` (base du service) et/ou `image_iiif_info` (son `info.json`).
 */
object SmkParser {
    fun searchUrl(query: ArtworkQuery): String =
        "https://api.smk.dk/api/v1/art/search/?lang=en&offset=0&rows=100&filters=" +
            java.net.URLEncoder.encode("[has_image:true],[public_domain:true]", "UTF-8") +
            "&keys=" + java.net.URLEncoder.encode(query.artistName, "UTF-8")

    fun parse(text: String, query: ArtworkQuery): List<Artwork> =
        obj(text)?.arr("items").orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val number = o.str("object_number") ?: o.str("id") ?: return@mapNotNull null
            if (o.strings("artist").none(query::matchesCreator)) return@mapNotNull null
            val title = o.arr("titles").firstNotNullOfOrNull { (it as? JsonObject)?.str("title")?.takeIf { t -> t.isNotBlank() } }
                ?: return@mapNotNull null
            val service = o.str("image_iiif_id")?.takeIf { it.startsWith("http") }?.trimEnd('/')
                ?: o.str("image_iiif_info")?.takeIf { it.startsWith("http") }?.removeSuffix("/info.json")?.trimEnd('/')
                ?: return@mapNotNull null
            val dated = o.arr("production_date").firstNotNullOfOrNull { it as? JsonObject }
            val year = MetParser.yearOf(dated?.str("start")?.take(4)?.toIntOrNull(), dated?.str("end")?.take(4)?.toIntOrNull())
                ?: return@mapNotNull null
            if (year !in query.years) return@mapNotNull null
            Artwork(
                id = "smk-" + slug(number), title = title.trim(), date = ArtworkDate.year(year),
                iiif = IiifRef(manifestUrl = "smk:$number", imageServiceId = service, canvasWidth = o.int("image_width"), canvasHeight = o.int("image_height")),
                provider = "Statens Museum for Kunst",
            )
        }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

/** Portrait d'un artiste : la vignette de l'article Wikipédia (`/api/rest_v1/page/summary/{titre}`). */
object WikipediaSummaryParser {
    fun summaryUrl(title: String): String = "https://en.wikipedia.org/api/rest_v1/page/summary/$title"

    fun thumbnail(text: String): String? = obj(text)?.objOf("thumbnail")?.str("source")?.takeIf { it.startsWith("https://") }
}
