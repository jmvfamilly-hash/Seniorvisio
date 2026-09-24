package com.seniorvisio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File

/**
 * LA visionneuse de photos. Une seule, pour les deux écrans.
 *
 * ═══ POURQUOI UNE SEULE, ET PAS DEUX QUI SE RESSEMBLENT ═══
 *
 * Deux endroits montrent des photos à Jean : l'accueil, où sa galerie tourne
 * toute la journée, et l'écran d'appel, où un proche lui présente les siennes.
 * C'étaient jusqu'ici deux chemins entièrement séparés — deux décodages, deux
 * gestions de mémoire, deux façons de changer d'image.
 *
 * Ils ne peuvent plus diverger : c'est ce composable, et lui seul, qui sait
 * afficher une photo. Un réglage de geste, une transition, un correctif de
 * mémoire valent désormais pour les deux écrans sans qu'on ait à y penser.
 *
 * ═══ CE QUI N'EST PAS ÉCRIT ICI, ET C'EST VOULU ═══
 *
 * Le pincement, la double-tape, l'inertie, le retour aux bords, le glissement
 * d'une photo à l'autre et le fondu de chargement viennent de Telephoto et de
 * Coil. Rien de tout cela n'est réimplémenté : des gestes tactiles écrits à la
 * main sont toujours un peu faux — un seuil de vitesse mal choisi, une inertie
 * qui ne ressemble à rien — et « un peu faux » sur un écran tactile, pour une
 * main qui tremble, veut dire inutilisable.
 *
 * ═══ LA RÉSOLUTION D'ORIGINE, ET COMMENT ELLE TIENT EN MÉMOIRE ═══
 *
 * Telephoto décode PAR TUILES : seule la portion visible est lue, à la finesse
 * où elle est regardée. C'est ce qui permet de garder les fichiers en pleine
 * définition — condition du zoom — sans les charger entiers. Une photo
 * d'appareil moderne fait cinquante mégaoctets de pixels ; la dalle en affiche
 * deux. Ce projet a déjà vu le tas natif monter à 883 Mo en décodant des
 * images entières pour n'en montrer qu'une vignette.
 *
 * @param photos les fichiers, dans l'ordre où Jean les verra. Des FICHIERS et
 *   non des bitmaps : c'est Coil qui décode, et c'est tout l'intérêt.
 * @param rang la photo à montrer. Imposée de l'extérieur — par la cadence de
 *   l'administrateur sur l'accueil, par le proche pendant un appel.
 * @param surRang appelé quand JEAN change de photo au doigt, jamais quand le
 *   changement vient de [rang]. Sans cette distinction, replacer le pager
 *   après une cadence rappellerait l'extérieur, qui replacerait le pager : une
 *   boucle.
 */
/*
 * ═══ POURQUOI UN CONSENTEMENT EXPLICITE ═══
 *
 * HorizontalPager et son état sont encore marqués expérimentaux dans Compose
 * 1.6 — celui qu'impose Kotlin 1.9. Ils sont stabilisés en 1.7, qui exige
 * Kotlin 2. Le compilateur refuse de les employer sans cette ligne : dix
 * erreurs, toutes identiques, à la première compilation.
 *
 * C'est un aveu de dette, pas une formalité. Le jour où ce projet passera à
 * Kotlin 2 et Compose 1.7, cette annotation devra disparaître — et si les
 * signatures ont changé d'ici là, c'est ici que ça se verra.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VisionneusePhotos(
    photos: List<File>,
    rang: Int,
    surRang: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (photos.isEmpty()) return

    val état = rememberPagerState(
        initialPage = rang.coerceIn(0, photos.lastIndex),
        pageCount = { photos.size },
    )

    // rememberUpdatedState : le rappel peut changer d'une recomposition à
    // l'autre, et les effets ci-dessous vivent plus longtemps qu'elles. Sans
    // ça, ils garderaient la toute première version — celle d'un écran qui
    // n'affiche peut-être plus rien.
    val rappelCourant by rememberUpdatedState(surRang)

    // ═══ L'EXTÉRIEUR DÉPLACE LE PAGER ═══
    //
    // animateScrollToPage et non scrollToPage : le changement de créneau doit
    // se voir glisser. C'est la transition demandée, et elle est celle de la
    // bibliothèque — on ne l'écrit pas.
    LaunchedEffect(rang, photos.size) {
        val voulu = rang.coerceIn(0, photos.lastIndex)
        if (état.currentPage != voulu) état.animateScrollToPage(voulu)
    }

    // ═══ ET JEAN DÉPLACE L'EXTÉRIEUR ═══
    //
    // settledPage et non currentPage : currentPage bouge pendant que le doigt
    // glisse, et prévenir à chaque pixel ferait remonter des rangs
    // intermédiaires que personne n'a choisis. On attend que la page se pose.
    LaunchedEffect(état) {
        snapshotFlow { état.settledPage }.collect { posée ->
            if (posée != rang) rappelCourant(posée)
        }
    }

    // Aucune page préchargée hors de celle qu'on regarde : c'est le défaut de
    // cette version, et on ne l'écrit pas pour ne pas dépendre d'un nom de
    // paramètre qui a changé d'une version de Compose à l'autre. Le bon
    // réglage ici est bien zéro — précharger triplerait la mémoire d'images
    // pour gagner un temps que personne n'attend, les photos changeant au
    // quart d'heure.
    HorizontalPager(
        state = état,
        modifier = modifier.fillMaxSize(),
    ) { page ->
        val étatZoom = rememberZoomableImageState(rememberZoomableState())

        // ═══ L'IMAGE REVIENT SEULE À SA VUE D'ENSEMBLE ═══
        //
        // Jean agrandit un visage, puis pose la tablette. Sans ce retour,
        // l'image resterait agrandie sur un coin indéchiffrable jusqu'à ce que
        // quelqu'un passe — et il n'a aucune raison de savoir comment défaire
        // un geste qu'il n'a peut-être pas fait exprès.
        //
        // L'effet se relance à chaque changement de zoom : le compte à rebours
        // repart tant qu'il manipule, et ne va au bout que lorsqu'il s'arrête.
        val fraction = étatZoom.zoomableState.zoomFraction
        LaunchedEffect(fraction) {
            if (fraction != null && fraction > SEUIL_AGRANDI) {
                delay(RETOUR_VUE_ENSEMBLE_MS)
                étatZoom.zoomableState.resetZoom()
            }
        }

        ZoomableAsyncImage(
            model = photos[page],
            // Null et non une description : cet écran n'est pas lu par un
            // lecteur d'écran, et une description inventée à partir d'un nom
            // de fichier serait pire que rien.
            contentDescription = null,
            state = étatZoom,
            modifier = Modifier.fillMaxSize(),
            // Jamais de recadrage : une photo de famille recadrée coupe des
            // visages, et Jean n'a aucun moyen de le signaler. Une photo en
            // portrait sur une dalle en paysage laissera des bandes. C'est le
            // prix, et c'est le bon.
            contentScale = ContentScale.Fit,
        )
    }
}

/** En dessous, l'image est à sa taille d'ensemble : rien à ramener. */
private const val SEUIL_AGRANDI = 0.01f

/**
 * Le délai avant que l'image agrandie revienne d'elle-même.
 *
 * Vingt secondes : assez pour regarder un détail sans être bousculé, assez peu
 * pour qu'une tablette laissée en plan ne garde pas un gros plan de coin
 * d'image jusqu'au lendemain.
 */
private const val RETOUR_VUE_ENSEMBLE_MS = 20_000L
