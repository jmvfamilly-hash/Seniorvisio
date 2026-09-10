package com.papyrus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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

        SpeechTrace.record("ÉCRAN onCreate", "démarrage de l'application")
        setContent { PapyrusScreen() }
    }

    // Le cycle de vie est tracé parce qu'il commande l'écoute : c'est la
    // disparition de cet écran qui relâche le microphone. Une transcription qui
    // s'arrête a alors deux causes très différentes — le moteur a lâché, ou le
    // système a mis l'application en arrière-plan — et rien ne les distinguait.
    override fun onStart() {
        super.onStart()
        SpeechTrace.record("ÉCRAN onStart", "")
    }

    override fun onStop() {
        super.onStop()
        SpeechTrace.record("ÉCRAN onStop", "écran quitté")
        // La trace est écrite ici plutôt qu'au seul appui long : une
        // application tuée en arrière-plan emporterait sinon tout ce qu'elle a
        // observé, et c'est précisément ce qu'on cherchait à comprendre.
        SpeechTrace.writeTo(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        SpeechTrace.record("ÉCRAN onDestroy", "")
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
    ) {
        granted = it
        SpeechTrace.record("ÉCRAN permission", if (it) "ACCORDÉE" else "REFUSÉE")
    }

    // Demandée au démarrage, sans écran d'accueil ni bouton : l'application n'a
    // rien d'autre à faire, et un refus se lit dans le texte affiché.
    LaunchedEffect(Unit) {
        if (granted) {
            SpeechTrace.record("ÉCRAN permission", "déjà accordée")
        } else {
            SpeechTrace.record("ÉCRAN permission", "demandée")
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Les énoncés clos, un par session du moteur. */
    val segments = remember { mutableStateListOf<String>() }
    var partial by remember { mutableStateOf("") }
    var diagnostic by remember { mutableStateOf("") }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        SpeechTrace.record("ÉCRAN écoute", "démarrage du gestionnaire")
        val manager = ContinuousSpeechManager(
            context = context,
            onPartial = {
                // Remplacement, jamais ajout — et c'est désormais écrit dans la
                // trace plutôt qu'à déduire du code. La relation entre l'ancien
                // et le nouveau texte est notée en amont (voir « tampon
                // partiel ») ; ici on note ce que l'écran affiche réellement.
                partial = it
                SpeechTrace.record("ÉCRAN partiel affiché", "${it.length} car.")
            },
            onFinal = { text ->
                segments.add(text)
                partial = ""
                // Le tampon ne grandit pas sans fin : une observation peut
                // durer des heures, et rien ne sert de garder ce qui est sorti
                // de l'écran depuis longtemps.
                var purged = 0
                while (segments.size > MAX_SEGMENTS) {
                    segments.removeAt(0)
                    purged++
                }
                SpeechTrace.record(
                    "ÉCRAN ligne ajoutée",
                    "${segments.size} lignes à l'écran" +
                        (if (purged > 0) ", $purged purgée(s) en tête" else "") +
                        " — « $text »",
                )
            },
            onRestart = { },
            onDiagnostic = {
                diagnostic = it
                SpeechTrace.record("ÉCRAN diagnostic", it)
            },
        )
        manager.start()
        onDispose {
            SpeechTrace.record("ÉCRAN écoute", "arrêt du gestionnaire")
            manager.stop()
        }
    }

    val scroll = rememberScrollState()
    val text = renderTranscript(segments.toList(), partial)

    // Du texte ACQUIS peut-il disparaître de l'écran ? C'est la seule forme de
    // perte qui compte : la ligne en cours, elle, est faite pour être remplacée.
    //
    // Deux corrections par rapport à la version précédente, qui signalait treize
    // pertes dont aucune n'en était une.
    //
    // On ne mesure plus que les lignes acquises, sans la ligne en cours ni sa
    // marque de plume. Douze des treize signalements venaient de la disparition
    // de cette marque au moment où une ligne passe d'« en cours » à « acquise » :
    // deux caractères, comptés comme une perte de texte.
    //
    // Et la mesure sort du LaunchedEffect. Celui-ci appelle une animation de
    // défilement, donc il suspend ; quand le texte change à nouveau avant la
    // fin, l'effet est annulé AVANT d'avoir enregistré la longueur, et la
    // comparaison suivante se fait contre une valeur périmée. C'est ce qui a
    // produit le treizième signalement, à vingt caractères. SideEffect
    // s'exécute après chaque composition effectivement affichée, sans jamais
    // être annulé — c'est exactement « ce que l'écran a montré ».
    val committedLength = remember { intArrayOf(0) }
    val committedChars = segments.sumOf { it.length }
    SideEffect {
        if (committedChars < committedLength[0]) {
            SpeechTrace.record(
                "ÉCRAN LIGNES ACQUISES PERDUES",
                "${committedLength[0]} → $committedChars car. — vérifier la purge juste au-dessus",
            )
        }
        committedLength[0] = committedChars
    }

    // Défilement vers le bas à chaque mot : c'est la dernière ligne qui compte,
    // et personne ne fera défiler à la main.
    LaunchedEffect(text) { scroll.animateScrollTo(scroll.maxValue) }

    // Appui long n'importe où : la trace part vers le sélecteur de partage.
    // Un geste plutôt qu'un bouton — l'écran doit rester nu, et personne ne
    // découvrira ce geste par hasard.
    val shareTrace = {
        SpeechTrace.record("ÉCRAN partage", "trace demandée")
        val file = SpeechTrace.writeTo(context)
        if (file != null) {
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.traces", file)
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Papyrus — trace")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Envoyer la trace",
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Partage impossible : ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Écriture de la trace impossible", Toast.LENGTH_LONG).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { shareTrace() }) }
            .background(Parchment)
            // Le parchemin ne se réduit pas à un aplat : une feuille ancienne
            // est plus sombre sur ses bords qu'en son centre. Dessiné plutôt
            // qu'importé — une image de fond pèserait plus lourd que toute
            // l'application, et se pixelliserait sur une autre dalle.
            .background(Brush.radialGradient(listOf(Color.Transparent, ParchmentEdge))),
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

        // Le seul mode d'emploi de l'application, et il tient en une ligne.
        // Elle porte aussi le geste : la zone de texte au-dessus défile, et un
        // conteneur défilant peut absorber l'appui long avant qu'il n'atteigne
        // le fond. Cette ligne-ci ne défile pas — le geste y aboutit toujours.
        BasicText(
            text = "appui long : envoyer la trace",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 40.dp, vertical = 12.dp)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { shareTrace() }) },
            style = TextStyle(color = FadedInk, fontSize = 13.sp),
        )
    }
}

/**
 * Le texte tel qu'il s'affiche : **un énoncé par ligne**, ajoutés les uns sous
 * les autres. Rien n'est jamais réécrit ni effacé.
 *
 * Le retour à la ligne fait office de marque de couture : chaque ligne est une
 * session du moteur, donc chaque passage à la ligne est l'instant précis où il
 * s'est arrêté puis a repris. C'est là, et seulement là, que des mots peuvent
 * manquer — et c'est tout l'objet de ce banc d'essai que de rendre ces endroits
 * repérables à l'œil.
 *
 * L'énoncé en cours de dictée occupe sa propre ligne, en encre plus claire : il
 * est encore susceptible d'être révisé par le moteur, et le distinguer évite de
 * prendre une hésitation de machine pour une hésitation de la personne.
 */
@Composable
private fun renderTranscript(segments: List<String>, partial: String): AnnotatedString =
    buildAnnotatedString {
        segments.forEachIndexed { index, segment ->
            if (index > 0) append('\n')
            append(segment)
        }
        if (partial.isNotEmpty()) {
            if (segments.isNotEmpty()) append('\n')
            // Reconnaissable SEULE, et pas seulement par contraste avec le
            // texte acquis : tant que rien n'est figé au-dessus, une simple
            // nuance de gris n'est comparable à rien et ne se perçoit pas.
            // D'où trois marques cumulées — un signe en tête, l'italique, et
            // une encre plus claire — dont chacune suffirait.
            withStyle(SpanStyle(color = PendingInk, fontStyle = FontStyle.Italic)) {
                append(PENDING_MARK)
                append(partial)
            }
        }
    }

/**
 * Le parchemin demandé, et une encre brune plutôt qu'un noir pur : sur un fond
 * chaud, le noir tranche comme de l'imprimé et défait tout l'effet.
 */
private val Parchment = Color(0xFFFBF5E6)

/** Ombrage des bords, à peine perceptible : au-delà, la feuille paraît sale. */
private val ParchmentEdge = Color(0x146B5A3E)

private val Ink = Color(0xFF3A2C1C)

/**
 * L'énoncé en cours, encore révisable par le moteur. Nettement plus clair qu'au
 * premier essai : à 60 % d'opacité la différence ne se voyait pas, surtout
 * quand cette ligne était seule à l'écran.
 */
private val PendingInk = Color(0x6B3A2C1C)

/** En tête de la ligne en cours. Une plume qui écrit encore. */
private const val PENDING_MARK = "✎ "


private val FadedInk = Color(0x553A2C1C)

/** Environ une heure de conversation soutenue. Au-delà, plus personne ne remontera. */
private const val MAX_SEGMENTS = 400
