package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** Ce qu'a rendu un musée : [cached] = copie hors ligne (le réseau a échoué). */
class MuseumResult(val artworks: List<Artwork>, val cached: Boolean)

/** Lit/écrit la copie locale d'une liste d'œuvres. */
internal class ArtworkCache(private val file: File) {
    fun save(list: List<Artwork>) { runCatching { file.writeText(ArtworkJson.encode(list)) } }
    fun read(): List<Artwork> = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()?.let(ArtworkJson::decode).orEmpty()
}

/**
 * Rijksmuseum : recherche (jusqu'à [maxPages] pages) puis, pour chaque objet, objet → VisualItem → DigitalObject
 * (voir [RijksmuseumParser]). Les objets sont lus [parallelism] à la fois. Une œuvre dont une étape échoue est ignorée.
 * Réseau en échec → copie locale ; rien du tout → `null`.
 */
class RijksmuseumRepository(
    private val source: ManifestSource,
    cacheFile: File,
    private val searchUrl: String = RijksmuseumParser.SEARCH_URL,
    private val maxPages: Int = 3,
    private val parallelism: Int = 6,
) {
    private val cache = ArtworkCache(cacheFile)

    suspend fun load(): MuseumResult? {
        try {
            val arts = fetch()
            if (arts.isNotEmpty()) { cache.save(arts); return MuseumResult(arts, cached = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // réseau indisponible : copie locale
        }
        return cache.read().takeIf { it.isNotEmpty() }?.let { MuseumResult(it, cached = true) }
    }

    private suspend fun fetch(): List<Artwork> = coroutineScope {
        val ids = LinkedHashSet<String>()
        var next: String? = searchUrl
        var pages = 0
        while (next != null && pages < maxPages) {
            // une page suivante en échec n'enlève pas les œuvres déjà trouvées ; seule la première page est indispensable
            val page = try {
                RijksmuseumParser.parseSearchPage(source.fetch(next))
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
        ids.map { id ->
            async {
                gate.withPermit {
                    try { resolve(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                }
            }
        }.awaitAll().filterNotNull()
            .filter { it.date.year in 1870..1890 }
            .sortedWith(compareBy({ it.date.positionEpochDay }, { it.id }))
    }

    private suspend fun resolve(objectId: String): Artwork? {
        val info = RijksmuseumParser.parseObject(source.fetch(objectId)) ?: return null
        val digitalObjects = RijksmuseumParser.parseVisualItem(source.fetch(info.visualItemUrl))
        for (url in digitalObjects) {
            val service = RijksmuseumParser.parseDigitalObject(source.fetch(url)) ?: continue
            return RijksmuseumParser.toArtwork(info, service)
        }
        return null
    }
}

/** Europeana : une seule requête de recherche. Même repli que [RijksmuseumRepository]. */
class EuropeanaRepository(
    private val source: ManifestSource,
    cacheFile: File,
    private val url: String = EuropeanaParser.searchUrl(),
) {
    private val cache = ArtworkCache(cacheFile)

    suspend fun load(): MuseumResult? {
        try {
            val arts = EuropeanaParser.parse(source.fetch(url))
            if (arts.isNotEmpty()) { cache.save(arts); return MuseumResult(arts, cached = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // réseau indisponible : copie locale
        }
        return cache.read().takeIf { it.isNotEmpty() }?.let { MuseumResult(it, cached = true) }
    }
}
