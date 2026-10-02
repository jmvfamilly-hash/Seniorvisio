package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.RightsCatalog
import com.vangoghtimeline.model.RightsInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Œuvres de Van Gogh de l'**Art Institute of Chicago**, lues dans la réponse de leur API publique
 * (`https://api.artic.edu/api/v1/artworks/search`). Chaque œuvre a un `image_id` qui désigne un service d'image **IIIF**
 * (`{config.iiif_url}/{image_id}`) : vignettes à la taille voulue, et zoom profond dans le visualiseur.
 *
 * Limite : l'API ne donne que l'ANNÉE (`date_start`/`date_end`). Les œuvres sont donc datées à l'année près
 * ([com.vangoghtimeline.model.DatePrecision.YEAR]) et placées au milieu de l'année : la frise ne prétend pas connaître le jour.
 * Pour des dates plus fines, un manifeste IIIF avec `navDate` (voir [IiifManifestParser]) les remplace.
 *
 * Pur Kotlin : testé sur la JVM avec une réponse type.
 */
object ArticParser {
    private const val FIELDS = "id,title,artist_title,date_start,date_end,place_of_origin,medium_display,image_id,thumbnail,is_public_domain,copyright_notice"

    /** Recherche plein texte du nom de l'artiste. Les œuvres protégées ne sont pas écartées : leurs droits sont affichés ([rightsOf]). */
    fun searchUrl(query: ArtworkQuery): String =
        "https://api.artic.edu/api/v1/artworks/search?q=" + java.net.URLEncoder.encode(query.artistName, "UTF-8").replace("+", "%20") +
            "&limit=100&fields=$FIELDS"

    val SEARCH_URL: String = searchUrl(ArtworkQuery.VAN_GOGH)

    private const val DEFAULT_IIIF = "https://www.artic.edu/iiif/2"

    /**
     * Règle AIC : l'`id` numérique d'une œuvre donne son manifeste IIIF
     * `https://api.artic.edu/api/v1/artworks/{id}/manifest.json` (ex. La Chambre : 28560) ;
     * son `image_id` donne le service d'image `https://www.artic.edu/iiif/2/{image_id}`
     * (`/info.json`, `/full/{w},/0/default.jpg`, tuiles `/{region}/{w},/0/default.jpg`).
     */
    fun manifestUrl(artworkId: Int): String = "https://api.artic.edu/api/v1/artworks/$artworkId/manifest.json"
    private val json = Json { ignoreUnknownKeys = true }

    /** Domaine public (images CC0 de l'AIC) ou, sinon, consultation privée avec la mention de droits du musée. */
    internal fun rightsOf(publicDomain: Boolean?, notice: String?): RightsInfo =
        if (publicDomain == true) RightsCatalog.publicDomain("Domaine public (CC0) — Art Institute of Chicago", "https://www.artic.edu/open-access/open-access-images")
        else RightsCatalog.viewOnly("Droits réservés${notice?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""} — Art Institute of Chicago", "https://www.artic.edu/terms")

    /** Les œuvres de Van Gogh ayant une image, par date croissante. Une réponse illisible rend une liste vide. */
    fun parse(text: String, query: ArtworkQuery = ArtworkQuery.VAN_GOGH, tally: Tally? = null): List<Artwork> {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: return emptyList()
        val iiif = (root["config"] as? JsonObject)?.str("iiif_url")?.trimEnd('/') ?: DEFAULT_IIIF
        val data = root["data"] as? JsonArray ?: run { tally?.shape = JsonReading.describeShape(text); return emptyList() }
        tally?.let { it.raw += data.size }

        val artworks = data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            // la recherche est plein texte : on ne garde que les œuvres DE l'artiste, pas celles qui le citent
            if (o.str("artist_title")?.let(query::matchesCreator) != true) { tally?.drop("d'un autre artiste"); return@mapNotNull null }
            val imageId = o.str("image_id") ?: run { tally?.drop("sans image"); return@mapNotNull null }
            val id = o.int("id") ?: return@mapNotNull null
            val title = o.str("title") ?: return@mapNotNull null
            val date = dateOf(o.int("date_start") ?: o.int("date_end"), query, tally) ?: return@mapNotNull null
            val thumb = o["thumbnail"] as? JsonObject
            Artwork(
                id = "artic-$id",
                title = title,
                date = date,
                place = o.str("place_of_origin")?.substringBefore(',')?.trim()?.ifEmpty { null },
                medium = o.str("medium_display"),
                iiif = IiifRef(
                    manifestUrl = manifestUrl(id),
                    imageServiceId = "$iiif/$imageId",
                    canvasWidth = thumb?.int("width"),
                    canvasHeight = thumb?.int("height"),
                ),
                provider = "Art Institute of Chicago",
                rights = rightsOf((o["is_public_domain"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull(), o.str("copyright_notice")),
            )
        }.sortedWith(compareBy({ it.date.positionEpochDay }, { it.id }))
        if (tally != null) { tally.kept += artworks.size; if (artworks.isEmpty()) tally.shape = JsonReading.describeShape(text) }
        return artworks
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
