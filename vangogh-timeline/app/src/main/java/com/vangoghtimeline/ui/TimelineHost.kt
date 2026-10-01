package com.vangoghtimeline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import coil.imageLoader
import com.iiifviewer.IiifPrewarm
import com.iiifviewer.IiifZoomViewer
import com.iiifviewer.LoadErrorListener
import com.vangoghtimeline.iiif.Diag
import com.iiifviewer.defaultIiifSources
import com.iiifviewer.rememberIiifZoomController
import com.vangoghtimeline.model.Artwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val OPEN_MS = 450
private const val CLOSE_MS = 320
private const val FADE_MS = 250
private const val VIEWER_READY_TIMEOUT_MS = 3000L

/**
 * La frise + l'ouverture d'une œuvre dans le visualiseur IIIF, par une transition « la vignette devient la page » :
 *
 * 1. **Toucher simple** sur une carte : on relève où elle est à l'écran ([OpenRequest.bounds]).
 * 2. **Ouverture** : un habillage (la même image que la carte) part de ce rectangle et grandit jusqu'à remplir l'écran, coins
 *    arrondis → droits, fond qui s'assombrit. Tout est animé dans les phases de mise en page et de dessin : aucune recomposition.
 * 3. **Dès le toucher** : [IiifZoomViewer] est monté, invisible, sous la vignette, avec l'instance de préchauffage de l'œuvre
 *    ([TimelinePrefetcher.acquire]) : si la carte était au sommet du rouleau, `info.json` et tuiles de la vue d'arrivée sont déjà là ;
 *    sinon ils se chargent pendant l'animation. Le visualiseur démarre à « image entière » = ce que montre l'habillage.
 * 4. **Fin de l'animation** : le visualiseur passe AU-DESSUS de la vignette, à fond transparent : les tuiles se posent sur l'image
 *    d'arrivée à mesure qu'elles arrivent, sans trou ni coupure ; dès les premières, la vignette s'efface dessous.
 * 5. **Retour** (dézoomer encore une fois à l'image entière, ou geste système ; pas de bouton) : le visualiseur DÉZOOME d'abord jusqu'à l'image entière (`animateToFit`), puis est retiré
 *    et l'habillage — identique à cette vue — se rétrécit jusqu'à la carte.
 *
 * Pourquoi pas `SharedTransitionLayout` : il n'existe qu'à partir de Compose 1.7 ; ce projet est sur 1.6 (Kotlin 1.9). Le principe
 * est le même (transformation de conteneur) mais écrit à la main, sans API expérimentale.
 */
@Composable
fun TimelineHost(
    artworks: List<Artwork>,
    modifier: Modifier = Modifier,
    credit: String? = null,
) {
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf<OpenRequest?>(null) }
    var viewerShown by remember { mutableStateOf(false) }   // le visualiseur est monté (et charge) dès le toucher…
    var viewerTop by remember { mutableStateOf(false) }     // …mais ne passe au-dessus de la vignette qu'à la fin de l'animation
    var prewarm by remember { mutableStateOf<IiifPrewarm?>(null) }
    var viewerReady by remember { mutableStateOf(false) }
    var viewerError by remember { mutableStateOf<String?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val expand = remember { Animatable(0f) }         // 0 = à la taille de la carte, 1 = plein écran
    val overlayAlpha = remember { Animatable(1f) }
    var running by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val zoomController = rememberIiifZoomController()

    val context = LocalContext.current
    val sources = remember { defaultIiifSources() }
    val currentRoot by rememberUpdatedState(rootSize)
    val prefetcher = remember { TimelinePrefetcher(context, context.imageLoader, sources, scope) { currentRoot } }
    DisposableEffect(prefetcher) { onDispose { prefetcher.close() } }

    // Un message bref (ex. œuvre sans image) : il s'efface seul.
    LaunchedEffect(hint) { if (hint != null) { delay(2200); hint = null } }

    fun open(req: OpenRequest) {
        if (request != null) return
        if (!req.artwork.iiif.canOpenViewer) {
            hint = "Pas d'image IIIF pour cette œuvre"
            return
        }
        request = req
        viewerReady = false; viewerError = null; viewerTop = false
        prewarm = prefetcher.acquire(req.artwork)     // déjà chaud si la carte était au sommet du rouleau ; sinon chauffé maintenant
        viewerShown = true                            // le visualiseur charge pendant l'animation, caché sous la vignette
        running = scope.launch {
            overlayAlpha.snapTo(1f)
            expand.snapTo(0f)
            expand.animateTo(1f, tween(OPEN_MS, easing = FastOutSlowInEasing))
            viewerTop = true                          // fin de l'animation : les tuiles se posent PAR-DESSUS la vignette
            withTimeoutOrNull(VIEWER_READY_TIMEOUT_MS) { snapshotFlow { viewerReady || viewerError != null }.first { it } }
            overlayAlpha.animateTo(0f, tween(FADE_MS))
        }
    }

    fun close() {
        if (closing) return
        running?.cancel()
        running = scope.launch {
            closing = true
            if (viewerShown) {
                // Le retour part TOUJOURS d'une vue au zoom minimal : on dézoome d'abord, sans quoi l'image qui rétrécit vers la
                // carte ne ressemblerait pas à ce qu'on vient de quitter (vue zoomée sur un détail).
                zoomController.animateToFit()
            }
            overlayAlpha.snapTo(1f)                   // l'habillage reprend la place du visualiseur, à l'identique (image entière)
            viewerTop = false
            viewerShown = false
            expand.animateTo(0f, tween(CLOSE_MS, easing = FastOutSlowInEasing))
            request?.let { prefetcher.viewerClosed(it.artwork) }
            prewarm = null
            request = null
            closing = false
        }
    }

    BackHandler(enabled = request != null && !closing) { close() }

    Box(modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
        TimelineScreen(artworks, onArtworkTap = ::open, prefetcher = prefetcher)

        credit?.let {
            BasicText(
                it,
                Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.58f).padding(10.dp),
                style = TextStyle(color = Color(0xFF8B96A3), fontSize = 11.sp, textAlign = TextAlign.End),
            )
        }
        hint?.let {
            BasicText(
                it,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                    .background(Color(0xCC1E2228), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                style = TextStyle(color = Color.White, fontSize = 13.sp),
            )
        }

        val req = request
        if (req != null) {
            if (viewerShown) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (viewerTop) 2f else 0f)                       // au-dessus de la vignette seulement à la fin de l'animation
                        .graphicsLayer { alpha = if (viewerTop) 1f else 0f },   // avant : invisible (et sous l'habillage qui absorbe les doigts)
                ) {
                    IiifZoomViewer(
                        manifestUrl = req.artwork.iiif.viewerUrl.orEmpty(),
                        sources = sources,
                        prewarm = prewarm,
                        transparentUntilReady = true,    // la vignette reste visible tant que les tuiles n'ont pas pris sa place
                        initialFocus = Offset.Unspecified,
                        initialZoom = 1f,                 // image entière : prolonge la vignette qui vient de remplir l'écran
                        controller = zoomController,
                        onReady = { viewerReady = true },
                        onUnzoomPastFit = { if (viewerTop && !closing) close() },   // dézoomer encore, déjà à l'image entière = revenir
                        onError = {
                            val msg = it.message ?: it.javaClass.simpleName
                            viewerError = msg
                            Diag.error("visionneuse", "ouverture impossible de « ${req.artwork.title} » (${req.artwork.provider}) : $msg", req.artwork.iiif.viewerUrl)
                        },
                        onLoadError = LoadErrorListener { url, attempt, error, last ->
                            val msg = error.message ?: error.javaClass.simpleName
                            Diag.warn("tuile", "essai $attempt${if (last) " (abandon)" else ""} : $msg", url, key = "tuile|${Diag.hostOf(url)}|${msg.take(60)}")
                        },
                    )
                    viewerError?.let { message ->
                        BasicText(
                            "Impossible d'ouvrir cette œuvre : $message",
                            Modifier.align(Alignment.Center).padding(24.dp),
                            style = TextStyle(color = Color(0xFFE6B8B0), fontSize = 14.sp),
                        )
                    }
                    // pendant le dézoom de sortie, les doigts sont absorbés : on ne relance pas un zoom en plein retour
                    if (closing) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                    })
                }
            }
            // L'habillage reste tant qu'il est visible : pendant l'ouverture, la fermeture, et jusqu'aux premières tuiles.
            if (!viewerTop || overlayAlpha.value > 0.001f) {
                ExpandingCard(req, expand, overlayAlpha, rootSize)
            }
        }
    }
}

/** La vignette qui grandit : rectangle interpolé entre la carte et l'écran, lu dans les phases de mise en page et de dessin. */
@Composable
private fun ExpandingCard(
    request: OpenRequest,
    progress: Animatable<Float, *>,
    alpha: Animatable<Float, *>,
    root: IntSize,
) {
    val density = LocalDensity.current
    val full = Rect(0f, 0f, root.width.toFloat(), root.height.toFloat())
    val cornerPx = with(density) { 8.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .drawBehind { drawRect(Color.Black, alpha = progress.value * 0.94f) }
            // pendant la transition, les doigts n'atteignent pas la frise dessous : on les absorbe
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            },
    ) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val r = lerp(request.bounds, full, progress.value)
                    val w = r.width.roundToInt().coerceAtLeast(1)
                    val h = r.height.roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
                }
                .graphicsLayer {
                    clip = true
                    shape = RoundedCornerShape(cornerPx * (1f - progress.value))
                },
        ) {
            ArtworkImage(
                artwork = request.artwork,
                widthPx = (if (root.width > 0) root.width else request.cardWidthPx),
                heightPx = (if (root.height > 0) root.height else request.cardHeightPx),
                placeholderKey = thumbKey(request.artwork, request.cardWidthPx, request.cardHeightPx),
            )
        }
    }
}
