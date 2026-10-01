package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artist
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkMerge
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.SourceSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/** Le service dit lui-même que cette API est retirée (HTTP 410, ou un message « retired ») : inutile de la retenter autrement. */
class ApiRetiredException(message: String) : IOException(message)

object ApiRetired {
    fun matches(message: String?): Boolean =
        message != null && (message.contains("retired", ignoreCase = true) || message.contains("HTTP 410"))
}

/** L'univers d'un artiste : les œuvres des sources CONNECTÉES (fusionnées), et le rapport de chaque source. */
class UniverseState(
    val artworks: List<Artwork>,
    val reports: List<SourceReport>,
    val done: Boolean,
    /** « Chicago (12) · Rijksmuseum (8) » : musées connectés et nombre d'œuvres montrées. */
    val credit: String,
) {
    val connectedCount: Int get() = reports.count { it.state.connected }
}

/**
 * Construit l'univers d'un artiste en trois temps, par source et EN PARALLÈLE :
 *
 * 1. **Chercher** ([MuseumSource.fetch]) ;
 * 2. **Valider l'accès** ([SourceValidator]) : les images/manifestes d'un échantillon doivent être lisibles. Sinon la source est
 *    REFUSÉE et aucune de ses œuvres n'entre dans la frise — pas de carte qui ne s'ouvre pas ;
 * 3. **Connecter** : les œuvres validées sont fusionnées ([ArtworkMerge]) et copiées sur disque (hors ligne).
 *
 * Si le réseau échoue pour une source, sa copie locale (issue d'une validation passée) est reprise. [onUpdate] est rappelé à chaque
 * source terminée, avec la fusion complète : la frise peut s'afficher dès la première.
 */
class UniverseLoader(
    private val sources: Map<String, MuseumSource>,
    private val validator: SourceValidator,
    private val cacheDir: File,
    private val timeoutMs: Long = 45_000,
    /** Mêmes sources avec un User-Agent sobre : retentées quand la requête normale échoue (voir [runSource]). */
    private val fallbackSources: Map<String, MuseumSource> = emptyMap(),
) {
    private class Outcome(val report: SourceReport, val artworks: List<Artwork>)

    suspend fun load(artist: Artist, onUpdate: (UniverseState) -> Unit): UniverseState = coroutineScope {
        val query = ArtworkQuery.of(artist)
        val reports = LinkedHashMap<String, SourceReport>()
        val connected = HashMap<String, List<Artwork>>()
        for (spec in artist.sources) {
            reports[spec.sourceId] = SourceReport(spec.sourceId, sources[spec.sourceId]?.name ?: spec.sourceId, SourceState.PENDING, 0, "vérification…")
        }

        fun snapshot(done: Boolean): UniverseState {
            // Europeana agrège les mêmes musées : ses notices d'un musée déjà connecté directement sont écartées.
            val directKeywords = artist.sources.filter { it.sourceId != "europeana" && connected.containsKey(it.sourceId) }
                .mapNotNull { sources[it.sourceId]?.europeanaKeyword }
            val lists = artist.sources.map { spec ->
                val list = connected[spec.sourceId].orEmpty()
                if (spec.sourceId == "europeana") list.filterNot { a -> directKeywords.any { a.provider.lowercase().contains(it) } } else list
            }
            val merged = ArtworkMerge.merge(lists)
            val credit = artist.sources.mapIndexedNotNull { i, spec ->
                val r = reports[spec.sourceId]
                if (r != null && r.state.connected && lists[i].isNotEmpty()) "${r.name} (${lists[i].size}${if (r.state == SourceState.CACHED) ", hors ligne" else ""})" else null
            }.joinToString(" · ")
            return UniverseState(merged, reports.values.toList(), done, credit)
        }

        artist.sources.map { spec ->
            async {
                val outcome = runSource(artist, spec, query)
                reports[spec.sourceId] = outcome.report
                if (outcome.report.state.connected) connected[spec.sourceId] = outcome.artworks
                onUpdate(snapshot(false))
            }
        }.awaitAll()
        snapshot(true).also(onUpdate)
    }

    private suspend fun runSource(artist: Artist, spec: SourceSpec, query: ArtworkQuery): Outcome {
        val source = sources[spec.sourceId]
        if (source == null) {
            Diag.warn("source", "source non implémentée (clé d'API ou service à venir)", sourceId = spec.sourceId, artistId = artist.id)
            return Outcome(SourceReport(spec.sourceId, spec.sourceId, SourceState.UNAVAILABLE, 0, "source non implémentée (clé d'API ou service à venir)"), emptyList())
        }
        val cache = File(cacheDir, "${artist.id}_${source.id}.json")

        val fetched = try {
            fetchWithFallback(artist, source, spec, query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiRetiredException) {
            return retired(artist, source, cache, e)
        } catch (e: Exception) {
            return fromCache(artist, source, cache, e)
        }
        if (fetched.isEmpty()) {
            Diag.warn("source", "aucune œuvre exploitable pour cet artiste", sourceId = source.id, artistId = artist.id)
            return Outcome(SourceReport(source.id, source.name, SourceState.EMPTY, 0, "aucune œuvre exploitable pour cet artiste"), emptyList())
        }

        val (ok, probes) = validator.validate(fetched)
        // chaque échantillon en échec est consigné, MÊME si la source est finalement connectée
        for (p in probes.filter { !it.ok }) {
            Diag.warn("validation", "échantillon « ${p.artworkTitle} » : ${p.detail}", p.url, source.id, artist.id)
        }
        if (!ok) {
            val why = probes.firstOrNull { !it.ok }?.detail ?: "accès impossible"
            Diag.error("validation", "source REFUSÉE (${probes.count { !it.ok }}/${probes.size} échantillons en échec) : $why", sourceId = source.id, artistId = artist.id)
            return Outcome(SourceReport(source.id, source.name, SourceState.REJECTED, fetched.size, "accès aux images refusé : $why", probes), emptyList())
        }
        runCatching { cache.writeText(ArtworkJson.encode(fetched)) }
        val detail = "${fetched.size} œuvres · accès vérifié (${probes.count { it.ok }}/${probes.size})"
        Diag.info("source", "connectée : $detail", sourceId = source.id, artistId = artist.id)
        return Outcome(SourceReport(source.id, source.name, SourceState.CONNECTED, fetched.size, detail, probes), fetched)
    }

    private suspend fun fetchOnce(src: MuseumSource, spec: SourceSpec, query: ArtworkQuery): List<Artwork> =
        withTimeoutOrNull(timeoutMs) { src.fetch(query, spec) } ?: throw IOException("délai dépassé (${timeoutMs / 1000} s)")

    /**
     * Requête normale ; si elle échoue, UNE nouvelle tentative avec le User-Agent sobre. Les deux échecs, et le succès du repli,
     * sont consignés : un repli qui « sauve » la source reste visible dans le rapport.
     */
    private suspend fun fetchWithFallback(artist: Artist, source: MuseumSource, spec: SourceSpec, query: ArtworkQuery): List<Artwork> {
        try {
            return fetchOnce(source, spec, query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            Diag.warn("source", "recherche en échec : $why", sourceId = source.id, artistId = artist.id)
            // API retirée par le service : le User-Agent n'y change rien, on ne retente pas
            if (ApiRetired.matches(why)) throw ApiRetiredException(why)
            val fallback = fallbackSources[source.id]
            if (fallback == null || e is IllegalArgumentException) throw e
            try {
                val result = fetchOnce(fallback, spec, query)
                Diag.warn("source", "repli (User-Agent sobre) RÉUSSI après l'échec de la requête normale : ${result.size} œuvres", sourceId = source.id, artistId = artist.id)
                return result
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                Diag.warn("source", "repli (User-Agent sobre) en échec aussi : ${e2.message ?: e2.javaClass.simpleName}", sourceId = source.id, artistId = artist.id)
                throw e
            }
        }
    }

    /** API retirée : la copie d'une connexion validée passée, sinon la source est INDISPONIBLE avec le message du service. */
    private fun retired(artist: Artist, source: MuseumSource, cache: File, error: ApiRetiredException): Outcome {
        Diag.error("source", "API RETIRÉE par le service : ${error.message}", sourceId = source.id, artistId = artist.id)
        val cached = runCatching { cache.takeIf { it.exists() }?.readText() }.getOrNull()?.let(ArtworkJson::decode).orEmpty()
        return if (cached.isNotEmpty()) {
            Outcome(SourceReport(source.id, source.name, SourceState.CACHED, cached.size, "API retirée : copie hors ligne d'une connexion passée (${error.message?.take(160)})"), cached)
        } else {
            Outcome(SourceReport(source.id, source.name, SourceState.UNAVAILABLE, 0, "API retirée par le service : ${error.message}"), emptyList())
        }
    }

    private fun fromCache(artist: Artist, source: MuseumSource, cache: File, error: Exception): Outcome {
        val cached = runCatching { cache.takeIf { it.exists() }?.readText() }.getOrNull()?.let(ArtworkJson::decode).orEmpty()
        val why = error.message ?: error.javaClass.simpleName
        return if (cached.isNotEmpty()) {
            Diag.warn("source", "connexion impossible → copie hors ligne utilisée (${cached.size} œuvres) : $why", sourceId = source.id, artistId = artist.id)
            Outcome(SourceReport(source.id, source.name, SourceState.CACHED, cached.size, "copie hors ligne ($why)"), cached)
        } else {
            Diag.error("source", "injoignable, aucune copie hors ligne : $why", sourceId = source.id, artistId = artist.id)
            Outcome(SourceReport(source.id, source.name, SourceState.UNREACHABLE, 0, why), emptyList())
        }
    }
}
