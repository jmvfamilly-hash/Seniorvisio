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
    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> =
        EuropeanaParser.parse(http.fetch(EuropeanaParser.searchUrl(query, key)), query)
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

/** The Met : une recherche, puis une notice par œuvre (au plus [maxObjects], [parallelism] à la fois ; une notice en échec est ignorée). */
class MetSource(
    private val http: ManifestSource,
    private val maxObjects: Int = 80,
    private val parallelism: Int = 6,
) : MuseumSource {
    override val id = "met"
    override val name = "The Metropolitan Museum of Art"
    override val europeanaKeyword = "metropolitan"

    override suspend fun fetch(query: ArtworkQuery, spec: SourceSpec): List<Artwork> = coroutineScope {
        val ids = MetParser.parseSearch(http.fetch(MetParser.searchUrl(query))).take(maxObjects)
        val gate = Semaphore(parallelism)
        ids.map { id ->
            async {
                gate.withPermit {
                    try { MetParser.parseObject(http.fetch(MetParser.objectUrl(id)), query) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                }
            }
        }.awaitAll().filterNotNull()
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
                if (pages == 0) throw e else break
            }
            ids += page.objectIds
            next = page.next
            pages++
        }
        val gate = Semaphore(parallelism)
        ids.map { objectId ->
            async {
                gate.withPermit {
                    try { resolve(objectId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
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
