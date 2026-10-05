package com.vangoghtimeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vangoghtimeline.model.Artist
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.MetaTagger
import com.vangoghtimeline.model.careerLines
import com.vangoghtimeline.model.RightsKind
import com.vangoghtimeline.model.formatFr

internal fun rightsColor(kind: RightsKind): Color = when (kind) {
    RightsKind.PUBLIC_DOMAIN -> Color(0xFF2E7D4F)
    RightsKind.OPEN_LICENSE -> Color(0xFF2F6DB5)
    RightsKind.VIEW_ONLY -> Color(0xFFB7791F)
    RightsKind.UNKNOWN -> Color(0xFF6B7480)
}

/** Pastille de licence : « PD » domaine public, « CC » licence ouverte, « © » consultation privée, « ? » droits non précisés. */
@Composable
internal fun RightsBadge(kind: RightsKind, modifier: Modifier = Modifier) {
    BasicText(
        kind.badge,
        modifier = modifier.background(rightsColor(kind), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold),
    )
}

/**
 * Vue détaillée d'une œuvre ouverte dans la visionneuse : une ligne « artiste — titre, date » et la licence (pastille, licence, fournisseur) ;
 * un toucher déplie la fiche (lieu, technique, dimensions, crédit, n° d'inventaire… selon le musée, lien vers sa fiche, attribution à citer,
 * conditions d'usage, adresse de la licence), un autre la replie. N'intercepte pas les gestes du visualiseur ailleurs que sur la barre.
 */
@Composable
internal fun RightsBar(artwork: Artwork, artist: Artist?, artistName: String?, level: Int, onLevel: (Int) -> Unit, modifier: Modifier = Modifier) {
    val rights = artwork.rights
    val expanded = level >= 2
    val uriHandler = LocalUriHandler.current
    Column(
        modifier
            .padding(10.dp)
            .fillMaxWidth(0.92f)
            .background(Color(0x99000000), RoundedCornerShape(10.dp))     // transparent : l'œuvre reste visible dessous
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        BasicText(
            (artistName?.let { "$it — " } ?: "") + artwork.title + ", " + artwork.date.formatFr(),
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
        if (rights != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RightsBadge(rights.kind)
                Spacer(Modifier.width(8.dp))
                BasicText(
                    "${rights.label} · ${artwork.provider}",
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color(0xFFE6E9ED), fontSize = 11.sp),
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.heightIn(max = if (level >= 3) 320.dp else 240.dp).verticalScroll(rememberScrollState())) {
                artistName?.let { Line("Artiste : $it") }
                artwork.place?.let { Line("Lieu : $it") }
                if (artwork.details.none { it.first == "Technique" }) artwork.medium?.let { Line("Technique : $it") }
                artwork.details.forEach { (label, value) -> Line("$label : $value") }
                if (artwork.provider.isNotEmpty()) Line("Fournisseur : ${artwork.provider}")
                artwork.pageUrl?.let { url ->
                    BasicText(
                        "Fiche du musée : $url",
                        modifier = Modifier.padding(top = 2.dp).clickable { runCatching { uriHandler.openUri(url) } },
                        style = TextStyle(color = Color(0xFF8DB8F0), fontSize = 11.sp),
                    )
                }
                if (rights != null) {
                    rights.attribution?.let { Line("Attribution à citer : $it") }
                    Line("Conditions : ${rights.conditions}")
                    rights.url?.let { Line("Licence : $it") }
                }
                if (level >= 3) {
                    val meta = remember(artwork) { MetaTagger.tag(artwork) }
                    val px = StyleIndex.get(artwork.id)
                    Line("Style : " + listOfNotNull(meta.subject.label, meta.technique.label, px?.mode?.label, px?.dominant?.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label.lowercase() }).joinToString(" · "))
                }
                if (level >= 3 && artist != null) {
                    // niveau 3 : l'artiste et son parcours
                    Line("L'artiste", bold = true)
                    Line("${artist.name}${artist.lifespan?.let { " ($it)" } ?: ""} · ${artist.origin} · ${artist.movement.labelFr}")
                    if (artist.mainStyle.isNotBlank()) Line("Style : ${artist.mainStyle}")
                    if (artist.emblematicWork.isNotBlank()) Line("Œuvre emblématique : ${artist.emblematicWork}")
                    val career = careerLines(artist)
                    if (career.isNotEmpty()) Line("Parcours", bold = true)
                    career.forEach { Line(it) }
                }
            }
        }
        DetailSlider(level, 3, onLevel)
    }
}

@Composable
private fun Line(text: String, bold: Boolean = false, dim: Boolean = false) {
    BasicText(
        text,
        modifier = Modifier.padding(top = 2.dp),
        style = TextStyle(color = if (dim) Color(0xFF8B96A3) else Color(0xFFD5DAE0), fontSize = 11.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    )
}

/**
 * Vue détaillée : le détail est masqué par défaut. Un bouton discret en bas à droite (avec la pastille de licence) affiche la fiche par-dessus
 * l'œuvre, en transparence, avec son curseur de niveau ; un nouveau toucher sur le bouton la masque.
 */
@Composable
internal fun DetailOverlay(artwork: Artwork, artist: Artist?, artistName: String?, level: Int, onLevel: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember(artwork.id) { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        if (open) RightsBar(artwork, artist, artistName, level, onLevel, Modifier.align(Alignment.BottomStart).padding(bottom = 46.dp))
        // toujours là, discrète : titre, auteur, date (le détail déplié les reprend en grand)
        else BasicText(
            artwork.title + (artistName?.let { " · $it" } ?: "") + " · " + artwork.date.formatFr(),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color(0xCCFFFFFF), fontSize = 11.sp),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.72f).padding(10.dp)
                .background(Color(0x4D000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Row(
            Modifier.align(Alignment.BottomEnd).padding(10.dp)
                .background(if (open) Color(0xCCF0D58A) else Color(0x66000000), RoundedCornerShape(16.dp))
                .pointerInput(artwork.id) { detectTapGestures(onTap = { open = !open }) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            artwork.rights?.let { RightsBadge(it.kind); Spacer(Modifier.width(6.dp)) }
            BasicText(
                if (open) "Masquer" else "Détail",
                style = TextStyle(color = if (open) Color(0xFF14171B) else Color(0xE6FFFFFF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
