package com.vangoghtimeline.iiif

import com.vangoghtimeline.iiif.JsonReading.arr
import com.vangoghtimeline.iiif.JsonReading.int
import com.vangoghtimeline.iiif.JsonReading.obj
import com.vangoghtimeline.iiif.JsonReading.objOf
import com.vangoghtimeline.iiif.JsonReading.str
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.RightsCatalog
import com.vangoghtimeline.model.RightsInfo
import com.vangoghtimeline.model.RightsKind
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Œuvres d'un artiste via **Wikidata + Wikimedia Commons** (APIs publiques, sans clé) :
 *
 * 1. l'identifiant Wikidata de l'artiste, lu depuis son article Wikipédia (`pageprops.wikibase_item`) ;
 * 2. une requête SPARQL liste ses œuvres qui ont une image (propriétés « créateur » P170 et « image » P18) avec titre, année de création
 *    (« inception » P571) et musée détenteur (P195) ;
 * 3. l'API de Commons donne, par lots, la taille du fichier et sa **licence** (`extmetadata` : nom, adresse, auteur de la photographie) ;
 * 4. l'image s'ouvre par `Special:FilePath/{fichier}?width=…` (miniature à une largeur STANDARD de Commons, voir [STANDARD_WIDTHS]) : une image ordinaire,
 *    découpée en tuiles par la visionneuse.
 *
 * Pur Kotlin : testé sur la JVM avec des réponses types (formats documentés, non comparés à des réponses réelles).
 */
object WikimediaParser {
    /**
     * Wikimedia ne sert les miniatures qu'à des largeurs STANDARD ; toute autre largeur (400, 3000…) est refusée par une limite de débit
     * (HTTP 429). Ces largeurs viennent de la politique des miniatures de Commons.
     */
    val STANDARD_WIDTHS = listOf(20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840)
    const val PREVIEW_WIDTH = 500
    /** Largeur d'ouverture quand la taille du fichier est connue / inconnue (la licence d'un lot a pu échouer). */
    const val VIEW_WIDTH = 3840
    const val VIEW_WIDTH_UNKNOWN_SIZE = 1920
    const val BATCH = 20

    /** Plus grande largeur standard qui ne dépasse ni [target] ni la largeur du fichier [original] (inconnue = pas de borne). */
    fun standardWidth(target: Int, original: Int?): Int {
        val limit = minOf(target, original ?: Int.MAX_VALUE)
        return STANDARD_WIDTHS.lastOrNull { it <= limit } ?: STANDARD_WIDTHS.first()
    }
    private const val LIMIT = 300

    fun qidUrl(wikipediaTitle: String): String =
        "https://en.wikipedia.org/w/api.php?action=query&prop=pageprops&ppprop=wikibase_item&redirects=1&format=json&titles=" + wikipediaTitle

    /** `query.pages.{id}.pageprops.wikibase_item` (ex. `Q297838`). */
    fun parseQid(text: String): String? =
        obj(text)?.objOf("query")?.objOf("pages")?.values?.firstNotNullOfOrNull { (it as? kotlinx.serialization.json.JsonObject)?.objOf("pageprops")?.str("wikibase_item") }

    fun sparql(qid: String): String =
        "SELECT ?item ?itemLabel ?inception ?image ?collectionLabel WHERE { " +
            "?item wdt:P170 wd:$qid ; wdt:P18 ?image . " +
            "OPTIONAL { ?item wdt:P571 ?inception . } OPTIONAL { ?item wdt:P195 ?collection . } " +
            "SERVICE wikibase:label { bd:serviceParam wikibase:language \"en,fr,es,nl,de,it\" . } } ORDER BY ?item LIMIT $LIMIT"

    fun sparqlUrl(qid: String): String =
        "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(sparql(qid), "UTF-8")

    /** Une œuvre lue dans la réponse SPARQL, avant la lecture de la licence. */
    class Item(val qid: String, val label: String, val year: Int?, val file: String, val collection: String?)

    fun parseSparql(text: String, tally: Tally? = null): List<Item> {
        val bindings = obj(text)?.objOf("results")?.arr("bindings")
        if (bindings == null) { tally?.shape = JsonReading.describeShape(text); return emptyList() }
        tally?.let { it.raw += bindings.size }
        val seen = HashSet<String>()
        val items = bindings.mapNotNull { el ->
            val b = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun v(k: String) = b.objOf(k)?.str("value")
            val qid = v("item")?.substringAfterLast('/')?.takeIf { it.startsWith("Q") } ?: return@mapNotNull null
            if (!seen.add(qid)) { tally?.drop("doublons (plusieurs images ou musées)"); return@mapNotNull null }
            val label = v("itemLabel")?.takeIf { it.isNotBlank() && !it.matches(Regex("Q\\d+")) } ?: run { tally?.drop("sans titre"); return@mapNotNull null }
            val file = fileNameOf(v("image") ?: return@mapNotNull null) ?: return@mapNotNull null
            val year = v("inception")?.let { Regex("""^(-?\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            Item(qid, label.trim(), year, file, v("collectionLabel")?.takeIf { it.isNotBlank() })
        }
        return items
    }

    /** `http://commons.wikimedia.org/wiki/Special:FilePath/Nom%20du%20fichier.jpg` → `Nom du fichier.jpg`. */
    fun fileNameOf(imageUrl: String): String? {
        val raw = imageUrl.substringAfter("Special:FilePath/", "").substringBefore('?').takeIf { it.isNotEmpty() } ?: return null
        return try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { raw }
    }

    fun filePathUrl(file: String, width: Int): String =
        "https://commons.wikimedia.org/wiki/Special:FilePath/" + URLEncoder.encode(file, "UTF-8").replace("+", "%20") + "?width=$width"

    /** Lecture par lots des métadonnées de fichiers Commons (taille et licence). */
    fun imageInfoUrl(files: List<String>): String =
        "https://commons.wikimedia.org/w/api.php?action=query&prop=imageinfo&iiprop=size%7Cextmetadata" +
            "&iiextmetadatafilter=LicenseShortName%7CLicenseUrl%7CArtist%7CAttributionRequired&format=json&titles=" +
            URLEncoder.encode(files.joinToString("|") { "File:$it" }, "UTF-8")

    class FileInfo(val width: Int?, val height: Int?, val rights: RightsInfo)

    /** Métadonnées par nom de fichier (sans « File: », espaces et non soulignés). */
    fun parseImageInfo(text: String): Map<String, FileInfo> {
        val pages = obj(text)?.objOf("query")?.objOf("pages") ?: return emptyMap()
        val out = HashMap<String, FileInfo>()
        for (page in pages.values) {
            val p = page as? kotlinx.serialization.json.JsonObject ?: continue
            val name = p.str("title")?.removePrefix("File:")?.replace('_', ' ') ?: continue
            val info = p.arr("imageinfo").firstOrNull() as? kotlinx.serialization.json.JsonObject ?: continue
            val meta = info.objOf("extmetadata")
            fun m(k: String) = meta?.objOf(k)?.str("value")
            out[name] = FileInfo(info.int("width"), info.int("height"), RightsCatalog.fromCommons(m("LicenseShortName"), m("LicenseUrl"), m("Artist")))
        }
        return out
    }

    /** Nom de fichier normalisé comme dans [parseImageInfo]. */
    fun normalizedName(file: String): String = file.replace('_', ' ')

    fun toArtwork(item: Item, info: FileInfo?, query: ArtworkQuery, tally: Tally? = null): Artwork? {
        val date = dateOf(item.year, query, tally) ?: return null
        val rights = info?.rights ?: RightsInfo(
            RightsKind.UNKNOWN, "Licence du fichier non lue (œuvre ancienne : probablement domaine public)",
            "https://commons.wikimedia.org/wiki/File:" + URLEncoder.encode(item.file, "UTF-8").replace("+", "_"), null,
        )
        tally?.let { it.kept++ }
        return Artwork(
            id = "wikimedia-${item.qid}", title = item.label, date = date,
            iiif = IiifRef(
                manifestUrl = "wikimedia:${item.qid}", thumbnailUrl = filePathUrl(item.file, standardWidth(PREVIEW_WIDTH, info?.width)),
                canvasWidth = info?.width, canvasHeight = info?.height, imageUrl = filePathUrl(item.file, standardWidth(if (info?.width != null) VIEW_WIDTH else VIEW_WIDTH_UNKNOWN_SIZE, info?.width)),
            ),
            provider = if (item.collection != null) "Wikimedia · ${item.collection}" else "Wikimedia Commons",
            details = detailsOf("Collection" to item.collection, "Fichier Commons" to item.file, "Wikidata" to item.qid),
            pageUrl = "https://commons.wikimedia.org/wiki/File:" + URLEncoder.encode(item.file, "UTF-8").replace("+", "_"),
            rights = rights,
        )
    }
}
