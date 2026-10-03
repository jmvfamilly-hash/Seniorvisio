package com.iiifviewer

/**
 * Cible d'un double-tap, en trois temps : depuis l'image entière (ou tout zoom plus faible que l'étape intermédiaire) → **milieu
 * perceptif** entre l'image entière et le zoom maximum ; de là (ou de tout zoom entre les deux) → **zoom maximum** ; depuis le maximum →
 * **image entière**.
 *
 * « Perceptif » : l'œil juge un zoom par son RAPPORT, pas par sa différence. Le milieu est donc la moyenne géométrique `√(min × max)` :
 * même facteur de grossissement de l'image entière à l'étape, et de l'étape au maximum (min ×1 → ×2,8 → ×8 pour un maximum à ×8).
 *
 * Si l'étape n'est pas nettement au-dessus de l'image entière (petite image : le maximum est proche du minimum), elle n'a pas de sens
 * et est sautée. Pur Kotlin : testé sur la JVM.
 */
object DoubleTapZoom {
    /** « Atteint » : à 2 % près, pour qu'un zoom arrêté un peu avant (pincement, arrondi) compte comme atteint. */
    private const val REACHED = 0.98f

    /** L'étape intermédiaire doit être au moins 25 % au-dessus de l'image entière pour exister. */
    private const val MIN_STEP_GAIN = 1.25f

    fun targetScale(current: Float, minScale: Float, maxScale: Float): Float {
        val half = kotlin.math.sqrt(minScale * maxScale)
        return when {
            current >= maxScale * REACHED -> minScale
            half >= minScale * MIN_STEP_GAIN && current < half * REACHED -> half
            else -> maxScale
        }
    }
}
