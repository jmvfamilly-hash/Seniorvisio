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
import com.vangoghtimeline.model.RightsCatalog
import kotlinx.serialization.json.JsonObject

/**
 * The Metropolitan Museum of Art — Open Access API (sans clé), en deux temps : `search` rend des identifiants, puis `objects/{id}` une
 * notice. Pas de service IIIF : on donne l'URL de l'image (`primaryImage`), que le visualiseur découpe lui-même en tuiles
 * ([com.iiifviewer.StaticImageUrl]). Seules les œuvres du domaine public sont gardées.
 */
object MetParser {
    private const val BASE = "https://collectionapi.metmuseum.org/public/collection/v1"
    private const val BASE_V11 = "https://collectionapi.metmuseum.org/public/collection/v1.1"

    /** Taille d'une page de la recherche v1.1 (paginée par `offset` et `limit`). */
    const val PAGE_SIZE = 100

    fun searchUrl(query: ArtworkQuery): String =
        "$BASE/search?hasImages=true&artistOrCulture=true&q=" + java.net.URLEncoder.encode(query.artistName, "UTF-8")

    fun objectUrl(id: Int): String = "$BASE/objects/$id"

    /**
     * Adresses de recherche à essayer dans l'ordre : complète ; `q` seul (au cas où un des filtres serait refusé) ; peintures
     * européennes (`departmentId=11`), la section où se trouvent Van Gogh, Sargent, Renoir…
     */
    fun searchVariants(query: ArtworkQuery): List<String> {
        val q = java.net.URLEncoder.encode(query.artistName, "UTF-8")
        val page = "offset=0&limit=$PAGE_SIZE"
        return listOf(
            // v1.1 (Elastic, paginée) : la v1/search a été retirée le 2026-10-01 (HTTP 410)
            "$BASE_V11/search?hasImages=true&artistOrCulture=true&q=$q&$page",
            "$BASE_V11/search?q=$q&$page",
            "$BASE_V11/search?departmentId=11&q=$q&$page",
            // anciennes adresses, en dernier recours seulement
            searchUrl(query), "$BASE/search?q=$q", "$BASE/search?departmentId=11&q=$q",
        )
    }

    /** Vrai pour une variante paginée (`offset=0&limit=…`) : il y a alors une page suivante possible. */
    fun isPaginated(url: String): Boolean = url.contains("offset=0&")

    /** Même recherche, page commençant à [offset]. */
    fun pageUrl(url: String, offset: Int): String = url.replace("offset=0&", "offset=$offset&")

    /** Nombre total de résultats annoncé par la réponse (`total`), s'il y en a un. */
    fun parseTotal(text: String): Int? = obj(text)?.int("total")

    /** Identifiants de la réponse de recherche (`objectIDs` peut être `null` quand il n'y a aucun résultat). */
    /**
     * Identifiants de la réponse de recherche. Tolérant : la forme de la v1.1 n'a pas pu être vérifiée au moment de l'écriture, on
     * accepte donc `objectIDs` (v1), puis `objects`/`results`/`items`/`data`/`ids`, des entiers OU des objets portant `objectID`/`id`,
     * et un tableau à la racine.
     */
    fun parseSearch(text: String): List<Int> {
        val root = JsonReading.root(text) ?: return emptyList()
        fun idsOf(arr: List<kotlinx.serialization.json.JsonElement>): List<Int> = arr.mapNotNull { el ->
            (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                ?: (el as? JsonObject)?.let { it.int("objectID") ?: it.int("id") }
        }
        if (root is kotlinx.serialization.json.JsonArray) return idsOf(root)
        val o = root as? JsonObject ?: return emptyList()
        for (key in listOf("objectIDs", "objects", "results", "items", "data", "ids")) {
            val ids = idsOf(o.arr(key))
            if (ids.isNotEmpty()) return ids
        }
        return emptyList()
    }

    /** Clés de premier niveau de la réponse (pour consigner sa forme quand elle réussit). */
    fun topLevelKeys(text: String): String = when (val r = JsonReading.root(text)) {
        is JsonObject -> r.keys.joinToString()
        is kotlinx.serialization.json.JsonArray -> "tableau de ${r.size}"
        else -> "non JSON"
    }

    fun parseObject(text: String, query: ArtworkQuery, tally: Tally? = null): Artwork? {
        tally?.let { it.raw++ }
        val o = obj(text) ?: run { tally?.drop("illisibles"); return null }
        val id = o.int("objectID") ?: run { tally?.drop("sans identifiant"); return null }
        // pas de filtre « domaine public » : le Met ne publie d'image que pour ces œuvres ; sans image, l'œuvre n'est pas consultable
        val publicDomain = o.bool("isPublicDomain") == true
        val image = o.str("primaryImage")?.takeIf { it.startsWith("http") } ?: run { tally?.drop(if (publicDomain) "sans image" else "hors domaine public (pas d'image publiée)"); return null }
        if (o.str("artistDisplayName")?.let(query::matchesCreator) != true) { tally?.drop("d'un autre artiste"); return null }
        val title = o.str("title")?.takeIf { it.isNotBlank() } ?: run { tally?.drop("sans titre"); return null }
        val year = yearOf(o.int("objectBeginDate"), o.int("objectEndDate")) ?: run { tally?.drop("sans date"); return null }
        if (year !in query.years) { tally?.drop("hors des dates plausibles"); return null }
        tally?.let { it.kept++ }
        return Artwork(
            id = "met-$id", title = title.trim(), date = ArtworkDate.year(year), medium = o.str("medium")?.takeIf { it.isNotBlank() },
            iiif = IiifRef(manifestUrl = "met:$id", thumbnailUrl = o.str("primaryImageSmall")?.takeIf { it.startsWith("http") }, imageUrl = image),
            provider = "The Metropolitan Museum of Art",
            rights = if (publicDomain) RightsCatalog.publicDomain("Domaine public — Met Open Access (CC0)", "https://www.metmuseum.org/about-the-met/policies-and-documents/open-access")
            else RightsCatalog.viewOnly("Droits réservés — ${o.str("rightsAndReproduction")?.takeIf { it.isNotBlank() } ?: "voir le Met"}", "https://www.metmuseum.org/policies/image-resources"),
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
        "https://openaccess-api.clevelandart.org/api/artworks/?has_image=1&limit=100&artists=" +
            java.net.URLEncoder.encode(query.artistName, "UTF-8")

    fun parse(text: String, query: ArtworkQuery, tally: Tally? = null): List<Artwork> {
        val data = obj(text)?.arr("data")
        if (data == null) { tally?.shape = JsonReading.describeShape(text); return emptyList() }
        tally?.let { it.raw += data.size }
        val artworks = data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.int("id") ?: return@mapNotNull null
            val title = o.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val creators = o.arr("creators").mapNotNull { (it as? JsonObject)?.str("description") }
            if (creators.none(query::matchesCreator)) { tally?.drop("d'un autre créateur"); return@mapNotNull null }
            val images = o.objOf("images")
            val print = images?.objOf("print")?.str("url")?.takeIf { it.startsWith("http") }
            val web = images?.objOf("web")?.str("url")?.takeIf { it.startsWith("http") }
            val image = print ?: web ?: run { tally?.drop("sans image"); return@mapNotNull null }
            val year = MetParser.yearOf(o.int("creation_date_earliest"), o.int("creation_date_latest"))
                ?: o.str("creation_date")?.let { Regex("""\b(1[5-9]\d{2})\b""").find(it)?.groupValues?.get(1)?.toInt() }
                ?: run { tally?.drop("sans date"); return@mapNotNull null }
            if (year !in query.years) { tally?.drop("hors des dates plausibles"); return@mapNotNull null }
            Artwork(
                id = "cleveland-$id", title = title.trim(), date = ArtworkDate.year(year), medium = o.str("technique")?.takeIf { it.isNotBlank() },
                iiif = IiifRef(
                    manifestUrl = "cleveland:$id", thumbnailUrl = web ?: print, imageUrl = image,
                    canvasWidth = images?.objOf("print")?.int("width") ?: images?.objOf("web")?.int("width"),
                    canvasHeight = images?.objOf("print")?.int("height") ?: images?.objOf("web")?.int("height"),
                ),
                provider = "Cleveland Museum of Art",
                rights = if (o.str("share_license_status")?.equals("CC0", ignoreCase = true) == true)
                    RightsCatalog.publicDomain("CC0 — Cleveland Museum of Art Open Access", "https://www.clevelandart.org/open-access")
                else RightsCatalog.viewOnly("Droits réservés${o.str("copyright")?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""} — Cleveland Museum of Art", "https://www.clevelandart.org/open-access"),
            )
        }
        if (tally != null) { tally.kept += artworks.size; if (artworks.isEmpty()) tally.shape = JsonReading.describeShape(text) }
        return artworks
    }
}

/**
 * Statens Museum for Kunst (Copenhague) — API ouverte (sans clé). Chaque notice porte un service IIIF natif :
 * `image_iiif_id` (base du service) et/ou `image_iiif_info` (son `info.json`).
 */
object SmkParser {
    fun searchUrl(query: ArtworkQuery): String =
        "https://api.smk.dk/api/v1/art/search/?lang=en&offset=0&rows=100&filters=" +
            java.net.URLEncoder.encode("[has_image:true]", "UTF-8") +
            "&keys=" + java.net.URLEncoder.encode(query.artistName, "UTF-8")

    fun parse(text: String, query: ArtworkQuery, tally: Tally? = null): List<Artwork> {
        val items = obj(text)?.arr("items")
        if (items == null) { tally?.shape = JsonReading.describeShape(text); return emptyList() }
        tally?.let { it.raw += items.size }
        val artworks = items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val number = o.str("object_number") ?: o.str("id") ?: return@mapNotNull null
            if (o.strings("artist").none(query::matchesCreator)) { tally?.drop("d'un autre artiste (ou champ « artist » inattendu)"); return@mapNotNull null }
            val title = o.arr("titles").firstNotNullOfOrNull { (it as? JsonObject)?.str("title")?.takeIf { t -> t.isNotBlank() } }
                ?: run { tally?.drop("sans titre"); return@mapNotNull null }
            val service = o.str("image_iiif_id")?.takeIf { it.startsWith("http") }?.trimEnd('/')
                ?: o.str("image_iiif_info")?.takeIf { it.startsWith("http") }?.removeSuffix("/info.json")?.trimEnd('/')
                ?: run { tally?.drop("sans service IIIF"); return@mapNotNull null }
            val dated = o.arr("production_date").firstNotNullOfOrNull { it as? JsonObject }
            val year = MetParser.yearOf(dated?.str("start")?.take(4)?.toIntOrNull(), dated?.str("end")?.take(4)?.toIntOrNull())
                ?: run { tally?.drop("sans date"); return@mapNotNull null }
            if (year !in query.years) { tally?.drop("hors des dates plausibles"); return@mapNotNull null }
            Artwork(
                id = "smk-" + slug(number), title = title.trim(), date = ArtworkDate.year(year),
                iiif = IiifRef(manifestUrl = "smk:$number", imageServiceId = service, canvasWidth = o.int("image_width"), canvasHeight = o.int("image_height")),
                provider = "Statens Museum for Kunst",
                rights = if (o["public_domain"].let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" })
                    RightsCatalog.publicDomain("Domaine public — SMK Open (CC0)", "https://open.smk.dk/en/about")
                else RightsCatalog.viewOnly("Droits réservés — ${o.str("rights")?.takeIf { it.isNotBlank() } ?: "voir le SMK"}", "https://open.smk.dk/en/about"),
            )
        }
        if (tally != null) { tally.kept += artworks.size; if (artworks.isEmpty()) tally.shape = JsonReading.describeShape(text) }
        return artworks
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

/** Portrait d'un artiste : la vignette de l'article Wikipédia (`/api/rest_v1/page/summary/{titre}`). */
object WikipediaSummaryParser {
    fun summaryUrl(title: String): String = "https://en.wikipedia.org/api/rest_v1/page/summary/$title"

    fun thumbnail(text: String): String? = obj(text)?.objOf("thumbnail")?.str("source")?.takeIf { it.startsWith("https://") }
}
