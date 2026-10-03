package com.vangoghtimeline.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.vangoghtimeline.model.Backdrop
import com.vangoghtimeline.model.BackdropIndex
import kotlin.math.roundToInt

/** Les fonds d'artistes (`assets/backdrops/index.json`) et la liste des images embarquées. */
internal class Backdrops(val index: Map<String, Backdrop>, val embedded: Set<String>) {
    fun of(artistId: String?): Backdrop? = artistId?.let { index[it] }?.takeIf { it.usable }
}

@Composable
internal fun rememberBackdrops(): Backdrops {
    val context = LocalContext.current
    return remember {
        Backdrops(
            runCatching { BackdropIndex.parse(context.assets.open("backdrops/index.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyMap()),
            runCatching { context.assets.list("backdrops")?.toSet() }.getOrNull().orEmpty(),
        )
    }
}

/**
 * L'image de fond d'un artiste, plein cadre, placée selon la règle des tiers ([ThirdsFit]) pour la taille de l'espace disponible. Rien si [backdrop]
 * est `null` ; le voile de lisibilité est ajouté par l'appelant.
 */
@Composable
internal fun BackdropImage(backdrop: Backdrop?, embedded: Set<String>, screenW: Float, screenH: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    Box(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.TopStart) {
        if (backdrop != null && screenW > 0f && screenH > 0f) {
            val p = backdrop.place(screenW, screenH)
            val data: Any = if ("${backdrop.artistId}.jpg" in embedded) backdrop.assetUri else backdrop.remoteUrl
            AsyncImage(
                model = ImageRequest.Builder(context).data(data).size(Size.ORIGINAL).crossfade(true).build(),
                contentDescription = backdrop.credit,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .requiredSize(with(density) { p.imageW.toDp() }, with(density) { p.imageH.toDp() })
                    .offset { IntOffset(p.offsetX.roundToInt(), p.offsetY.roundToInt()) },
            )
        }
    }
}
