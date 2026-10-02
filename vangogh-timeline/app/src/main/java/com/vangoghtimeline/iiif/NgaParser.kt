package com.vangoghtimeline.iiif

import com.vangoghtimeline.iiif.JsonReading.arr
import com.vangoghtimeline.iiif.JsonReading.bool
import com.vangoghtimeline.iiif.JsonReading.int
import com.vangoghtimeline.iiif.JsonReading.obj
import com.vangoghtimeline.iiif.JsonReading.str
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.RightsCatalog
import kotlinx.serialization.json.JsonObject

/**
 * **National Gallery of Art** (Washington). Le NGA ne publie PAS d'API en ligne : seulement son open data en CSV (CC0, environ 170 Mo,
 * mis à jour chaque jour). L'appli ne les télécharge jamais : `tools/nga_extract.py` en tire un petit JSON par artiste
 * (`assets/nga/{artiste}.json`), que ce lecteur analyse. Les images, elles, sont servies en IIIF par `api.nga.gov/iiif/{uuid}`.
 *
 * Écartées (et comptées) : œuvres « d'après », « suiveur », « imitateur », « attribué à », collaborations ; œuvres sans date précise
 * (l'intervalle d'une vie entière) ; hors des dates plausibles.
 */
object NgaParser {
    private val DOUBTFUL = Regex("""\b(after|follower|imitator|attributed|school|circle|copy|workshop|manner)\b|\band\b""", RegexOption.IGNORE_CASE)
    private const val OPEN_ACCESS = "https://www.nga.gov/open-access-images.html"

    fun assetName(query: ArtworkQuery): String = com.vangoghtimeline.model.slugOf(query.artistName)

    fun parse(text: String, query: ArtworkQuery, tally: Tally? = null): List<Artwork> {
        val works = obj(text)?.arr("works")
        if (works == null) { tally?.shape = JsonReading.describeShape(text); return emptyList() }
        tally?.let { it.raw += works.size }
        val artworks = works.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.int("id") ?: return@mapNotNull null
            val title = o.str("title")?.takeIf { it.isNotBlank() } ?: run { tally?.drop("sans titre"); return@mapNotNull null }
            val attribution = o.str("attribution").orEmpty()
            if (!query.matchesCreator(attribution)) { tally?.drop("d'un autre artiste"); return@mapNotNull null }
            if (DOUBTFUL.containsMatchIn(attribution)) { tally?.drop("attribution douteuse (d'après, suiveur, collaboration…)"); return@mapNotNull null }
            val service = o.str("iiif")?.takeIf { it.startsWith("http") } ?: run { tally?.drop("sans service IIIF"); return@mapNotNull null }
            val begin = o.int("begin")
            val end = o.int("end")
            // sans date lisible, un intervalle large est celui de la vie de l'artiste : pas une date d'œuvre
            if (o.str("date").isNullOrBlank() && begin != null && end != null && end - begin > 10) { tally?.drop("sans date précise"); return@mapNotNull null }
            val year = MetParser.yearOf(begin, end) ?: run { tally?.drop("sans date"); return@mapNotNull null }
            if (year !in query.years) { tally?.drop("hors des dates plausibles"); return@mapNotNull null }
            val open = o.bool("openaccess") == true
            val credit = o.str("credit")?.takeIf { it.isNotBlank() }
            val attributionLine = "National Gallery of Art, Washington" + (credit?.let { " — $it" } ?: "")
            val maxPixels = o.int("maxpixels")
            Artwork(
                id = "nga-$id", title = title.trim(), date = ArtworkDate.year(year),
                medium = o.str("medium")?.takeIf { it.isNotBlank() },
                iiif = IiifRef(manifestUrl = "nga:${o.str("accession") ?: id}", imageServiceId = service.trimEnd('/'), canvasWidth = o.int("width"), canvasHeight = o.int("height")),
                provider = "National Gallery of Art",
                rights = if (open) RightsCatalog.publicDomain("Open access (CC0) — National Gallery of Art", OPEN_ACCESS, attributionLine)
                else RightsCatalog.viewOnly(
                    "Image à accès restreint (usage loyal)${maxPixels?.let { " — résolution limitée à $it px" } ?: ""} — National Gallery of Art", "https://www.nga.gov/legal.html", attributionLine,
                ),
            )
        }.sortedWith(compareBy({ it.date.positionEpochDay }, { it.id }))
        if (tally != null) { tally.kept += artworks.size; if (artworks.isEmpty()) tally.shape = JsonReading.describeShape(text) }
        return artworks
    }
}
