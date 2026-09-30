package com.vangoghtimeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.formatFr

/**
 * Carte d'une œuvre : vignette IIIF + titre + date (au niveau de précision réellement connu).
 *
 * Les paramètres sont l'[Artwork] et la taille en pixels, PAS la position : quand la carte se déplace (zoom du temps,
 * changement de couloir), ses paramètres ne changent pas et Compose saute sa recomposition.
 *
 * Coil, pour des dizaines de vignettes :
 * - `size(w, h)` exact : on ne décode que les pixels affichés ; l'URL IIIF `!w,h` fait redimensionner par le SERVEUR ;
 * - `allowRgb565(true)` : les vignettes opaques prennent deux fois moins de mémoire ;
 * - `memoryCacheKey` stable (œuvre + taille) : revenir sur une carte déjà vue est instantané ;
 * - pas de fondu (`crossfade(false)`) : un fondu par carte coûte une animation par carte pendant le défilement ;
 * - la carte n'est composée que tant qu'elle est proche de l'écran (voir [TimelineLayout]) : Coil annule tout
 *   téléchargement dont la carte quitte la composition.
 */
@Composable
fun ArtworkCard(
    artwork: Artwork,
    widthPx: Int,
    heightPx: Int,
    onClick: (Artwork) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val url = remember(artwork.id, widthPx, heightPx) { artwork.iiif.thumbnailUrlFor(widthPx, heightPx) }
    val request = remember(url, widthPx, heightPx) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(widthPx, heightPx)
                .memoryCacheKey("${artwork.id}@${widthPx}x$heightPx")
                .allowRgb565(true)
                .crossfade(false)
                .build()
        }
    }
    val description = remember(artwork) { "${artwork.title}, ${artwork.date.formatFr()}" }

    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(placeColor(artwork.place).copy(alpha = 0.35f))
            .clickable { onClick(artwork) }
            .semantics { contentDescription = description },
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlaceholderArt(artwork)
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            BasicText(
                artwork.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White, fontSize = 12.sp),
            )
            BasicText(
                artwork.date.formatFr() + (artwork.place?.let { " · $it" } ?: ""),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color(0xFFD9D3BF), fontSize = 10.sp),
            )
        }
    }
}

/** Sans image (manifeste sans vignette, ou démo hors ligne) : un aplat dégradé aux couleurs du lieu. */
@Composable
private fun PlaceholderArt(artwork: Artwork) {
    val base = placeColor(artwork.place)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.45f)))),
    )
}

/** Une couleur par lieu de vie : la frise se lit aussi comme une carte des périodes. */
fun placeColor(place: String?): Color = when (place) {
    "Nuenen", "Etten", "La Haye", "Drenthe" -> Color(0xFF7A6A4F)
    "Anvers", "Paris" -> Color(0xFF5E6E8C)
    "Arles" -> Color(0xFFD9A521)
    "Saint-Rémy-de-Provence" -> Color(0xFF2F5D9E)
    "Auvers-sur-Oise" -> Color(0xFF5E8C5A)
    else -> Color(0xFF6B6B6B)
}
