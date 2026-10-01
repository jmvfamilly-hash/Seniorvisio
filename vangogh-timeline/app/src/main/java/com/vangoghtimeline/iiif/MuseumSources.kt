package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.SourceSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork>
}

class AicSource(private val http: ManifestSource) : MuseumSource {
    override val id = "aic"
    override val name = "Art Institute of Chicago"
    override val europeanaKeyword = "art institute"
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> =
        ArticParser.parse(http.fetch(ArticParser.searchUrl(query)), query)
}

class EuropeanaSource(private val http: ManifestSource, private val key: String = EuropeanaParser.DEMO_KEY) : MuseumSource {
    override val id = "europeana"
    override val name = "Europeana"
    override val europeanaKeyword = "\u0000"   // Europeana ne fait pas doublon avec elle-même

    /** Variantes de nom essayées dans l'ordre (exact, sans accents, nom de famille) ; chacune est consignée avec son résultat. */
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> {
        val variants = EuropeanaParser.searchVariants(query, key)
        var firstError: Exception? = null
        var answered = false
        for ((index, url) in variants.withIndex()) {
            try {
                val arts = EuropeanaParser.parse(http.fetch(url), query)
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
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> =
        ClevelandParser.parse(http.fetch(ClevelandParser.searchUrl(query)), query)
}

class SmkSource(private val http: ManifestSource) : MuseumSource {
    override val id = "smk"
    override val name = "Statens Museum for Kunst"
    override val europeanaKeyword = "statens museum"
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> =
        SmkParser.parse(http.fetch(SmkParser.searchUrl(query)), query)
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
) : MuseumSource {
    override val id = "met"
    override val name = "The Metropolitan Museum of Art"
    override val europeanaKeyword = "metropolitan"

    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> = coroutineScope {
        val ids = searchIds(query).take(maxObjects)
        val gate = Semaphore(parallelism)
        ids.map { objectId ->
            async {
                gate.withPermit {
                    val url = MetParser.objectUrl(objectId)
                    try {
                        MetParser.parseObject(http.fetch(url), query)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // notice ignorée : consignée (regroupée), la source continue
                        Diag.warn("source", "notice ignorée : ${e.message ?: e.javaClass.simpleName}", url, id, key = "met-object|${e.message?.take(40)}")
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
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
        ids.map { objectId ->
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
        }.awaitAll().filterNotNull().filter { it.date.year in query.years }
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

/** Les sources implémentées, par identifiant (celui des [SourceSpec]). */
fun defaultMuseumSources(http: ManifestSource): Map<String, MuseumSource> =
    listOf(AicSource(http), RijksSource(http), EuropeanaSource(http), MetSource(http), ClevelandSource(http), SmkSource(http))
        .associateBy { it.id }
