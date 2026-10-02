package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artist
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkMerge
import com.vangoghtimeline.model.ArtworkQuery
import com.vangoghtimeline.model.RightsCatalog
import com.vangoghtimeline.model.SourceSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    /** Faux tant qu'au moins une source est encore en cours de recherche ou de rafraîchissement. */
    val done: Boolean,
    /** « Chicago (12) · Rijksmuseum (8) » : musées connectés et nombre d'œuvres montrées. */
    val credit: String,
    /** Date (ms) des données les plus anciennes parmi les sources connectées ; 0 si aucune. */
    val updatedAtMs: Long = 0L,
) {
    /** « 120 domaine public · 14 licence ouverte · 3 consultation privée » : décompte des licences des œuvres montrées. */
    val rightsSummary: String get() = RightsCatalog.summary(artworks)

    val connectedCount: Int get() = reports.count { it.state.connected }
}

/**
 * Construit l'univers d'un artiste, **magasin local d'abord** :
 *
 * 1. Les sources déjà enregistrées ([UniverseStore]) sont lues et l'univers est publié TOUT DE SUITE, sans réseau.
 * 2. Pour chaque source, [StorePolicy] décide : fraîche (rien à faire, 7 jours), à rafraîchir (on s'en sert déjà, mise à jour en
 *    arrière-plan), ou à chercher (jamais obtenue, ou échec vieux d'une heure). `force` rafraîchit tout.
 * 3. Une source à chercher suit trois temps, EN PARALLÈLE : **chercher** ([MuseumSource.fetch]), **valider l'accès** ([SourceValidator] :
 *    sinon la source est REFUSÉE et aucune de ses œuvres n'entre dans la frise), **connecter** (fusion par [ArtworkMerge] et enregistrement).
 *
 * Si le réseau échoue pour une source, la copie enregistrée d'une connexion validée passée est reprise. [onUpdate] est rappelé à
 * chaque étape, avec la fusion complète.
 */
class UniverseLoader(
    private val sources: Map<String, MuseumSource>,
    private val validator: SourceValidator,
    /** Dossier du magasin local (un fichier JSON par artiste). */
    private val cacheDir: File,
    private val timeoutMs: Long = 45_000,
    /** Mêmes sources avec un User-Agent sobre : retentées quand la requête normale échoue (voir [runSource]). */
    private val fallbackSources: Map<String, MuseumSource> = emptyMap(),
    /** Délai avant le nouvel essai automatique des sources bloquées temporairement ([SourceState.LIMITED]) ; négatif = jamais. */
    private val limitedRetryDelayMs: Long = 60_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class Outcome(val report: SourceReport, val artworks: List<Artwork>, val fetchedAt: Long)

    suspend fun load(artist: Artist, force: Boolean = false, onUpdate: (UniverseState) -> Unit): UniverseState = coroutineScope {
        val query = ArtworkQuery.of(artist)
        val reports = LinkedHashMap<String, SourceReport>()
        val connected = HashMap<String, List<Artwork>>()
        val fetchedAt = HashMap<String, Long>()
        val stored = HashMap<String, StoredSource>()
        val start = clock()

        // ── 1. magasin local ─────────────────────────────────────────────────────
        withContext(Dispatchers.IO) { UniverseStore.read(cacheDir, artist.id) }?.sources?.forEach { (id, s) ->
            if (artist.sources.any { it.sourceId == id }) stored[id] = s
        }
        for (spec in artist.sources) {
            val st = stored[spec.sourceId]
            if (st == null) {
                reports[spec.sourceId] = SourceReport(spec.sourceId, sources[spec.sourceId]?.name ?: spec.sourceId, SourceState.PENDING, 0, "vérification…")
            } else {
                reports[spec.sourceId] = st.toReport(spec.sourceId)
                if (st.state.connected) { connected[spec.sourceId] = st.artworks; fetchedAt[spec.sourceId] = st.fetchedAt }
            }
        }

        fun snapshot(done: Boolean): UniverseState {
            // Europeana agrège les mêmes musées : ses notices d'un musée déjà connecté directement sont écartées.
            val aggregators = setOf("europeana", "wikimedia")
            val directKeywords = artist.sources.filter { it.sourceId !in aggregators && connected.containsKey(it.sourceId) }
                .mapNotNull { sources[it.sourceId]?.europeanaKeyword }
            val lists = artist.sources.map { spec ->
                val list = connected[spec.sourceId].orEmpty()
                if (spec.sourceId in aggregators) list.filterNot { a -> directKeywords.any { a.provider.lowercase().contains(it) } } else list
            }
            val merged = ArtworkMerge.merge(lists)
            val credit = artist.sources.mapIndexedNotNull { i, spec ->
                val r = reports[spec.sourceId]
                if (r != null && r.state.connected && lists[i].isNotEmpty()) "${r.name} (${lists[i].size}${if (r.state == SourceState.CACHED) ", hors ligne" else ""})" else null
            }.joinToString(" · ")
            val oldest = artist.sources.mapNotNull { fetchedAt[it.sourceId]?.takeIf { t -> t > 0 } }.minOrNull() ?: 0L
            return UniverseState(merged, reports.values.toList(), done, credit, oldest)
        }

        // ── 2. décision par source ───────────────────────────────────────────────
        val decisions = artist.sources.associate { it.sourceId to StorePolicy.decide(stored[it.sourceId], start, force) }
        val toRun = artist.sources.filter { decisions[it.sourceId] != StoreDecision.FRESH }
        Diag.info(
            "magasin",
            "sources fraîches (sans réseau) : ${artist.sources.filter { decisions[it.sourceId] == StoreDecision.FRESH }.joinToString { it.sourceId }.ifEmpty { "aucune" }} ; " +
                "à actualiser : ${toRun.joinToString { "${it.sourceId} (${decisions[it.sourceId]})" }.ifEmpty { "aucune" }}${if (force) " ; actualisation demandée" else ""}",
            artistId = artist.id,
        )
        // l'univers enregistré s'affiche tout de suite ; « terminé » seulement s'il n'y a rien à chercher
        if (stored.isNotEmpty() || toRun.isEmpty()) onUpdate(snapshot(toRun.isEmpty()))

        // ── 3. recherche, validation, enregistrement ─────────────────────────────
        suspend fun runAndStore(spec: SourceSpec, done: Boolean) {
            val outcome = runSource(artist, spec, query, stored[spec.sourceId])
            val now = clock()
            reports[spec.sourceId] = outcome.report
            if (outcome.report.state.connected) { connected[spec.sourceId] = outcome.artworks; fetchedAt[spec.sourceId] = outcome.fetchedAt }
            else { connected.remove(spec.sourceId); fetchedAt.remove(spec.sourceId) }
            stored[spec.sourceId] = StoredSource(
                outcome.report.name, outcome.report.state, outcome.report.detail, outcome.report.count,
                if (outcome.report.state.connected) outcome.fetchedAt else 0L, now, outcome.artworks, outcome.report.probes,
            )
            val copy = StoredUniverse(UniverseStore.PARSER_VERSION, stored.toMap())
            withContext(Dispatchers.IO) { UniverseStore.write(cacheDir, artist.id, copy) }
            onUpdate(snapshot(done))
        }

        toRun.map { spec -> async { runAndStore(spec, false) } }.awaitAll()
        if (toRun.isNotEmpty()) onUpdate(snapshot(true))     // (sinon, déjà publié « terminé » depuis le magasin)

        // sources bloquées temporairement : UN nouvel essai automatique après une pause, sans gêner l'affichage (déjà publié ci-dessus)
        val limited = artist.sources.filter { reports[it.sourceId]?.state == SourceState.LIMITED }
        if (limited.isNotEmpty() && limitedRetryDelayMs >= 0) {
            Diag.info("source", "nouvel essai automatique dans ${limitedRetryDelayMs / 1000} s des sources bloquées temporairement : ${limited.joinToString { it.sourceId }}", artistId = artist.id)
            delay(limitedRetryDelayMs)
            limited.map { spec -> async { runAndStore(spec, true) } }.awaitAll()
        }
        snapshot(true)
    }

    private suspend fun runSource(artist: Artist, spec: SourceSpec, query: ArtworkQuery, previous: StoredSource?): Outcome {
        val source = sources[spec.sourceId]
        if (source == null) {
            Diag.warn("source", "source non implémentée (clé d'API ou service à venir)", sourceId = spec.sourceId, artistId = artist.id)
            return Outcome(SourceReport(spec.sourceId, spec.sourceId, SourceState.UNAVAILABLE, 0, "source non implémentée (clé d'API ou service à venir)"), emptyList(), 0L)
        }
        // copie d'une connexion validée passée (pour les replis) : seulement si elle contient des œuvres
        val old = previous?.takeIf { it.state.connected && it.artworks.isNotEmpty() }

        val fetched = try {
            fetchWithFallback(artist, source, spec, query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiRetiredException) {
            return retired(artist, source, old, e)
        } catch (e: Exception) {
            if (TemporaryBlock.matches(e.message)) return limited(artist, source, old, e)
            return fromCache(artist, source, old, e)
        }
        if (source.reconnaissanceOnly) {
            return Outcome(SourceReport(source.id, source.name, SourceState.UNAVAILABLE, 0, "source de reconnaissance : aucune œuvre ajoutée, la forme des réponses est consignée dans le journal"), emptyList(), 0L)
        }
        if (fetched.isEmpty()) {
            Diag.warn("source", "aucune œuvre exploitable pour cet artiste", sourceId = source.id, artistId = artist.id)
            return Outcome(SourceReport(source.id, source.name, SourceState.EMPTY, 0, "aucune œuvre exploitable pour cet artiste"), emptyList(), 0L)
        }

        val verdict = validator.decide(fetched)
        val ok = verdict.ok
        val probes = verdict.lines
        // chaque échantillon en échec est consigné, MÊME si la source est finalement connectée
        for (p in probes.filter { !it.ok }) {
            Diag.warn("validation", "échantillon « ${p.artworkTitle} » : ${p.detail}", p.url, source.id, artist.id)
        }
        if (verdict.inconclusive) {
            val why = probes.firstOrNull { !it.ok }?.detail ?: "réseau indisponible"
            val cached = old?.artworks.orEmpty()
            if (cached.isNotEmpty()) {
                Diag.warn("validation", "accès aux images non vérifiable (réseau : $why) → copie hors ligne utilisée (${cached.size} œuvres)", sourceId = source.id, artistId = artist.id)
                return Outcome(SourceReport(source.id, source.name, SourceState.CACHED, cached.size, "accès non vérifiable (réseau) : copie hors ligne ($why)", probes), cached, old?.fetchedAt ?: 0L)
            }
            Diag.warn("validation", "accès aux images non vérifiable (échantillons tous en échec de réseau, ce n'est pas un refus) : $why", sourceId = source.id, artistId = artist.id)
            return Outcome(SourceReport(source.id, source.name, SourceState.LIMITED, fetched.size, "accès non vérifiable pour l'instant (réseau ou limite de débit), nouvel essai automatique : $why", probes), emptyList(), 0L)
        }
        if (!ok) {
            val why = probes.firstOrNull { !it.ok }?.detail ?: "accès impossible"
            Diag.error("validation", "source REFUSÉE (${probes.count { !it.ok }}/${probes.size} échantillons en échec) : $why", sourceId = source.id, artistId = artist.id)
            return Outcome(SourceReport(source.id, source.name, SourceState.REJECTED, fetched.size, "accès aux images refusé : $why", probes), emptyList(), 0L)
        }
        val detail = "${fetched.size} œuvres · accès vérifié (${probes.count { it.ok }}/${probes.size})"
        Diag.info("source", "connectée : $detail", sourceId = source.id, artistId = artist.id)
        return Outcome(SourceReport(source.id, source.name, SourceState.CONNECTED, fetched.size, detail, probes), fetched, clock())
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
    private fun retired(artist: Artist, source: MuseumSource, old: StoredSource?, error: ApiRetiredException): Outcome {
        Diag.error("source", "API RETIRÉE par le service : ${error.message}", sourceId = source.id, artistId = artist.id)
        val cached = old?.artworks.orEmpty()
        return if (cached.isNotEmpty()) {
            Outcome(SourceReport(source.id, source.name, SourceState.CACHED, cached.size, "API retirée : copie hors ligne d'une connexion passée (${error.message?.take(160)})"), cached, old?.fetchedAt ?: 0L)
        } else {
            Outcome(SourceReport(source.id, source.name, SourceState.UNAVAILABLE, 0, "API retirée par le service : ${error.message}"), emptyList(), 0L)
        }
    }

    /** Blocage temporaire : la copie d'une connexion passée si elle existe, sinon la source est LIMITÉE (réessayée plus tard). */
    private fun limited(artist: Artist, source: MuseumSource, old: StoredSource?, error: Exception): Outcome {
        val why = error.message ?: error.javaClass.simpleName
        val cached = old?.artworks.orEmpty()
        return if (cached.isNotEmpty()) {
            Diag.warn("source", "bloquée temporairement → copie hors ligne utilisée (${cached.size} œuvres) : $why", sourceId = source.id, artistId = artist.id)
            Outcome(SourceReport(source.id, source.name, SourceState.CACHED, cached.size, "bloquée temporairement : copie hors ligne ($why)"), cached, old?.fetchedAt ?: 0L)
        } else {
            Diag.error("source", "BLOQUÉE TEMPORAIREMENT (pare-feu anti-robot ou limite de débit), aucune copie : $why", sourceId = source.id, artistId = artist.id)
            Outcome(SourceReport(source.id, source.name, SourceState.LIMITED, 0, "bloquée temporairement, nouvel essai automatique : $why"), emptyList(), 0L)
        }
    }

    private fun fromCache(artist: Artist, source: MuseumSource, old: StoredSource?, error: Exception): Outcome {
        val cached = old?.artworks.orEmpty()
        val why = error.message ?: error.javaClass.simpleName
        return if (cached.isNotEmpty()) {
            Diag.warn("source", "connexion impossible → copie hors ligne utilisée (${cached.size} œuvres) : $why", sourceId = source.id, artistId = artist.id)
            Outcome(SourceReport(source.id, source.name, SourceState.CACHED, cached.size, "copie hors ligne ($why)"), cached, old?.fetchedAt ?: 0L)
        } else {
            Diag.error("source", "injoignable, aucune copie hors ligne : $why", sourceId = source.id, artistId = artist.id)
            Outcome(SourceReport(source.id, source.name, SourceState.UNREACHABLE, 0, why), emptyList(), 0L)
        }
    }
}
