package com.iiifviewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt

private val Backdrop = Color(0xFF101010)

/**
 * Visionneuse IIIF Deep Zoom native.
 *
 * @param manifestUrl URL de l'`info.json` IIIF Image API 3.0.
 * @param initialFocus point d'intérêt de départ, en PIXELS IMAGE (ex. le centre d'un œil).
 * @param initialZoom zoom de départ, en multiple du zoom « image entière visible » (4f = ×4).
 */
@Composable
fun IiifZoomViewer(
    manifestUrl: String,
    initialFocus: Offset,
    initialZoom: Float,
    modifier: Modifier = Modifier,
    sources: IiifSources = remember { defaultIiifSources() },
    onError: (Throwable) -> Unit = {},
) {
    var info by remember(manifestUrl) { mutableStateOf<IiifImageInfo?>(null) }
    LaunchedEffect(manifestUrl) {
        try {
            info = sources.loadInfo(manifestUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        }
    }

    val loaded = info
    if (loaded == null) {
        Box(modifier.fillMaxSize().background(Backdrop)) // en attente de l'info.json
    } else {
        ZoomSurface(loaded, initialFocus, initialZoom, sources, modifier)
    }
}

@Composable
private fun ZoomSurface(
    info: IiifImageInfo,
    initialFocus: Offset,
    initialZoom: Float,
    sources: IiifSources,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope() // Main : convient au TileManager (état mono-thread)
    val controller = remember(info) { ViewportController(info.width, info.height, scope) }
    val manager = remember(info) {
        TileManager(info, sources, scope, controller.viewport, controller.screenSize, Dispatchers.Default)
    }
    DisposableEffect(manager) { onDispose { manager.close() } }

    // Ces State ne sont LUS que dans le bloc de dessin : un pan/zoom n'invalide que la phase draw,
    // aucune recomposition.
    val viewportState = controller.viewport.collectAsState()
    val tilesState = manager.loadedTiles.collectAsState()

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { controller.onScreenSized(it.width, it.height, initialFocus, initialZoom) }
            // Clé = controller : si l'image change, les détecteurs sont relancés sur le bon contrôleur.
            .pointerInput(controller) {
                detectTapGestures(onDoubleTap = { controller.doubleTapZoom(it) })
            }
            .pointerInput(controller) {
                detectViewportGestures(
                    onStart = controller::stopAnimation, // un doigt posé arrête l'inertie
                    onGesture = controller::transformBy,  // la rotation est ignorée
                    onFling = controller::fling,
                )
            },
    ) {
        drawTiles(viewportState.value, tilesState.value)
    }
}

/**
 * Painter's algorithm. `tiles` arrive déjà trié grossier → fin : les tuiles floues déjà en cache
 * forment le fond, les tuiles nettes les recouvrent au fur et à mesure de leur arrivée.
 * Aucune allocation : boucle indexée, IntOffset/IntSize sont des value classes.
 */
private fun DrawScope.drawTiles(vp: ViewportState, tiles: List<LoadedTile>) {
    drawRect(Backdrop)
    val screenW = size.width.toInt()
    val screenH = size.height.toInt()
    for (i in tiles.indices) {
        val (tile, bitmap) = tiles[i]
        val r = tile.region
        // Projection image → écran :  screen = image · s + t. Les DEUX bords sont arrondis
        // séparément et la taille en est déduite : deux tuiles voisines partagent exactement
        // le même bord, donc aucun liseré, quel que soit le zoom fractionnaire.
        val left = (r.x * vp.scale + vp.translationX).roundToInt()
        val top = (r.y * vp.scale + vp.translationY).roundToInt()
        val right = ((r.x + r.width) * vp.scale + vp.translationX).roundToInt()
        val bottom = ((r.y + r.height) * vp.scale + vp.translationY).roundToInt()
        if (right <= 0 || bottom <= 0 || left >= screenW || top >= screenH) continue // hors écran
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height), // le bitmap = exactement la tuile
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(right - left, bottom - top), // étire une tuile grossière sur sa zone
            filterQuality = FilterQuality.Low,             // bilinéaire : flou lisse, pas de pixels carrés
        )
    }
}
