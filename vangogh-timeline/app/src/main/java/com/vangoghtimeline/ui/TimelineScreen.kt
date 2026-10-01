package com.vangoghtimeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vangoghtimeline.model.Artwork
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
) {
    val density = LocalDensity.current
    val state = rememberTimelineScrollState()
    var daysPerPixel by rememberSaveable { mutableStateOf(initialDaysPerPixel) }

    val card = remember(density) {
        with(density) { CardSpec(width = 172.dp.toPx(), height = 150.dp.toPx(), gapX = 10.dp.toPx(), gapY = 10.dp.toPx()) }
    }
    val margin = with(density) { 40.dp.toPx() }
    // La mise en page ne dépend que des œuvres, de l'échelle et de la taille des cartes : pas du défilement.
    val plan = remember(artworks, daysPerPixel, card, margin) { TimelineEngine.layout(artworks, daysPerPixel, card, margin) }

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

    Column(modifier.fillMaxSize().background(Background)) {
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
                    onTap = onArtworkTap?.let { open -> { request: OpenRequest -> if (!state.tapSuppressed) open(request) } },
                )
            }
            BasicText(
                text = "1 px = ${"%.1f".format(daysPerPixel)} j · ${artworks.size} œuvres · ${plan.laneCount} couloirs",
                style = TextStyle(color = Color(0xFF8B96A3), fontSize = 11.sp),
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
    }
}
