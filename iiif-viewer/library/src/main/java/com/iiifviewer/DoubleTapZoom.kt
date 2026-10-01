package com.iiifviewer

/**
 * Cible d'un double-tap : de n'importe quel zoom, on va d'abord au ZOOM MAXIMUM (sur le point tapé) ; seulement une fois au maximum,
 * le double-tap suivant ramène à l'image entière (dézoom complet). Pur Kotlin : testé sur la JVM.
 */
object DoubleTapZoom {
    /** « Au maximum » : à 2 % près, pour qu'un zoom arrêté un peu avant (pincement, arrondi) compte comme atteint. */
    private const val AT_MAX = 0.98f

    fun targetScale(current: Float, minScale: Float, maxScale: Float): Float =
        if (current >= maxScale * AT_MAX) minScale else maxScale
}
