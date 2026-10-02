package com.vangoghtimeline.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vangoghtimeline.iiif.SourceReport
import com.vangoghtimeline.iiif.SourceState
import com.vangoghtimeline.model.AgeFormat
import com.vangoghtimeline.model.Artist
import com.vangoghtimeline.model.ArtistBackdrops
import com.vangoghtimeline.model.Backdrop
import com.vangoghtimeline.model.MenuRow
import com.vangoghtimeline.model.SortMode
import com.vangoghtimeline.model.menuRows
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Gold = Color(0xFFF0D58A)
private val DimGold = Color(0xFFB59A5A)
private val Muted = Color(0xFFC3C9D1)
private val Glass = Color(0x8C161A20)          // panneaux translucides : le tableau reste visible dessous
private val GlassBorder = Color(0x40FFFFFF)
private val Ink = Color(0xFF0E1013)

/**
 * Menu de sélection d'un artiste (maquette « Chronologie des Impressionnistes »), en deux dispositions :
 *
 * - **Portrait** : un point d'intérêt d'un tableau majeur de l'artiste en fond plein écran ([ArtistBackdrops]) ; en haut une barre de recherche
 *   transparente et le tri ; une bande verticale de portraits à droite ; la fiche de l'artiste (style, lieu, période, sources) en bas.
 * - **Paysage** : la fiche et la recherche à gauche, les portraits sur un **arc de cercle** à droite (glisser pour les faire défiler).
 *
 * Un toucher sur un portrait sélectionne l'artiste (et lance la connexion de ses sources, voir [AppRoot]) ; un autre toucher, ou le bouton
 * « Ouvrir la frise », ouvre son univers. Appui long dans la fiche : rapport d'anomalies. Le zoom sémantique, la navigation thématique et les
 * filtres par pays viendront plus tard.
 */
@Composable
fun ArtistMenuScreen(
    artists: List<Artist>,
    selectedId: String?,
    model: AppModel,
    onTap: (Artist) -> Unit,
    modifier: Modifier = Modifier,
    /** Appui long dans la fiche : copie le rapport d'anomalies (tous artistes, tous services, navigation). */
    onReportLongPress: () -> Unit = {},
    /** « Actualiser » : recherche et revalide toutes les sources de l'artiste sélectionné. */
    onRefresh: (Artist) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(SortMode.PERIOD.name) }
    val sort = SortMode.valueOf(sortName)
    val rows = remember(artists, query, sort) { menuRows(artists, query, sort) }
    val selected = artists.firstOrNull { it.id == selectedId }
    // fond : l'artiste sélectionné, à défaut le premier qui a un fond (le menu n'est jamais « vide »)
    val backdropArtistId = selected?.id?.takeIf { ArtistBackdrops.of(it) != null } ?: artists.firstOrNull { ArtistBackdrops.of(it.id) != null }?.id
    val backdrop = backdropArtistId?.let { ArtistBackdrops.of(it) }

    BoxWithConstraints(modifier.fillMaxSize().background(Ink)) {
        val landscape = maxWidth > maxHeight
        val density = LocalDensity.current
        val aspect = with(density) { maxWidth.toPx() / maxHeight.toPx() }
        val outWidth = if (landscape) 1600 else 1080

        // ── fond : point d'intérêt d'un tableau majeur, fondu entre deux artistes ──
        Crossfade(targetState = backdrop, animationSpec = tween(700), label = "fond") { b ->
            Box(Modifier.fillMaxSize()) {
                if (b != null) {
                    AsyncImage(model = b.url(aspect, outWidth), contentDescription = b.credit, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                // voile : le texte reste lisible quelle que soit la clarté du tableau
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x22000000), Color(0xB3000000)))))
            }
        }

        if (landscape) {
            Row(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    SearchBar(query) { query = it }
                    Spacer(Modifier.height(8.dp))
                    SortPanel(sort) { sortName = it.name }
                    Spacer(Modifier.weight(1f))
                    ArtistPanel(selected, backdrop, model, onTap, onRefresh, onReportLongPress, Modifier.heightIn(max = maxHeight * 0.62f))
                    CaptionBar(backdrop)
                }
                ArcPicker(
                    items = rows.filterIsInstance<MenuRow.Item>().map { it.artist },
                    selectedId = selectedId, portraits = model.portraits, onTap = onTap,
                    modifier = Modifier.width(maxWidth * 0.40f).fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
                SearchBar(query) { query = it }
                Spacer(Modifier.height(8.dp))
                SortPanel(sort) { sortName = it.name }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                        ArtistPanel(selected, backdrop, model, onTap, onRefresh, onReportLongPress, Modifier.heightIn(max = maxHeight * 0.58f))
                    }
                    Spacer(Modifier.width(8.dp))
                    PortraitStrip(rows, selectedId, model.portraits, onTap, Modifier.width(78.dp).fillMaxHeight())
                }
                CaptionBar(backdrop)
            }
        }
    }
}

// ── recherche et tri ─────────────────────────────────────────────────────────────

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Glass).border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val r = size.minDimension * 0.34f
            drawCircle(Muted, radius = r, center = Offset(size.width * 0.42f, size.height * 0.42f), style = Stroke(width = 3f))
            drawLine(Muted, Offset(size.width * 0.66f, size.height * 0.66f), Offset(size.width * 0.92f, size.height * 0.92f), strokeWidth = 3.5f, cap = StrokeCap.Round)
        }
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box { if (query.isEmpty()) BasicText("Rechercher un artiste, un pays, un style…", style = TextStyle(color = Color(0xFF8E96A1), fontSize = 17.sp)); inner() }
            },
        )
    }
}

@Composable
private fun SortPanel(sort: SortMode, onSort: (SortMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Glass).border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val currentSort by rememberUpdatedState(onSort)
        BasicText("TRIER PAR :", style = TextStyle(color = Color.White, fontSize = 12.sp, letterSpacing = 0.6.sp))
        for (mode in SortMode.values()) {
            val on = mode == sort
            BasicText(
                mode.label,
                maxLines = 1,
                style = TextStyle(color = if (on) Gold else Color.White, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                modifier = Modifier
                    .border(BorderStroke(1.dp, if (on) Gold else GlassBorder), RoundedCornerShape(8.dp))
                    .pointerInput(mode) { detectTapGestures(onTap = { currentSort(mode) }) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}

// ── portraits : bande verticale (portrait) et arc de cercle (paysage) ──────────────

@Composable
private fun PortraitChip(artist: Artist, selected: Boolean, portraitUrl: String?, size: androidx.compose.ui.unit.Dp, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val currentTap by rememberUpdatedState(onTap)
    val enabled = artist.hasUniverse
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled || selected) 1f else 0.6f }
            .border(BorderStroke(if (selected) 3.dp else 1.5.dp, if (selected) Gold else DimGold), RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2E343E), Color(0xFF14171C))))
            .pointerInput(artist.id) { detectTapGestures(onTap = { currentTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(artist.initials, style = TextStyle(color = Color(0xFF8B96A3), fontSize = 20.sp, fontFamily = FontFamily.Serif))
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
}

/** Bande verticale de portraits, à droite (portrait) : groupes sous un titre, défilement vertical. */
@Composable
private fun PortraitStrip(rows: List<MenuRow>, selectedId: String?, portraits: Map<String, String>, onTap: (Artist) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Glass).border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(16.dp))) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(rows, key = { r -> when (r) { is MenuRow.Header -> "h:${r.text}"; is MenuRow.Item -> r.artist.id } }) { row ->
                when (row) {
                    is MenuRow.Header -> BasicText(
                        row.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = DimGold, fontSize = 8.sp, letterSpacing = 0.4.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    is MenuRow.Item -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PortraitChip(row.artist, row.artist.id == selectedId, portraits[row.artist.id], 62.dp, onTap = { onTap(row.artist) })
                        BasicText(
                            row.artist.name.substringAfterLast(' '), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = if (row.artist.id == selectedId) Gold else Color.White, fontSize = 9.sp, textAlign = TextAlign.Center),
                            modifier = Modifier.width(66.dp).padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Portraits sur un ARC DE CERCLE le long du bord droit (paysage) : glisser verticalement les fait défiler le long de l'arc ; le portrait du
 * centre est le plus grand. Sélectionner un artiste (toucher) le ramène au centre.
 */
@Composable
private fun ArcPicker(items: List<Artist>, selectedId: String?, portraits: Map<String, String>, onTap: (Artist) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(0f) }                  // position (en nombre de portraits) de celui qui est au centre de l'arc
    val selectedIndex = items.indexOfFirst { it.id == selectedId }
    LaunchedEffect(selectedIndex, items.size) { if (selectedIndex >= 0) pos.animateTo(selectedIndex.toFloat(), tween(350)) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val radius = hPx * 0.62f
        val step = 0.30f                                   // radians entre deux portraits
        val itemPx = radius * step
        val halfPx = with(density) { 31.dp.toPx() }
        Box(
            Modifier.fillMaxSize().pointerInput(items.size) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { pos.animateTo(pos.value.roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0)).toFloat(), tween(250)) } },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        scope.launch { pos.snapTo((pos.value - dy / itemPx).coerceIn(-0.4f, items.size - 0.6f)) }
                    },
                )
            },
        ) {
            items.forEachIndexed { i, artist ->
                val rel = i - pos.value
                if (abs(rel) <= 3.4f) {
                    val theta = rel * step
                    val cx = wPx * 0.36f + radius * (1f - cos(theta))
                    val cy = hPx / 2f + radius * sin(theta)
                    val scale = (1.3f - 0.13f * abs(rel)).coerceAtLeast(0.7f)
                    PortraitChip(
                        artist, artist.id == selectedId, portraits[artist.id], 62.dp, onTap = { onTap(artist) },
                        modifier = Modifier
                            .offset { IntOffset((cx - halfPx).roundToInt(), (cy - halfPx).roundToInt()) }
                            .graphicsLayer { scaleX = scale; scaleY = scale },
                    )
                }
            }
        }
    }
}

// ── fiche de l'artiste ──────────────────────────────────────────────────────────────

/** Légende du tableau de fond (crédit) et rappel du geste de rapport. */
@Composable
private fun CaptionBar(backdrop: Backdrop?) {
    BasicText(
        (backdrop?.credit ?: "") + "   ·   appui long sur la fiche : rapport d'anomalies",
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = Color(0xB3FFFFFF), fontSize = 9.sp, fontStyle = FontStyle.Italic),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/**
 * La fiche : nom, tableau de fond et mouvement ; style, œuvres, lieu et période ; l'état de l'univers (œuvres, sources — dépliable, avec
 * « Actualiser ») et le bouton d'ouverture de la frise. Défile si elle est plus haute que sa place ; l'appui long ouvre le rapport.
 */
@Composable
private fun ArtistPanel(
    artist: Artist?, backdrop: Backdrop?, model: AppModel,
    onTap: (Artist) -> Unit, onRefresh: (Artist) -> Unit, onReport: () -> Unit, modifier: Modifier = Modifier,
) {
    val currentReport by rememberUpdatedState(onReport)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x99261C12))
            .border(BorderStroke(1.dp, Color(0x55F0D58A)), RoundedCornerShape(16.dp))
            // appui long n'importe où dans la fiche : rapport d'anomalies ; un défilement annule l'appui long
            .pointerInput(Unit) { detectTapGestures(onLongPress = { currentReport() }) }
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (artist == null) {
            BasicText("Touchez un portrait pour découvrir l'artiste.", style = TextStyle(color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center))
            return@Column
        }
        BasicText(artist.name, maxLines = 2, style = TextStyle(color = Color.White, fontSize = 26.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
        // le tableau montré en fond (et sa date), comme « Juin 1888 » dans la maquette
        val shownWork = backdrop?.takeIf { it.artistId == artist.id }
        BasicText(
            shownWork?.let { "${it.titleFr}, ${it.date}" } ?: (artist.lifespan ?: ""),
            style = TextStyle(color = Color(0xFFEFE6D0), fontSize = 16.sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(artist.movement.labelFr, style = TextStyle(color = Gold, fontSize = 17.sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center))
        Spacer(Modifier.height(8.dp))
        InfoRow("Style", artist.mainStyle)
        InfoRow("Œuvres", artist.workType)
        artist.locations.firstOrNull()?.let { InfoRow("Lieu", artist.locations.take(2).joinToString(" · ") { it.location }) }
        InfoRow("Période", listOfNotNull(artist.activePeriod.takeIf { it.isNotBlank() }, artist.lifespan?.let { "($it)" }).joinToString(" "))
        Spacer(Modifier.height(10.dp))
        UniverseSection(artist, model, onTap, onRefresh)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText("$label : ", style = TextStyle(color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        BasicText(value, style = TextStyle(color = Color(0xFFF1F3F5), fontSize = 12.sp))
    }
}

/** L'univers de l'artiste : résumé d'une ligne, détail des sources dépliable, bouton d'ouverture de la frise. */
@Composable
private fun UniverseSection(artist: Artist, model: AppModel, onTap: (Artist) -> Unit, onRefresh: (Artist) -> Unit) {
    var details by rememberSaveable(artist.id) { mutableStateOf(false) }
    val state = model.universes[artist.id]
    val currentTap by rememberUpdatedState(onTap)
    val currentRefresh by rememberUpdatedState(onRefresh)
    if (!artist.hasUniverse) {
        BasicText("Pas encore d'univers pour cet artiste : aucune source d'œuvres n'est connectée.", style = TextStyle(color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center))
        return
    }
    val summary = when {
        state == null -> "Vérification de l'accès aux œuvres…"
        state.artworks.isNotEmpty() -> "${state.artworks.size} œuvres · ${state.connectedCount}/${state.reports.size} sources${if (state.done) "" else " · chargement…"}"
        state.done -> "Aucune source validée pour l'instant."
        else -> "Connexion des sources en cours…"
    }
    BasicText(
        "$summary   ${if (details) "▲" else "▼"}",
        style = TextStyle(color = Color(0xFFE3E7EC), fontSize = 12.sp, textAlign = TextAlign.Center),
        modifier = Modifier.pointerInput(artist.id) { detectTapGestures(onTap = { details = !details }) }.padding(vertical = 4.dp),
    )
    if (details && state != null) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (r in state.reports) SourceLine(r)
            if (state.artworks.isNotEmpty()) {
                BasicText("Licences : ${state.rightsSummary}. Consultation privée ; licence et conditions de chaque œuvre à l'ouverture (PD / CC / © / ?).", style = TextStyle(color = Color(0xFFC9D0D8), fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
            }
            val age = if (state.updatedAtMs > 0) AgeFormat.fr(System.currentTimeMillis() - state.updatedAtMs) else null
            BasicText(
                (if (age != null) "Données mises à jour $age (actualisation automatique après 7 jours)." else "Données en cours d'obtention.") + if (!state.done) "  Mise à jour en cours…" else "",
                style = TextStyle(color = Muted, fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp),
            )
            BasicText(
                "Actualiser maintenant",
                style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 6.dp).background(Color(0x66FFFFFF), RoundedCornerShape(14.dp))
                    .pointerInput(artist.id) { detectTapGestures(onTap = { currentRefresh(artist) }) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    val canOpen = state != null && state.artworks.isNotEmpty()
    BasicText(
        "Ouvrir la frise  ›",
        style = TextStyle(color = if (canOpen) Ink else Color(0xFFB0B6BF), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (canOpen) Gold else Color(0x55FFFFFF))
            .pointerInput(artist.id, canOpen) { detectTapGestures(onTap = { if (canOpen) currentTap(artist) }) }
            .padding(vertical = 10.dp),
    )
}

@Composable
private fun SourceLine(r: SourceReport) {
    val color = when (r.state) {
        SourceState.CONNECTED, SourceState.CACHED, SourceState.PARTIAL -> Color(0xFF4CC38A)
        SourceState.REJECTED, SourceState.UNREACHABLE -> Color(0xFFE5645A)
        SourceState.PENDING -> Color(0xFFE3B65A)
        SourceState.LIMITED -> Color(0xFFE59A3B)
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
