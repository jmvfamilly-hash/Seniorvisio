package com.vangoghtimeline.ui

import android.content.Context
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.layout.onGloballyPositioned
import com.vangoghtimeline.model.formatFr
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.MapCluster
import com.vangoghtimeline.model.MuseumMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.float
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val WORLD = 1000f                 // le monde de Mercator, en unités du tracé (carré WORLD × WORLD)
private val Ocean = Color(0xFF14202B)
private val Land = Color(0xFF2B3138)
private val Coast = Color(0xFF4A5560)
private val Gold = Color(0xFFF0D58A)

/** Le contour des terres (Natural Earth 1:50m), lu une fois et gardé : le tracé ne dépend pas du zoom. */
private object LandShape {
    @Volatile var path: Path? = null

    fun load(context: Context): Path? = path ?: runCatching {
        val root = Json.parseToJsonElement(context.assets.open("map/land.json").bufferedReader().use { it.readText() }).jsonObject
        val p = Path()
        for (ring in root.getValue("rings").jsonArray) {
            val c = ring.jsonArray
            var k = 0
            while (k + 1 < c.size) {
                val (x, y) = MuseumMap.project(c[k + 1].jsonPrimitive.float.toDouble(), c[k].jsonPrimitive.float.toDouble())
                if (k == 0) p.moveTo(x.toFloat() * WORLD, y.toFloat() * WORLD) else p.lineTo(x.toFloat() * WORLD, y.toFloat() * WORLD)
                k += 2
            }
            p.close()
        }
        p
    }.getOrNull().also { path = it }
}

/** Repères proches à l'écran fusionnés : un groupe = un ou plusieurs lieux. */
private class MarkerGroup(val clusters: MutableList<MapCluster>, var x: Float, var y: Float) {
    val count: Int get() = clusters.sumOf { it.artworks.size }
}

/**
 * Vue CARTE : les œuvres posées sur une carte du monde, au lieu où elles sont conservées (musée de la source, ou collection de la fiche).
 * Pincer pour zoomer, glisser pour se déplacer. Un repère montre la vignette d'une œuvre du lieu et leur nombre ; plusieurs lieux trop proches
 * à l'écran sont regroupés (toucher le groupe zoome dessus). Toucher un lieu ouvre sa fiche en bas : toutes ses œuvres, que l'on ouvre comme
 * dans la frise. Les œuvres sans lieu connu sont accessibles par la puce « lieu inconnu ».
 */
@Composable
fun MapScreen(
    artworks: List<Artwork>,
    onArtworkTap: (OpenRequest) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    artistNameOf: ((Artwork) -> String?)? = null,
    /** Une œuvre est ouverte dans la visionneuse : le parcours plein écran se met en attente et reprend à sa fermeture. */
    viewerOpen: Boolean = false,
    /** « ▶ Lecture » d'un lieu : le parcours de ses œuvres dans la visionneuse. */
    onPlayTour: ((List<Artwork>, Int) -> Unit)? = null,
    /** Sous l'en-tête : les filtres, communs à la frise et à la carte ; [artworks] est déjà filtrée. */
    filterBar: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var land by remember { mutableStateOf(LandShape.path) }
    LaunchedEffect(Unit) { if (land == null) land = withContext(Dispatchers.Default) { LandShape.load(context) } }
    val (clusters, unknown) = remember(artworks) { MuseumMap.clusters(artworks) }
    var selected by remember(artworks) { mutableStateOf<Pair<String, List<Artwork>>?>(null) }

    Column(modifier.fillMaxSize().background(Ocean)) {
        Row(Modifier.fillMaxWidth().background(Color(0xB314171B)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                title ?: "Carte des lieux de conservation", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color(0xFFE6E9ED), fontSize = 15.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f),
            )
            headerTrailing?.invoke()
        }
        filterBar?.invoke()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }
            // échelle = largeur du monde en pixels ; décalage = position du coin du monde à l'écran
            // vue de départ : tous les lieux à l'écran (à défaut, l'Europe)
            val fit = remember(w, h, clusters) {
                val pts = clusters.map { MuseumMap.project(it.place.lat, it.place.lon) }.ifEmpty { listOf(MuseumMap.project(62.0, -12.0), MuseumMap.project(35.0, 30.0)) }
                val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
                val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
                val dx = max(maxX - minX, 0.02) * 1.35; val dy = max(maxY - minY, 0.02) * 1.35
                val s = if (w > 0f) min(w / dx, h / dy).toFloat().coerceIn(w * 0.8f, w * 400f) else 1f
                s to Offset(w / 2f - ((minX + maxX) / 2 * s).toFloat(), h / 2f - ((minY + maxY) / 2 * s).toFloat())
            }
            // échelle = largeur du monde en pixels ; origine = position du coin du monde à l'écran
            var scale by remember(fit) { mutableStateOf(fit.first) }
            var origin by remember(fit) { mutableStateOf(fit.second) }
            Canvas(
                Modifier.fillMaxSize().pointerInput(fit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(w * 0.8f, w * 400f)
                        origin = centroid - (centroid - origin) * (ns / scale) + pan
                        scale = ns
                    }
                },
            ) {
                drawRect(Ocean)
                val p = land
                if (p != null) withTransform({ translate(origin.x, origin.y); scale(scale / WORLD, scale / WORLD, pivot = Offset.Zero) }) {
                    drawPath(p, Land)
                    drawPath(p, Coast, style = Stroke(width = WORLD / scale))
                }
            }
            // repères : regroupement glouton à l'écran (les lieux les plus fournis d'abord)
            val radius = with(density) { 56.dp.toPx() }
            val groups = ArrayList<MarkerGroup>()
            for (c in clusters) {
                val (nx, ny) = MuseumMap.project(c.place.lat, c.place.lon)
                val x = origin.x + nx.toFloat() * scale
                val y = origin.y + ny.toFloat() * scale
                val g = groups.firstOrNull { hypot(it.x - x, it.y - y) < radius }
                if (g == null) groups += MarkerGroup(mutableListOf(c), x, y) else g.clusters += c
            }
            val markerW = 76.dp; val markerH = 64.dp
            val mw = with(density) { markerW.toPx() }; val mh = with(density) { markerH.toPx() }
            for (g in groups) {
                if (g.x < -mw || g.y < -mh || g.x > w + mw || g.y > h + mh) continue
                val first = g.clusters.first()
                val label = if (g.clusters.size == 1) first.place.name else "${first.place.city} · ${g.clusters.size} lieux"
                Marker(
                    artwork = first.artworks.first(), count = g.count, label = label, stacked = g.count > 1,
                    modifier = Modifier.offset { IntOffset((g.x - mw / 2f).roundToInt(), (g.y - mh).roundToInt()) },
                    width = markerW, height = markerH,
                    onDoubleTap = {
                        val all = g.clusters.flatMap { it.artworks }.sortedBy { it.date.positionEpochDay }
                        selected = (if (g.clusters.size == 1) "${first.place.name} · ${first.place.city}" else "${first.place.city} · ${g.clusters.size} lieux") to all
                    },
                    onTap = {
                        if (g.clusters.size == 1) selected = "${first.place.name} · ${first.place.city}" to first.artworks
                        else scope.launch {
                            // groupe : on zoome dessus (×3), centré sur lui
                            val s0 = scale; val o0 = origin; val target = Offset(g.x, g.y)
                            val s1 = (s0 * 3f).coerceAtMost(w * 400f)
                            val o1 = Offset(w / 2f, h / 2f) - (target - o0) * (s1 / s0)
                            animate(0f, 1f, animationSpec = tween(380)) { v, _ -> scale = s0 + (s1 - s0) * v; origin = o0 + (o1 - o0) * v }
                        }
                    },
                )
            }
            if (land == null) BasicText("Chargement de la carte…", style = TextStyle(color = Color(0xFF8B96A3), fontSize = 13.sp), modifier = Modifier.align(Alignment.Center))
            if (clusters.isEmpty()) BasicText(
                "Aucun lieu de conservation reconnu pour ces œuvres.", style = TextStyle(color = Color(0xFFC9D0D8), fontSize = 14.sp, textAlign = TextAlign.Center),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            if (unknown.isNotEmpty()) BasicText(
                "Lieu inconnu : ${unknown.size}",
                style = TextStyle(color = Color(0xFFE6E9ED), fontSize = 12.sp),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .pointerInput(unknown.size) { detectTapGestures(onTap = { selected = "Lieu de conservation inconnu" to unknown }) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            BasicText(
                "${clusters.size} lieux · ${artworks.size - unknown.size} œuvres localisées",
                style = TextStyle(color = Color(0xFF8B96A3), fontSize = 11.sp),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
            selected?.let { (head, list) ->
                var fullscreen by remember(list) { mutableStateOf(false) }
                if (fullscreen) FullscreenSlideshow(head, list, onArtworkTap, artistNameOf, paused = viewerOpen, onStop = { fullscreen = false }, modifier = Modifier.fillMaxSize())
                else PlaceStrip(head, list, landscape = w > h, onArtworkTap, artistNameOf, onPlay = { if (onPlayTour != null) onPlayTour(list, 0) else fullscreen = true }, onClose = { selected = null }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun Marker(
    artwork: Artwork, count: Int, label: String, stacked: Boolean,
    width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp,
    modifier: Modifier, onTap: () -> Unit, onDoubleTap: () -> Unit = onTap,
) {
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val density = LocalDensity.current
    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width, height).pointerInput(artwork.id) { detectTapGestures(onTap = { tap() }, onDoubleTap = { doubleTap() }) }) {
            // une pile : deux cartons décalés derrière la vignette quand le lieu a plusieurs œuvres
            if (stacked) {
                Box(Modifier.offset(6.dp, (-6).dp).fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A4048)))
                Box(Modifier.offset(3.dp, (-3).dp).fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color(0xFF4A515A)))
            }
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).border(BorderStroke(1.5.dp, Gold), RoundedCornerShape(6.dp))) {
                val wPx = with(density) { width.roundToPx() }; val hPx = with(density) { height.roundToPx() }
                ArtworkImage(artwork, wPx, hPx)
            }
            BasicText(
                count.toString(),
                style = TextStyle(color = Color(0xFF14171B), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).background(Gold, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        BasicText(
            label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 2.dp).background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
        // la pointe du repère : le lieu exact est sous ce point
        Box(Modifier.size(6.dp).background(Gold, CircleShape))
    }
}

/**
 * Un lieu consulté : ses œuvres dans une frise d'un TIERS de la hauteur de l'écran, dans l'ordre du temps — une ligne qui défile en paysage, des
 * lignes qui reviennent à la ligne en portrait. Toucher une œuvre l'ouvre dans la visionneuse ; « ▶ Lecture » lance le parcours en plein écran.
 */
@Composable
private fun PlaceStrip(
    head: String, list: List<Artwork>, landscape: Boolean, onArtworkTap: (OpenRequest) -> Unit, artistNameOf: ((Artwork) -> String?)?,
    onPlay: () -> Unit, onClose: () -> Unit, modifier: Modifier,
) {
    val play by rememberUpdatedState(onPlay)
    val close by rememberUpdatedState(onClose)
    val density = LocalDensity.current
    Column(modifier.fillMaxWidth().fillMaxHeight(0.34f).background(Color(0xEB101317))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("$head · ${list.size} œuvres", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            BasicText(
                "▶ Lecture",
                style = TextStyle(color = Color(0xFF14171B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(end = 8.dp).background(Gold, RoundedCornerShape(12.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = { play() }) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            BasicText("✕", style = TextStyle(color = Color.White, fontSize = 16.sp),
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { close() }) }.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (landscape) {
                // paysage : une seule ligne, on la fait défiler du doigt
                val ch = maxHeight - 12.dp
                val cw = ch * 1.15f
                val wPx = with(density) { cw.roundToPx() }; val hPx = with(density) { ch.roundToPx() }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { a ->
                        Box(Modifier.size(cw, ch)) { ArtworkCard(a, wPx, hPx, artistName = artistNameOf?.invoke(a), onTap = onArtworkTap) }
                    }
                }
            } else {
                // portrait : les vignettes reviennent à la ligne ; on fait défiler les lignes
                val cw = 104.dp; val ch = 92.dp
                val wPx = with(density) { cw.roundToPx() }; val hPx = with(density) { ch.roundToPx() }
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(cw),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list.size, key = { list[it].id }) { k ->
                        val a = list[k]
                        Box(Modifier.size(cw, ch)) { ArtworkCard(a, wPx, hPx, artistName = artistNameOf?.invoke(a), onTap = onArtworkTap) }
                    }
                }
            }
        }
    }
}

/**
 * Le parcours d'un lieu en PLEIN ÉCRAN : les œuvres défilent en fondu enchaîné (une toutes les 4 s). « ❚❚ Pause » ou un pincement (zoom)
 * revient à la liste ; un toucher ouvre l'œuvre affichée dans la visionneuse de détail.
 */
@Composable
private fun FullscreenSlideshow(
    head: String, list: List<Artwork>, onArtworkTap: (OpenRequest) -> Unit, artistNameOf: ((Artwork) -> String?)?,
    paused: Boolean, onStop: () -> Unit, modifier: Modifier,
) {
    val stop by rememberUpdatedState(onStop)
    val openArtwork by rememberUpdatedState(onArtworkTap)
    var index by remember(list) { mutableStateOf(0) }
    // en attente pendant que la visionneuse est ouverte (l'œuvre reste celle qu'on a ouverte) ; reprend dès sa fermeture
    LaunchedEffect(index, list, paused) { if (!paused && list.size > 1) { kotlinx.coroutines.delay(4000); index = (index + 1) % list.size } }
    val current = list.getOrNull(index) ?: return
    val coords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(Color(0xFF0B0D10))) {
        val wPx = with(density) { maxWidth.roundToPx() }
        val hPx = with(density) { maxHeight.roundToPx() }
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { coords[0] = it }
                .pointerInput(current.id) {
                    detectTapGestures(onTap = {
                        val c = coords[0]?.takeIf { it.isAttached } ?: return@detectTapGestures
                        val bounds = androidx.compose.ui.geometry.Rect(c.localToRoot(Offset.Zero), androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat()))
                        openArtwork(OpenRequest(current, bounds, wPx, hPx))
                    })
                }
                // pincer : retour à la liste
                .pointerInput(Unit) {
                    var fired = false
                    detectTransformGestures { _, _, zoom, _ -> if (!fired && zoom != 1f) { fired = true; stop() } }
                },
        ) {
            androidx.compose.animation.Crossfade(targetState = current, animationSpec = tween(900), label = "fondu") { a ->
                coil.compose.AsyncImage(
                    model = a.iiif.thumbnailUrlFor(wPx, hPx)?.let { thumbRequest(androidx.compose.ui.platform.LocalContext.current, a, it, wPx, hPx) },
                    contentDescription = a.title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("$head · ${index + 1}/${list.size}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            BasicText(
                "❚❚ Pause",
                style = TextStyle(color = Color(0xFF14171B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.background(Gold, RoundedCornerShape(12.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = { stop() }) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            artistNameOf?.invoke(current)?.let { BasicText(it, style = TextStyle(color = Gold, fontSize = 11.sp)) }
            BasicText(current.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            BasicText(current.date.formatFr() + (current.place?.let { " · $it" } ?: ""), style = TextStyle(color = Color(0xFFD9D3BF), fontSize = 12.sp))
            BasicText("Toucher : ouvrir l'œuvre · pincer : revenir à la liste", style = TextStyle(color = Color(0xFF8B96A3), fontSize = 11.sp), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Bascule Frise / Carte, dans l'en-tête des deux vues. */
@Composable
fun ViewModeToggle(mapMode: Boolean, onChange: (Boolean) -> Unit) {
    val change by rememberUpdatedState(onChange)
    Row(Modifier.clip(RoundedCornerShape(10.dp)).border(BorderStroke(1.dp, Color(0x40FFFFFF)), RoundedCornerShape(10.dp))) {
        for ((label, isMap) in listOf("Frise" to false, "Carte" to true)) {
            val on = mapMode == isMap
            BasicText(
                label,
                style = TextStyle(color = if (on) Color(0xFF14171B) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.background(if (on) Gold else Color.Transparent)
                    .pointerInput(isMap) { detectTapGestures(onTap = { change(isMap) }) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
