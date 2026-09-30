package com.vangoghtimeline.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Position de défilement 2D de la frise : [scrollX] (temps) et [scrollY] (couloirs), en pixels du contenu.
 *
 * [scrollX]/[scrollY] sont des états Compose LUS uniquement en phase de placement et de dessin : faire défiler ne
 * recompose rien, cela ne fait que déplacer les cartes déjà composées.
 */
@Stable
class TimelineScrollState(private val scope: CoroutineScope) {
    var scrollX by mutableFloatStateOf(0f); private set
    var scrollY by mutableFloatStateOf(0f); private set
    var viewportWidth by mutableFloatStateOf(0f); private set
    var viewportHeight by mutableFloatStateOf(0f); private set

    private var contentWidth = 0f
    private var contentHeight = 0f
    private var flingJob: Job? = null

    val maxScrollX: Float get() = max(0f, contentWidth - viewportWidth)
    val maxScrollY: Float get() = max(0f, contentHeight - viewportHeight)

    fun setViewport(width: Float, height: Float) {
        viewportWidth = width; viewportHeight = height
        clampNow()
    }

    fun setContent(width: Float, height: Float) {
        contentWidth = width; contentHeight = height
        clampNow()
    }

    fun scrollBy(dx: Float, dy: Float) {
        scrollX = (scrollX + dx).coerceIn(0f, maxScrollX)
        scrollY = (scrollY + dy).coerceIn(0f, maxScrollY)
    }

    /** Sans borne : pour recaler après un changement d'échelle, avant que la nouvelle largeur du contenu soit connue. */
    fun scrollToUnclamped(x: Float, y: Float) {
        scrollX = x; scrollY = y
    }

    fun stopFling() {
        flingJob?.cancel()
        flingJob = null
    }

    /** Inertie : le défilement continue à la vitesse [vx],[vy] (px/s) et ralentit exponentiellement, bord à bord. */
    fun fling(vx: Float, vy: Float) {
        stopFling()
        flingJob = scope.launch {
            var last = Offset.Zero
            Animatable(Offset.Zero, Offset.VectorConverter).animateDecay(
                initialVelocity = Offset(vx, vy),
                animationSpec = exponentialDecay(absVelocityThreshold = 20f),
            ) {
                scrollBy(value.x - last.x, value.y - last.y)
                last = value
            }
        }
    }

    private fun clampNow() {
        scrollX = scrollX.coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
    }
}

@Composable
fun rememberTimelineScrollState(): TimelineScrollState {
    val scope = rememberCoroutineScope()
    return remember { TimelineScrollState(scope) }
}

/**
 * Défilement libre dans toutes les directions, avec inertie, et pincement pour zoomer l'échelle du temps.
 *
 * `Modifier.scrollable` ne gère qu'une orientation et `draggable2D` n'existe pas dans cette version de Compose :
 * le détecteur est donc écrit à la main (seuil de « touch slop », vitesse par `VelocityTracker`).
 * Le pincement ne change QUE l'axe X (le temps) ; [onZoomX] reçoit l'abscisse écran du centre des doigts et le facteur.
 */
@Composable
fun Modifier.scroll2D(
    state: TimelineScrollState,
    onZoomX: (centroidX: Float, zoom: Float) -> Unit = { _, _ -> },
): Modifier {
    val currentZoom by rememberUpdatedState(onZoomX)
    return pointerInput(state) {
        awaitEachGesture {
            val slop = viewConfiguration.touchSlop
            val tracker = VelocityTracker()
            var panAcc = Offset.Zero
            var zoomAcc = 1f
            var pastSlop = false
            var maxPointers = 1
            var cancelled = false

            awaitFirstDown(requireUnconsumed = false)
            state.stopFling() // un doigt posé arrête l'inertie

            do {
                val event = awaitPointerEvent()
                cancelled = event.changes.any { it.isConsumed }
                if (!cancelled) {
                    val pointers = event.changes.count { it.pressed }
                    maxPointers = max(maxPointers, pointers)
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()

                    if (!pastSlop) {
                        panAcc += pan
                        zoomAcc *= zoom
                        val zoomMotion = abs(1 - zoomAcc) * event.calculateCentroidSize(useCurrent = false)
                        if (panAcc.getDistance() > slop || zoomMotion > slop) pastSlop = true
                    }
                    if (pastSlop) {
                        if (pointers >= 2 && zoom != 1f) currentZoom(event.calculateCentroid(useCurrent = false).x, zoom)
                        // le contenu suit le doigt : défiler de −pan
                        if (pan != Offset.Zero) state.scrollBy(-pan.x, -pan.y)
                        val now = event.calculateCentroid(useCurrent = true)
                        if (now != Offset.Unspecified) tracker.addPosition(event.changes[0].uptimeMillis, now)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (!cancelled && event.changes.any { it.pressed })

            // Inertie seulement pour un geste à un doigt (après un pincement, le centre des doigts saute à la levée).
            if (pastSlop && !cancelled && maxPointers == 1) {
                val v = tracker.calculateVelocity()
                state.fling(-v.x, -v.y)
            }
        }
    }
}
