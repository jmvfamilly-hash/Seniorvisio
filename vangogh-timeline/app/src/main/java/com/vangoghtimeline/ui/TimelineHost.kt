package com.vangoghtimeline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.iiifviewer.HttpIiifSources
import com.vangoghtimeline.TimelineApp
import com.iiifviewer.rememberIiifZoomController
import com.vangoghtimeline.model.Artist
import com.vangoghtimeline.model.Artwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val OPEN_MS = 450
private const val CLOSE_MS = 320
private const val FADE_MS = 250

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
    /** Artiste dont on montre l'univers : son nom en tête de la frise, et dans la vue détaillée d'une œuvre. */
    artistName: String? = null,
    artistLife: String? = null,
    /** L'artiste (fond de la frise, niveau 3 de la vue détaillée) et le niveau de détail partagé avec le menu. */
    artist: Artist? = null,
    /** Recherche transversale : l'artiste de chaque œuvre (nom sur les cartes, fiche de l'artiste dans la vue détaillée). */
    artistFor: ((Artwork) -> Artist?)? = null,
    /** Remplace l'en-tête (ex. « Recherche : … »). */
    title: String? = null,
    level: Int = 1,
    onLevel: (Int) -> Unit = {},
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
    var still by remember { mutableStateOf<Artwork?>(null) }      // œuvre affichée sans visionneuse (image non zoomable)
    val zoomController = rememberIiifZoomController()

    val context = LocalContext.current
    val sources = remember { HttpIiifSources((context.applicationContext as? TimelineApp)?.tileCache) }
    val currentRoot by rememberUpdatedState(rootSize)
    val prefetcher = remember { TimelinePrefetcher(context, context.imageLoader, sources, scope) { currentRoot } }
    DisposableEffect(prefetcher) { onDispose { prefetcher.close() } }

    // Un message bref (ex. œuvre sans image) : il s'efface seul.
    LaunchedEffect(hint) { if (hint != null) { delay(2200); hint = null } }

    fun open(req: OpenRequest) {
        if (request != null) return
        if (!req.artwork.iiif.canOpenViewer) {
            still = req.artwork          // pas d'image zoomable : on l'affiche quand même, sans zoom
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
            // La vignette RESTE, telle quelle, tant que les premières tuiles ne sont pas à l'écran (un témoin d'attente l'accompagne,
            // voir WaitIndicator) : plus de délai après lequel elle disparaîtrait sur un écran vide. En cas d'erreur, elle reste aussi.
            snapshotFlow { viewerReady }.first { it }
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
        // parcours dans la visionneuse : (œuvres, indice de départ) ; null = pas de parcours
        var tour by remember { mutableStateOf<Pair<List<Artwork>, Int>?>(null) }
        val playTour: (List<Artwork>, Int) -> Unit = { list, start ->
            // toutes les œuvres, zoomables ou non : une image non zoomable est montrée telle quelle
            if (list.isNotEmpty()) tour = list to start.coerceIn(0, list.size - 1) else hint = "Aucune œuvre à parcourir"
        }
        // deux vues des mêmes œuvres : la frise (le temps) ou la carte (le lieu de conservation) ; on bascule par l'en-tête
        var mapMode by rememberSaveable { mutableStateOf(false) }
        val header = title ?: artistName?.let { n -> artistLife?.let { "$n · $it" } ?: n }
        val nameOf = artistFor?.let { f -> { a: Artwork -> f(a)?.name } }
        // filtres au-dessus des deux vues : les mêmes œuvres filtrées sur la frise et sur la carte
        val works = rememberWorkFilter(artworks)
        if (mapMode) MapScreen(works.artworks, onArtworkTap = ::open, title = header, artistNameOf = nameOf, viewerOpen = request != null, onPlayTour = playTour, filterBar = works.bar, headerTrailing = { ViewModeToggle(true) { mapMode = it } })
        else TimelineScreen(works.artworks, onArtworkTap = ::open, prefetcher = prefetcher, viewerOpen = request != null, onPlayTour = playTour, backdropArtistId = artist?.id, level = level, onLevel = onLevel, artistNameOf = nameOf, title = header, filterBar = works.bar, emptyText = works.emptyText, headerTrailing = { ViewModeToggle(false) { mapMode = it } })

        hint?.let {
            BasicText(
                it,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                    .background(Color(0xCC1E2228), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                style = TextStyle(color = Color.White, fontSize = 13.sp),
            )
        }

        still?.let { a ->
            StillImageView(a, artistFor?.invoke(a) ?: artist, level, onLevel, onClose = { still = null }, modifier = Modifier.fillMaxSize().zIndex(5f))
        }
        tour?.let { (list, start) ->
            TourViewer(
                list, start, sources, prefetcher,
                artistOf = { a -> artistFor?.invoke(a) ?: artist }, level = level, onLevel = onLevel,
                onExit = { tour = null },
                modifier = Modifier.fillMaxSize().zIndex(4f),
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
                    // licence et conditions de l'œuvre ouverte (un toucher sur la barre déplie le détail)
                    if (viewerTop && !closing) DetailOverlay(req.artwork, artistFor?.invoke(req.artwork) ?: artist, artistFor?.invoke(req.artwork)?.name ?: artistName, level, onLevel)
                    if (closing) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                    })
                }
            }
            // L'habillage reste tant qu'il est visible : pendant l'ouverture, la fermeture, et jusqu'aux premières tuiles.
            if (!viewerTop || overlayAlpha.value > 0.001f) {
                ExpandingCard(req, expand, overlayAlpha, rootSize)
            }
            // Témoin d'attente : le visualiseur charge encore (téléchargement de l'image, lecture des informations, premières tuiles).
            if (viewerShown && viewerTop && !viewerReady && viewerError == null && !closing) {
                WaitIndicator(Modifier.align(Alignment.Center).zIndex(3f))
            }
        }
    }
}

/** Témoin d'attente (anneau qui tourne et mot), visible seulement si l'attente dépasse 300 ms : pas de clignotement quand tout est déjà prêt. */
@Composable
private fun WaitIndicator(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(300); visible = true }
    if (!visible) return
    val transition = rememberInfiniteTransition(label = "attente")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle",
    )
    Row(
        modifier.background(Color(0xCC000000), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(20.dp)) {
            drawArc(Color.White, startAngle = angle, sweepAngle = 270f, useCenter = false, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(10.dp))
        BasicText("Chargement de l'image…", style = TextStyle(color = Color.White, fontSize = 13.sp))
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


/**
 * Le PARCOURS dans la visionneuse : chaque œuvre s'ouvre en plein écran dans la visionneuse IIIF, on peut zoomer et naviguer directement
 * dessus. Lecture automatique (une œuvre toutes les ~8 s une fois l'image chargée, fondu enchaîné) ; dès qu'on touche l'image, la lecture se met
 * en pause et on explore librement ; « ▷ Reprendre » relance. Les tuiles de l'œuvre SUIVANTE sont chargées d'avance (préchauffage) pendant
 * qu'on regarde la courante. Sortie : ✕, geste retour, ou dézoom au-delà de l'image entière.
 */
@Composable
internal fun TourViewer(
    tour: List<Artwork>, startIndex: Int, sources: com.iiifviewer.IiifSources, prefetcher: TimelinePrefetcher,
    artistOf: (Artwork) -> Artist?, level: Int, onLevel: (Int) -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier,
) {
    val exit by rememberUpdatedState(onExit)
    var index by remember(tour) { mutableStateOf(startIndex.coerceIn(0, (tour.size - 1).coerceAtLeast(0))) }
    var playing by remember(tour) { mutableStateOf(true) }
    var ready by remember { mutableStateOf(false) }
    val current = tour.getOrNull(index) ?: return
    val next = tour.getOrNull(index + 1)
    BackHandler { exit() }

    // tuiles : l'œuvre courante (déjà chaude si elle était la suivante) et, d'avance, la suivante
    val currentId by rememberUpdatedState(current.id)
    DisposableEffect(current.id) { onDispose { if (current.iiif.canOpenViewer) prefetcher.viewerClosed(current) } }
    DisposableEffect(next?.id) {
        val n = next?.takeIf { it.iiif.canOpenViewer }
        if (n != null) prefetcher.acquire(n)
        onDispose { if (n != null && n.id != currentId) prefetcher.viewerClosed(n) }
    }
    LaunchedEffect(index) { ready = false }
    LaunchedEffect(index, playing, ready) {
        if (!playing || !ready) return@LaunchedEffect
        delay(8000)
        if (index < tour.size - 1) index++ else playing = false
    }

    Box(modifier.background(Color.Black)) {
        // toute interaction avec l'IMAGE met la lecture en pause (on observe sans consommer : la visionneuse reçoit les gestes) ;
        // les boutons du haut et du détail sont en dehors de cette zone
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (e.changes.any { it.pressed }) playing = false
                }
            }
        }) {
        androidx.compose.animation.Crossfade(targetState = current, animationSpec = tween(700), label = "parcours") { a ->
            if (!a.iiif.canOpenViewer) {
                // pas d'image zoomable : affichée telle quelle, le zoom est sans effet ; le parcours continue normalement
                LaunchedEffect(a.id) { if (a.id == currentId) ready = true }
                Box(Modifier.fillMaxSize()) {
                    ArtworkImage(a, 1600, 1600)
                    NotZoomableBadge(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
                }
                return@Crossfade
            }
            val prewarm = remember(a.id) { prefetcher.acquire(a) }
            val controller = rememberIiifZoomController()
            Box(Modifier.fillMaxSize()) {
                // la vignette reste dessous tant que les tuiles n'ont pas pris sa place
                ArtworkImage(a, 1080, 1080)
                IiifZoomViewer(
                    manifestUrl = a.iiif.viewerUrl.orEmpty(),
                    sources = sources,
                    prewarm = prewarm,
                    transparentUntilReady = true,
                    initialFocus = Offset.Unspecified,
                    initialZoom = 1f,
                    controller = controller,
                    onReady = { if (a.id == currentId) ready = true },
                    onUnzoomPastFit = { exit() },
                    onError = {
                        if (a.id == currentId) ready = true            // une œuvre qui ne s'ouvre pas ne bloque pas le parcours
                        Diag.error("parcours", "ouverture impossible de « ${a.title} » : ${it.message ?: it.javaClass.simpleName}", a.iiif.viewerUrl)
                    },
                    onLoadError = LoadErrorListener { url, attempt, error, last ->
                        val msg = error.message ?: error.javaClass.simpleName
                        Diag.warn("tuile", "essai $attempt${if (last) " (abandon)" else ""} : $msg", url, key = "tuile|${Diag.hostOf(url)}|${msg.take(60)}")
                    },
                )
            }
        }
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Parcours · ${index + 1}/${tour.size}",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color(0xFFF0D58A), fontSize = 14.sp), modifier = Modifier.weight(1f),
            )
            BasicText(
                if (playing) "❚❚" else "▷ Reprendre",
                style = TextStyle(color = Color(0xFF14171B), fontSize = 13.sp),
                modifier = Modifier.padding(horizontal = 6.dp).background(Color(0xFFF0D58A), RoundedCornerShape(12.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = { playing = !playing; if (playing && index >= tour.size - 1) index = 0 }) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            BasicText("✕", style = TextStyle(color = Color.White, fontSize = 16.sp),
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { exit() }) }.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        if (!ready) WaitIndicator(Modifier.align(Alignment.Center))
        DetailOverlay(current, artistOf(current), artistOf(current)?.name, level, onLevel)
    }
}


@Composable
private fun NotZoomableBadge(modifier: Modifier = Modifier) {
    BasicText(
        "Image non zoomable",
        style = TextStyle(color = Color(0xE6FFFFFF), fontSize = 12.sp),
        modifier = modifier.background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Une œuvre sans image zoomable (pas de service IIIF) : l'image disponible, en plein écran, sans zoom ; le détail reste accessible. */
@Composable
private fun StillImageView(artwork: Artwork, artist: Artist?, level: Int, onLevel: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val close by rememberUpdatedState(onClose)
    BackHandler { close() }
    Box(modifier.background(Color.Black).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        ArtworkImage(artwork, 1600, 1600)
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            BasicText("✕", style = TextStyle(color = Color.White, fontSize = 16.sp),
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { close() }) }.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        NotZoomableBadge(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
        DetailOverlay(artwork, artist, artist?.name, level, onLevel)
    }
}
