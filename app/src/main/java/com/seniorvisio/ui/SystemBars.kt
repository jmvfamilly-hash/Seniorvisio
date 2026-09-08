package com.seniorvisio.ui

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Masque la barre de navigation Android (le bandeau gris en bas de l'écran)
 * sur les écrans destinés à Jean.
 *
 * Trois raisons, dans l'ordre d'importance :
 *
 *  1. Ce bandeau ne lui sert à rien. En mode kiosque, Retour et Récents sont
 *     déjà neutralisés (voir KioskManager) et Accueil ramène à l'écran où il
 *     se trouve déjà : trois boutons qui ne font rien, mais qui invitent à
 *     appuyer — exactement le genre de manipulation qui finit par donner le
 *     sentiment que la tablette « ne répond pas ».
 *  2. Il mange une bande de l'écran en permanence, y compris pendant un
 *     appel, sur une dalle où chaque ligne de sous-titre compte.
 *  3. Il trahit l'appareil : Senior Visio doit ressembler à un cadre posé au
 *     mur, pas à une tablette Android sur laquelle on aurait ouvert une app.
 *
 * Mode « sticky » et non permanent : un glissement depuis le bas ramène la
 * barre quelques secondes avant qu'elle ne reparte d'elle-même. C'est
 * volontaire — un intervenant sur place garde un moyen d'y accéder sans
 * qu'un appui malheureux de Jean puisse la faire réapparaître pour de bon.
 *
 * La barre d'état (haut) est laissée en place : elle n'affiche rien
 * d'actionnable et montre l'heure, le Wi-Fi et la batterie, trois
 * informations utiles à qui vient s'occuper de la tablette.
 *
 * À rappeler dans onWindowFocusChanged : Android réaffiche les barres à
 * chaque perte de focus (boîte de dialogue système, notification en plein
 * écran, retour depuis un autre écran), et rien ne les remasque tout seul.
 */
fun Activity.hideNavigationBar() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.navigationBars())
}
