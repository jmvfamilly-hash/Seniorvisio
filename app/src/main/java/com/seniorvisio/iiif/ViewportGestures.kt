package com.seniorvisio.iiif

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.max

/**
 * Équivalent de `detectTransformGestures` (même logique de seuil de « touch slop »), avec en plus
 * la notification de début de geste et la VITESSE à la levée du doigt — que `detectTransformGestures`
 * ne donne pas, d'où ce détecteur maison.
 *
 * [onFling] n'est appelé que pour un geste à un seul doigt : après un pinch, les deux doigts
 * ne se lèvent jamais exactement ensemble et le centroïde saute, ce qui produirait une inertie parasite.
 */
suspend fun PointerInputScope.detectViewportGestures(
    onStart: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onFling: (Velocity) -> Unit,
) {
    awaitEachGesture {
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        var zoomAcc = 1f
        var panAcc = Offset.Zero
        var pastSlop = false
        var maxPointers = 1

        awaitFirstDown(requireUnconsumed = false)
        onStart()

        do {
            val event = awaitPointerEvent()
            val cancelled = event.changes.any { it.isConsumed }
            if (!cancelled) {
                maxPointers = max(maxPointers, event.changes.count { it.pressed })
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastSlop) {
                    zoomAcc *= zoomChange
                    panAcc += panChange
                    val zoomMotion = abs(1 - zoomAcc) * event.calculateCentroidSize(useCurrent = false)
                    if (zoomMotion > slop || panAcc.getDistance() > slop) pastSlop = true
                }

                if (pastSlop) {
                    // centroïde d'AVANT le geste : c'est ce que ViewportController.transformBy attend.
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoomChange != 1f || panChange != Offset.Zero) onGesture(centroid, panChange, zoomChange)

                    val now = event.calculateCentroid(useCurrent = true)
                    if (now != Offset.Unspecified) tracker.addPosition(event.changes[0].uptimeMillis, now)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!cancelled && event.changes.any { it.pressed })

        if (pastSlop && !cancelled && maxPointers == 1) onFling(tracker.calculateVelocity())
    }
}
