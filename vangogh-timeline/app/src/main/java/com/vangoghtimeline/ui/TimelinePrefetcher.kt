package com.vangoghtimeline.ui

import android.content.Context
import androidx.compose.ui.unit.IntSize
import coil.ImageLoader
import coil.memory.MemoryCache
import com.iiifviewer.IiifPrewarm
import com.iiifviewer.IiifSources
import com.iiifviewer.LoadErrorListener
import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.FocusCandidate
import com.vangoghtimeline.model.PriorityPrefetcher
import com.vangoghtimeline.model.RollerTopPolicy
import kotlinx.coroutines.CoroutineScope

/** Une vignette à précharger : même clé et même taille que celles de la carte (voir [thumbRequest]). */
internal class ThumbTarget(val artwork: Artwork, val url: String, val widthPx: Int, val heightPx: Int) {
    val key: String = thumbKey(artwork, widthPx, heightPx)
}

/**
 * Anticipation de la navigation dans la frise, à deux étages :
 *
 * 1. **Sommet du rouleau → image entière.** Une carte qui arrive au centre de l'écran (face à l'utilisateur) voit les tuiles de sa
 *    vue d'arrivée chargées en asynchrone ([IiifPrewarm]) ; elles sont libérées dès qu'elle n'est plus au sommet
 *    ([RollerTopPolicy]). Un toucher ouvre alors l'image sans attente : tuiles, `info.json` et connexion sont déjà là.
 * 2. **Prochain défilement → vignettes.** Les vignettes de ce qui apparaîtra si l'utilisateur continue (large dans le sens du
 *    mouvement) sont chargées d'avance dans le cache Coil, celles qui font face à l'utilisateur d'abord, de haut en bas
 *    ([PriorityPrefetcher]).
 *
 * Tout tourne sur le thread principal (`scope` = Main) ; réseau et décodage sont déportés par Coil et par [IiifPrewarm].
 */
class TimelinePrefetcher(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val sources: IiifSources,
    private val scope: CoroutineScope,
    private val screenSize: () -> IntSize,
) {
    private val policy = RollerTopPolicy()
    private val prewarms = HashMap<String, IiifPrewarm>()
    private val inUse = HashSet<String>()                // ouvertes dans le visualiseur : jamais libérées sous ses pieds
    private var active: Set<String> = emptySet()
    private val thumbs = PriorityPrefetcher<ThumbTarget>(scope, parallel = 3, keyOf = { it.key }) { load(it) }

    /** Ce qui est au sommet du rouleau a changé (appelé à chaque image de défilement : très bon marché sans changement). */
    fun onTop(candidates: List<FocusCandidate>, cardWidth: Float, artworkOf: (String) -> Artwork?) {
        val size = screenSize()
        if (size.width <= 0 || size.height <= 0) return
        val change = policy.update(candidates, cardWidth)
        active = change.active.toSet()
        for (id in change.released) release(id)
        for (id in change.active) {
            val art = artworkOf(id) ?: continue
            prewarmFor(art)?.warm(size.width, size.height)
        }
    }

    /** Vignettes du prochain défilement, DANS L'ORDRE de priorité. */
    internal fun onNextScroll(targets: List<ThumbTarget>) = thumbs.submit(targets)

    /**
     * L'instance de préchauffage de cette œuvre, créée au besoin et chauffée tout de suite : à appeler à l'ouverture dans le
     * visualiseur. Elle est protégée de la libération jusqu'à [viewerClosed].
     */
    fun acquire(artwork: Artwork): IiifPrewarm? {
        val prewarm = prewarmFor(artwork) ?: return null
        inUse += artwork.id
        val size = screenSize()
        prewarm.warm(size.width, size.height)
        return prewarm
    }

    /** Le visualiseur est fermé : si la carte n'est plus au sommet du rouleau, la mémoire est rendue. */
    fun viewerClosed(artwork: Artwork) {
        inUse -= artwork.id
        if (artwork.id !in active) release(artwork.id)
    }

    fun close() {
        thumbs.clear()
        prewarms.values.forEach { it.close() }
        prewarms.clear()
    }

    private fun prewarmFor(artwork: Artwork): IiifPrewarm? {
        prewarms[artwork.id]?.let { return it }
        val url = artwork.iiif.viewerUrl ?: return null
        val listener = LoadErrorListener { tileUrl, attempt, error, last ->
            val msg = error.message ?: error.javaClass.simpleName
            Diag.warn("préchauffage", "essai $attempt${if (last) " (abandon)" else ""} : $msg", tileUrl, key = "prewarm|${Diag.hostOf(tileUrl)}|${msg.take(60)}")
        }
        return IiifPrewarm(url, sources, scope, onLoadError = listener).also { prewarms[artwork.id] = it }
    }

    private fun release(id: String) {
        if (id in inUse) return
        prewarms[id]?.release()   // les tuiles partent, l'info.json (minuscule) reste : un retour au sommet repart plus vite
    }

    private suspend fun load(t: ThumbTarget) {
        if (imageLoader.memoryCache?.get(MemoryCache.Key(t.key)) != null) return   // déjà en mémoire
        imageLoader.execute(thumbRequest(context, t.artwork, t.url, t.widthPx, t.heightPx))
    }
}
