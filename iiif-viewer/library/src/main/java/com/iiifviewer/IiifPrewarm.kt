package com.iiifviewer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Préchauffage d'UNE image, indépendamment de tout écran : lit l'`info.json` (ou le manifeste) et charge, en asynchrone, les
 * tuiles de la vue d'arrivée (voir [PrewarmPlanner]). Un [IiifZoomViewer] ouvert ensuite avec cette instance (`prewarm = …`)
 * saute l'attente de l'`info.json` et reprend les tuiles déjà là — y compris celles qui arrivent encore après son ouverture.
 *
 * Cycle de vie, pensé pour un appelant qui sait QUAND une image est susceptible d'être ouverte (ex. la vignette est au sommet
 * du rouleau d'une frise) :
 * - [warm] commence (ou reprend) le chargement des tuiles ; sans effet s'il est déjà en cours ou terminé à cette taille d'écran ;
 * - [release] annule les téléchargements et libère les tuiles (l'`info.json`, minuscule, est gardé : le réchauffement suivant
 *   est plus rapide) ;
 * - [close] libère tout, définitivement.
 *
 * @param scope DOIT être mono-thread (Main / `rememberCoroutineScope`) : l'état mutable n'y est touché que depuis ce thread.
 */
class IiifPrewarm(
    private val url: String,
    private val sources: IiifSources,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val budgetBytes: Long = 16L * 1024 * 1024,
    private val parallel: Int = 4,
) {
    private val ownScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val _info = MutableStateFlow<IiifImageInfo?>(null)
    private val _tiles = MutableStateFlow<List<LoadedTile>>(emptyList())
    private val held = LinkedHashMap<String, LoadedTile>()
    private var infoJob: Deferred<IiifImageInfo>? = null
    private var tilesJob: Job? = null
    private var warmedFor: Pair<Int, Int>? = null
    private var closed = false

    /** `info.json` quand il est lu (jamais remis à `null` par [release]). */
    val info: StateFlow<IiifImageInfo?> = _info.asStateFlow()

    /** Tuiles actuellement en mémoire, prêtes à être reprises par un [TileManager]. Vide après [release]. */
    val tiles: StateFlow<List<LoadedTile>> = _tiles.asStateFlow()

    /** Vrai si des téléchargements sont en cours ou si des tuiles sont retenues. */
    val isWarm: Boolean get() = tilesJob != null

    /** L'`info.json` de l'image, lu une seule fois même si plusieurs appelants l'attendent (échec : l'appel suivant réessaie). */
    suspend fun awaitInfo(): IiifImageInfo {
        _info.value?.let { return it }
        check(!closed) { "IiifPrewarm fermé" }
        val job = infoJob ?: ownScope.async { sources.loadInfo(url) }.also { infoJob = it }
        try {
            return job.await().also { _info.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (infoJob === job) infoJob = null
            throw e
        }
    }

    /** Commence (ou reprend) le chargement des tuiles de la vue d'arrivée pour un écran de cette taille. */
    fun warm(screenWidth: Int, screenHeight: Int) {
        if (closed || screenWidth <= 0 || screenHeight <= 0) return
        val size = screenWidth to screenHeight
        if (tilesJob != null && warmedFor == size) return
        tilesJob?.cancel()
        warmedFor = size
        tilesJob = ownScope.launch {
            val info = try { awaitInfo() } catch (e: CancellationException) { throw e } catch (e: Throwable) { return@launch }
            val plan = PrewarmPlanner.plan(info, screenWidth, screenHeight, budgetBytes)
            val gate = Semaphore(parallel)
            // Lancées dans l'ordre du plan ; le sémaphore (équitable) garde cet ordre : grossier d'abord, puis le centre.
            coroutineScope {
                for (tile in plan) {
                    if (tile.id in held) continue
                    launch {
                        gate.withPermit {
                            val bitmap = fetch(IiifUrls.tile(info, tile)) ?: return@withPermit
                            held[tile.id] = LoadedTile(tile, bitmap)
                            _tiles.value = held.values.toList()
                        }
                    }
                }
            }
        }
    }

    /** Annule les téléchargements et rend la mémoire des tuiles. Peut être suivi d'un nouveau [warm]. */
    fun release() {
        tilesJob?.cancel()
        tilesJob = null
        warmedFor = null
        held.clear()
        _tiles.value = emptyList()
    }

    fun close() {
        if (closed) return
        release()
        closed = true
        ownScope.cancel()
    }

    /** Une tuile, un seul nouvel essai : le préchauffage est facultatif, le visualiseur redemandera ce qui manque. */
    private suspend fun fetch(tileUrl: String): androidx.compose.ui.graphics.ImageBitmap? {
        repeat(2) { attempt ->
            try {
                return withContext(ioDispatcher) { sources.load(tileUrl) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 0) delay(400)
            }
        }
        return null
    }
}
