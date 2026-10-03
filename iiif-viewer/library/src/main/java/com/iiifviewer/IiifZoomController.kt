package com.iiifviewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * Poignée pour piloter un [IiifZoomViewer] depuis l'extérieur.
 *
 * Cas d'usage : un écran qui ferme le visualiseur par une transition (l'image rétrécit vers sa vignette) veut d'abord ramener le
 * zoom au minimum, sinon la transition partirait d'une vue zoomée qui ne ressemble pas à la vignette :
 * `controller.animateToFit()` puis la transition de sortie.
 */
@Stable
class IiifZoomController {
    internal var viewport: ViewportController? = null

    /** Vrai si l'image est entière à l'écran (ou si le visualiseur n'est pas encore prêt : rien à dézoomer). */
    val isFit: Boolean get() = viewport?.isFit() ?: true

    /** Revient au zoom minimal en s'animant ; rend la main quand c'est fait. Sans effet si l'image est déjà entière. */
    suspend fun animateToFit() {
        viewport?.animateToFit()
    }
}

@Composable
fun rememberIiifZoomController(): IiifZoomController = remember { IiifZoomController() }
