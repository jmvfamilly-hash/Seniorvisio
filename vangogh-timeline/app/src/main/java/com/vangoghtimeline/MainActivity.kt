package com.vangoghtimeline

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vangoghtimeline.iiif.CollectionLoader
import com.vangoghtimeline.iiif.HttpManifestSource
import com.vangoghtimeline.iiif.ManifestRepository
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.ui.TimelineHost

/**
 * Frise chronologique des œuvres de Van Gogh.
 *
 * Sans argument : jeu de démonstration hors ligne. Pour charger une vraie collection IIIF (Presentation 3, `items` = manifestes) :
 *   adb shell am start -n com.vangoghtimeline/.MainActivity -d "https://serveur/iiif/collection/vangogh.json"
 *
 * Si l'appli s'est fermée au lancement précédent, le journal de plantage s'affiche d'abord (voir [CrashReporter]).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val collectionUrl = intent?.dataString
        val lastCrash = CrashReporter.read(this)
        setContent {
            var crash by remember { mutableStateOf(lastCrash) }
            val trace = crash
            if (trace != null) {
                CrashScreen(
                    trace = trace,
                    onShare = { shareText(trace) },
                    onClose = { CrashReporter.clear(this); crash = null },
                )
            } else {
                Timeline(collectionUrl)
            }
        }
    }

    @Composable
    private fun Timeline(collectionUrl: String?) {
        val state by produceState<Load>(Load.Busy, collectionUrl) {
            value = if (collectionUrl == null) {
                // Par défaut : les œuvres de Van Gogh de trois musées (Art Institute of Chicago, Rijksmuseum, Europeana), chargées en
                // parallèle : la frise s'affiche dès la première réponse, les autres musées s'y ajoutent.
                val result = CollectionLoader(HttpManifestSource(), filesDir).load { value = Load.Done(it.artworks, it.credit) }
                if (result != null) Load.Done(result.artworks, result.credit)
                else Load.Done(SampleArtworks.all, "Hors ligne : œuvres de démonstration, sans images")
            } else try {
                val arts = ManifestRepository(HttpManifestSource()).loadCollection(collectionUrl)
                if (arts.isEmpty()) Load.Failed("Aucune œuvre datée dans cette collection.") else Load.Done(arts, collectionUrl)
            } catch (e: Exception) {
                Load.Failed("Impossible de lire la collection : ${e.message ?: e.javaClass.simpleName}")
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF0F1114))) {
            when (val s = state) {
                Load.Busy -> Message("Chargement des manifestes…")
                is Load.Failed -> Message(s.reason)
                is Load.Done -> TimelineHost(s.artworks, credit = s.credit)
            }
        }
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Journal de plantage — Van Gogh, frise")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Envoyer le journal de plantage"))
    }

    private sealed interface Load {
        data object Busy : Load
        data class Failed(val reason: String) : Load
        data class Done(val artworks: List<Artwork>, val credit: String) : Load
    }
}

@Composable
private fun BoxScope.Message(text: String) {
    BasicText(text, Modifier.align(Alignment.Center), style = TextStyle(color = Color(0xFFB0B6BF), fontSize = 15.sp))
}

/** Écran affiché après un plantage : la trace, à partager ; puis on repart sur la frise. */
@Composable
private fun CrashScreen(trace: String, onShare: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0F1114)).padding(16.dp)) {
        BasicText("L'application s'est fermée la dernière fois.", style = TextStyle(color = Color.White, fontSize = 17.sp))
        BasicText(
            "Partage ce journal pour que la cause soit corrigée, puis ouvre la frise.",
            style = TextStyle(color = Color(0xFFB0B6BF), fontSize = 13.sp),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CrashButton("Partager le journal", filled = true, onClick = onShare)
            CrashButton("Ouvrir la frise", filled = false, onClick = onClose)
        }
        Spacer(Modifier.height(12.dp))
        BasicText(
            trace,
            style = TextStyle(color = Color(0xFFE6B8B0), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun CrashButton(label: String, filled: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = if (filled) Color(0xFF0F1114) else Color.White, fontSize = 14.sp),
        modifier = Modifier
            .background(if (filled) Color(0xFFD9A521) else Color(0xFF2A2F36), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
