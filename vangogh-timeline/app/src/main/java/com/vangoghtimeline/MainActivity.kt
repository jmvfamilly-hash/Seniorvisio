package com.vangoghtimeline

import android.content.Intent
import java.io.File
import android.os.Build
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vangoghtimeline.iiif.HttpImageReachability
import com.vangoghtimeline.iiif.PLAIN_USER_AGENT
import com.vangoghtimeline.iiif.RateLimiter
import com.vangoghtimeline.iiif.RetryingSource
import com.vangoghtimeline.iiif.SourceValidator
import com.vangoghtimeline.iiif.UniverseLoader
import com.vangoghtimeline.iiif.defaultMuseumSources
import com.vangoghtimeline.model.ArtistCatalog
import com.vangoghtimeline.ui.AppModel
import com.vangoghtimeline.ui.AppRoot
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
                if (collectionUrl != null) Timeline(collectionUrl) else Root()
            }
        }
    }

    /** Menu des artistes puis frise de l'artiste choisi (voir [AppRoot]). */
    @Composable
    private fun Root() {
        val scope = rememberCoroutineScope()
        val artists = remember {
            runCatching { ArtistCatalog.parse(assets.open("artists_by_movement.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())
        }
        val model = remember {
            val http = HttpManifestSource()
            val plainHttp = HttpManifestSource(PLAIN_USER_AGENT)
            // Tout le trafic vers le Met passe par UNE file commune (2 requêtes à la fois, 150 ms d'écart) et est retenté après un blocage
            // temporaire : plusieurs artistes chargés ensemble ne déclenchent plus le pare-feu anti-robot du Met.
            val metLimiter = RateLimiter(parallel = 1, minGapMs = 400)
            // Wikidata et Commons limitent le débit (429) : une file commune plus lente, et des nouveaux essais plus patients
            val wikiLimiter = RateLimiter(parallel = 2, minGapMs = 400)
            val wikiRetries = listOf(2_000L, 5_000L, 10_000L)
            val notices = File(filesDir, "met_notices")
            val wikimediaCache = File(filesDir, "wikimedia_cache")      // pages SPARQL et licences, reprises d'un passage à l'autre
            // National Gallery of Art : fichiers extraits de son open data (assets/nga/), lus sans réseau
            val ngaAsset: (String) -> String? = { name -> runCatching { assets.open("nga/$name.json").bufferedReader().use { it.readText() } }.getOrNull() }
            val loader = UniverseLoader(
                defaultMuseumSources(http, RetryingSource(http, metLimiter, "metmuseum.org"), notices, RetryingSource(http, wikiLimiter, "wiki", wikiRetries), wikimediaCache = wikimediaCache, ngaAsset = ngaAsset),
                SourceValidator(http, HttpImageReachability()),
                File(filesDir, "universe_store").apply { mkdirs() },     // magasin JSON local : un fichier par artiste
                // repli : mêmes sources avec un User-Agent sobre, si un serveur refuse celui d'un navigateur
                fallbackSources = defaultMuseumSources(plainHttp, RetryingSource(plainHttp, metLimiter, "metmuseum.org"), notices, RetryingSource(plainHttp, wikiLimiter, "wiki", wikiRetries), wikimediaCache = wikimediaCache, ngaAsset = ngaAsset),
            )
            AppModel(scope, loader, http, File(filesDir, "portraits.json"))
        }
        if (artists.isEmpty()) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0F1114))) { Message("Catalogue des artistes illisible.") }
        } else {
            val header = {
                "Version ${BuildConfig.BUILD_REV}\nAppareil : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
                    "\nCache de tuiles : ${(application as TimelineApp).tileCache.stats()}"
            }
            AppRoot(artists, model, reportHeader = header, lastCrash = { CrashReporter.read(this) })
        }
    }

    @Composable
    private fun Timeline(collectionUrl: String) {
        val state by produceState<Load>(Load.Busy, collectionUrl) {
            value = try {
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
