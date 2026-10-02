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

    /** Faux si la dernière lecture pour cet artiste n'est pas finie (lecture par tranches) : le chargeur la reprendra. */
    fun isComplete(query: ArtworkQuery): Boolean = true
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

    /** Variantes de mots-clés (nom complet, sans accents, nom de famille) : la première qui donne des œuvres gagne ; chacune est consignée. */
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val keys = SmkParser.searchKeys(query)
        var firstError: Exception? = null
        var answered = false
        for ((index, k) in keys.withIndex()) {
            val url = SmkParser.searchUrl(query, k)
            try {
                val tally = Tally()
                val arts = SmkParser.parse(http.fetch(url), query, tally)
                tally.log(id, artistIdOf(query), "variante ${index + 1}/${keys.size} « $k »")
                answered = true
                if (arts.isNotEmpty()) return arts
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                Diag.warn("source", "recherche : variante ${index + 1}/${keys.size} en échec : ${e.message ?: e.javaClass.simpleName}", url, id)
            }
        }
        if (!answered && firstError != null) throw firstError
        return emptyList()
    }
}

/**
 * National Gallery of Art : pas d'API en ligne, seulement un open data en CSV. Les œuvres des artistes de la frise sont extraites
 * à l'avance (`tools/nga_extract.py`) dans des fichiers `assets/nga/{artiste}.json` lus par [readAsset] (voir [NgaParser]).
 */
class NgaSource(private val readAsset: (String) -> String?) : MuseumSource {
    override val id = "nga"
    override val name = "National Gallery of Art"
    override val europeanaKeyword = "national gallery of art"
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val text = readAsset(NgaParser.assetName(query)) ?: return emptyList()    // aucun fichier pour cet artiste : source vide, pas une erreur
        val tally = Tally()
        return NgaParser.parse(text, query, tally).also { tally.log(id, artistIdOf(query)) }
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
    private val maxObjects: Int = 400,
    private val parallelism: Int = 6,
    /** Notices déjà lues (une par fichier) : elles ne changent pas, on ne les redemande pas (et le Met n'est pas sollicité deux fois). */
    private val noticeCache: java.io.File? = null,
    /** Notices NON gardées sur disque lues par passage : au-delà, la lecture s'arrête (source PARTIELLE) et reprend au passage suivant. */
    private val maxRequests: Int = 100,
    /** Passé ce délai, on ne lance plus de nouvelle notice (le chargeur coupe à 45 s : mieux vaut rendre ce qu'on a que tout perdre). */
    private val softDeadlineMs: Long = 30_000,
    private val clockNanos: () -> Long = System::nanoTime,
) : MuseumSource {
    override val id = "met"
    override val name = "The Metropolitan Museum of Art"
    override val europeanaKeyword = "metropolitan"

    private val unfinished: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    override fun isComplete(query: ArtworkQuery): Boolean = artistIdOf(query) !in unfinished

    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> = coroutineScope {
        val ids = searchIds(query).take(maxObjects)
        val gate = Semaphore(parallelism)
        val tally = Tally()
        val started = clockNanos()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val postponed = java.util.concurrent.atomic.AtomicInteger()
        val artworks = ids.map { objectId ->
            async {
                gate.withPermit {
                    val url = MetParser.objectUrl(objectId)
                    try {
                        val cached = readNotice(objectId)
                        if (cached == null && (requests.incrementAndGet() > maxRequests || (clockNanos() - started) / 1_000_000 > softDeadlineMs)) {
                            postponed.incrementAndGet()
                            return@withPermit null
                        }
                        val text = cached ?: http.fetch(url).also { writeNotice(objectId, it) }
                        MetParser.parseObject(text, query, tally)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // blocage temporaire : on arrête (source « limitée », reprise plus tard) ; les notices déjà lues sont en cache disque
                        if (TemporaryBlock.matches(e.message)) throw e
                        // notice ignorée : consignée (regroupée), la source continue
                        tally.raw++
                        tally.drop(if (e.message?.contains("HTTP 404") == true) "n'existent plus (404)" else "non lues (erreur réseau)")
                        Diag.warn("source", "notice ignorée : ${e.message ?: e.javaClass.simpleName}", url, id, key = "met-object|${e.message?.take(40)}")
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
        if (postponed.get() > 0) {
            unfinished += artistIdOf(query)
            tally.raw += postponed.get()
            tally.drop("à lire au prochain chargement", postponed.get())
        } else {
            unfinished -= artistIdOf(query)
        }
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

    /** Pages suivantes d'une recherche paginée dont la première page était pleine, jusqu'à [maxObjects] ; un échec garde les pages déjà lues. */
    private suspend fun morePages(url: String, firstPageSize: Int, total: Int?): List<Int> {
        if (!MetParser.isPaginated(url) || firstPageSize < MetParser.PAGE_SIZE || (total != null && total <= MetParser.PAGE_SIZE)) return emptyList()
        val out = ArrayList<Int>()
        var offset = MetParser.PAGE_SIZE
        var lastSize = firstPageSize
        while (offset < maxObjects && lastSize >= MetParser.PAGE_SIZE && (total == null || offset < total)) {
            val next = MetParser.pageUrl(url, offset)
            val page = try {
                MetParser.parseSearch(http.fetch(next))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.warn("source", "page ${offset / MetParser.PAGE_SIZE + 1} de la recherche en échec (les pages déjà lues sont gardées) : ${e.message ?: e.javaClass.simpleName}", next, id)
                break
            }
            out += page
            lastSize = page.size
            offset += MetParser.PAGE_SIZE
        }
        return out
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
class WikimediaSource(private val http: ManifestSource, private val parallelism: Int = 1) : MuseumSource {
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
 * Source de RECONNAISSANCE de **CER.ES** (Red Digital de Colecciones de Museos de España, dont le Museo Sorolla) : aucun point d'accès
 * vérifié n'est connu. Elle interroge la fiche d'une œuvre connue (déduite d'un identifiant Europeana : table FDOC, musée MSM), la page
 * d'accueil et quelques adresses probables de moissonnage OAI-PMH, puis consigne dans [Diag] ce qui revient : code, titre de la page,
 * formulaires et champs, liens d'images, mentions de licence, début du corps. N'ajoute aucune œuvre : le vrai lecteur s'écrira avec ces formes.
 */
class CeresProbe(private val http: ManifestSource) : MuseumSource {
    override val id = "ceres"
    override val name = "CER.ES / Museo Sorolla (reconnaissance)"
    override val europeanaKeyword = "museo sorolla"
    override val reconnaissanceOnly = true

    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val urls = listOf(
            "https://ceres.mcu.es/pages/Main?idt=27659&inventary=85829&table=FDOC&museum=MSM",
            "https://ceres.cultura.gob.es/",
            "https://ceres.mcu.es/pages/Main",
            "https://ceres.mcu.es/oai/?verb=Identify",
            "https://ceres.cultura.gob.es/oai/request?verb=Identify",
        )
        for (url in urls) {
            try {
                Diag.info("reconnaissance", describe(http.fetch(url)), url, id, artistIdOf(query), key = "ceres|$url")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.warn("reconnaissance", "inaccessible : ${e.message ?: e.javaClass.simpleName}", url, id, artistIdOf(query), key = "ceres|$url|err")
            }
        }
        return emptyList()
    }

    internal companion object {
        /** Forme d'une page HTML (ou d'une réponse OAI) en une ligne : de quoi écrire un lecteur sans la page sous les yeux. */
        fun describe(text: String): String {
            val flat = text.replace(Regex("\\s+"), " ")
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(flat)?.groupValues?.get(1)?.trim()?.take(100)
            val forms = Regex("<form[^>]*action=\"([^\"]*)\"", RegexOption.IGNORE_CASE).findAll(flat).map { it.groupValues[1] }.distinct().take(4).toList()
            val inputs = Regex("<(?:input|select|textarea)[^>]*name=\"([^\"]+)\"", RegexOption.IGNORE_CASE).findAll(flat).map { it.groupValues[1] }.distinct().take(15).toList()
            val images = Regex("(?:src|href)=\"([^\"]+\\.(?:jpe?g|png|tiff?)[^\"]*)\"", RegexOption.IGNORE_CASE).findAll(flat).map { it.groupValues[1] }.distinct().take(4).toList()
            val oai = Regex("OAI-PMH|repositoryName|oai_dc", RegexOption.IGNORE_CASE).containsMatchIn(flat)
            val licence = Regex("creative commons|licencia|derechos de reproducci|dominio p.blico|copyright", RegexOption.IGNORE_CASE).findAll(flat).map { it.value.lowercase() }.distinct().take(4).toList()
            return "${text.length} caractères · titre « ${title ?: "—"} » · formulaires $forms · champs $inputs · images $images · OAI-PMH : ${if (oai) "oui" else "non"} · mentions de droits $licence · début : ${flat.take(160)}"
        }
    }
}

/** Les sources implémentées, par identifiant (celui des [SourceSpec]). */
fun defaultMuseumSources(
    http: ManifestSource,
    /** Accès au Met : cadencé et retenté en cas de blocage temporaire (voir [RetryingSource]) ; par défaut, le même que les autres. */
    metHttp: ManifestSource = http,
    metNoticeCache: java.io.File? = null,
    /** Accès à Wikidata/Commons : cadencé et retenté (limite de débit 429) ; par défaut, le même que les autres. */
    wikiHttp: ManifestSource = http,
    /** Lecture d'un fichier d'`assets/nga/` (nom sans extension) ; `null` = pas de fichier. */
    ngaAsset: (String) -> String? = { null },
): Map<String, MuseumSource> =
    listOf(
        AicSource(http), RijksSource(http), EuropeanaSource(http), MetSource(metHttp, noticeCache = metNoticeCache),
        ClevelandSource(http), SmkSource(http), NgaSource(ngaAsset), WikimediaSource(wikiHttp), CeresProbe(http),
    ).associateBy { it.id }
