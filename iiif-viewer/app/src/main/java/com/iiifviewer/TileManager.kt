package com.iiifviewer

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Tuile prête à dessiner. Dans la liste publiée, l'ordre EST l'ordre de peinture (grossier → fin).
 *
 * On ne publie pas de rectangle écran précalculé : il serait périmé dès le geste suivant (le
 * viewport change à chaque frame, la liste seulement quand des tuiles arrivent/partent). Le Canvas
 * projette donc [tile].region avec le viewport COURANT — quatre multiplications, sans allocation.
 */
data class LoadedTile(val tile: Tile, val bitmap: ImageBitmap)

/**
 * Pont entre viewport et réseau.
 *
 * @param scope DOIT être mono-thread (Main / `rememberCoroutineScope`) : toutes les structures
 *   mutables ci-dessous n'y sont touchées que depuis ce thread. Seul le téléchargement + décodage
 *   part sur [ioDispatcher] (passer `Dispatchers.IO` sur JVM/Android : il n'existe pas en commonMain).
 */
class TileManager(
    private val info: IiifImageInfo,
    private val source: TileImageSource,
    scope: CoroutineScope,
    viewport: StateFlow<ViewportState>,
    screenSize: StateFlow<ScreenSize>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxCachedTiles: Int = 100,
    maxParallelDownloads: Int = 6,
) {
    // Scope enfant : close() annule tout sans toucher au scope du parent.
    private val managerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val cache = LruCache<String, ImageBitmap>(maxCachedTiles)
    /** Niveau le plus grossier : quelques tuiles gardées en permanence (jamais évincées, jamais annulées). */
    private val pinned = HashMap<String, ImageBitmap>()
    private val baseFactor = info.scaleFactors.last()

    private val inFlight = HashMap<Tile, Job>()
    private val failed = HashSet<Tile>()
    private val gate = Semaphore(maxParallelDownloads)

    private val _loadedTiles = MutableStateFlow<List<LoadedTile>>(emptyList())
    val loadedTiles: StateFlow<List<LoadedTile>> = _loadedTiles.asStateFlow()

    private var lastViewport = ViewportState()
    private var lastScreen = ScreenSize(0, 0)

    init {
        // Filet anti-écran-noir : le niveau le plus grossier (souvent 1 tuile) est chargé d'emblée.
        TileCalculator.allTiles(info, baseFactor).forEach(::request)

        managerScope.launch {
            // StateFlow conflate : sous pan rapide, seul le dernier viewport est traité.
            combine(viewport, screenSize) { vp, size -> vp to size }
                .collect { (vp, size) -> onViewportChanged(vp, size) }
        }
    }

    fun close() {
        managerScope.cancel()
        inFlight.clear()
        cache.clear()
        pinned.clear()
    }

    private fun onViewportChanged(vp: ViewportState, size: ScreenSize) {
        lastViewport = vp
        lastScreen = size
        if (size.width <= 0 || size.height <= 0) return

        val ideal = TileCalculator.idealScaleFactor(vp.scale, info)
        val parentLevel = info.scaleFactors.firstOrNull { it > ideal } // niveau N-1 (plus grossier)
        val idealTiles = TileCalculator.calculateTilesAtLevel(vp, info, size.width, size.height, ideal)
        val parentTiles = parentLevel
            ?.let { TileCalculator.calculateTilesAtLevel(vp, info, size.width, size.height, it) }
            .orEmpty()

        val wanted = HashSet<Tile>(idealTiles.size + parentTiles.size).apply {
            addAll(idealTiles); addAll(parentTiles)
        }

        // ── Annulation agressive ─────────────────────────────────────────────
        // Toute requête dont la tuile n'est plus voulue est coupée tout de suite
        // (le TileImageSource doit couper la connexion sur annulation).
        // Liste d'abord, cancel ensuite : ne jamais modifier la map pendant qu'on la parcourt.
        val stale = inFlight.filterKeys { it.scaleFactor != baseFactor && it !in wanted }
        stale.forEach { (tile, job) ->
            inFlight.remove(tile)
            job.cancel()
        }
        failed.retainAll(wanted) // une tuile qui sort puis rentre aura droit à un nouvel essai

        // ── Nouvelles requêtes ───────────────────────────────────────────────
        // Le parent d'abord (4× moins de tuiles, arrive vite : rend un flou immédiatement),
        // puis le niveau idéal, du centre de l'écran vers les bords.
        parentTiles.forEach(::request)
        val cx = (size.width / 2f - vp.translationX) / vp.scale
        val cy = (size.height / 2f - vp.translationY) / vp.scale
        idealTiles
            .sortedBy { t ->
                val dx = t.region.x + t.region.width / 2f - cx
                val dy = t.region.y + t.region.height / 2f - cy
                dx * dx + dy * dy
            }
            .forEach(::request)

        publish()
    }

    private fun request(tile: Tile) {
        if (tile in inFlight || tile in failed || isLoaded(tile)) return
        // LAZY : l'entrée existe dans la map avant que le corps ne puisse s'exécuter.
        val job = managerScope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                val bitmap = fetchWithRetry(IiifUrls.tile(info, tile))
                if (tile.scaleFactor == baseFactor) pinned[tile.id] = bitmap else cache.put(tile.id, bitmap)
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed += tile
            } finally {
                if (inFlight[tile] === self) inFlight.remove(tile)
            }
        }
        inFlight[tile] = job
        job.start()
    }

    private suspend fun fetchWithRetry(url: String): ImageBitmap {
        var attempt = 0
        while (true) {
            try {
                // Le permis est libéré à l'annulation : une requête annulée en file d'attente ne consomme rien.
                return gate.withPermit { withContext(ioDispatcher) { source.load(url) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++attempt >= 3) throw e
                delay(500L * attempt)
            }
        }
    }

    private fun isLoaded(tile: Tile) = pinned.containsKey(tile.id) || cache.peek(tile.id) != null

    /**
     * Liste de peinture : du niveau le plus grossier jusqu'au niveau idéal, uniquement les tuiles
     * en mémoire qui touchent l'écran. Une tuile floue déjà en cache reste donc affichée sous
     * l'écran jusqu'à ce que la tuile nette la recouvre — jamais de trou.
     */
    private fun publish() {
        val (w, h) = lastScreen
        if (w <= 0 || h <= 0) return
        val ideal = TileCalculator.idealScaleFactor(lastViewport.scale, info)
        val out = ArrayList<LoadedTile>()
        for (level in info.scaleFactors.asReversed()) {
            if (level < ideal) continue // plus fin que nécessaire : inutile
            for (tile in TileCalculator.calculateTilesAtLevel(lastViewport, info, w, h, level)) {
                val bitmap = pinned[tile.id] ?: cache[tile.id] ?: continue
                out += LoadedTile(tile, bitmap)
            }
        }
        _loadedTiles.value = out
    }
}
