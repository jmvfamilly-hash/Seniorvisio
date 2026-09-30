package com.vangoghtimeline.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vangoghtimeline.model.PlacedArtwork
import com.vangoghtimeline.model.TimelinePlan
import kotlin.math.roundToInt

/** Donnée portée par chaque enfant : où le placer dans le contenu. */
private data class PlacementElement(val placed: PlacedArtwork) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = placed
}

/**
 * Mise en page libre 2D, **virtualisée** : seules les œuvres qui touchent la fenêtre visible (plus une marge de
 * [overscan]) sont composées, le reste n'existe pas — ni composition, ni image Coil, ni mémoire.
 *
 * Pourquoi c'est fluide :
 * 1. [TimelinePlan.visible] : recherche dichotomique, O(log n + visibles).
 * 2. `derivedStateOf` : la liste n'est republiée (donc recomposée) que lorsque l'ENSEMBLE des œuvres visibles change.
 *    Entre deux, faire défiler ne recompose rien.
 * 3. Le défilement ([TimelineScrollState.scrollX]/[scrollY]) n'est lu que dans le bloc de placement : Compose ne refait
 *    que le placement (pas de nouvelle mesure, pas de recomposition) — les cartes glissent.
 * 4. La marge [overscan] compose les cartes un peu AVANT leur entrée à l'écran : Coil a déjà commencé à charger la
 *    vignette quand elle devient visible.
 * 5. Chaque carte est mesurée à taille fixe (`Constraints.fixed`) : coût de mesure constant.
 */
@Composable
fun TimelineLayout(
    plan: TimelinePlan,
    state: TimelineScrollState,
    modifier: Modifier = Modifier,
    overscan: Dp = 320.dp,
    onZoomX: (centroidX: Float, zoom: Float) -> Unit = { _, _ -> },
    content: @Composable (PlacedArtwork) -> Unit,
) {
    val overscanPx = with(LocalDensity.current) { overscan.toPx() }

    // Le contenu a changé (nouvelle échelle, nouvelles œuvres) : les bornes de défilement suivent.
    SideEffect { state.setContent(plan.contentWidth, plan.contentHeight) }

    val visible by remember(plan, overscanPx) {
        derivedStateOf {
            plan.visible(
                left = state.scrollX - overscanPx,
                top = state.scrollY - overscanPx,
                right = state.scrollX + state.viewportWidth + overscanPx,
                bottom = state.scrollY + state.viewportHeight + overscanPx,
            )
        }
    }

    Layout(
        content = {
            for (placed in visible) {
                // key : une carte garde son identité (et sa vignette déjà chargée) quand l'ensemble visible change.
                key(placed.artwork.id) { Box(Modifier.then(PlacementElement(placed))) { content(placed) } }
            }
        },
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { state.setViewport(it.width.toFloat(), it.height.toFloat()) }
            .scroll2D(state, onZoomX),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeables = measurables.map { m ->
            val p = m.parentData as PlacedArtwork
            m.measure(Constraints.fixed(p.width.roundToInt(), p.height.roundToInt())) to p
        }
        layout(width, height) {
            // Seule lecture du défilement : uniquement en phase de placement.
            val sx = state.scrollX
            val sy = state.scrollY
            for ((placeable, p) in placeables) {
                placeable.place((p.x - sx).roundToInt(), (p.y - sy).roundToInt())
            }
        }
    }
}
