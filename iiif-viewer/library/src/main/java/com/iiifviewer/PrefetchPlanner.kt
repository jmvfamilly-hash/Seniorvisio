package com.iiifviewer

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Mouvement lissé du point de vue, estimé par le [TileManager] à chaque changement de viewport.
 *
 * @property vx pan horizontal, px écran / s (négatif : le contenu part vers la gauche, on regarde à droite)
 * @property vy pan vertical, px écran / s
 * @property vz vitesse de zoom, d ln(échelle) / s (> 0 : zoom avant)
 * @property anchorX point de zoom, écran ([Float.NaN] si inconnu : on prend le centre)
 */
data class ViewMotion(
    val vx: Float = 0f,
    val vy: Float = 0f,
    val vz: Float = 0f,
    val anchorX: Float = Float.NaN,
    val anchorY: Float = Float.NaN,
)

/**
 * Tuile voulue, avec sa priorité : plus petit = plus urgent.
 *
 *  0  niveau idéal, écran visible              — sans elles l'image est floue
 *  1  niveau parent (N-1), écran visible       — flou immédiat en attendant
 *  2  niveau idéal, marge autour de l'écran    — PAN : la zone qui va apparaître, prolongée dans le sens du mouvement
 *  3  niveau plus fin, autour du point de zoom — ZOOM : l'image nette avant d'y arriver
 *  4  niveaux plus grossiers, champ élargi     — DÉZOOM : le champ s'agrandit, le flou est déjà là
 */
data class PlannedTile(val tile: Tile, val priority: Int, val distSq: Float)

/** Plan de chargement : pur, sans réseau ni UI, donc testable sur la JVM. */
object PrefetchPlanner {
    const val MARGIN = 0.35f          // marge fixe autour de l'écran, en fraction de l'écran
    const val LOOKAHEAD_S = 0.6f      // on regarde 600 ms devant, dans le sens du mouvement
    const val MAX_EXTENSION = 1.0f    // prolongement maximal, en écrans
    const val ZOOM_RATE = 0.3f        // |d ln(zoom)/s| au-delà duquel on considère que l'utilisateur zoome
    const val IDLE_SPEED = 0.05f      // écrans/s en dessous desquels on considère le point de vue au repos

    fun plan(
        viewport: ViewportState,
        info: IiifImageInfo,
        screenWidth: Int,
        screenHeight: Int,
        motion: ViewMotion = ViewMotion(),
    ): List<PlannedTile> {
        val sw = screenWidth.toFloat()
        val sh = screenHeight.toFloat()
        val factors = info.scaleFactors
        val ideal = TileCalculator.idealScaleFactor(viewport.scale, info)
        val i = factors.indexOf(ideal)
        val finer = factors.getOrNull(i - 1)
        val parent = factors.getOrNull(i + 1)
        val grand = factors.getOrNull(i + 2)

        val speed = hypot(motion.vx, motion.vy) / max(sw, sh)       // écrans par seconde
        val zoomingIn = motion.vz > ZOOM_RATE
        val zoomingOut = motion.vz < -ZOOM_RATE
        val idle = speed < IDLE_SPEED && !zoomingIn && !zoomingOut

        // centre de la vue, en pixels image : sert à ordonner les tuiles d'une même priorité
        val cxImage = (sw / 2f - viewport.translationX) / viewport.scale
        val cyImage = (sh / 2f - viewport.translationY) / viewport.scale

        val plan = LinkedHashMap<String, PlannedTile>()
        fun add(tiles: List<Tile>, priority: Int) {
            for (t in tiles) {
                val dx = t.region.x + t.region.width / 2f - cxImage
                val dy = t.region.y + t.region.height / 2f - cyImage
                val old = plan[t.id]
                if (old == null || priority < old.priority) plan[t.id] = PlannedTile(t, priority, dx * dx + dy * dy)
            }
        }
        fun inRect(l: Float, t: Float, r: Float, b: Float, f: Int) =
            TileCalculator.calculateTilesInRect(viewport, info, l, t, r, b, f)

        add(inRect(0f, 0f, sw, sh, ideal), 0)
        if (parent != null) add(inRect(0f, 0f, sw, sh, parent), 1)

        // PAN : marge fixe + prolongement dans le sens du déplacement (le contenu va vers −v : on regarde vers −v).
        fun extension(v: Float, size: Float) = (-v * LOOKAHEAD_S).coerceIn(-MAX_EXTENSION * size, MAX_EXTENSION * size)
        var l = -MARGIN * sw
        var r = sw * (1 + MARGIN)
        var t = -MARGIN * sh
        var b = sh * (1 + MARGIN)
        val dx = extension(motion.vx, sw)
        val dy = extension(motion.vy, sh)
        if (dx > 0) r += dx else l += dx
        if (dy > 0) b += dy else t += dy
        add(inRect(l, t, r, b, ideal), 2)

        // ZOOM AVANT (ou repos) : le niveau plus fin autour du point de zoom.
        if (finer != null && (zoomingIn || idle)) {
            val ax = if (motion.anchorX.isNaN()) sw / 2f else motion.anchorX
            val ay = if (motion.anchorY.isNaN()) sh / 2f else motion.anchorY
            val h = (if (zoomingIn) 0.5f else 0.3f) * min(sw, sh)
            add(inRect(ax - h, ay - h, ax + h, ay + h, finer), 3)
        }
        // ZOOM ARRIÈRE (ou repos) : niveaux grossiers sur un champ ×1,6 plus large.
        if (parent != null && (zoomingOut || idle)) {
            val ex = 0.3f * sw
            val ey = 0.3f * sh
            add(inRect(-ex, -ey, sw + ex, sh + ey, parent), 4)
            if (grand != null) add(inRect(-ex, -ey, sw + ex, sh + ey, grand), 4)
        }
        return plan.values.toList()
    }
}
