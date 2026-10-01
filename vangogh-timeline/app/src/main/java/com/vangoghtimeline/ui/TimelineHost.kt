package com.vangoghtimeline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iiifviewer.IiifSources
import com.iiifviewer.IiifZoomViewer
import com.vangoghtimeline.demo.DemoIiifSources
import com.vangoghtimeline.model.Artwork
import kotlinx.coroutines.Job
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
 * 1. **Double-tap** sur une carte : on relève où elle est à l'écran ([OpenRequest.bounds]).
 * 2. **Ouverture** : un habillage (la même image que la carte) part de ce rectangle et grandit jusqu'à remplir l'écran, coins
 *    arrondis → droits, fond qui s'assombrit. Tout est animé dans les phases de mise en page et de dessin : aucune recomposition.
 * 3. **Fin de l'animation** : [IiifZoomViewer] est monté dessous avec l'URL de l'œuvre (`infoJsonUrl` si le manifeste l'a donnée,
 *    sinon l'URL du manifeste, que le visualiseur sait lire). Il démarre à « image entière » = ce que montre l'habillage.
 * 4. **Dès les premières tuiles** (ou après 3 s), l'habillage s'efface : on passe sans coupure de la vignette à l'image zoomable.
 * 5. **Retour** (bouton ou geste système) : le visualiseur est retiré et l'habillage se rétrécit jusqu'à la carte.
 *
 * Pourquoi pas `SharedTransitionLayout` : il n'existe qu'à partir de Compose 1.7 ; ce projet est sur 1.6 (Kotlin 1.9). Le principe
 * est le même (transformation de conteneur) mais écrit à la main, sans API expérimentale.
 */
@Composable
fun TimelineHost(
    artworks: List<Artwork>,
    modifier: Modifier = Modifier,
    sources: IiifSources = remember { DemoIiifSources() },
) {
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf<OpenRequest?>(null) }
    var viewerShown by remember { mutableStateOf(false) }
    var viewerReady by remember { mutableStateOf(false) }
    var viewerError by remember { mutableStateOf<String?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val expand = remember { Animatable(0f) }         // 0 = à la taille de la carte, 1 = plein écran
    val overlayAlpha = remember { Animatable(1f) }
    var running by remember { mutableStateOf<Job?>(null) }

    fun open(req: OpenRequest) {
        if (request != null) return
        request = req
        viewerShown = false; viewerReady = false; viewerError = null
        running = scope.launch {
            overlayAlpha.snapTo(1f)
            expand.snapTo(0f)
            expand.animateTo(1f, tween(OPEN_MS, easing = FastOutSlowInEasing))
            viewerShown = true                        // fin de l'animation : on passe l'œuvre au visualiseur
            withTimeoutOrNull(VIEWER_READY_TIMEOUT_MS) { snapshotFlow { viewerReady || viewerError != null }.first { it } }
            overlayAlpha.animateTo(0f, tween(FADE_MS))
        }
    }

    fun close() {
        running?.cancel()
        running = scope.launch {
            viewerShown = false
            overlayAlpha.snapTo(1f)
            expand.animateTo(0f, tween(CLOSE_MS, easing = FastOutSlowInEasing))
            request = null
        }
    }

    BackHandler(enabled = request != null) { close() }

    Box(modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
        TimelineScreen(artworks, onArtworkDoubleTap = ::open)

        val req = request
        if (req != null) {
            if (viewerShown) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    IiifZoomViewer(
                        manifestUrl = req.artwork.iiif.infoJsonUrl ?: req.artwork.iiif.manifestUrl,
                        initialFocus = Offset.Unspecified,
                        initialZoom = 1f,                 // image entière : prolonge la vignette qui vient de remplir l'écran
                        sources = sources,
                        onReady = { viewerReady = true },
                        onError = { viewerError = it.message ?: it.javaClass.simpleName },
                    )
                    viewerError?.let { message ->
                        BasicText(
                            "Impossible d'ouvrir cette œuvre : $message",
                            Modifier.align(Alignment.Center).padding(24.dp),
                            style = TextStyle(color = Color(0xFFE6B8B0), fontSize = 14.sp),
                        )
                    }
                    BackPill(onClick = ::close, Modifier.align(Alignment.TopStart).padding(12.dp))
                }
            }
            // L'habillage reste tant qu'il est visible : pendant l'ouverture, la fermeture, et jusqu'aux premières tuiles.
            if (!viewerShown || overlayAlpha.value > 0.001f) {
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

@Composable
private fun BackPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    BasicText(
        "‹  Retour",
        style = TextStyle(color = Color.White, fontSize = 15.sp),
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(20.dp))
            // sans indication : hors thème Material, l'indication par défaut est un voile de débogage
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
