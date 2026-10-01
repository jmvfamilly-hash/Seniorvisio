package com.vangoghtimeline.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vangoghtimeline.iiif.SourceReport
import com.vangoghtimeline.iiif.SourceState
import com.vangoghtimeline.model.Artist
import com.vangoghtimeline.model.Movement

private val Gold = Color(0xFFF0D58A)
private val DimGold = Color(0xFF8A7440)
private val Ink = Color(0xFF0F1114)
private val Muted = Color(0xFF9AA3AF)

/** Dégradé de la barre « période d'activité » : une teinte par famille (avant / pendant / après l'impressionnisme). */
internal fun movementColors(m: Movement): List<Color> = when (m) {
    Movement.PRE_IMPRESSIONISM -> listOf(Color(0xFFC98A3B), Color(0xFFE3B65A))
    Movement.IMPRESSIONISM -> listOf(Color(0xFF3B7DD8), Color(0xFF4FB8A6), Color(0xFFF2B84B), Color(0xFFE56B4D))
    Movement.POST_IMPRESSIONISM -> listOf(Color(0xFF7B4FB8), Color(0xFFD86AA5), Color(0xFFF2A154))
}

/**
 * Menu de sélection d'un artiste (maquette « Chronologie des Impressionnistes »).
 *
 * - Un portrait par artiste, en cadre doré ; **grisé** quand l'artiste n'a pas d'univers (aucune source d'œuvres configurée).
 * - **Un toucher** sélectionne (et lance en arrière-plan la connexion + validation de ses sources) ; ses informations s'affichent
 *   dessous. **Un autre toucher** sur l'artiste sélectionné ouvre son univers dans la frise (décision dans [onTap], voir [AppRoot]).
 */
@Composable
fun ArtistMenuScreen(
    artists: List<Artist>,
    selectedId: String?,
    model: AppModel,
    onTap: (Artist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = artists.firstOrNull { it.id == selectedId }
    Column(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1F27), Color(0xFF0E1013))))
            .statusBarsPadding(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            BasicText(
                "Chronologie des Impressionnistes",
                style = TextStyle(color = Color.White, fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
            )
            BasicText(
                "MOUVEMENT ARTISTIQUE",
                style = TextStyle(color = Gold, fontSize = 13.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
            )
            Spacer(Modifier.height(4.dp))
            BasicText("PARCOURS TEMPOREL & PÉRIODES ACTIVES  ·  défilez horizontalement", style = TextStyle(color = Muted, fontSize = 10.sp, letterSpacing = 0.5.sp))
        }

        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(artists, key = { it.id }) { artist ->
                ArtistCard(artist, artist.id == selectedId, model.portraits[artist.id]) { onTap(artist) }
            }
        }

        PeriodsBar(artists, selected)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            if (selected == null) {
                BasicText("Touchez un portrait pour voir son artiste.", style = TextStyle(color = Muted, fontSize = 14.sp))
            } else {
                ArtistInfo(selected, model)
            }
        }
    }
}

@Composable
private fun ArtistCard(artist: Artist, selected: Boolean, portraitUrl: String?, onTap: () -> Unit) {
    val currentTap by rememberUpdatedState(onTap)
    val enabled = artist.hasUniverse
    Column(
        Modifier
            .width(150.dp)
            .graphicsLayer {
                val s = if (selected) 1.04f else 1f
                scaleX = s; scaleY = s
                alpha = if (enabled || selected) 1f else 0.62f
            }
            .pointerInput(artist.id) { detectTapGestures(onTap = { currentTap() }) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .border(BorderStroke(if (selected) 3.dp else 2.dp, if (selected) Gold else DimGold), RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF2E343E), Color(0xFF14171C)))),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(artist.initials, style = TextStyle(color = Color(0xFF5B6573), fontSize = 38.sp, fontFamily = FontFamily.Serif))
            if (portraitUrl != null) {
                AsyncImage(
                    model = portraitUrl,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    // sans univers : portrait en niveaux de gris
                    colorFilter = if (enabled) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            artist.name.uppercase(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            artist.lifespan ?: "",
            style = TextStyle(color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        // barre « période d'activité » : colorée si l'artiste a un univers, sinon grise
        val range = if (artist.activeStart != null && artist.activeEnd != null) "${artist.activeStart} – ${artist.activeEnd}" else artist.activePeriod
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (enabled) Brush.horizontalGradient(movementColors(artist.movement))
                    else Brush.horizontalGradient(listOf(Color(0xFF4A505A), Color(0xFF3A3F48))),
                )
                .padding(vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("PÉRIODE D'ACTIVITÉ", style = TextStyle(color = Color.White, fontSize = 7.sp, letterSpacing = 0.6.sp))
            BasicText(range, style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(4.dp))
        BasicText(
            "« ${artist.emblematicWork} »",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Muted, fontSize = 9.sp, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Axe du temps du menu : la période d'activité de l'artiste sélectionné, mise en valeur sur l'ensemble du catalogue. */
@Composable
private fun PeriodsBar(artists: List<Artist>, selected: Artist?) {
    val minYear = artists.mapNotNull { it.activeStart }.minOrNull() ?: 1780
    val maxYear = artists.mapNotNull { it.activeEnd }.maxOrNull() ?: 1950
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val span = (maxYear - minYear).coerceAtLeast(1)
            fun x(year: Int) = (year - minYear).toFloat() / span * size.width
            val axisY = size.height * 0.62f
            drawLine(Color(0xFF3A4350), Offset(0f, axisY), Offset(size.width, axisY), strokeWidth = 4f)
            var year = (minYear / 10 + 1) * 10
            while (year < maxYear) {
                val tall = year % 50 == 0
                drawLine(Color(0xFF566070), Offset(x(year), axisY - if (tall) 9f else 5f), Offset(x(year), axisY + if (tall) 9f else 5f), strokeWidth = 2f)
                year += 10
            }
            val s = selected?.activeStart
            val e = selected?.activeEnd
            if (selected != null && s != null && e != null) {
                val left = x(s)
                val width = (x(e) - left).coerceAtLeast(6f)
                val colors = if (selected.hasUniverse) movementColors(selected.movement) else listOf(Color(0xFF7A828E), Color(0xFF5A616B))
                drawRoundRect(
                    brush = Brush.horizontalGradient(colors, startX = left, endX = left + width),
                    topLeft = Offset(left, axisY - 7f),
                    size = Size(width, 14f),
                    cornerRadius = CornerRadius(7f, 7f),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("$minYear", style = TextStyle(color = Muted, fontSize = 10.sp))
            BasicText(
                selected?.let { s -> "${s.activeStart ?: ""}–${s.activeEnd ?: ""}  ·  ${s.movement.labelFr}" } ?: "",
                style = TextStyle(color = Gold, fontSize = 10.sp),
            )
            BasicText("$maxYear", style = TextStyle(color = Muted, fontSize = 10.sp))
        }
    }
}

@Composable
private fun ArtistInfo(artist: Artist, model: AppModel) {
    BasicText(artist.name, style = TextStyle(color = Color.White, fontSize = 20.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold))
    BasicText(
        listOfNotNull(artist.lifespan, artist.origin, artist.mainStyle).filter { it.isNotBlank() }.joinToString("  ·  "),
        style = TextStyle(color = Gold, fontSize = 12.sp),
    )
    Spacer(Modifier.height(8.dp))
    Label("Œuvre emblématique")
    Body(artist.emblematicWork)
    Label("Type d'œuvres")
    Body(artist.workType)
    if (artist.locations.isNotEmpty()) {
        Label("Lieux de création")
        for (l in artist.locations) {
            BasicText("• ${l.location}", style = TextStyle(color = Color(0xFFE3E7EC), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Body("${l.workType} — ${l.period}")
        }
    }
    Spacer(Modifier.height(10.dp))
    Label("Univers dans la frise")
    val state = model.universes[artist.id]
    when {
        !artist.hasUniverse -> Body("Pas encore d'univers pour cet artiste : aucune source d'œuvres n'est connectée. Il est grisé tant qu'une source n'est pas validée.")
        state == null -> Body("Vérification de l'accès aux œuvres…")
        else -> {
            for (r in state.reports) SourceLine(r)
            Spacer(Modifier.height(8.dp))
            val msg = when {
                state.artworks.isNotEmpty() -> "Touchez à nouveau le portrait pour ouvrir son univers (${state.artworks.size} œuvres${if (state.done) "" else ", d'autres arrivent"})."
                state.done -> "Aucune source validée : l'univers ne peut pas s'ouvrir pour l'instant."
                else -> "Connexion des sources en cours…"
            }
            BasicText(msg, style = TextStyle(color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun SourceLine(r: SourceReport) {
    val color = when (r.state) {
        SourceState.CONNECTED, SourceState.CACHED -> Color(0xFF4CC38A)
        SourceState.REJECTED, SourceState.UNREACHABLE -> Color(0xFFE5645A)
        SourceState.PENDING -> Color(0xFFE3B65A)
        SourceState.EMPTY, SourceState.UNAVAILABLE -> Color(0xFF7A828E)
    }
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 4.dp).size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column {
            BasicText(r.name, style = TextStyle(color = Color(0xFFE3E7EC), fontSize = 12.sp, fontWeight = FontWeight.Bold))
            BasicText(r.detail, style = TextStyle(color = Muted, fontSize = 11.sp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(6.dp))
    BasicText(text.uppercase(), style = TextStyle(color = DimGold, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold))
}

@Composable
private fun Body(text: String) {
    BasicText(text, style = TextStyle(color = Color(0xFFC9D0D8), fontSize = 13.sp))
}
