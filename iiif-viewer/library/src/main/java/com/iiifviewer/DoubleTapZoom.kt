package com.iiifviewer

/**
 * Cible d'un double-tap, en trois temps : depuis l'image entière (ou tout zoom plus faible que la moitié du maximum) → **moitié du
 * zoom maximum** ; de là (ou de tout zoom entre la moitié et le maximum) → **zoom maximum** ; depuis le maximum → **image entière**.
 *
 * Si la moitié du maximum n'est pas nettement au-dessus de l'image entière (petite image : le maximum est proche du minimum), l'étape
 * intermédiaire n'a pas de sens et est sautée. Pur Kotlin : testé sur la JVM.
 */
object DoubleTapZoom {
    /** « Atteint » : à 2 % près, pour qu'un zoom arrêté un peu avant (pincement, arrondi) compte comme atteint. */
    private const val REACHED = 0.98f

    /** L'étape intermédiaire doit être au moins 25 % au-dessus de l'image entière pour exister. */
    private const val MIN_STEP_GAIN = 1.25f

    fun targetScale(current: Float, minScale: Float, maxScale: Float): Float {
        val half = maxScale / 2f
        return when {
            current >= maxScale * REACHED -> minScale
            half >= minScale * MIN_STEP_GAIN && current < half * REACHED -> half
            else -> maxScale
        }
    }
}
