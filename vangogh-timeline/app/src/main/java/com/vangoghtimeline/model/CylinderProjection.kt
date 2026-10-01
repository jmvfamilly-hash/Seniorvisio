package com.vangoghtimeline.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Projection « rouleau » : la frise est enroulée sur un cylindre vertical vu de face. Au centre de l'écran, les cartes sont
 * (presque) à leur place ; vers les bords, elles tournent autour du cylindre : elles se resserrent, se couchent et s'estompent.
 *
 * Géométrie : une carte dont le centre est à [offset] pixels du centre de l'écran (mesure sur la frise « à plat », donc le
 * long de l'arc) est à l'angle `φ = offset / R` sur le cylindre de rayon `R`. Vue de face, elle apparaît à `R·sin φ` du
 * centre, avec une largeur réduite de `cos φ` : c'est la compression du temps vers les bords.
 *
 * Le rayon est choisi pour que le bord de l'écran (offset = largeur/2) corresponde à `maxAngle` / [reach] : plus [reach] est
 * grand, plus le centre reste plat et plus l'effet se concentre sur les bords.
 *
 * Aucune dépendance : testé sur la JVM.
 */
class CylinderProjection(
    viewportWidth: Float,
    maxAngleDegrees: Float = 72f,
    reach: Float = 1.15f,
) {
    private val half = viewportWidth / 2f
    private val maxAngle = (maxAngleDegrees * PI / 180.0).toFloat()

    /** Rayon du cylindre, en pixels. */
    val radius: Float = if (half > 0f) reach * half / maxAngle else 1f

    /** Angle sur le cylindre (rad), de −π/2 (derrière, à gauche) à π/2 (derrière, à droite). */
    fun angle(offset: Float): Float = (offset / radius).coerceIn(-HALF_PI, HALF_PI)

    /** Décalage horizontal apparent par rapport au centre de l'écran. */
    fun project(offset: Float): Float = radius * sin(angle(offset))

    /** Réduction de largeur : cos φ, jamais en dessous de [MIN_SCALE] pour qu'une carte reste lisible. */
    fun scaleX(angle: Float): Float = max(MIN_SCALE, cos(angle))

    /** Opacité : 1 au centre, 0 quand la carte est tout à fait de profil. */
    fun alpha(angle: Float): Float = cos(angle).coerceIn(0f, 1f).pow(1.3f)

    /** Rotation autour de l'axe Y (degrés) : une fraction de φ, pour une inclinaison légère. */
    fun rotationYDegrees(angle: Float, factor: Float = ROTATION_FACTOR): Float = (angle * 180.0 / PI).toFloat() * factor

    companion object {
        private const val HALF_PI = (PI / 2.0).toFloat()
        const val MIN_SCALE = 0.18f
        const val ROTATION_FACTOR = 0.6f
    }
}
