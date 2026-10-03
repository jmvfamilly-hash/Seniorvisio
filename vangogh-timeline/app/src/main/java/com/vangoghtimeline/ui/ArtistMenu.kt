package com.vangoghtimeline.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import coil.size.Size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import com.vangoghtimeline.model.careerLines
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
 * Menu de sélection d'un artiste (maquette « Chronologie des Impressionnistes »), IDENTIQUE en portrait et en paysage :
 *
 * - en fond plein écran, UN tableau majeur de l'artiste sélectionné (image embarquée dans l'APK, voir [BackdropIndex]), placé pour que le regard
 *   ou le visage — à défaut un arbre — tombe sur une ligne des tiers en largeur et en hauteur ([ThirdsFit]) ;
 * - en haut, la recherche transparente et le tri ; au milieu, la fiche de l'artiste (style, lieu, période, sources) ;
 * - en bas au centre, les portraits sur un **demi-cercle** (glisser horizontalement pour les faire défiler, le portrait central est le plus grand).
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
    /** Niveau de détail (1 nom et dates, 2 détails, 3 contenu étendu), partagé avec la vue détaillée d'une œuvre. */
    level: Int = 1,
    onLevel: (Int) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val sort = SortMode.PERIOD
    val rows = remember(artists, query, sort) { menuRows(artists, query, sort) }
    val items = remember(rows) { rows.filterIsInstance<MenuRow.Item>().map { it.artist } }
    val selected = artists.firstOrNull { it.id == selectedId }
    val backdrops = rememberBackdrops()
    // fond : l'artiste sélectionné, à défaut le premier de la liste (le menu n'est jamais « vide »)
    val backdrop = backdrops.of((selected ?: items.firstOrNull() ?: artists.firstOrNull())?.id)

    BoxWithConstraints(modifier.fillMaxSize().background(Ink)) {
        // lus ici : les lambdas imbriquées (Row, Column…) ont leur propre récepteur et n'ont plus accès à maxWidth / maxHeight
        val maxW = maxWidth
        val maxH = maxHeight
        val landscape = maxW > maxH
        val density = LocalDensity.current
        val screenW = with(density) { maxW.toPx() }
        val screenH = with(density) { maxH.toPx() }

        // ── fond : un tableau, regard / visage / arbre sur la règle des tiers, fondu entre deux artistes ──
        Crossfade(targetState = backdrop, animationSpec = tween(700), label = "fond") { b ->
            Box(Modifier.fillMaxSize()) {
                BackdropImage(b, backdrops.embedded, screenW, screenH)
                // voile : le texte reste lisible quelle que soit la clarté du tableau
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x22000000), Color(0xB3000000)))))
            }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            SearchBar(query) { query = it }
            Spacer(Modifier.height(8.dp))
            // le niveau 3 n'existe que si l'artiste a un univers
            val maxLevel = if (selected?.hasUniverse == true) 3 else 2
            val shown = level.coerceAtMost(maxLevel)
            if (!landscape) {
                // portrait : fiche au milieu ; demi-cercle centré en bas, curseur au centre du cercle
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtistPanel(selected, backdrop, model, level, onTap, onRefresh, onReportLongPress, Modifier.widthIn(max = 560.dp))
                }
                CaptionBar(backdrop)
                ArcPicker(items, selectedId, model.portraits, onTap, landscape, maxW, Modifier.fillMaxWidth())
                DetailSlider(shown, maxLevel) { onLevel(it) }
            } else {
                // paysage : la fiche à gauche ; à droite le demi-cercle de portraits et, tout à droite, le curseur de niveau
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                        ArtistPanel(selected, backdrop, model, level, onTap, onRefresh, onReportLongPress, Modifier.fillMaxWidth())
                        CaptionBar(backdrop)
                    }
                    SideArcPicker(items, selectedId, model.portraits, onTap, Modifier.width(150.dp).fillMaxHeight())
                    VerticalDetailSlider(shown, maxLevel, Modifier.width(38.dp).fillMaxHeight()) { onLevel(it) }
                }
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

/**
 * Portraits sur un DEMI-CERCLE, centré en bas de l'écran (identique en portrait et en paysage) : le portrait du centre est le plus haut et le plus
 * grand, les autres redescendent de part et d'autre. Glisser horizontalement les fait défiler ; toucher un portrait le sélectionne et le ramène
 * au centre. Les titres de groupe du tri ne sont pas affichés ici (l'ordre, lui, suit le tri choisi).
 */
@Composable
private fun ArcPicker(
    items: List<Artist>, selectedId: String?, portraits: Map<String, String>, onTap: (Artist) -> Unit,
    landscape: Boolean, screenWidth: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(0f) }                  // position (en nombre de portraits) de celui qui est au centre de l'arc
    val selectedIndex = items.indexOfFirst { it.id == selectedId }
    LaunchedEffect(selectedIndex, items.size) { if (selectedIndex >= 0) pos.animateTo(selectedIndex.toFloat(), tween(350)) }
    val density = LocalDensity.current
    val chip = 60.dp
    val radiusPx = with(density) { (if (landscape) 170.dp else minOf(screenWidth * 0.6f, 300.dp)).toPx() }
    val chipPx = with(density) { chip.toPx() }
    val maxTheta = 0.95f                                    // l'arc va de -54° à +54° autour du centre
    val stepRad = (chipPx * 1.15f) / radiusPx               // angle entre deux portraits voisins
    val arcLenPx = radiusPx * stepRad
    val drop = radiusPx * (1f - cos(maxTheta))
    val height = with(density) { (drop + chipPx * 1.3f + 30.dp.toPx()).toDp() }
    BoxWithConstraints(modifier.height(height)) {
        val wPx = with(density) { maxWidth.toPx() }
        Box(
            Modifier.fillMaxSize().pointerInput(items.size) {
                detectHorizontalDragGestures(
                    onDragEnd = { scope.launch { pos.animateTo(pos.value.roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0)).toFloat(), tween(250)) } },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        scope.launch { pos.snapTo((pos.value - dx / arcLenPx).coerceIn(-0.4f, items.size - 0.6f)) }
                    },
                )
            },
        ) {
            items.forEachIndexed { i, artist ->
                val rel = i - pos.value
                val theta = rel * stepRad
                if (abs(theta) <= maxTheta + stepRad * 0.5f) {
                    val cx = wPx / 2f + radiusPx * sin(theta)
                    val top = chipPx * 0.2f + radiusPx * (1f - cos(theta))
                    val scale = (1.3f - 0.42f * abs(theta) / maxTheta).coerceAtLeast(0.8f)
                    val selectedNow = artist.id == selectedId
                    Column(
                        Modifier
                            .offset { IntOffset((cx - chipPx / 2f).roundToInt(), top.roundToInt()) }
                            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = (1.1f - abs(theta) / (maxTheta + stepRad)).coerceIn(0.35f, 1f) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PortraitChip(artist, selectedNow, portraits[artist.id], chip, onTap = { onTap(artist) })
                        BasicText(
                            artist.name.substringAfterLast(' '), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = if (selectedNow) Gold else Color.White, fontSize = 9.sp, textAlign = TextAlign.Center),
                            modifier = Modifier.width(chip + 6.dp).padding(top = 2.dp),
                        )
                    }
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
        (backdrop?.credit?.plus("   ·   ") ?: "") + "appui long sur la fiche : rapport d'anomalies",
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
    artist: Artist?, backdrop: Backdrop?, model: AppModel, level: Int,
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
        // niveau 1 : seulement le nom et les dates
        val shown = if (artist.hasUniverse) level else level.coerceAtMost(2)
        if (shown <= 1) {
            artist.lifespan?.let { BasicText(it, style = TextStyle(color = Color(0xFFEFE6D0), fontSize = 16.sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center)) }
            return@Column
        }
        // le tableau montré en fond (et sa date), comme « Juin 1888 » dans la maquette
        val shownWork = backdrop?.takeIf { it.artistId == artist.id }
        BasicText(
            shownWork?.let { "${it.title}, ${it.date}" } ?: (artist.lifespan ?: ""),
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
        if (shown >= 3) {
            // niveau 3 : contenu étendu (origine, œuvre emblématique, sources détaillées)
            InfoRow("Origine", artist.origin)
            InfoRow("Œuvre emblématique", artist.emblematicWork)
            careerLines(artist).takeIf { it.isNotEmpty() }?.let { lines ->
                BasicText("Parcours", style = TextStyle(color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 6.dp))
                for (l in lines) BasicText(l, style = TextStyle(color = Color(0xFFF1F3F5), fontSize = 12.sp), modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
            }
        }
        UniverseSection(artist, model, onTap, onRefresh, expanded = shown >= 3)
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
private fun UniverseSection(artist: Artist, model: AppModel, onTap: (Artist) -> Unit, onRefresh: (Artist) -> Unit, expanded: Boolean = false) {
    var detailsToggle by rememberSaveable(artist.id) { mutableStateOf(false) }
    val details = detailsToggle || expanded
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
        modifier = Modifier.pointerInput(artist.id) { detectTapGestures(onTap = { detailsToggle = !detailsToggle }) }.padding(vertical = 4.dp),
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

/** Curseur transparent à 1 à 3 positions (nom et dates / détails / contenu étendu) ; toucher ou glisser. Les positions au-delà de [max] sont grisées. */
@Composable
internal fun DetailSlider(level: Int, max: Int, onLevel: (Int) -> Unit) {
    val currentLevel by rememberUpdatedState(onLevel)
    val density = LocalDensity.current
    val widthDp = 220.dp
    val widthPx = with(density) { widthDp.toPx() }
    fun levelAt(x: Float) = (((x / widthPx) * 3f).toInt() + 1).coerceIn(1, max)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(widthDp).height(34.dp)
                .pointerInput(max) {
                    detectTapGestures(onTap = { currentLevel(levelAt(it.x)) })
                }
                .pointerInput(max) {
                    detectHorizontalDragGestures(onHorizontalDrag = { change, _ -> change.consume(); currentLevel(levelAt(change.position.x)) })
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2f
                val xs = listOf(0.1667f, 0.5f, 0.8333f).map { it * size.width }
                drawLine(Color(0x55FFFFFF), Offset(xs[0], y), Offset(xs[2], y), strokeWidth = 3f, cap = StrokeCap.Round)
                xs.forEachIndexed { i, x -> drawCircle(if (i + 1 <= max) Color(0x99FFFFFF) else Color(0x33FFFFFF), radius = 5f, center = Offset(x, y)) }
                drawCircle(Color(0xCCF0D58A), radius = 12f, center = Offset(xs[level - 1], y))
                drawCircle(Color(0x66FFFFFF), radius = 12f, center = Offset(xs[level - 1], y), style = Stroke(width = 2f))
            }
        }
        BasicText(
            when (level) { 1 -> "Nom et dates"; 2 -> "Détails"; else -> "Contenu étendu" },
            style = TextStyle(color = Color(0xB3FFFFFF), fontSize = 10.sp),
        )
    }
}

/**
 * Portrait : les portraits sur un DEMI-CERCLE le long du bord droit (le portrait central, le plus grand, est le plus à gauche ; les autres s'écartent
 * vers la droite). Glisser verticalement les fait défiler ; toucher un portrait le sélectionne et le ramène au centre.
 */
@Composable
private fun SideArcPicker(items: List<Artist>, selectedId: String?, portraits: Map<String, String>, onTap: (Artist) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(0f) }
    val selectedIndex = items.indexOfFirst { it.id == selectedId }
    LaunchedEffect(selectedIndex, items.size) { if (selectedIndex >= 0) pos.animateTo(selectedIndex.toFloat(), tween(350)) }
    val density = LocalDensity.current
    val chip = 52.dp
    val chipPx = with(density) { chip.toPx() }
    val radiusPx = with(density) { 200.dp.toPx() }
    val step = (chipPx * 1.2f) / radiusPx
    val maxTheta = 0.95f
    val arcLenPx = radiusPx * step
    BoxWithConstraints(modifier) {
        val hPx = with(density) { maxHeight.toPx() }
        Box(
            Modifier.fillMaxSize().pointerInput(items.size) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { pos.animateTo(pos.value.roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0)).toFloat(), tween(250)) } },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        scope.launch { pos.snapTo((pos.value - dy / arcLenPx).coerceIn(-0.4f, items.size - 0.6f)) }
                    },
                )
            },
        ) {
            items.forEachIndexed { i, artist ->
                val rel = i - pos.value
                val theta = rel * step
                if (abs(theta) <= maxTheta + step * 0.5f) {
                    val cx = chipPx * 0.4f + radiusPx * (1f - cos(theta))
                    val cy = hPx / 2f + radiusPx * sin(theta)
                    val scale = (1.3f - 0.42f * abs(theta) / maxTheta).coerceAtLeast(0.8f)
                    val on = artist.id == selectedId
                    Column(
                        Modifier
                            .offset { IntOffset(cx.roundToInt(), (cy - chipPx / 2f).roundToInt()) }
                            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = (1.1f - abs(theta) / (maxTheta + step)).coerceIn(0.35f, 1f) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PortraitChip(artist, on, portraits[artist.id], chip, onTap = { onTap(artist) })
                        BasicText(
                            artist.name.substringAfterLast(' '), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = if (on) Gold else Color.White, fontSize = 9.sp, textAlign = TextAlign.Center),
                            modifier = Modifier.width(chip + 6.dp).padding(top = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Curseur de niveau vertical (portrait), transparent : niveau 1 en haut, 3 en bas ; toucher ou glisser. */
@Composable
internal fun VerticalDetailSlider(level: Int, max: Int, modifier: Modifier = Modifier, onLevel: (Int) -> Unit) {
    val currentLevel by rememberUpdatedState(onLevel)
    val heightDp = 170.dp
    val heightPx = with(LocalDensity.current) { heightDp.toPx() }
    fun levelAt(y: Float) = (((y / heightPx) * 3f).toInt() + 1).coerceIn(1, max)
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("Niv.", style = TextStyle(color = Color(0xB3FFFFFF), fontSize = 9.sp))
        Box(
            Modifier.width(34.dp).height(heightDp)
                .pointerInput(max) { detectTapGestures(onTap = { currentLevel(levelAt(it.y)) }) }
                .pointerInput(max) { detectVerticalDragGestures(onVerticalDrag = { change, _ -> change.consume(); currentLevel(levelAt(change.position.y)) }) },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                val ys = listOf(0.1667f, 0.5f, 0.8333f).map { it * size.height }
                drawLine(Color(0x55FFFFFF), Offset(x, ys[0]), Offset(x, ys[2]), strokeWidth = 3f, cap = StrokeCap.Round)
                ys.forEachIndexed { i, y -> drawCircle(if (i + 1 <= max) Color(0x99FFFFFF) else Color(0x33FFFFFF), radius = 5f, center = Offset(x, y)) }
                drawCircle(Color(0xCCF0D58A), radius = 12f, center = Offset(x, ys[level - 1]))
                drawCircle(Color(0x66FFFFFF), radius = 12f, center = Offset(x, ys[level - 1]), style = Stroke(width = 2f))
            }
        }
        BasicText(level.toString(), style = TextStyle(color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold))
    }
}
