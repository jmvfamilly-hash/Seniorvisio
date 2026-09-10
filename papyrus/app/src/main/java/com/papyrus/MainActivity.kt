package com.papyrus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Banc d'essai de l'écoute continue, indépendant de Senior Visio.
 *
 * Un fond, un texte, un défilement. Rien à toucher : ni bouton, ni barre, ni
 * réglage. L'écoute démarre seule et ne s'arrête qu'avec l'écran.
 *
 * Ce dépouillement n'est pas de l'esthétique : il s'agit d'observer un seul
 * phénomène — les coupures de la reconnaissance continue (voir
 * ContinuousSpeechManager) — et tout élément d'interface supplémentaire serait
 * une variable de plus dans une mesure qui en compte déjà trop.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // L'écran ne doit pas s'éteindre au milieu d'une observation : c'est un
        // texte qu'on regarde défiler sans jamais toucher la tablette, donc
        // sans jamais repousser la veille.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Plein écran permanent : les barres système reviennent d'un balayage
        // si besoin, mais ne prennent aucune place et ne s'affichent jamais
        // d'elles-mêmes.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setContent { PapyrusScreen() }
    }
}

@Composable
private fun PapyrusScreen() {
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    // Demandée au démarrage, sans écran d'accueil ni bouton : l'application n'a
    // rien d'autre à faire, et un refus se lit dans le texte affiché.
    LaunchedEffect(Unit) {
        if (!granted) requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Les énoncés clos, un par session du moteur. */
    val segments = remember { mutableStateListOf<String>() }
    var partial by remember { mutableStateOf("") }
    var diagnostic by remember { mutableStateOf("") }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val manager = ContinuousSpeechManager(
            context = context,
            onPartial = { partial = it },
            onFinal = { text ->
                segments.add(text)
                partial = ""
                // Le tampon ne grandit pas sans fin : une observation peut
                // durer des heures, et rien ne sert de garder ce qui est sorti
                // de l'écran depuis longtemps.
                while (segments.size > MAX_SEGMENTS) segments.removeAt(0)
            },
            onRestart = { },
            onDiagnostic = { diagnostic = it },
        )
        manager.start()
        onDispose { manager.stop() }
    }

    val scroll = rememberScrollState()
    val text = renderTranscript(segments.toList(), partial)

    // Défilement vers le bas à chaque mot : c'est la dernière ligne qui compte,
    // et personne ne fera défiler à la main.
    LaunchedEffect(text) { scroll.animateScrollTo(scroll.maxValue) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Parchment),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 40.dp, vertical = 32.dp),
        ) {
            BasicText(
                text = text,
                style = TextStyle(
                    color = Ink,
                    fontSize = 36.sp,
                    // Interligne généreux : à cette taille, des lignes serrées
                    // se lisent mal de loin, et c'est de loin qu'on regarde une
                    // tablette murale.
                    lineHeight = 50.sp,
                    fontFamily = FontFamily.Serif,
                ),
            )
        }

        // Une seule ligne de service, en bas et très effacée : elle dit quel
        // moteur travaille et ce qui l'empêche. Sans elle, un modèle de langue
        // absent produit un écran vide — indiscernable d'un micro muet ou d'une
        // permission refusée.
        if (diagnostic.isNotEmpty()) {
            BasicText(
                text = diagnostic,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 40.dp, vertical = 12.dp),
                style = TextStyle(color = FadedInk, fontSize = 13.sp),
            )
        }
    }
}

/**
 * Le texte tel qu'il s'affiche, avec une marque discrète à chaque couture.
 *
 * Chaque marque est un endroit où le moteur s'est arrêté puis a été relancé —
 * c'est-à-dire exactement l'instant où des mots peuvent avoir été perdus. Les
 * montrer est tout l'objet de ce banc d'essai : sans elles, une phrase amputée
 * ressemble à une phrase mal comprise, et les deux ne se corrigent pas de la
 * même façon.
 */
@Composable
private fun renderTranscript(segments: List<String>, partial: String): AnnotatedString =
    buildAnnotatedString {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                withStyle(SpanStyle(color = FadedInk)) { append(SEAM) }
            }
            append(segment)
        }
        if (partial.isNotEmpty()) {
            if (segments.isNotEmpty()) withStyle(SpanStyle(color = FadedInk)) { append(SEAM) }
            append(partial)
        }
    }

/** Le parchemin demandé, et une encre chaude plutôt qu'un noir pur, qui trancherait trop. */
private val Parchment = Color(0xFFFBF5E6)
private val Ink = Color(0xFF2B2118)
private val FadedInk = Color(0x552B2118)

/** Sans espace insécable ni retour : une couture ne doit jamais couper une ligne en deux. */
private const val SEAM = " · "

/** Environ une heure de conversation soutenue. Au-delà, plus personne ne remontera. */
private const val MAX_SEGMENTS = 400
