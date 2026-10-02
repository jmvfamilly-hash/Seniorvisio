package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.SourceSpec
import com.vangoghtimeline.model.slugOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/**
 * Un musée (ou agrégateur) interrogeable pour un artiste. [fetch] ne fait QUE chercher et lire : il lève une exception si le
 * réseau ou l'API échoue. Vérifier que les œuvres rendues sont vraiment accessibles en IIIF est le rôle de [SourceValidator], jamais
 * d'une source — c'est ce qui permet de brancher un musée de plus sans risquer d'exposer des œuvres qui ne s'ouvrent pas.
 */
interface MuseumSource {
    val id: String
    val name: String

    /** Fragment (minuscule) du champ « fournisseur » d'Europeana pour ce musée : ses notices Europeana font doublon avec cette source. */
    val europeanaKeyword: String

    /** Vrai pour une source de RECONNAISSANCE : elle n'ajoute aucune œuvre, elle consigne la forme des réponses d'un service à explorer. */
    val reconnaissanceOnly: Boolean get() = false

    suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork>
}

private fun artistIdOf(query: ArtworkQuery) = slugOf(query.artistName)

class AicSource(private val http: ManifestSource) : MuseumSource {
    override val id = "aic"
    override val name = "Art Institute of Chicago"
    override val europeanaKeyword = "art institute"
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val tally = Tally()
        return ArticParser.parse(http.fetch(ArticParser.searchUrl(query)), query, tally).also { tally.log(id, artistIdOf(query)) }
    }
}

class EuropeanaSource(private val http: ManifestSource, private val key: String = EuropeanaParser.DEMO_KEY) : MuseumSource {
    override val id = "europeana"
    override val name = "Europeana"
    override val europeanaKeyword = "\u0000"   // Europeana ne fait pas doublon avec elle-même

    /** Variantes de nom essayées dans l'ordre (exact, sans accents, nom de famille) ; chacune est consignée avec son résultat. */
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val variants = EuropeanaParser.variants(query, key).toMutableList()
        // fournisseur de données propre à l'artiste (ex. « Museo Sorolla ») : toutes les notices image de ce musée, créateur non exigé
        spec.term?.let { variants += EuropeanaParser.dataProviderUrl(it, key) to false }
        var firstError: Exception? = null
        var answered = false
        for ((index, variant) in variants.withIndex()) {
            val (url, surnameOnly) = variant
            try {
                val tally = Tally()
                val arts = EuropeanaParser.parse(http.fetch(url), query, tally, strictCreator = surnameOnly)
                tally.log(id, artistIdOf(query), "variante ${index + 1}/${variants.size}${if (surnameOnly) ", nom de famille seul : créateur exigé" else ""}")
                answered = true
                if (arts.isNotEmpty()) {
                    if (index > 0) Diag.warn("source", "recherche : variante ${index + 1}/${variants.size} utilisée (les précédentes n'ont rien donné)", url, id)
                    return arts
                }
                Diag.warn("source", "recherche : variante ${index + 1}/${variants.size} sans œuvre exploitable", url, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                Diag.warn("source", "recherche : variante ${index + 1}/${variants.size} en échec : ${e.message ?: e.javaClass.simpleName}", url, id)
            }
        }
        if (!answered && firstError != null) throw firstError
        return emptyList()
    }
}

class ClevelandSource(private val http: ManifestSource) : MuseumSource {
    override val id = "cleveland"
    override val name = "Cleveland Museum of Art"
    override val europeanaKeyword = "cleveland"
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val tally = Tally()
        return ClevelandParser.parse(http.fetch(ClevelandParser.searchUrl(query)), query, tally).also { tally.log(id, artistIdOf(query)) }
    }
}

class SmkSource(private val http: ManifestSource) : MuseumSource {
    override val id = "smk"
    override val name = "Statens Museum for Kunst"
    override val europeanaKeyword = "statens museum"
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val tally = Tally()
        return SmkParser.parse(http.fetch(SmkParser.searchUrl(query)), query, tally).also { tally.log(id, artistIdOf(query)) }
    }
}

/**
 * The Met : une recherche, puis une notice par œuvre (au plus [maxObjects], [parallelism] à la fois ; la recherche v1.1 est paginée par 100, deux pages au plus).
 *
 * La recherche essaie des VARIANTES d'adresse dans l'ordre (complète, `q` seul, peintures européennes) : la première qui répond avec
 * des résultats gagne. Chaque variante en échec est consignée dans [Diag] même si la suivante réussit. Une notice en échec est
 * ignorée mais consignée (regroupée par message).
 */
class MetSource(
    private val http: ManifestSource,
    private val maxObjects: Int = 120,
    private val parallelism: Int = 6,
    /** Notices déjà lues (une par fichier) : elles ne changent pas, on ne les redemande pas (et le Met n'est pas sollicité deux fois). */
    private val noticeCache: java.io.File? = null,
) : MuseumSource {
    override val id = "met"
    override val name = "The Metropolitan Museum of Art"
    override val europeanaKeyword = "metropolitan"

    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> = coroutineScope {
        val ids = searchIds(query).take(maxObjects)
        val gate = Semaphore(parallelism)
        val tally = Tally()
        val artworks = ids.map { objectId ->
            async {
                gate.withPermit {
                    val url = MetParser.objectUrl(objectId)
                    try {
                        val cached = readNotice(objectId)
                        val text = cached ?: http.fetch(url).also { writeNotice(objectId, it) }
                        MetParser.parseObject(text, query, tally)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // notice ignorée : consignée (regroupée), la source continue
                        tally.raw++
                        tally.drop(if (e.message?.contains("HTTP 404") == true) "n'existent plus (404)" else "non lues (erreur réseau)")
                        Diag.warn("source", "notice ignorée : ${e.message ?: e.javaClass.simpleName}", url, id, key = "met-object|${e.message?.take(40)}")
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
        tally.log(id, artistIdOf(query))
        artworks
    }

    private fun readNotice(objectId: Int): String? =
        noticeCache?.let { dir -> runCatching { java.io.File(dir, "$objectId.json").takeIf { it.exists() }?.readText() }.getOrNull() }

    private fun writeNotice(objectId: Int, text: String) {
        noticeCache?.let { dir -> runCatching { dir.mkdirs(); java.io.File(dir, "$objectId.json").writeText(text) } }
    }

    private suspend fun searchIds(query: ArtworkQuery): List<Int> {
        val variants = MetParser.searchVariants(query)
        var firstError: Exception? = null
        for ((index, url) in variants.withIndex()) {
            try {
                val text = http.fetch(url)
                val ids = MetParser.parseSearch(text)
                if (ids.isNotEmpty()) {
                    if (index > 0) Diag.warn("source", "recherche : variante ${index + 1}/${variants.size} utilisée après l'échec des précédentes", url, id)
                    // forme de la réponse consignée : la v1.1 n'a pas pu être vérifiée à l'écriture, le rapport la montrera
                    Diag.info("source", "recherche réussie : clés [${MetParser.topLevelKeys(text)}], total ${MetParser.parseTotal(text)}, ${ids.size} identifiants ; début : ${text.take(200).replace('\n', ' ')}", url, id)
                    return ids + morePages(url, ids.size, MetParser.parseTotal(text))
                }
                Diag.warn("source", "recherche : variante ${index + 1}/${variants.size} sans résultat (clés [${MetParser.topLevelKeys(text)}])", url, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                Diag.warn("source", "recherche : variante ${index + 1}/${variants.size} en échec : ${e.message ?: e.javaClass.simpleName}", url, id)
            }
        }
        throw firstError ?: IllegalStateException("recherche sans résultat (${variants.size} variantes essayées)")
    }

    /** Page suivante (une seule) d'une recherche paginée dont la première page était pleine ; son échec n'enlève rien à la première. */
    private suspend fun morePages(url: String, firstPageSize: Int, total: Int?): List<Int> {
        if (!MetParser.isPaginated(url) || firstPageSize < MetParser.PAGE_SIZE || (total != null && total <= MetParser.PAGE_SIZE)) return emptyList()
        val next = MetParser.pageUrl(url, MetParser.PAGE_SIZE)
        return try {
            MetParser.parseSearch(http.fetch(next))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.warn("source", "page 2 de la recherche en échec (la première page est gardée) : ${e.message ?: e.javaClass.simpleName}", next, id)
            emptyList()
        }
    }
}

/**
 * Rijksmuseum (Data Services, Linked Art) : recherche (jusqu'à [maxPages] pages) puis, par objet, objet → VisualItem → DigitalObject
 * (voir [RijksmuseumParser]). Une œuvre dont une étape échoue est ignorée ; une page suivante en échec n'enlève pas les œuvres déjà
 * trouvées (seule la première page est indispensable).
 */
class RijksSource(
    private val http: ManifestSource,
    private val maxPages: Int = 3,
    private val parallelism: Int = 6,
) : MuseumSource {
    override val id = "rijks"
    override val name = "Rijksmuseum"
    override val europeanaKeyword = "rijksmuseum"

    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> = coroutineScope {
        val creator = spec.term ?: throw IllegalArgumentException("Rijksmuseum : terme de recherche (« Nom, Prénom ») manquant")
        val ids = LinkedHashSet<String>()
        var next: String? = RijksmuseumParser.searchUrl(creator)
        var pages = 0
        while (next != null && pages < maxPages) {
            val page = try {
                RijksmuseumParser.parseSearchPage(http.fetch(next))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (pages == 0) throw e
                Diag.warn("source", "page ${pages + 1} de la recherche en échec (les œuvres déjà trouvées sont gardées) : ${e.message ?: e.javaClass.simpleName}", next, id)
                break
            }
            ids += page.objectIds
            next = page.next
            pages++
        }
        val gate = Semaphore(parallelism)
        val tally = Tally().also { it.raw = ids.size }
        val resolved = ids.map { objectId ->
            async {
                gate.withPermit {
                    try {
                        resolve(objectId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Diag.warn("source", "objet ignoré : ${e.message ?: e.javaClass.simpleName}", objectId, id, key = "rijks-object|${e.message?.take(40)}")
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
        val kept = resolved.filter { it.date.year in query.years }
        tally.drop("sans image ou étape en échec", ids.size - resolved.size)
        tally.drop("hors des dates plausibles", resolved.size - kept.size)
        tally.kept = kept.size
        tally.log(id, artistIdOf(query))
        kept
    }

    private suspend fun resolve(objectId: String): Artwork? {
        val info = RijksmuseumParser.parseObject(http.fetch(objectId)) ?: return null
        for (url in RijksmuseumParser.parseVisualItem(http.fetch(info.visualItemUrl))) {
            val service = RijksmuseumParser.parseDigitalObject(http.fetch(url)) ?: continue
            return RijksmuseumParser.toArtwork(info, service)
        }
        return null
    }
}

/**
 * Wikimedia (Wikidata + Commons) : voir [WikimediaParser]. Le terme de la source est le titre de l'article Wikipédia de l'artiste.
 * Les licences sont lues par lots de [WikimediaParser.BATCH] fichiers ; un lot en échec laisse ses œuvres avec une licence « non lue »
 * (échec consigné), il ne fait pas échouer la source.
 */
class WikimediaSource(private val http: ManifestSource, private val parallelism: Int = 3) : MuseumSource {
    override val id = "wikimedia"
    override val name = "Wikimedia (Wikidata + Commons)"
    override val europeanaKeyword = "\u0000"
    private val qids = HashMap<String, String>()

    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> = coroutineScope {
        val title = spec.term ?: throw IllegalArgumentException("Wikimedia : titre de l'article Wikipédia manquant")
        val qid = qids[title] ?: (WikimediaParser.parseQid(http.fetch(WikimediaParser.qidUrl(title)))
            ?: throw IOException("identifiant Wikidata introuvable pour l'article « $title »")).also { qids[title] = it }
        val tally = Tally()
        val items = WikimediaParser.parseSparql(http.fetch(WikimediaParser.sparqlUrl(qid)), tally)
        val gate = Semaphore(parallelism)
        val infos = HashMap<String, WikimediaParser.FileInfo>()
        items.map { it.file }.chunked(WikimediaParser.BATCH).map { files ->
            async {
                gate.withPermit {
                    try {
                        WikimediaParser.parseImageInfo(http.fetch(WikimediaParser.imageInfoUrl(files)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Diag.warn("source", "licences d'un lot de ${files.size} fichiers non lues : ${e.message ?: e.javaClass.simpleName}", sourceId = id, artistId = artistIdOf(query), key = "wikimedia-batch|${e.message?.take(40)}")
                        emptyMap()
                    }
                }
            }
        }.awaitAll().forEach { infos.putAll(it) }
        val artworks = items.mapNotNull { WikimediaParser.toArtwork(it, infos[WikimediaParser.normalizedName(it.file)], query, tally) }
        tally.log(id, artistIdOf(query))
        artworks
    }
}

/**
 * Source de RECONNAISSANCE de la Hispanic Society of America (eMuseum) : aucun point d'accès JSON/IIIF vérifié n'est connu. Elle interroge
 * quelques adresses de recherche et consigne dans [Diag] ce qui revient (code, type, nombre de liens d'objets, mentions IIIF, début du corps),
 * pour écrire le vrai lecteur au cycle suivant. N'ajoute aucune œuvre.
 */
class HispanicSocietyProbe(private val http: ManifestSource) : MuseumSource {
    override val id = "hispanic"
    override val name = "Hispanic Society of America (reconnaissance)"
    override val europeanaKeyword = "hispanic society"
    override val reconnaissanceOnly = true

    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val term = java.net.URLEncoder.encode(spec.term ?: query.match, "UTF-8").replace("+", "%20")
        val urls = listOf(
            "https://hispanicsociety.emuseum.com/search/$term/objects",
            "https://hispanicsociety.emuseum.com/search/$term/objects/list",
            "https://hispanicsociety.emuseum.com/search/$term",
            "https://diglib.hispanicsociety.org/search?q=$term",
        )
        for (url in urls) {
            try {
                val text = http.fetch(url)
                val links = Regex("""/objects/(\d+)""").findAll(text).map { it.groupValues[1] }.toSet().size
                val iiif = Regex("iiif|manifest", RegexOption.IGNORE_CASE).findAll(text).count()
                Diag.info(
                    "reconnaissance", "${text.length} caractères, $links liens d'objets distincts, $iiif mentions iiif/manifest ; début : ${text.take(200).replace(Regex("\\s+"), " ")}",
                    url, id, artistIdOf(query), key = "hsa|$url",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.warn("reconnaissance", "inaccessible : ${e.message ?: e.javaClass.simpleName}", url, id, artistIdOf(query), key = "hsa|$url|err")
            }
        }
        return emptyList()
    }
}

/** Les sources implémentées, par identifiant (celui des [SourceSpec]). */
fun defaultMuseumSources(
    http: ManifestSource,
    /** Accès au Met : cadencé et retenté en cas de blocage temporaire (voir [RetryingSource]) ; par défaut, le même que les autres. */
    metHttp: ManifestSource = http,
    metNoticeCache: java.io.File? = null,
): Map<String, MuseumSource> =
    listOf(
        AicSource(http), RijksSource(http), EuropeanaSource(http), MetSource(metHttp, noticeCache = metNoticeCache),
        ClevelandSource(http), SmkSource(http), WikimediaSource(http), HispanicSocietyProbe(http),
    ).associateBy { it.id }
