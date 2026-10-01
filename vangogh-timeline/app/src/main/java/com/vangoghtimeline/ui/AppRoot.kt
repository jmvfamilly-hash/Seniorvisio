package com.vangoghtimeline.ui

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vangoghtimeline.iiif.SourceState
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
fun AppRoot(artists: List<Artist>, model: AppModel) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(artists) { model.loadPortraits(artists) }

    val opened = artists.firstOrNull { it.id == openedId }
    if (opened == null) {
        ArtistMenuScreen(
            artists = artists,
            selectedId = selectedId,
            model = model,
            onTap = { artist ->
                if (selectedId == artist.id && artist.hasUniverse) {
                    model.prepare(artist)
                    openedId = artist.id
                } else {
                    selectedId = artist.id
                    model.prepare(artist)       // préchauffage : connexion et validation des sources pendant la lecture des infos
                }
            },
        )
    } else {
        BackHandler { openedId = null }
        val state = model.universes[opened.id]
        Box(Modifier.fillMaxSize().background(Color(0xFF0F1114))) {
            when {
                state != null && state.artworks.isNotEmpty() ->
                    key(opened.id) { TimelineHost(state.artworks, credit = state.credit) }
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
