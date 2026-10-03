package com.vangoghtimeline.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.model.Artist
import com.vangoghtimeline.model.SearchHistory
import com.vangoghtimeline.model.WorkSearch
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Racine : le menu des artistes, puis la frise de l'artiste choisi.
 *
 * - **Premier toucher** sur un portrait : l'artiste est sélectionné, ses informations s'affichent, et la connexion de ses sources
 *   (recherche + validation de l'accès IIIF) démarre en arrière-plan ([AppModel.prepare]).
 * - **Toucher sur l'artiste déjà sélectionné** : son univers s'ouvre dans la frise (s'il en a un).
 * - **Retour système** depuis la frise : retour au menu (le retour d'une œuvre ouverte reste le dézoom, voir [TimelineHost]).
 */
@Composable
fun AppRoot(artists: List<Artist>, model: AppModel, reportHeader: () -> String = { "" }, lastCrash: () -> String? = { null }) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val copyReport: () -> Unit = {
        val text = model.buildReport(artists, reportHeader(), lastCrash())
        clipboard.setText(AnnotatedString(text))
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        Toast.makeText(context, "Rapport d'anomalies copié (${Diag.snapshot().size} lignes)", Toast.LENGTH_SHORT).show()
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var level by rememberSaveable { mutableStateOf(1) }          // niveau de détail, partagé entre le menu et la vue détaillée

    LaunchedEffect(artists) { model.loadPortraits(artists); model.loadStored(artists) }

    // ── recherche transversale : recherches conservées sur l'appareil (searches.json) ──
    val searchFile = remember { java.io.File(context.filesDir, "searches.json") }
    var history by remember { mutableStateOf(SearchHistory.decode(runCatching { searchFile.readText() }.getOrNull())) }
    fun saveHistory(h: List<String>) { history = h; runCatching { searchFile.writeText(SearchHistory.encode(h)) } }
    var resultsQuery by rememberSaveable { mutableStateOf<String?>(null) }

    val opened = artists.firstOrNull { it.id == openedId }
    val searching = resultsQuery
    if (opened == null && searching != null) {
        BackHandler { resultsQuery = null }
        // relu à chaque œuvre qui arrive (les univers chargés ou lus sur disque)
        val known = model.knownWorks(artists)
        val hits = remember(searching, known.values.sumOf { it.size }) { WorkSearch.search(searching, known) }
        val artistById = remember(hits) { hits.associate { it.artwork.id to it.artist } }
        val missingCount = model.missing(artists).size
        var loadingMissing by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color(0xFF0F1114))) {
            if (hits.isEmpty()) Message("Aucune œuvre pour « $searching ».\n(${artists.count { it.hasUniverse } - missingCount} artistes sur ${artists.count { it.hasUniverse }} consultés)\n\n(retour : geste système)")
            else key(searching) {
                TimelineHost(
                    hits.map { it.artwork }, credit = null, title = "Recherche : « $searching » · ${hits.size} œuvres · ${hits.map { it.artist.id }.toSet().size} peintres",
                    artistFor = { artistById[it.id] }, level = level, onLevel = { level = it },
                )
            }
            if (missingCount > 0) {
                BasicText(
                    if (loadingMissing) "Chargement des $missingCount artistes manquants…" else "$missingCount artistes pas encore chargés — toucher pour les ajouter à la recherche",
                    style = TextStyle(color = Color(0xFFF0D58A), fontSize = 12.sp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 10.dp)
                        .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .pointerInput(missingCount) { androidx.compose.foundation.gestures.detectTapGestures(onTap = { if (!loadingMissing) { loadingMissing = true; model.loadMissing(artists) { } } }) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    } else if (opened == null) {
        ArtistMenuScreen(
            artists = artists,
            selectedId = selectedId,
            model = model,
            onReportLongPress = copyReport,
            onRefresh = { model.refresh(it) },
            level = level, onLevel = { level = it },
            history = history,
            onSearchWorks = { q -> if (q.isNotBlank()) { saveHistory(SearchHistory.add(history, q)); resultsQuery = q.trim() } },
            onForgetSearch = { saveHistory(SearchHistory.remove(history, it)) },
            onTap = { artist ->
                if (selectedId == artist.id && artist.hasUniverse) {
                    model.prepare(artist)
                    Diag.context = artist.id     // les événements de navigation (vignettes, tuiles) sont attribués à cet artiste
                    openedId = artist.id
                } else {
                    selectedId = artist.id
                    model.prepare(artist)       // préchauffage : connexion et validation des sources pendant la lecture des infos
                }
            },
        )
    } else {
        BackHandler { openedId = null; Diag.context = null }
        val state = model.universes[opened.id]
        Box(Modifier.fillMaxSize().background(Color(0xFF0F1114))) {
            when {
                state != null && state.artworks.isNotEmpty() ->
                    key(opened.id) { TimelineHost(state.artworks, credit = state.credit, artistName = opened.name, artistLife = opened.lifespan, artist = opened, level = level, onLevel = { level = it }) }
                state != null && state.done -> Message(
                    "Aucune œuvre de ${opened.name} n'a pu être connectée.\n" +
                        state.reports.joinToString("\n") { "${it.name} : ${it.detail}" } + "\n\n(retour : geste système)",
                )
                else -> Message("Connexion aux musées…")
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(text, Modifier.padding(24.dp), style = TextStyle(color = Color(0xFFC9D0D8), fontSize = 14.sp))
    }
}
