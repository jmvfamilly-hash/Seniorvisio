package com.papyrus

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay

/**
 * Papyrus : la dictée de la pièce, affichée à une vitesse de lecture.
 *
 * ═══ Ce que cet écran résout ═══
 *
 * Le moteur de reconnaissance produit par à-coups — plusieurs résultats dans
 * la même milliseconde, puis des secondes de rien. Un écran qui suivrait ce
 * rythme sauterait, et personne ne peut lire un texte qui saute. Encore moins
 * quelqu'un d'âgé, pour qui chaque secousse est une ligne perdue.
 *
 * Le texte défile donc à **cadence fixe**, indépendante de celle du moteur.
 * Entre les deux, une file d'attente absorbe l'écart (voir ScrollPipeline).
 *
 * ═══ La règle qu'il ne faut pas enfreindre ═══
 *
 * Quand la file grossit, le défilement **ne s'accélère pas**. Jamais. Un stock
 * important se signale par un point discret, et rien d'autre. Accélérer pour
 * rattraper reviendrait à rendre le texte illisible exactement au moment où il
 * y en a le plus à lire — l'inverse du service rendu.
 *
 * Seule une personne peut changer le rythme, par le sélecteur en bas.
 *
 * ═══ Trois zones, et une qui ne bouge pas ═══
 *
 * En haut, le **passé** : ce qui est sorti de la fenêtre de lecture, en encre
 * pâlie, définitif. Au milieu, la **fenêtre de lecture** — les deux dernières
 * lignes, en pleine encre, là où l'œil se pose. En bas, le **futur** : ce que
 * le moteur propose mais qui n'est pas encore acquis, en italique clair.
 *
 * **La fenêtre de lecture est ancrée.** Elle occupe toujours le même endroit
 * de l'écran, et c'est le texte qui la traverse — il monte à travers elle et
 * sort par le haut. C'est l'inverse de ce que faisait la version précédente :
 * un texte qui grandissait vers le bas dans une zone qu'on faisait défiler à
 * chaque mot, si bien que la fenêtre dérivait pendant l'animation et que l'œil
 * devait la suivre. Or suivre du regard une ligne qui se déplace est
 * exactement le travail qu'on cherche à épargner.
 *
 * Deux choses tiennent cet ancrage, et il faut les deux : le texte acquis est
 * aligné sur le BORD BAS de sa zone, et la zone future a une hauteur FIXE,
 * réservée même quand elle est vide. Sans la seconde, une hypothèse qui
 * s'allonge repousserait la fenêtre vers le haut — elle se remettrait à bouger,
 * par l'autre bout.
 *
 * La frontière entre passé et fenêtre de lecture est calculée par la mise en
 * page elle-même, pas devinée à partir d'un nombre de mots : « deux lignes »
 * ne veut rien dire tant qu'on ne sait pas où le texte se coupe, et il se coupe
 * ailleurs selon la longueur des mots.
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

    // --- L'état affiché -----------------------------------------------------

    /** Les mots déjà défilés, dans l'ordre. Rien n'en sort que par la purge d'ancienneté. */
    val shown = remember { mutableStateListOf<String>() }

    /** Les mots proposés par le moteur mais pas encore acquis : la zone future. */
    var pending by remember { mutableStateOf("") }

    var diagnostic by remember { mutableStateOf("") }
    var backlogVisible by remember { mutableStateOf(false) }
    var stillListening by remember { mutableStateOf(false) }

    // La vitesse survit au redémarrage de l'application : un réglage de confort
    // qu'il faut refaire à chaque lancement n'est pas un réglage, c'est une
    // corvée.
    var rate by remember { mutableStateOf(loadRate(context)) }

    // --- La chaîne, construite une fois pour toutes --------------------------
    //
    // Retenue HORS de l'effet d'écoute, et c'est essentiel : elle ne doit rien
    // perdre quand la session du moteur se relance, or les relances sont
    // incessantes. Une file reconstruite à chaque session produirait exactement
    // la coupure visible que cette architecture existe pour supprimer.
    val queue = remember { WordQueue() }
    val indicator = remember {
        BacklogIndicator(ScrollDefaults.BACKLOG_ENTER, ScrollDefaults.BACKLOG_EXIT)
    }
    val stabiliser = remember {
        WordStabiliser(ScrollDefaults.STABLE_DELAY_MS) { word -> queue.enqueue(word) }
    }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        SpeechTrace.record(
            "ÉCRAN écoute",
            "démarrage du gestionnaire, mode ${SequencingMode.SEQUENCE.label}",
        )
        val speech = ContinuousSpeechManager(
            context = context,
            // LE SEUL MODE LIVRÉ, et aucune interface ne permet d'en changer.
            // Les deux autres régimes restent dans le gestionnaire comme
            // instruments de mesure, mais rien ici ne peut les atteindre.
            mode = SequencingMode.SEQUENCE,
            onPartial = { text ->
                stabiliser.submitPartial(text, SystemClock.elapsedRealtime())
                pending = stabiliser.pendingWords().joinToString(" ")
            },
            // Sans emploi en mode séquence : le figeage appartient au
            // stabilisateur, pas au gestionnaire.
            onFinal = { },
            onSessionEnd = { finalText ->
                stabiliser.finish(finalText)
                pending = ""
                SpeechTrace.record(
                    "APP réconciliation",
                    if (finalText.isNullOrBlank())
                        "aucun texte final — les candidats restants sont acquis tels quels"
                    else "texte final reçu, ${finalText.length} car.",
                )
            },
            onRestart = { },
            onDiagnostic = {
                diagnostic = it
                SpeechTrace.record("ÉCRAN diagnostic", it)
            },
        )
        speech.start()
        onDispose {
            SpeechTrace.record("ÉCRAN écoute", "arrêt du gestionnaire")
            speech.stop()
        }
    }

    // --- L'horloge de défilement --------------------------------------------
    //
    // Relancée quand la vitesse change, et rien d'autre n'en dépend : ni la
    // file, ni le texte déjà affiché. Changer de palier ne peut donc produire
    // ni saut ni perte — l'invariant est vrai par construction, pas par
    // vigilance.
    LaunchedEffect(granted, rate) {
        if (!granted) return@LaunchedEffect
        var lastWordAtMs = SystemClock.elapsedRealtime()
        while (true) {
            delay(rate.intervalMs)
            val now = SystemClock.elapsedRealtime()

            // Le temps mûrit même sans nouveau résultat du moteur : sans ce
            // rappel, les derniers mots d'une phrase resteraient candidats
            // pour toujours, le silence les empêchant d'être promus alors que
            // c'est lui qui prouve qu'ils sont définitifs.
            stabiliser.tick(now)

            val word = queue.dequeue()
            if (word != null) {
                shown.add(word)
                lastWordAtMs = now
                // Purge d'ancienneté, très au-delà de ce qu'un écran montre :
                // elle borne la mémoire sans jamais retirer quoi que ce soit
                // de visible.
                while (shown.size > MAX_WORDS) shown.removeAt(0)
            }

            indicator.update(queue.backlog)
            backlogVisible = indicator.visible

            // Deux informations distinctes, et il ne faut pas les confondre :
            // le voyant dit « il reste à lire », celui-ci dit « le moteur
            // écoute toujours ». Un écran parfaitement immobile pendant trente
            // secondes n'aurait sinon aucune explication — la trace en montre
            // trois sessions de suite.
            stillListening = queue.backlog == 0 &&
                now - lastWordAtMs > ScrollDefaults.SILENCE_HINT_MS

            pending = stabiliser.pendingWords().joinToString(" ")
        }
    }

    // --- Le rendu ------------------------------------------------------------

    // Où commence la fenêtre de lecture, en caractères depuis le début du
    // texte. Fourni par la mise en page elle-même (voir onTextLayout), et non
    // estimé à partir d'un nombre de mots.
    var readingWindowStart by remember { mutableStateOf(0) }

    val body = shown.joinToString(" ")

    // Les hauteurs des deux zones basses, en unités d'interligne. Dérivées de
    // la taille du texte et non écrites en dur : si quelqu'un agrandit la
    // police du système, les bandes grandissent avec elle et la mise en page
    // tient toujours.
    val lineHeight = with(LocalDensity.current) { LINE_HEIGHT.toDp() }

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
                .padding(horizontal = 40.dp)
                .padding(top = 24.dp, bottom = 78.dp),
        ) {
            // ═══ LE TEXTE ACQUIS, CALÉ PAR LE BAS ═══
            //
            // C'est ici que se joue « la fenêtre de lecture ne bouge pas ».
            //
            // Cette zone occupe tout l'espace disponible au-dessus de la zone
            // future, et son contenu est aligné sur son BORD INFÉRIEUR. Le
            // texte grandit donc vers le HAUT, et déborde par le haut, où il
            // est coupé. Son bord bas ne bouge jamais — donc les deux
            // dernières lignes non plus.
            //
            // La version précédente faisait l'inverse : un texte qui grandit
            // vers le bas dans une zone qu'on faisait défiler jusqu'en bas à
            // chaque mot. Le résultat tenait dans l'écran, mais la fenêtre de
            // lecture dérivait pendant l'animation de défilement, et l'œil
            // devait la suivre. Or c'est exactement le travail qu'on cherche à
            // épargner.
            //
            // wrapContentHeight(unbounded) est ce qui rend la chose possible :
            // sans lui, le texte serait MESURÉ à la hauteur de la zone et donc
            // tronqué par le bas — on perdrait les dernières lignes, c'est-à-
            // dire les seules qui comptent. Avec lui, il est mesuré sans
            // limite de hauteur, puis posé en bas ; le débordement passe en
            // haut, où clipToBounds le coupe.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds(),
                contentAlignment = Alignment.BottomStart,
            ) {
                BasicText(
                    text = renderBody(body, readingWindowStart),
                    modifier = Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true),
                    style = TextStyle(
                        color = Ink,
                        fontSize = TEXT_SIZE,
                        // Interligne généreux : à cette taille, des lignes
                        // serrées se lisent mal de loin, et c'est de loin qu'on
                        // regarde une tablette murale.
                        lineHeight = LINE_HEIGHT,
                        fontFamily = FontFamily.Serif,
                    ),
                    onTextLayout = { layout ->
                        val firstVisibleLine =
                            (layout.lineCount - READING_WINDOW_LINES).coerceAtLeast(0)
                        val start = layout.getLineStart(firstVisibleLine)
                        // Réaffecté seulement s'il change : ce rappel survient à
                        // chaque mise en page, et écrire la même valeur
                        // relancerait une composition pour rien.
                        if (start != readingWindowStart) readingWindowStart = start
                    },
                )
            }

            // ═══ LA ZONE FUTURE, DE HAUTEUR FIXE ═══
            //
            // Fixe, et c'est la seconde moitié de l'ancrage. Si cette bande
            // grandissait avec le texte proposé, elle repousserait la fenêtre
            // de lecture vers le haut à chaque mot entendu — la fenêtre se
            // remettrait à bouger, par l'autre bout.
            //
            // Elle réserve donc sa place en permanence, occupée ou non. Le
            // texte y est aligné par le bas lui aussi : ce sont les derniers
            // mots proposés qui comptent, et une hypothèse plus longue que
            // deux lignes doit laisser filer son début, pas sa fin.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lineHeight * FUTURE_LINES)
                    .clipToBounds(),
                contentAlignment = Alignment.BottomStart,
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    BasicText(
                        text = pending,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .wrapContentHeight(Alignment.Bottom, unbounded = true),
                        style = TextStyle(
                            color = PendingInk,
                            fontSize = TEXT_SIZE,
                            lineHeight = LINE_HEIGHT,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                        ),
                    )
                    // Le voyant de retard, en bordure de la zone future.
                    //
                    // Un point, rien de plus : pas de chiffre qui change, pas
                    // de couleur qui s'aggrave, pas de clignotement. Un
                    // compteur « 12 mots en attente » attirerait l'œil à chaque
                    // mot et casserait la lecture posée, qui est tout ce qu'on
                    // cherche ici. Présence ou absence, et c'est assez : « il
                    // reste à lire » n'a pas de degrés.
                    if (backlogVisible) {
                        Spacer(Modifier.size(16.dp))
                        Box(
                            Modifier
                                .padding(bottom = 14.dp)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(FadedInk)
                        )
                    }
                }
            }
        }

        // Le signal « toujours à l'écoute », distinct du voyant de retard.
        // Celui-ci répond à « est-ce que ça marche encore ? » quand rien ne
        // bouge depuis longtemps ; l'autre à « y a-t-il plus à lire ? ».
        if (stillListening) {
            BasicText(
                text = "à l'écoute",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 40.dp, vertical = 24.dp),
                style = TextStyle(color = FadedInk, fontSize = 15.sp, fontStyle = FontStyle.Italic),
            )
        }

        // Le diagnostic : quel moteur travaille, et ce qui l'empêche. Sans lui,
        // un modèle de langue absent produit un écran vide — indiscernable d'un
        // micro muet ou d'une permission refusée.
        if (diagnostic.isNotEmpty()) {
            BasicText(
                text = diagnostic,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 40.dp, vertical = 14.dp),
                style = TextStyle(color = FadedInk, fontSize = 13.sp),
            )
        }

        // Le sélecteur de vitesse, seule commande de l'écran.
        //
        // La valeur choisie reste affichée en permanence, et pas seulement au
        // moment du réglage : quelqu'un qui trouve le défilement trop lent doit
        // pouvoir voir où il en est sans rien toucher.
        //
        // Des crans nommés plutôt qu'un curseur : « mets-le sur normal » est une
        // consigne qu'un aidant peut donner au téléphone, « cent trente-sept
        // mots par minute » non. La valeur numérique accompagne le libellé, en
        // petit, pour le diagnostic.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 40.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedStep("−") { rate = rate.previous().also { saveRate(context, it) } }
                BasicText(
                    text = rate.label,
                    style = TextStyle(color = Ink, fontSize = 17.sp, fontFamily = FontFamily.Serif),
                )
                SpeedStep("+") { rate = rate.next().also { saveRate(context, it) } }
            }
            BasicText(
                text = "${rate.wordsPerMinute} mots/min · appui long : envoyer la trace",
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { shareTrace() }) },
                style = TextStyle(color = FadedInk, fontSize = 13.sp),
            )
        }
    }
}

/**
 * Un cran du sélecteur.
 *
 * La zone tactile est nettement plus large que le signe : à cette taille, une
 * cible ajustée au glyphe se rate une fois sur deux, et ce bouton s'adresse à
 * des doigts qui ne visent plus très bien.
 */
@Composable
private fun SpeedStep(sign: String, onClick: () -> Unit) {
    BasicText(
        text = sign,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        style = TextStyle(color = Ink, fontSize = 24.sp),
    )
}

/**
 * Le corps du texte, coupé en deux à l'endroit que la mise en page a désigné.
 *
 * Avant [readingWindowStart], le passé : encre pâlie, définitif, présent pour
 * le contexte et non pour être lu. À partir de là, la fenêtre de lecture, en
 * pleine encre — c'est là que l'œil doit se poser, et le contraste l'y ramène
 * sans qu'on ait à y penser.
 */
private fun renderBody(body: String, readingWindowStart: Int): AnnotatedString =
    buildAnnotatedString {
        val split = readingWindowStart.coerceIn(0, body.length)
        if (split > 0) {
            withStyle(SpanStyle(color = PastInk)) { append(body.substring(0, split)) }
        }
        append(body.substring(split))
    }

private fun prefs(context: Context) =
    context.getSharedPreferences("papyrus", Context.MODE_PRIVATE)

private fun loadRate(context: Context): ScrollRate =
    ScrollRate.fromNameOrDefault(prefs(context).getString(KEY_RATE, null))

private fun saveRate(context: Context, rate: ScrollRate) {
    prefs(context).edit().putString(KEY_RATE, rate.name).apply()
    SpeechTrace.record("ÉCRAN vitesse", "réglée sur ${rate.label} (${rate.wordsPerMinute} mots/min)")
}

private const val KEY_RATE = "scroll_rate"

/**
 * La fenêtre de lecture, en lignes. Deux, comme le veut la spécification : de
 * quoi tenir une proposition entière sous les yeux sans que le regard ait à
 * balayer un paragraphe.
 *
 * Ce nombre ne définit PAS une hauteur à l'écran — il désigne les deux
 * dernières lignes du texte acquis, celles qui reçoivent l'encre pleine. Leur
 * position, elle, est fixée par l'ancrage bas de la zone qui les contient.
 */
private const val READING_WINDOW_LINES = 2

/**
 * La hauteur réservée en permanence à la zone future, en lignes.
 *
 * Réservée même quand elle est vide, et c'est la condition pour que la fenêtre
 * de lecture ne bouge pas : une bande qui grandirait avec le texte proposé
 * repousserait la fenêtre vers le haut à chaque mot entendu.
 *
 * Deux lignes suffisent à l'usage — une hypothèse du moteur dépasse rarement
 * une dizaine de mots avant d'être acquise ou remplacée.
 */
private const val FUTURE_LINES = 2

/** Taille du texte de dictée. Assez grande pour être lue de l'autre bout d'une pièce. */
private val TEXT_SIZE = 36.sp

/**
 * Interligne. Sert deux fois : à la mise en page du texte, et au calcul de la
 * hauteur des bandes — les deux doivent venir de la même valeur, sans quoi la
 * zone future réserverait une place qui ne correspond à aucun nombre de lignes.
 */
private val LINE_HEIGHT = 50.sp

/**
 * Le parchemin demandé, et une encre brune plutôt qu'un noir pur : sur un fond
 * chaud, le noir tranche comme de l'imprimé et défait tout l'effet.
 */
private val Parchment = Color(0xFFFBF5E6)

/** Ombrage des bords, à peine perceptible : au-delà, la feuille paraît sale. */
private val ParchmentEdge = Color(0x146B5A3E)

private val Ink = Color(0xFF3A2C1C)

/**
 * Le passé : lisible si on le cherche, effacé si on ne le cherche pas. Assez
 * pâle pour que l'œil revienne de lui-même à la fenêtre de lecture, assez
 * présent pour qu'on puisse relire la phrase précédente sans rien toucher.
 */
private val PastInk = Color(0x663A2C1C)

/** La zone future, encore révisable. Plus claire que le passé, et en italique. */
private val PendingInk = Color(0x593A2C1C)

private val FadedInk = Color(0x553A2C1C)

/**
 * Borne de la mémoire d'affichage, en mots. Deux mille mots font plus d'une
 * heure de dictée à la cadence normale — très au-delà de ce qu'un écran montre,
 * donc la purge ne retire jamais rien de visible.
 */
private const val MAX_WORDS = 2_000
