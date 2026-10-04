package com.vangoghtimeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.commentOf
import com.vangoghtimeline.model.formatFr

/** Ce que la frise sait au moment d'un toucher : l'œuvre, et où est sa carte à l'écran (pour animer à partir d'elle). */
data class OpenRequest(val artwork: Artwork, val bounds: Rect, val cardWidthPx: Int, val cardHeightPx: Int)

/** Clé du cache mémoire Coil d'une vignette : stable (œuvre + taille), partagée entre la carte et l'habillage de la transition. */
internal fun thumbKey(artwork: Artwork, widthPx: Int, heightPx: Int) = "${artwork.id}@${widthPx}x$heightPx"

/**
 * La requête Coil d'une vignette. UNE seule définition, partagée par la carte et par le préchargement ([TimelinePrefetcher]) :
 * même clé mémoire, même taille de décodage, donc une vignette préchargée est trouvée telle quelle quand sa carte apparaît.
 */
internal fun thumbRequest(
    context: android.content.Context,
    artwork: Artwork,
    url: String,
    widthPx: Int,
    heightPx: Int,
    placeholderKey: String? = null,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(widthPx, heightPx)
        .memoryCacheKey(thumbKey(artwork, widthPx, heightPx))
        .apply { if (placeholderKey != null) placeholderMemoryCacheKey(placeholderKey) }
        .allowRgb565(true)
        .crossfade(false)
        // chaque vignette en échec est consignée (regroupée par serveur et message) : navigation et préchargement
        .listener(onError = { request, result ->
            val url = request.data.toString()
            val msg = result.throwable.message ?: result.throwable.javaClass.simpleName
            Diag.warn("vignette", msg, url, key = "vignette|${Diag.hostOf(url)}|$msg")
        })
        .build()

/**
 * L'image d'une œuvre : un fond dessiné aux couleurs du lieu (jamais « vide »), puis la vignette IIIF par-dessus quand elle existe.
 *
 * @param placeholderKey clé mémoire d'une vignette DÉJÀ chargée à une autre taille, affichée en attendant : c'est ce qui rend la
 *   transition carte → plein écran continue (l'image de la carte reste visible pendant que la grande version arrive).
 */
@Composable
internal fun ArtworkImage(
    artwork: Artwork,
    widthPx: Int,
    heightPx: Int,
    modifier: Modifier = Modifier,
    placeholderKey: String? = null,
) {
    val context = LocalContext.current
    val url = remember(artwork.id, widthPx, heightPx) { artwork.iiif.thumbnailUrlFor(widthPx, heightPx) }
    val request = remember(url, widthPx, heightPx, placeholderKey) {
        url?.let { thumbRequest(context, artwork, it, widthPx, heightPx, placeholderKey) }
    }
    Box(modifier.fillMaxSize()) {
        // Le fond est toujours là : pendant le chargement, ou si le serveur IIIF ne répond pas, la carte n'est jamais « vide ».
        PlaceholderArt(artwork)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Carte d'une œuvre : vignette IIIF + titre + date (au niveau de précision réellement connu).
 *
 * [onTap] : toucher simple sur la carte, sans AUCUN retour visuel ni consommation des doigts tant qu'aucun geste de défilement
 * ou de pincement n'est reconnu (la frise observe les doigts avant les cartes). `null` = la carte ne réagit à rien.
 *
 * Les paramètres sont l'[Artwork] et la taille en pixels, PAS la position : quand la carte se déplace (zoom du temps,
 * changement de couloir, rouleau), ses paramètres ne changent pas et Compose saute sa recomposition.
 *
 * Coil, pour des dizaines de vignettes (voir [ArtworkImage]) : taille de décodage exacte, vignette redimensionnée par le serveur
 * IIIF (`!w,h`), RGB 565, clé de cache mémoire stable, pas de fondu ; la carte n'est composée que tant qu'elle est proche de
 * l'écran (voir [TimelineLayout]) et Coil annule tout téléchargement dont la carte quitte la composition.
 */
@Composable
fun ArtworkCard(
    artwork: Artwork,
    widthPx: Int,
    heightPx: Int,
    modifier: Modifier = Modifier,
    /** Niveau de détail : 1 titre et date ; 2 + lieu et origine (la carte est alors deux fois plus grande) ; 3 + commentaire. */
    level: Int = 1,
    /** Recherche transversale : le nom de l'artiste, en tête de la carte. */
    artistName: String? = null,
    /** Œuvre courante du parcours chronologique : cadre doré. */
    highlighted: Boolean = false,
    /** Niveau 3 : le texte du détail À CÔTÉ de la vignette (écran en paysage) plutôt que dessous (portrait). */
    textBeside: Boolean = false,
    onTap: ((OpenRequest) -> Unit)? = null,
) {
    if (level >= 3) { DetailedCard(artwork, widthPx, heightPx, modifier, artistName, highlighted, textBeside, onTap); return }
    val description = remember(artwork) { "${artwork.title}, ${artwork.date.formatFr()}" }
    // Poignée sur la position à l'écran, lue seulement au double-tap : une référence ordinaire, pas un état (sinon la carte se
    // recomposerait à chaque image de défilement).
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentTap by rememberUpdatedState(onTap)

    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .then(if (highlighted) Modifier.border(3.dp, Color(0xFFF0D58A), RoundedCornerShape(8.dp)) else Modifier)
            .onGloballyPositioned { coordinates[0] = it }
            .semantics { contentDescription = description }
            .pointerInput(artwork, widthPx, heightPx, onTap != null) {
                if (onTap != null) {
                    detectTapGestures(onTap = {
                        val bounds = coordinates[0]?.takeIf { it.isAttached }?.boundsInRoot()
                        if (bounds != null) currentTap?.invoke(OpenRequest(artwork, bounds, widthPx, heightPx))
                    })
                }
            },
    ) {
        ArtworkImage(artwork, widthPx, heightPx)
        // pastille de licence : PD domaine public, CC licence ouverte, © consultation privée, ? non précisé
        artwork.rights?.let { RightsBadge(it.kind, Modifier.align(Alignment.TopEnd).padding(5.dp)) }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (artistName != null) BasicText(artistName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color(0xFFF0D58A), fontSize = 10.sp))
            BasicText(
                artwork.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White, fontSize = if (level >= 2) 15.sp else 12.sp, fontWeight = if (level >= 2) androidx.compose.ui.text.font.FontWeight.SemiBold else null),
            )
            if (level >= 2) {
                BasicText(artwork.date.formatFr(), maxLines = 1, style = TextStyle(color = Color(0xFFD9D3BF), fontSize = 12.sp))
                artwork.place?.let { BasicText("Lieu : $it", maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color(0xFFD9D3BF), fontSize = 12.sp)) }
                if (artwork.provider.isNotEmpty()) BasicText("Origine : ${artwork.provider}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color(0xFFD9D3BF), fontSize = 12.sp))
                if (level >= 3) {
                    val comment = remember(artwork) { commentOf(artwork) }
                    if (comment.isNotEmpty()) BasicText(comment, maxLines = 4, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White, fontSize = 11.sp), modifier = Modifier.padding(top = 3.dp))
                }
            } else {
                BasicText(
                    artwork.date.formatFr() + (artwork.place?.let { " · $it" } ?: ""),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color(0xFFD9D3BF), fontSize = 10.sp),
                )
            }
        }
    }
}

/**
 * Sans image (manifeste sans vignette, démo hors ligne, chargement en cours) : des coups de pinceau dessinés
 * aux couleurs du lieu, propres à chaque œuvre (graine = identifiant). Dessin mis en cache : recalculé seulement
 * si la taille de la carte change, jamais pendant le défilement.
 */
@Composable
internal fun PlaceholderArt(artwork: Artwork) {
    val base = placeColor(artwork.place)
    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val rnd = java.util.Random(artwork.id.hashCode().toLong())
                val light = lerp(base, Color.White, 0.35f)
                val dark = lerp(base, Color.Black, 0.45f)
                val strokes = List(26) {
                    val cx = rnd.nextFloat() * size.width
                    val cy = rnd.nextFloat() * size.height
                    val r = (0.08f + rnd.nextFloat() * 0.22f) * size.minDimension
                    Triple(Offset(cx - r, cy - r), Size(2 * r, 2 * r), rnd.nextInt(3))
                }
                val sweeps = List(26) { 40f + rnd.nextFloat() * 200f to rnd.nextFloat() * 360f }
                onDrawBehind {
                    drawRect(Brush.linearGradient(listOf(dark, base)))
                    strokes.forEachIndexed { i, (topLeft, arcSize, tone) ->
                        drawArc(
                            color = if (tone == 0) light else if (tone == 1) base else dark,
                            startAngle = sweeps[i].second,
                            sweepAngle = sweeps[i].first,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            alpha = 0.55f,
                            style = Stroke(width = size.minDimension * 0.035f, cap = StrokeCap.Round),
                        )
                    }
                }
            },
    )
}

/** Une couleur par lieu de vie : la frise se lit aussi comme une carte des périodes. */
fun placeColor(place: String?): Color {
    val p = place?.lowercase() ?: return Color(0xFF6B6B6B)
    return when {
        listOf("nuenen", "etten", "haye", "hague", "drenthe").any { it in p } -> Color(0xFF7A6A4F)
        listOf("anvers", "antwerp", "paris").any { it in p } -> Color(0xFF5E6E8C)
        "arles" in p -> Color(0xFFD9A521)
        "saint-r" in p || "saint r" in p -> Color(0xFF2F5D9E)
        "auvers" in p -> Color(0xFF5E8C5A)
        else -> Color(0xFF6B6B6B)
    }
}

/**
 * Niveau 3 de la frise : la vignette et, à côté (paysage) ou dessous (portrait), tout le texte du détail — titre, date, lieu, origine, commentaire,
 * fiche du musée. On parcourt la frise du doigt comme aux autres niveaux ; un toucher sur la carte ouvre l'œuvre depuis sa vignette.
 */
@Composable
private fun DetailedCard(
    artwork: Artwork, widthPx: Int, heightPx: Int, modifier: Modifier, artistName: String?, highlighted: Boolean, textBeside: Boolean,
    onTap: ((OpenRequest) -> Unit)?,
) {
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentTap by rememberUpdatedState(onTap)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // la vignette garde une forme de tableau ; le reste de la carte est pour le texte
    val imgW = if (textBeside) (heightPx * 1.14f).toInt().coerceAtMost((widthPx * 0.55f).toInt()) else widthPx
    val imgH = if (textBeside) heightPx else (widthPx * 0.876f).toInt().coerceAtMost((heightPx * 0.6f).toInt())
    val text = remember(artwork) { detailText(artwork) }
    val tapModifier = Modifier.pointerInput(artwork, imgW, imgH, onTap != null) {
        if (onTap != null) detectTapGestures(onTap = {
            val bounds = coordinates[0]?.takeIf { it.isAttached }?.boundsInRoot()
            if (bounds != null) currentTap?.invoke(OpenRequest(artwork, bounds, imgW, imgH))
        })
    }
    val image = @Composable {
        Box(Modifier.size(with(density) { imgW.toDp() }, with(density) { imgH.toDp() }).onGloballyPositioned { coordinates[0] = it }) {
            ArtworkImage(artwork, imgW, imgH)
            artwork.rights?.let { RightsBadge(it.kind, Modifier.align(Alignment.TopEnd).padding(5.dp)) }
        }
    }
    val body = @Composable { m: Modifier ->
        Column(m.background(Color(0xE6181B20)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (artistName != null) BasicText(artistName, maxLines = 1, style = TextStyle(color = Color(0xFFF0D58A), fontSize = 11.sp))
            BasicText(artwork.title, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            BasicText(text, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color(0xFFD9D3BF), fontSize = 12.sp, lineHeight = 16.sp), modifier = Modifier.padding(top = 4.dp))
        }
    }
    val frame = modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
        .then(if (highlighted) Modifier.border(3.dp, Color(0xFFF0D58A), RoundedCornerShape(8.dp)) else Modifier)
        .semantics { contentDescription = "${artwork.title}, ${artwork.date.formatFr()}" }
        .then(tapModifier)
    if (textBeside) androidx.compose.foundation.layout.Row(frame) { image(); body(Modifier.fillMaxSize()) }
    else Column(frame) { image(); body(Modifier.fillMaxSize()) }
}

/** Le texte complet du détail d'une œuvre (niveau 3) : date, lieu, origine, commentaire puis les lignes de la fiche du musée. */
internal fun detailText(a: Artwork): String = buildList {
    add(a.date.formatFr() + (a.place?.let { " · $it" } ?: ""))
    if (a.provider.isNotEmpty()) add("Origine : ${a.provider}")
    commentOf(a).takeIf { it.isNotEmpty() }?.let { add(it) }
    a.details.filter { (label, _) -> label !in setOf("Date", "Description", "Commentaire") }.forEach { (label, value) -> add("$label : $value") }
}.joinToString("\n")
