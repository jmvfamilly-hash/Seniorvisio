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
    private val maxRequests: Int = 60,
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
        val blockMessage = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val artworks = ids.map { objectId ->
            async {
                gate.withPermit {
                    val url = MetParser.objectUrl(objectId)
                    try {
                        val cached = readNotice(objectId)
                        // pare-feu déclenché pendant ce passage : on n'insiste pas, le reste attend le passage suivant
                        if (cached == null && blockMessage.get() != null) { postponed.incrementAndGet(); return@withPermit null }
                        if (cached == null && (requests.incrementAndGet() > maxRequests || (clockNanos() - started) / 1_000_000 > softDeadlineMs)) {
                            postponed.incrementAndGet()
                            return@withPermit null
                        }
                        val text = cached ?: http.fetch(url).also { writeNotice(objectId, it) }
                        MetParser.parseObject(text, query, tally)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // blocage temporaire : cette notice et les suivantes attendent le prochain passage ; celles déjà lues sont gardées (cache disque)
                        if (TemporaryBlock.matches(e.message)) { blockMessage.compareAndSet(null, e.message); postponed.incrementAndGet(); return@withPermit null }
                        // notice ignorée : consignée (regroupée), la source continue
                        tally.raw++
                        tally.drop(if (e.message?.contains("HTTP 404") == true) "n'existent plus (404)" else "non lues (erreur réseau)")
                        Diag.warn("source", "notice ignorée : ${e.message ?: e.javaClass.simpleName}", url, id, key = "met-object|${e.message?.take(40)}")
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
        // bloqué avant d'avoir pu lire une seule notice : rien à montrer, la source est « limitée » (reprise plus tard)
        blockMessage.get()?.let { if (artworks.isEmpty()) throw IOException(it) }
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
 *
 * **Paginée et progressive** : la requête SPARQL (ordonnée) est suivie page par page (300 œuvres, jusqu'à [maxItems]) ; les licences sont
 * lues par lots de [WikimediaParser.BATCH] fichiers, un lot à la fois (Commons limite le débit). Pages SPARQL (24 h) et licences sont gardées
 * dans [cacheDir] : un passage qui s'arrête à son délai ([softDeadlineMs], le chargeur coupe à 45 s) rend ce qu'il a — les œuvres dont la
 * licence n'est pas encore lue s'affichent avec « licence non lue » — et la source est PARTIELLE (reprise automatique, voir [isComplete]).
 * Un lot en échec n'enlève rien : ses fichiers restent à lire au passage suivant.
 */
class WikimediaSource(
    private val http: ManifestSource,
    private val maxItems: Int = 1200,
    private val cacheDir: java.io.File? = null,
    private val softDeadlineMs: Long = 25_000,
    private val clockNanos: () -> Long = System::nanoTime,
) : MuseumSource {
    override val id = "wikimedia"
    override val name = "Wikimedia (Wikidata + Commons)"
    override val europeanaKeyword = "\u0000"
    private val qids = HashMap<String, String>()
    private val unfinished: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    override fun isComplete(query: ArtworkQuery): Boolean = artistIdOf(query) !in unfinished

    private fun cacheFile(name: String) = cacheDir?.let { java.io.File(it, name) }
    private fun readPage(slug: String, offset: Int): String? = runCatching {
        cacheFile("$slug-sparql-$offset.json")?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < 24 * 3_600_000L }?.readText()
    }.getOrNull()
    private fun writePage(slug: String, offset: Int, text: String) { runCatching { cacheDir?.mkdirs(); cacheFile("$slug-sparql-$offset.json")?.writeText(text) } }

    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val title = spec.term ?: throw IllegalArgumentException("Wikimedia : titre de l'article Wikipédia manquant")
        val slug = artistIdOf(query)
        val started = clockNanos()
        fun elapsedMs() = (clockNanos() - started) / 1_000_000
        val qid = qids[title] ?: (WikimediaParser.parseQid(http.fetch(WikimediaParser.qidUrl(title)))
            ?: throw IOException("identifiant Wikidata introuvable pour l'article « $title »")).also { qids[title] = it }
        val tally = Tally()

        // ── 1. œuvres : pages SPARQL ─────────────────────────────────────────────
        val items = LinkedHashMap<String, WikimediaParser.Item>()
        var incomplete = false
        var offset = 0
        while (offset < maxItems) {
            val text = readPage(slug, offset) ?: run {
                if (offset > 0 && elapsedMs() > softDeadlineMs * 3 / 5) { incomplete = true; return@run null }   // le reste au prochain passage
                try {
                    http.fetch(WikimediaParser.sparqlUrl(qid, offset)).also { writePage(slug, offset, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (offset == 0) throw e
                    Diag.warn("source", "page SPARQL ${offset / WikimediaParser.SPARQL_PAGE + 1} en échec (les pages déjà lues sont gardées) : ${e.message ?: e.javaClass.simpleName}", sourceId = id, artistId = slug, key = "wikimedia-page|${e.message?.take(40)}")
                    incomplete = true
                    null
                }
            } ?: break
            val before = tally.raw
            WikimediaParser.parseSparql(text, tally).forEach { items.putIfAbsent(it.qid, it) }
            if (tally.raw - before < WikimediaParser.SPARQL_PAGE) break           // dernière page
            offset += WikimediaParser.SPARQL_PAGE
        }

        // ── 2. licences : seulement pour les œuvres qu'on gardera, un lot à la fois, avec cache disque ──
        val candidates = items.values.filter { it.year == null || it.year in query.years }
        tally.drop("hors des dates plausibles", items.size - candidates.size)
        val infoFile = cacheFile("$slug-info.json")
        val infos: HashMap<String, WikimediaParser.FileInfo?> = infoFile?.let { f -> runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull() }
            ?.let(WikimediaParser::decodeInfoCache) ?: HashMap()
        val missing = candidates.map { WikimediaParser.normalizedName(it.file) }.filter { it !in infos }.distinct()
        var postponed = 0
        for (batch in missing.chunked(WikimediaParser.BATCH)) {
            if (elapsedMs() > softDeadlineMs) { postponed += batch.size; continue }
            try {
                val got = WikimediaParser.parseImageInfo(http.fetch(WikimediaParser.imageInfoUrl(batch)))
                for (name in batch) infos[name] = got[name]                       // absent de la réponse : mémorisé « sans métadonnées »
                runCatching { cacheDir?.mkdirs(); infoFile?.writeText(WikimediaParser.encodeInfoCache(infos)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postponed += batch.size
                Diag.warn("source", "licences d'un lot de ${batch.size} fichiers non lues (reprises au prochain passage) : ${e.message ?: e.javaClass.simpleName}", sourceId = id, artistId = slug, key = "wikimedia-batch|${e.message?.take(40)}")
            }
        }
        if (postponed > 0) Diag.info("source", "$postponed licences restent à lire (elles s'afficheront « non lues » en attendant)", sourceId = id, artistId = slug, key = "wikimedia-left|$slug")
        if (incomplete || postponed > 0) unfinished += slug else unfinished -= slug

        val artworks = candidates.mapNotNull { WikimediaParser.toArtwork(it, infos[WikimediaParser.normalizedName(it.file)], query, tally) }
        tally.log(id, slug)
        return artworks
    }
}

/**
 * Source de RECONNAISSANCE : aucun point d'accès vérifié n'est connu pour ce musée. Elle interroge quelques adresses probables et consigne dans
 * [Diag] ce qui revient (code, titre, formulaires, champs, images, liens d'objets, mentions IIIF/JSON-LD, forme d'un JSON, extrait lisible) pour
 * écrire le vrai lecteur au cycle suivant. [follow] peut tirer d'une réponse d'autres adresses à lire (ex. la première fiche d'objet d'une
 * recherche). N'ajoute aucune œuvre.
 */
open class ProbeSource(
    override val id: String,
    override val name: String,
    override val europeanaKeyword: String,
    private val http: ManifestSource,
    /** Longueur de l'extrait du corps consigné (160 pour une page ordinaire ; plus pour une documentation ou une réponse SPARQL). */
    private val excerptChars: Int = 160,
    /** D'une réponse, les adresses suivantes à lire (la première seulement est suivie). */
    private val follow: (text: String) -> List<String> = { emptyList() },
    /** Adresses à essayer ; reçoit le nom de l'artiste déjà encodé pour une URL. */
    private val urls: (term: String) -> List<String>,
) : MuseumSource {
    override val reconnaissanceOnly = true

    private suspend fun probe(url: String, query: ArtworkQuery, followed: Boolean) {
        try {
            val text = http.fetch(url)
            Diag.info("reconnaissance", (if (followed) "(suite) " else "") + describe(text, excerptChars), url, id, artistIdOf(query), key = "$id|$url")
            if (!followed) follow(text).firstOrNull()?.let { probe(it, query, followed = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.warn("reconnaissance", "inaccessible : ${e.message ?: e.javaClass.simpleName}", url, id, artistIdOf(query), key = "$id|$url|err")
        }
    }

    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val term = java.net.URLEncoder.encode(query.artistName, "UTF-8").replace("+", "%20")
        for (url in urls(term)) probe(url, query, followed = false)
        return emptyList()
    }

    internal companion object {
        /** Texte lisible d'une page : sans scripts, styles ni balises, espaces réduits. */
        fun readable(text: String): String =
            text.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ").replace(Regex("&nbsp;|&amp;"), " ").replace(Regex("\\s+"), " ").trim()

        /** Forme d'une réponse (page HTML, JSON ou OAI) en une ligne : de quoi écrire un lecteur sans la page sous les yeux. */
        fun describe(text: String, excerptChars: Int = 160): String {
            val flat = text.replace(Regex("\\s+"), " ")
            val json = flat.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(flat)?.groupValues?.get(1)?.trim()?.take(100)
            val forms = Regex("<form[^>]*action=\"([^\"]*)\"", RegexOption.IGNORE_CASE).findAll(flat).map { it.groupValues[1].substringBefore(";jsessionid") }.distinct().take(4).toList()
            val inputs = Regex("<(?:input|select|textarea)[^>]*name=\"([^\"]+)\"", RegexOption.IGNORE_CASE).findAll(flat).map { it.groupValues[1] }.distinct().take(15).toList()
            val images = Regex("(?:src|href)=\"([^\"]+\\.(?:jpe?g|png|tiff?)[^\"]*)\"", RegexOption.IGNORE_CASE).findAll(flat).map { it.groupValues[1] }.filterNot { it.contains("icon") || it.contains("favicon") }.distinct().take(4).toList()
            val links = Regex("href=\"([^\"]*/(?:objects?|artworks?|collection)/[\\w.-]*\\d[\\w.-]*[^\"]*)\"", RegexOption.IGNORE_CASE).findAll(flat).map { it.groupValues[1].substringBefore(";jsessionid") }.distinct().take(3).toList()
            val marks = listOf("IIIF", "manifest", "OAI-PMH", "application/ld+json", "__NEXT_DATA__", "sparql", "linked.art", "creativecommons", "open access", "public domain")
                .filter { flat.contains(it, ignoreCase = true) }
            val licence = Regex("creative commons|licencia|copyright|public domain|open access|CC0", RegexOption.IGNORE_CASE).findAll(flat).map { it.value.lowercase() }.distinct().take(4).toList()
            val body = if (json || excerptChars <= 160) flat.take(excerptChars) else readable(text).take(excerptChars)
            return "${text.length} caractères · " +
                (if (json) "JSON : ${JsonReading.describeShape(text)}" else "titre « ${title ?: "—"} » · formulaires $forms · champs $inputs · images $images · liens d'objets $links") +
                " · repères $marks · droits $licence · " + (if (json || excerptChars <= 160) "début" else "texte") + " : $body"
        }
    }
}

/** CER.ES (Red Digital de Colecciones de Museos de España, dont le Museo Sorolla) : la fiche d'une œuvre connue (déduite d'un identifiant Europeana), l'accueil et des adresses OAI-PMH probables. */
class CeresProbe(http: ManifestSource) : ProbeSource("ceres", "CER.ES / Museo Sorolla (reconnaissance)", "museo sorolla", http, urls = { _ ->
    listOf(
        "https://ceres.mcu.es/pages/Main?idt=27659&inventary=85829&table=FDOC&museum=MSM",
        "https://ceres.cultura.gob.es/",
        "https://ceres.mcu.es/pages/Main",
        "https://ceres.mcu.es/oai/?verb=Identify",
        "https://ceres.cultura.gob.es/oai/request?verb=Identify",
    )
})

private fun sparqlUrl(query: String) = "https://data.getty.edu/museum/collection/sparql?query=" + java.net.URLEncoder.encode(query, "UTF-8")

/**
 * J. Paul Getty Museum. Reconnaissance rev35 : `data.getty.edu/museum/collection/` renvoie vers `/docs/` et son point **SPARQL répond** (JSON standard
 * `head`/`results`). Cette 2e passe lit la documentation, les types RDF les plus fréquents, une œuvre (SPARQL puis sa fiche JSON-LD) et une recherche
 * par nom, pour écrire la vraie source.
 */
class GettyProbe(http: ManifestSource) : ProbeSource(
    "getty", "J. Paul Getty Museum (reconnaissance)", "getty", http, excerptChars = 900,
    follow = { text -> Regex("\"(https://data\\.getty\\.edu/museum/collection/object/[^\"]+)\"").find(text)?.groupValues?.get(1)?.let { listOf(it) } ?: emptyList() },
    urls = { term ->
        listOf(
            "https://data.getty.edu/museum/collection/docs/",
            sparqlUrl("SELECT ?type (COUNT(*) AS ?n) WHERE { ?s a ?type } GROUP BY ?type ORDER BY DESC(?n) LIMIT 25"),
            sparqlUrl("SELECT ?s WHERE { ?s <http://www.cidoc-crm.org/cidoc-crm/P108i_was_produced_by> ?p } LIMIT 1"),
            sparqlUrl("SELECT ?s ?p ?o WHERE { ?s ?p ?o . FILTER(isLiteral(?o) && CONTAINS(LCASE(STR(?o)), \"" + java.net.URLDecoder.decode(term, "UTF-8").lowercase() + "\")) } LIMIT 8"),
        )
    },
)

/**
 * Museum of Fine Arts, Boston. Reconnaissance rev35 : la recherche de `collections.mfa.org` (chemin `search/objects`, un astérisque, puis le nom) répond une page de résultats (Apache Tapestry,
 * pas d'API). Cette 2e passe lit la page de résultats puis la PREMIÈRE fiche d'objet trouvée, pour voir les liens, les images et les droits.
 */
class MfaProbe(http: ManifestSource) : ProbeSource(
    "mfa", "Museum of Fine Arts, Boston (reconnaissance)", "museum of fine arts, boston", http, excerptChars = 700,
    follow = { text -> Regex("href=\"(/objects/\\d+[^\"]*)\"").find(text)?.groupValues?.get(1)?.substringBefore(";jsessionid")?.let { listOf("https://collections.mfa.org$it") } ?: emptyList() },
    urls = { term -> listOf("https://collections.mfa.org/search/objects/*/$term") },
)

/** Van Gogh Museum : recherche de la collection en ligne, puis la première fiche d'œuvre trouvée ; plateforme « Van Gogh Worldwide ». */
class VanGoghMuseumProbe(http: ManifestSource) : ProbeSource(
    "vgm", "Van Gogh Museum (reconnaissance)", "van gogh museum", http, excerptChars = 700,
    follow = { text -> Regex("href=\"(/en/collection/[sd]\\d+[^\"]*)\"").find(text)?.groupValues?.get(1)?.let { listOf("https://www.vangoghmuseum.nl$it") } ?: emptyList() },
    urls = { term ->
        listOf(
            "https://www.vangoghmuseum.nl/en/collection?q=$term",
            "https://vangoghworldwide.org/",
        )
    },
)

/** Les sources implémentées, par identifiant (celui des [SourceSpec]). */
fun defaultMuseumSources(
    http: ManifestSource,
    /** Accès au Met : cadencé et retenté en cas de blocage temporaire (voir [RetryingSource]) ; par défaut, le même que les autres. */
    metHttp: ManifestSource = http,
    metNoticeCache: java.io.File? = null,
    /** Accès à Wikidata/Commons : cadencé et retenté (limite de débit 429) ; par défaut, le même que les autres. */
    wikiHttp: ManifestSource = http,
    /** Dossier du cache de Wikimedia (pages SPARQL et licences, d'un passage à l'autre) ; `null` = pas de cache. */
    wikimediaCache: java.io.File? = null,
    /** Lecture d'un fichier d'`assets/nga/` (nom sans extension) ; `null` = pas de fichier. */
    ngaAsset: (String) -> String? = { null },
): Map<String, MuseumSource> =
    listOf(
        AicSource(http), RijksSource(http), EuropeanaSource(http), MetSource(metHttp, noticeCache = metNoticeCache),
        ClevelandSource(http), SmkSource(http), NgaSource(ngaAsset), WikimediaSource(wikiHttp, cacheDir = wikimediaCache),
        CeresProbe(http), GettyProbe(http), MfaProbe(http), VanGoghMuseumProbe(http),
    ).associateBy { it.id }
