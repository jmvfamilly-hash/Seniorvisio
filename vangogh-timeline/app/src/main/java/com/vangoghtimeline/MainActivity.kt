package com.vangoghtimeline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.vangoghtimeline.iiif.HttpManifestSource
import com.vangoghtimeline.iiif.ManifestRepository
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.ui.TimelineScreen

/**
 * Frise chronologique des œuvres de Van Gogh.
 *
 * Sans argument : jeu de démonstration hors ligne. Pour charger une vraie collection IIIF (Presentation 3, `items` = manifestes) :
 *   adb shell am start -n com.vangoghtimeline/.MainActivity -d "https://serveur/iiif/collection/vangogh.json"
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val collectionUrl = intent?.dataString
        setContent {
            val state by produceState<Load>(Load.Busy, collectionUrl) {
                value = if (collectionUrl == null) Load.Done(SampleArtworks.all) else try {
                    val arts = ManifestRepository(HttpManifestSource()).loadCollection(collectionUrl)
                    if (arts.isEmpty()) Load.Failed("Aucune œuvre datée dans cette collection.") else Load.Done(arts)
                } catch (e: Exception) {
                    Load.Failed("Impossible de lire la collection : ${e.message ?: e.javaClass.simpleName}")
                }
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF0F1114))) {
                when (val s = state) {
                    Load.Busy -> Message("Chargement des manifestes…")
                    is Load.Failed -> Message(s.reason)
                    is Load.Done -> TimelineScreen(s.artworks)
                }
            }
        }
    }

    private sealed interface Load {
        data object Busy : Load
        data class Failed(val reason: String) : Load
        data class Done(val artworks: List<Artwork>) : Load
    }
}

@androidx.compose.runtime.Composable
private fun androidx.compose.foundation.layout.BoxScope.Message(text: String) {
    BasicText(text, Modifier.align(Alignment.Center), style = TextStyle(color = Color(0xFFB0B6BF), fontSize = 15.sp))
}
