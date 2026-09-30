package com.seniorvisio.iiif

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max
import kotlin.math.min

data class ScreenSize(val width: Int, val height: Int)

/**
 * Source de vérité du [ViewportState] : applique les gestes, borne le zoom et « colle » l'image aux bords.
 * Expose l'état en [StateFlow], consommé par le [TileManager] et par le Canvas.
 */
class ViewportController(private val imageWidth: Int, private val imageHeight: Int) {

    private val _viewport = MutableStateFlow(ViewportState())
    val viewport: StateFlow<ViewportState> = _viewport.asStateFlow()

    private val _screenSize = MutableStateFlow(ScreenSize(0, 0))
    val screenSize: StateFlow<ScreenSize> = _screenSize.asStateFlow()

    private var minScale = 0f
    private var maxScale = 0f
    private var initialised = false

    /**
     * À appeler à chaque mesure. Au premier appel, place la caméra sur [initialFocus] (pixels image)
     * avec un zoom de [initialZoom] × le zoom « image entière visible » : l'utilisateur démarre sur
     * un détail et découvre l'image en dézoomant.
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
            // On veut  screen(focus) = centre écran  ⇒  t = centre - focus * scale
            _viewport.value = clamp(
                ViewportState(scale, width / 2f - initialFocus.x * scale, height / 2f - initialFocus.y * scale),
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
        _viewport.update { vp ->
            val newScale = (vp.scale * zoom).coerceIn(minScale, maxScale)
            val ratio = newScale / vp.scale
            clamp(
                ViewportState(
                    scale = newScale,
                    translationX = centroid.x + pan.x - (centroid.x - vp.translationX) * ratio,
                    translationY = centroid.y + pan.y - (centroid.y - vp.translationY) * ratio,
                ),
                w, h,
            )
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
}
