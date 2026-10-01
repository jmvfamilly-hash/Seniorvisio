package com.iiifviewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.time.TimeSource

/**
 * Tuile prête à dessiner. Dans la liste publiée, l'ordre EST l'ordre de peinture (grossier → fin).
 *
 * On ne publie pas de rectangle écran précalculé : il serait périmé dès le geste suivant (le
 * viewport change à chaque frame, la liste seulement quand des tuiles arrivent/partent). Le Canvas
 * projette donc [tile].region avec le viewport COURANT — quatre multiplications, sans allocation.
 */
data class LoadedTile(val tile: Tile, val bitmap: ImageBitmap)

/**
 * Pont entre viewport et réseau, avec navigation prédictive et gestion de la mémoire.
 *
 * - **Prédiction** : à chaque viewport, [PrefetchPlanner] dit quelles tuiles vont servir (écran, marge de pan prolongée dans le
 *   sens du mouvement, niveau plus fin autour du point de zoom, niveaux grossiers pour le dézoom). Au repos (250 ms), le plan
 *   couvre pan, zoom et dézoom à la fois.
 * - **Priorités** : les tuiles visibles passent avant tout ; le préchargement n'occupe que 2 voies tant qu'une tuile visible
 *   attend, et une tuile déjà demandée est remontée en priorité si elle devient visible.
 * - **Mémoire** : plafond en octets. Après 400 ms de calme, les tuiles éloignées du point de vue ou plus fines que nécessaire
 *   sont relâchées ; sous pression, on évince d'abord ce qui est loin et mal adapté au zoom, jamais ce qui est voulu.
 *
 * @param scope DOIT être mono-thread (Main / `rememberCoroutineScope`) : toutes les structures mutables ci-dessous n'y sont
 *   touchées que depuis ce thread. Seul le téléchargement + décodage part sur [ioDispatcher] (passer `Dispatchers.IO` sur
 *   JVM/Android : il n'existe pas en commonMain).
 * @param zoomAnchor dernier point de zoom (écran), fourni par le contrôleur de viewport ; [Offset.Unspecified] = centre.
 */
class TileManager(
    private val info: IiifImageInfo,
    private val source: TileImageSource,
    scope: CoroutineScope,
    viewport: StateFlow<ViewportState>,
    screenSize: StateFlow<ScreenSize>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxCacheBytes: Long = 96L * 1024 * 1024,
    private val maxParallelDownloads: Int = 6,
    private val zoomAnchor: () -> Offset = { Offset.Unspecified },
    /** Tuiles déjà préchargées par ailleurs (voir [IiifPrewarm]) : reprises d'emblée, et au fil de leur arrivée. */
    private val warm: StateFlow<List<LoadedTile>>? = null,
    /** Chaque essai de tuile en échec, y compris ceux qu'un nouvel essai rattrape (voir [LoadErrorListener]). */
    private val onLoadError: LoadErrorListener? = null,
) {
    // Scope enfant : close() annule tout sans toucher au scope du parent.
    private val managerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private class Cached(val bitmap: ImageBitmap, val bytes: Long, val tile: Tile, var lastUsedMs: Long)

    private class TileJob(val tile: Tile, var priority: Int, var distSq: Float) {
        var job: Job? = null
        var started = false
        var prefetch = false
    }

    /** Ordre d'insertion = ancienneté d'usage (on réinsère à chaque lecture). */
    private val store = LinkedHashMap<String, Cached>()
    private var bytes = 0L

    /** Niveau le plus grossier : quelques tuiles gardées en permanence (jamais évincées, jamais annulées). */
    private val pinned = HashMap<String, ImageBitmap>()
    private val baseFactor = info.scaleFactors.last()

    private val jobs = HashMap<String, TileJob>()
    private val queue = ArrayList<TileJob>()
    private var running = 0
    private var runningPrefetch = 0
    private val failed = HashSet<String>()
    private var wanted: Set<String> = emptySet()

    private val _loadedTiles = MutableStateFlow<List<LoadedTile>>(emptyList())
    val loadedTiles: StateFlow<List<LoadedTile>> = _loadedTiles.asStateFlow()

    private var lastViewport = ViewportState()
    private var lastScreen = ScreenSize(0, 0)
    private val clock = TimeSource.Monotonic.markNow()
    private fun nowMs() = clock.elapsedNow().inWholeMilliseconds

    // Mouvement lissé : pan (px/s), zoom (d ln(échelle)/s)
    private var vx = 0f
    private var vy = 0f
    private var vz = 0f
    private var prev: ViewportState? = null
    private var prevMs = 0L

    init {
        warm?.value?.forEach(::adopt) // reprises AVANT toute requête : elles évitent un téléchargement
        // Filet anti-écran-noir : le niveau le plus grossier (souvent 1 tuile) est chargé d'emblée.
        TileCalculator.allTiles(info, baseFactor).forEach { request(it, 0, 0f) }

        warm?.let { feed ->
            // Le préchauffage peut ne pas être fini à l'ouverture : ses tuiles suivantes sont adoptées à leur arrivée
            // (et la requête que le gestionnaire avait lancée entre-temps pour la même tuile est abandonnée).
            managerScope.launch {
                feed.collect { list ->
                    var added = false
                    for (lt in list) if (!isLoaded(lt.tile.id)) { adopt(lt); added = true }
                    if (added) publish()
                }
            }
        }

        managerScope.launch {
            // StateFlow conflate ; collectLatest annule l'attente au moindre nouveau mouvement : les étapes « au repos » ne
            // s'exécutent donc qu'après un vrai moment calme.
            combine(viewport, screenSize) { vp, size -> vp to size }.collectLatest { (vp, size) ->
                trackMotion(vp)
                onViewportChanged(vp, size, currentMotion())
                delay(IDLE_DELAY_MS)
                vx = 0f; vy = 0f; vz = 0f; prev = null                   // repos : préparer pan, zoom et dézoom à la fois
                onViewportChanged(vp, size, ViewMotion(anchorX = Float.NaN, anchorY = Float.NaN))
                delay(SWEEP_DELAY_MS - IDLE_DELAY_MS)
                sweep(aggressive = false)
            }
        }
    }

    /** App en arrière-plan / mémoire rare : ne garde que ce qui est voulu tout de suite. */
    fun trimMemory() = sweep(aggressive = true)

    fun close() {
        managerScope.cancel()
        jobs.clear(); queue.clear()
        store.clear(); bytes = 0
        pinned.clear()
    }

    // ── Mouvement ────────────────────────────────────────────────────────────────
    private fun trackMotion(vp: ViewportState) {
        val now = nowMs()
        val p = prev
        if (p != null) {
            val dt = (now - prevMs) / 1000f
            if (dt > 0.001f) {
                val k = if (dt > 0.25f) 1f else 0.35f                     // un long silence repart de zéro
                vx += k * ((vp.translationX - p.translationX) / dt - vx)
                vy += k * ((vp.translationY - p.translationY) / dt - vy)
                vz += k * (ln(vp.scale / p.scale) / dt - vz)
            }
        }
        prev = vp
        prevMs = now
    }

    private fun currentMotion(): ViewMotion {
        val a = zoomAnchor()
        val known = a != Offset.Unspecified
        return ViewMotion(vx, vy, vz, if (known) a.x else Float.NaN, if (known) a.y else Float.NaN)
    }

    // ── Plan → requêtes ──────────────────────────────────────────────────────────
    private fun onViewportChanged(vp: ViewportState, size: ScreenSize, motion: ViewMotion) {
        lastViewport = vp
        lastScreen = size
        if (size.width <= 0 || size.height <= 0) return

        val plan = PrefetchPlanner.plan(vp, info, size.width, size.height, motion)
        wanted = plan.mapTo(HashSet(plan.size * 2)) { it.tile.id }

        // ── Annulation agressive ─────────────────────────────────────────────
        // Toute requête dont la tuile n'est plus voulue est coupée tout de suite
        // (le TileImageSource doit couper la connexion sur annulation).
        // Liste d'abord, cancel ensuite : ne jamais modifier la map pendant qu'on la parcourt.
        val stale = jobs.values.filter { it.tile.scaleFactor != baseFactor && it.tile.id !in wanted }
        stale.forEach { cancel(it) }
        failed.retainAll(wanted) // une tuile qui sort puis rentre aura droit à un nouvel essai

        // ── Nouvelles requêtes, les plus urgentes d'abord ────────────────────
        // Le préchargement ne dépasse jamais 90 % du budget mémoire : charger pour évincer aussitôt ne ferait que
        // gaspiller le réseau.
        val tileBytes = info.tileSize.toLong() * info.tileSize * 4
        var room = ((maxCacheBytes * 0.9 - bytes) / tileBytes).toInt() - jobs.values.count { it.priority >= 2 }
        for (p in plan.sortedWith(compareBy<PlannedTile>({ it.priority }, { it.distSq }))) {
            val id = p.tile.id
            if (p.priority >= 2 && !isLoaded(id) && id !in jobs) {
                if (room <= 0) continue
                room--
            }
            request(p.tile, p.priority, p.distSq)
        }
        publish()
    }

    private fun adopt(lt: LoadedTile) {
        jobs[lt.tile.id]?.let { cancel(it) }
        failed -= lt.tile.id
        put(lt.tile, lt.bitmap)
    }

    private fun isLoaded(id: String) = pinned.containsKey(id) || store.containsKey(id)

    private fun image(id: String): ImageBitmap? {
        pinned[id]?.let { return it }
        val e = store.remove(id) ?: return null
        e.lastUsedMs = nowMs()
        store[id] = e // réinsérée en fin = la plus récente
        return e.bitmap
    }

    private fun request(tile: Tile, priority: Int, distSq: Float) {
        val id = tile.id
        if (id in failed || isLoaded(id)) return
        val current = jobs[id]
        if (current != null) { // déjà demandée : on la remonte si elle devient plus urgente
            if (priority < current.priority) current.priority = priority
            current.distSq = distSq
            return
        }
        val job = TileJob(tile, priority, distSq)
        jobs[id] = job
        queue += job
        pump()
    }

    /** Le plus urgent d'abord. Le préchargement ne prend que 2 voies tant qu'une tuile visible attend. */
    private fun pump() {
        while (running < maxParallelDownloads) {
            val urgent = queue.any { it.priority < 2 } || running - runningPrefetch > 0
            val slots = if (urgent) 2 else maxParallelDownloads
            var best: TileJob? = null
            for (j in queue) {
                if (j.priority >= 2 && runningPrefetch >= slots) continue
                val b = best
                if (b == null || j.priority < b.priority || (j.priority == b.priority && j.distSq < b.distSq)) best = j
            }
            start(best ?: return)
        }
    }

    private fun start(tj: TileJob) {
        queue.remove(tj)
        tj.started = true
        tj.prefetch = tj.priority >= 2
        running++
        if (tj.prefetch) runningPrefetch++
        val tile = tj.tile
        tj.job = managerScope.launch {
            try {
                val bitmap = fetchWithRetry(IiifUrls.tile(info, tile))
                put(tile, bitmap)
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed += tile.id
            } finally {
                running--
                if (tj.prefetch) runningPrefetch--
                if (jobs[tile.id] === tj) jobs.remove(tile.id)
                pump()
            }
        }
    }

    private fun cancel(tj: TileJob) {
        if (jobs[tj.tile.id] === tj) jobs.remove(tj.tile.id)
        if (tj.started) tj.job?.cancel() // la voie est rendue dans le finally du job
        else queue.remove(tj)            // pas encore démarrée : rien à décompter
    }

    private suspend fun fetchWithRetry(url: String): ImageBitmap {
        var attempt = 0
        while (true) {
            try {
                return withContext(ioDispatcher) { source.load(url) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                onLoadError?.onError(url, attempt, e, attempt >= 3)
                if (attempt >= 3) throw e
                delay(500L * attempt)
            }
        }
    }

    private fun put(tile: Tile, bitmap: ImageBitmap) {
        if (tile.scaleFactor == baseFactor) { pinned[tile.id] = bitmap; return }
        store.remove(tile.id)?.let { bytes -= it.bytes }
        val size = bitmap.width.toLong() * bitmap.height * 4
        store[tile.id] = Cached(bitmap, size, tile, nowMs())
        bytes += size
        if (bytes > maxCacheBytes) enforceBudget()
    }

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
                val bitmap = image(tile.id) ?: continue
                out += LoadedTile(tile, bitmap)
            }
        }
        _loadedTiles.value = out
    }

    // ── Libération de la mémoire ─────────────────────────────────────────────────
    /**
     * Après un moment calme : relâche les tuiles éloignées du point de vue (hors de 0,8 écran autour), ou devenues inutiles
     * (plus fines que nécessaire, hors zone de zoom préparée). [aggressive] : tout ce qui n'est pas voulu.
     *
     * Sur Android les Bitmap n'ont pas de « close » en commun : on retire les références, le ramasse-miettes fait le reste.
     */
    private fun sweep(aggressive: Boolean) {
        val (w, h) = lastScreen
        if (w <= 0 || h <= 0) return
        val vp = lastViewport
        val ideal = TileCalculator.idealScaleFactor(vp.scale, info)
        val now = nowMs()
        val l = (-0.8f * w - vp.translationX) / vp.scale
        val t = (-0.8f * h - vp.translationY) / vp.scale
        val r = (1.8f * w - vp.translationX) / vp.scale
        val b = (1.8f * h - vp.translationY) / vp.scale
        val victims = store.values.filter { e ->
            val reg = e.tile.region
            val inZone = reg.x < r && reg.x + reg.width > l && reg.y < b && reg.y + reg.height > t
            val tooFine = e.tile.scaleFactor < ideal
            val recent = now - e.lastUsedMs < RECENT_MS
            e.tile.id !in wanted && (aggressive || !inZone || (tooFine && !recent))
        }
        victims.forEach { evict(it.tile.id) }
        if (bytes > maxCacheBytes) enforceBudget()
    }

    private fun evict(id: String) {
        store.remove(id)?.let { bytes -= it.bytes }
    }

    /** Sous pression : évince d'abord ce qui est loin et mal adapté au zoom, jamais ce qui est voulu tout de suite. */
    private fun enforceBudget() {
        val (w, h) = lastScreen
        val vp = lastViewport
        val ideal = if (vp.scale > 0f) TileCalculator.idealScaleFactor(vp.scale, info) else 1
        while (bytes > maxCacheBytes && store.isNotEmpty()) {
            var victim: String? = null
            var worst = -1f
            if (w > 0 && h > 0) {
                for ((id, e) in store) {
                    if (id in wanted) continue
                    val reg = e.tile.region
                    val x = (reg.x + reg.width / 2f) * vp.scale + vp.translationX
                    val y = (reg.y + reg.height / 2f) * vp.scale + vp.translationY
                    val distance = hypot(x - w / 2f, y - h / 2f) / max(w, h)        // en nombres d'écrans
                    val score = distance + 2f * abs(log2(e.tile.scaleFactor.toFloat() / ideal))
                    if (score > worst) { worst = score; victim = id }
                }
            }
            evict(victim ?: store.keys.first()) // tout est voulu : la plus ancienne
        }
    }

    private companion object {
        const val IDLE_DELAY_MS = 250L
        const val SWEEP_DELAY_MS = 400L
        const val RECENT_MS = 2000L
    }
}
