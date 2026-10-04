package com.vangoghtimeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ChronoTour
import com.vangoghtimeline.model.formatFr
import com.vangoghtimeline.model.ColorMode
import com.vangoghtimeline.model.MetaTagger
import com.vangoghtimeline.model.NamedColor
import com.vangoghtimeline.model.Subject
import com.vangoghtimeline.model.TagFilter
import com.vangoghtimeline.model.Technique
import com.vangoghtimeline.model.CardSpec
import com.vangoghtimeline.model.FocusCandidate
import com.vangoghtimeline.model.NextScrollOrder
import com.vangoghtimeline.model.TimelineEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs
import kotlin.math.roundToInt

private val Background = Color(0xFF0F1114)
private const val MIN_DAYS_PER_PIXEL = 0.25f   // très zoomé : un jour ≈ 4 px
private const val MAX_THUMB_PREFETCH = 36
private const val MAX_DAYS_PER_PIXEL = 12f     // toute la vie tient dans un écran

/**
 * Frise chronologique : axe X = le temps (1 px = `daysPerPixel` jours), axe Y = couloirs qui évitent tout chevauchement.
 * Défilement libre dans les deux sens ; pincement = zoom de l'échelle du temps, centré sous les doigts.
 */
@Composable
fun TimelineScreen(
    artworks: List<Artwork>,
    modifier: Modifier = Modifier,
    initialDaysPerPixel: Float = 1.6f,
    onArtworkTap: ((OpenRequest) -> Unit)? = null,
    roller: Boolean = true,
    prefetcher: TimelinePrefetcher? = null,
    /** Nom de l'artiste (et ses dates de vie) affiché en tête de la frise ; `null` = pas d'en-tête. */
    title: String? = null,
    /** Artiste dont l'image de fond (celle du menu) reste derrière la frise. */
    backdropArtistId: String? = null,
    /** Niveau de détail partagé : 1 zoom actuel ; 2 cartes deux fois plus grandes avec lieu et origine ; 3 + commentaire. */
    level: Int = 1,
    onLevel: (Int) -> Unit = {},
    /** Recherche transversale : le nom de l'artiste de chaque œuvre, écrit sur sa carte. */
    artistNameOf: ((Artwork) -> String?)? = null,
    /** À droite du titre (bascule Frise / Carte). */
    headerTrailing: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val state = rememberTimelineScrollState()
    var daysPerPixel by rememberSaveable { mutableStateOf(initialDaysPerPixel) }

    // ── filtres par sujet / technique (métadonnées) et par couleur (analyse des vignettes, voir StyleIndex) ──
    val context = androidx.compose.ui.platform.LocalContext.current
    val metas = remember(artworks) { artworks.associate { it.id to MetaTagger.tag(it) } }
    val styleVersion = StyleIndex.version
    var fSubject by rememberSaveable { mutableStateOf<String?>(null) }
    var fTechnique by rememberSaveable { mutableStateOf<String?>(null) }
    var fMode by rememberSaveable { mutableStateOf<String?>(null) }
    var fHue by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = TagFilter(fSubject?.let { Subject.valueOf(it) }, fTechnique?.let { Technique.valueOf(it) }, fMode?.let { ColorMode.valueOf(it) }, fHue?.let { NamedColor.valueOf(it) })
    val allArtworks = artworks
    LaunchedEffect(allArtworks) { StyleIndex.index(context, context.imageLoader, allArtworks) }
    val artworks = remember(allArtworks, filter, styleVersion) {
        if (!filter.active) allArtworks else allArtworks.filter { filter.matches(metas.getValue(it.id), StyleIndex.get(it.id)) }
    }
    val subjectCounts = remember(metas) { metas.values.groupingBy { it.subject }.eachCount() }
    val techniqueCounts = remember(metas) { metas.values.groupingBy { it.technique }.eachCount() }

    val cardScale = if (level >= 2) 2f else 1f
    val card = remember(density, cardScale) {
        with(density) { CardSpec(width = 172.dp.toPx() * cardScale, height = 150.dp.toPx() * cardScale, gapX = 10.dp.toPx(), gapY = 10.dp.toPx()) }
    }
    val margin = with(density) { 40.dp.toPx() }
    // La mise en page ne dépend que des œuvres, de l'échelle et de la taille des cartes : pas du défilement.
    val plan = remember(artworks, daysPerPixel, card, margin) { TimelineEngine.layout(artworks, daysPerPixel, card, margin) }

    // ── parcours chronologique : œuvre par œuvre dans l'ordre du temps, le cadre doré suit ──
    val tourOrder = remember(artworks) { ChronoTour.order(artworks) }
    val placedById = remember(plan) { plan.items.associateBy { it.artwork.id } }
    var tour by rememberSaveable { mutableStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    val tourIdx = if (tour in tourOrder.indices) tour else -1       // un filtre qui retire des œuvres peut invalider l'indice
    val tourId = tourOrder.getOrNull(tourIdx)?.id
    fun startIndex(): Int {
        val cx = state.scrollX + state.viewportWidth / 2f
        val i = tourOrder.indexOfFirst { a -> placedById[a.id]?.let { it.x + it.width / 2f >= cx } == true }
        return if (i < 0) tourOrder.size - 1 else i
    }
    LaunchedEffect(tourId, plan) {
        val item = tourId?.let { placedById[it] } ?: return@LaunchedEffect
        val sx = state.scrollX
        val sy = state.scrollY
        val tx = (item.x + item.width / 2f - state.viewportWidth / 2f).coerceIn(0f, state.maxScrollX)
        val ty = (item.y + item.height / 2f - state.viewportHeight / 2f).coerceIn(0f, state.maxScrollY)
        state.stopFling()
        androidx.compose.animation.core.animate(0f, 1f, animationSpec = androidx.compose.animation.core.tween(550)) { v, _ ->
            state.scrollToUnclamped(sx + (tx - sx) * v, sy + (ty - sy) * v)
        }
    }
    LaunchedEffect(playing, tourIdx) {
        if (!playing) return@LaunchedEffect
        delay(2600)
        val n = ChronoTour.next(tourIdx, tourOrder.size, startIndex())
        if (n == tourIdx) playing = false else tour = n
    }

    if (prefetcher != null) {
        // Anticipation : sommet du rouleau → image entière préchauffée ; prochain défilement → vignettes (voir TimelinePrefetcher).
        LaunchedEffect(prefetcher, plan, card) {
            val artworkById = plan.items.associate { it.artwork.id to it.artwork }
            var lastX = state.scrollX
            var direction = 0
            snapshotFlow { state.scrollX to state.scrollY }.conflate().collect { (sx, sy) ->
                val vw = state.viewportWidth
                val vh = state.viewportHeight
                if (vw > 0f && vh > 0f) {
                    if (abs(sx - lastX) > 2f) { direction = if (sx > lastX) 1 else -1; lastX = sx }
                    val cx = sx + vw / 2f
                    // 1. cartes autour du centre de l'écran (le sommet du rouleau), à l'écran en hauteur
                    val top = plan.visible(cx - card.width, sy, cx + card.width, sy + vh)
                        .map { FocusCandidate(it.artwork.id, it.x + it.width / 2f - cx, it.y) }
                    prefetcher.onTop(top, card.width) { artworkById[it] }
                    // 2. ce qui sera visible au prochain défilement : large dans le sens du mouvement, face à l'utilisateur d'abord
                    val (left, right) = NextScrollOrder.zone(sx, vw, direction)
                    val ordered = NextScrollOrder.order(plan.visible(left, sy - card.height, right, sy + vh + card.height), cx)
                    prefetcher.onNextScroll(
                        ordered.take(MAX_THUMB_PREFETCH).mapNotNull { p ->
                            val w = p.width.roundToInt()
                            val h = p.height.roundToInt()
                            p.artwork.iiif.thumbnailUrlFor(w, h)?.let { ThumbTarget(p.artwork, it, w, h) }
                        },
                    )
                }
                delay(60)   // au plus ~16 mises à jour par seconde ; la dernière position est toujours traitée (conflate)
            }
        }
    }

    val backdrops = rememberBackdrops()
    val backdrop = backdrops.of(backdropArtistId)
    Box(modifier.fillMaxSize().background(Background)) {
        if (backdrop != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                BackdropImage(backdrop, backdrops.embedded, with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() })
            }
            // voile : la frise et ses cartes restent lisibles sur le tableau
            Box(Modifier.fillMaxSize().background(Color(0xB30F1114)))
        }
    Column(Modifier.fillMaxSize()) {
        if (title != null || headerTrailing != null) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().background(Color(0xB314171B)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    title.orEmpty(),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color(0xFFE6E9ED), fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                )
                headerTrailing?.invoke()
            }
        }
        FilterBar(
            filter, { f -> fSubject = f.subject?.name; fTechnique = f.technique?.name; fMode = f.mode?.name; fHue = f.hue?.name },
            subjectCounts, techniqueCounts, shown = artworks.size, total = allArtworks.size, indexed = StyleIndex.indexedCount(allArtworks),
        )
        TimeAxis(plan, state, roller = roller)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TimelineLayout(
                plan = plan,
                state = state,
                modifier = Modifier.fillMaxSize(),
                roller = roller,
                onZoomX = { centroidX, zoom ->
                    // La date sous les doigts reste sous les doigts : on la retrouve avant, on recale après.
                    val day = plan.scale.dayAt(state.scrollX + centroidX - plan.leftInset)
                    val newScale = (daysPerPixel / zoom).coerceIn(MIN_DAYS_PER_PIXEL, MAX_DAYS_PER_PIXEL)
                    val newContentX = plan.leftInset + ((day - plan.scale.originEpochDay) / newScale).toFloat()
                    daysPerPixel = newScale
                    state.scrollToUnclamped(newContentX - centroidX, state.scrollY)
                },
            ) { placed ->
                ArtworkCard(
                    artwork = placed.artwork,
                    widthPx = placed.width.roundToInt(),
                    heightPx = placed.height.roundToInt(),
                    level = level,
                    artistName = artistNameOf?.invoke(placed.artwork),
                    highlighted = placed.artwork.id == tourId,
                    onTap = onArtworkTap?.let { open -> { request: OpenRequest -> if (!state.tapSuppressed) open(request) } },
                )
            }
            if (artworks.isEmpty()) BasicText(
                if (filter.needsPixels && StyleIndex.indexedCount(allArtworks) < allArtworks.size) "Aucune œuvre analysée ne correspond pour l'instant — l'analyse des couleurs continue…" else "Aucune œuvre ne correspond à ces filtres.",
                style = TextStyle(color = Color(0xFFC9D0D8), fontSize = 14.sp), modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            // niveau 1 : le zoom actuel
            if (level <= 1) BasicText(
                text = "1 px = ${"%.1f".format(daysPerPixel)} j · ${artworks.size} œuvres · ${plan.laneCount} couloirs",
                style = TextStyle(color = Color(0xFF8B96A3), fontSize = 11.sp),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.38f).padding(10.dp),
            )
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) { DetailSlider(level, 3, onLevel) }
            TourBar(
                position = tourIdx, total = tourOrder.size, label = tourOrder.getOrNull(tourIdx)?.date?.formatFr(), playing = playing,
                onPrevious = { tour = ChronoTour.previous(tourIdx, tourOrder.size, startIndex()) },
                onNext = { tour = ChronoTour.next(tourIdx, tourOrder.size, startIndex()) },
                onPlay = { playing = !playing; if (playing && tourIdx < 0) tour = startIndex() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            )
        }
    }
    }
}

/** Parcours chronologique : œuvre précédente / suivante dans l'ordre du temps, et lecture automatique (une œuvre toutes les ~2,6 s). */
@Composable
private fun TourBar(position: Int, total: Int, label: String?, playing: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    if (total == 0) return
    val prev by androidx.compose.runtime.rememberUpdatedState(onPrevious)
    val next by androidx.compose.runtime.rememberUpdatedState(onNext)
    val play by androidx.compose.runtime.rememberUpdatedState(onPlay)
    androidx.compose.foundation.layout.Row(
        modifier.background(Color(0xB3000000), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TourButton("◀") { prev() }
        BasicText(
            if (position < 0) "Parcours" else "${position + 1} / $total" + (label?.let { " · $it" } ?: ""),
            maxLines = 1,
            style = TextStyle(color = Color(0xFFE6E9ED), fontSize = 12.sp),
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        TourButton("▶") { next() }
        TourButton(if (playing) "❚❚" else "▷ Lecture") { play() }
    }
}

@Composable
private fun TourButton(text: String, onTap: () -> Unit) {
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    BasicText(
        text,
        style = TextStyle(color = Color(0xFFF0D58A), fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier
            .androidx_pointerTap { tap() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun Modifier.androidx_pointerTap(onTap: () -> Unit): Modifier =
    this.pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
