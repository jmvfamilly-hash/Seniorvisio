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

    LaunchedEffect(artists) { model.loadPortraits(artists) }

    val opened = artists.firstOrNull { it.id == openedId }
    if (opened == null) {
        ArtistMenuScreen(
            artists = artists,
            selectedId = selectedId,
            model = model,
            onReportLongPress = copyReport,
            onRefresh = { model.refresh(it) },
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
                    key(opened.id) { TimelineHost(state.artworks, credit = state.credit, artistName = opened.name, artistLife = opened.lifespan) }
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
