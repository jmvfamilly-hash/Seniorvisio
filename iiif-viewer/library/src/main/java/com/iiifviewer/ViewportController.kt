package com.iiifviewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class ScreenSize(val width: Int, val height: Int)

/**
 * Source de vérité du [ViewportState] : applique les gestes, borne le zoom et « colle » l'image aux bords.
 * Expose l'état en [StateFlow], consommé par le [TileManager] et par le Canvas.
 */
class ViewportController(
    private val imageWidth: Int,
    private val imageHeight: Int,
    /** Scope Main (horloge de frames requise) : porte les animations d'inertie et de double-tap. */
    private val scope: CoroutineScope,
) {

    private val _viewport = MutableStateFlow(ViewportState())
    val viewport: StateFlow<ViewportState> = _viewport.asStateFlow()

    private val _screenSize = MutableStateFlow(ScreenSize(0, 0))
    val screenSize: StateFlow<ScreenSize> = _screenSize.asStateFlow()

    /** Dernier point de zoom (écran) : le [TileManager] y prépare le niveau plus fin avant qu'on y arrive. */
    var zoomAnchor: Offset = Offset.Unspecified
        private set

    private var minScale = 0f
    private var maxScale = 0f
    private var initialised = false

    /**
     * À appeler à chaque mesure. Au premier appel, place la caméra sur [initialFocus] (pixels image)
     * avec un zoom de [initialZoom] × le zoom « image entière visible » : l'utilisateur démarre sur
     * un détail et découvre l'image en dézoomant. [Offset.Unspecified] = centre de l'image.
     */
    fun onScreenSized(width: Int, height: Int, initialFocus: Offset, initialZoom: Float) {
        if (width <= 0 || height <= 0) return
        // Zoom minimal = image entière visible (« fit »). Zoom max : 8× le fit, jamais sous 200 % natif.
        val fit = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        minScale = fit
        maxScale = max(2f, fit * 8f)
        _screenSize.value = ScreenSize(width, height)

        if (!initialised) {
            initialised = true
            val scale = (fit * initialZoom).coerceIn(minScale, maxScale)
            val focus = if (initialFocus == Offset.Unspecified) Offset(imageWidth / 2f, imageHeight / 2f) else initialFocus
            // On veut  screen(focus) = centre écran  ⇒  t = centre - focus * scale
            _viewport.value = clamp(
                ViewportState(scale, width / 2f - focus.x * scale, height / 2f - focus.y * scale),
                width, height,
            )
        } else {
            _viewport.update { clamp(it.copy(scale = it.scale.coerceIn(minScale, maxScale)), width, height) }
        }
    }

    /**
     * Applique un geste de `detectTransformGestures`.
     *
     * [centroid] est le centre des doigts AVANT le geste (`calculateCentroid(useCurrent = false)`),
     * [pan] le déplacement de ce centre. Le point image sous les doigts doit rester sous les doigts :
     *
     *   p  = (centroid − t) / s          point image sous le centroïde avant le geste
     *   s' = s · zoom                    nouveau zoom (borné)
     *   t' = centroid + pan − p · s'     p doit s'afficher en centroid + pan
     *      = centroid + pan − (centroid − t) · (s' / s)
     */
    fun transformBy(centroid: Offset, pan: Offset, zoom: Float) {
        val (w, h) = _screenSize.value
        if (w <= 0) return
        if (zoom != 1f) zoomAnchor = centroid
        trackZoomOutPastFit(zoom)
        _viewport.update { vp ->
            clamp(anchoredTransform(vp, centroid, pan, (vp.scale * zoom).coerceIn(minScale, maxScale)), w, h)
        }
    }

    /**
     * Appelé UNE fois quand l'utilisateur, déjà à l'image entière au début de son geste, continue à pincer vers le dézoom d'environ
     * 20 % de plus : « dézoomer encore » = vouloir quitter l'image. Un appelant s'en sert pour fermer le visualiseur.
     */
    var onZoomOutPastFit: (() -> Unit)? = null

    private var gestureStartedAtFit = false
    private var pastFitAmount = 0f      // ln du dézoom demandé au-delà du minimum, dans le geste en cours
    private var pastFitFired = false

    /** Un doigt vient de toucher l'écran : stoppe l'inertie et ouvre un nouveau geste. */
    fun onGestureStart() {
        stopAnimation()
        gestureStartedAtFit = isFit()
        pastFitAmount = 0f
        pastFitFired = false
    }

    private fun trackZoomOutPastFit(zoom: Float) {
        if (!gestureStartedAtFit || pastFitFired || zoom == 1f || zoom <= 0f) return
        if (!isFit()) { pastFitAmount = 0f; return }            // il a zoomé dans ce même geste : pas un « dézoom au-delà »
        pastFitAmount = max(0f, pastFitAmount - ln(zoom))
        if (pastFitAmount > PAST_FIT_TRIGGER) {
            pastFitFired = true
            onZoomOutPastFit?.invoke()
        }
    }

    /** Formule ci-dessus, non bornée en translation. */
    private fun anchoredTransform(vp: ViewportState, centroid: Offset, pan: Offset, newScale: Float): ViewportState {
        val ratio = newScale / vp.scale
        return ViewportState(
            scale = newScale,
            translationX = centroid.x + pan.x - (centroid.x - vp.translationX) * ratio,
            translationY = centroid.y + pan.y - (centroid.y - vp.translationY) * ratio,
        )
    }

    // ── Animations (une seule à la fois) ────────────────────────────────────────
    private var animation: Job? = null

    /** À appeler dès qu'un doigt touche l'écran : l'inertie en cours s'arrête net sous le doigt. */
    fun stopAnimation() {
        animation?.cancel()
        animation = null
    }

    /**
     * Inertie : à la levée du doigt, la caméra continue avec la vitesse [velocity] (px/s écran)
     * et ralentit exponentiellement. Chaque pas repasse par [transformBy], donc les bords collants
     * s'appliquent : l'image s'arrête contre le bord au lieu de le dépasser.
     */
    fun fling(velocity: Velocity) {
        stopAnimation()
        animation = scope.launch {
            var last = Offset.Zero
            Animatable(Offset.Zero, Offset.VectorConverter).animateDecay(
                initialVelocity = Offset(velocity.x, velocity.y),
                animationSpec = exponentialDecay(absVelocityThreshold = 20f),
            ) {
                transformBy(Offset.Zero, value - last, 1f) // deltas : le pas est un pan pur
                last = value
            }
        }
    }

    /** Vrai tant que l'image est entière à l'écran (zoom minimal). */
    fun isFit(): Boolean = minScale > 0f && _viewport.value.scale <= minScale * 1.02f

    /**
     * Revient au zoom minimal (image entière, centrée) en s'animant ; rend la main à la fin. Exécutée dans la coroutine de
     * l'APPELANT : il peut enchaîner une autre animation derrière (ex. une transition de sortie) sans minuterie.
     * Le zoom est interpolé en géométrique et ancré au centre de l'écran, comme un dézoom naturel.
     */
    suspend fun animateToFit() {
        stopAnimation()
        val start = _viewport.value
        val (w, h) = _screenSize.value
        if (w <= 0 || minScale <= 0f || start.scale <= minScale * 1.001f) return
        val center = Offset(w / 2f, h / 2f)
        animate(0f, 1f, animationSpec = tween(280, easing = FastOutSlowInEasing)) { f, _ ->
            val scale = start.scale * (minScale / start.scale).pow(f)
            _viewport.value = clamp(anchoredTransform(start, center, Offset.Zero, scale), w, h)
        }
        _viewport.value = clamp(ViewportState(minScale, 0f, 0f), w, h) // état final exact : les bords collants centrent l'image
    }

    /**
     * Double-tap : bascule entre « image entière » et ×4, en gardant le point tapé fixe à l'écran.
     * Le zoom est interpolé en géométrique (s = s0 · (s1/s0)^f) : la vitesse de zoom perçue reste
     * constante, contrairement à une interpolation linéaire qui semble démarrer lentement puis s'emballer.
     */
    fun doubleTapZoom(focus: Offset) {
        stopAnimation()
        val start = _viewport.value
        val (w, h) = _screenSize.value
        if (w <= 0) return
        zoomAnchor = focus
        val target = if (start.scale > minScale * 1.5f) minScale else (minScale * 4f).coerceAtMost(maxScale)
        animation = scope.launch {
            animate(0f, 1f, animationSpec = tween(300, easing = FastOutSlowInEasing)) { f, _ ->
                val scale = start.scale * (target / start.scale).pow(f)
                // Toujours ancré sur le viewport de DÉPART : pas de dérive cumulée image après image.
                _viewport.value = clamp(anchoredTransform(start, focus, Offset.Zero, scale), w, h)
            }
        }
    }

    /**
     * Bords collants. L'image affichée mesure (W·s) × (H·s) :
     *  - plus grande que l'écran : t ∈ [écran − W·s, 0] (aucun bord ne peut entrer dans l'écran) ;
     *  - plus petite : centrée.
     */
    private fun clamp(vp: ViewportState, screenW: Int, screenH: Int): ViewportState {
        fun axis(t: Float, shown: Float, screen: Int): Float =
            if (shown <= screen) (screen - shown) / 2f else t.coerceIn(screen - shown, 0f)
        return vp.copy(
            translationX = axis(vp.translationX, imageWidth * vp.scale, screenW),
            translationY = axis(vp.translationY, imageHeight * vp.scale, screenH),
        )
    }

    private companion object {
        /** ln(1,25) ≈ 0,22 : il faut pincer d'environ 20 % de plus que le minimum pour que ce soit voulu, pas un frôlement. */
        const val PAST_FIT_TRIGGER = 0.22f
    }
}
