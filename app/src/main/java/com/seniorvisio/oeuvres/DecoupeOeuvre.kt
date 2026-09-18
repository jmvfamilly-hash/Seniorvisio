package com.seniorvisio.oeuvres

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Montre un détail d'une toile sans jamais la charger en entier.
 *
 * ═══ LE SEUL MOYEN DE TENIR LA HAUTE DÉFINITION SUR CETTE TABLETTE ═══
 *
 * Une toile de musée fait couramment cent mégapixels. Décodée d'un bloc en
 * ARGB_8888, elle pèse quatre cents mégaoctets — plus que tout ce que ce
 * projet a passé deux jours à traquer, pour UNE image.
 *
 * BitmapRegionDecoder décode UNIQUEMENT le rectangle demandé. La mémoire est
 * donc bornée par la dalle, jamais par l'œuvre : un détail de cent mégapixels
 * coûte exactement autant qu'un détail de deux. C'est ce qui rend le zoom
 * possible ici, et rien d'autre ne le rendrait possible.
 *
 * ═══ ET C'EST POURQUOI LE FICHIER D'ORIGINE DOIT SURVIVRE ═══
 *
 * Le rangement ordinaire ré-encode toute image à 1920 px de côté (voir
 * VerificateurPhoto, CÔTÉ_PLAFOND) : c'est juste pour une photo de famille
 * qu'on regarde en entier, et absurde pour une toile dont on veut voir la
 * touche du pinceau. Agrandir un détail dans un fichier déjà réduit ne montre
 * que de la bouillie.
 *
 * Les œuvres passent donc par un rangement qui garde l'original intact (voir
 * VerificateurOeuvre). Les deux chemins coexistent, et c'est voulu : ils
 * servent deux usages opposés.
 */
object DecoupeOeuvre {

    private const val TAG = "DecoupeOeuvre"

    /**
     * Quelle part de la toile un détail occupe, en largeur.
     *
     * Un tiers : assez serré pour qu'on voie la matière, assez large pour
     * qu'on reconnaisse ce qu'on regarde. Plus étroit, le détail devient une
     * abstraction dont personne ne sait de quelle partie du tableau elle
     * vient — et Jean n'a aucun moyen de reculer pour vérifier.
     */
    private const val PART_DU_DETAIL = 1f / 3f

    /**
     * Le rectangle à découper autour d'un point, en pixels de la toile.
     *
     * Arithmétique pure, séparée du décodage : c'est la partie où une erreur
     * de bord passe inaperçue à l'œil — un détail décalé ressemble à un détail.
     * Elle s'éprouve donc hors d'Android.
     *
     * RECADRÉ, ET NON DÉPLACÉ, quand le point est près d'un bord : un point à
     * x=0,96 (une signature dans le coin) verrait sinon sa fenêtre glisser
     * vers le centre, et la signature sortirait du cadre. La fenêtre est
     * poussée à l'intérieur en gardant sa taille.
     */
    fun regionAutour(largeur: Int, hauteur: Int, x: Float, y: Float): Rect {
        val côté = (minOf(largeur, hauteur) * PART_DU_DETAIL).toInt().coerceAtLeast(1)
        val demi = côté / 2
        val cx = (x.coerceIn(0f, 1f) * largeur).toInt()
        val cy = (y.coerceIn(0f, 1f) * hauteur).toInt()
        var gauche = (cx - demi).coerceIn(0, (largeur - côté).coerceAtLeast(0))
        var haut = (cy - demi).coerceIn(0, (hauteur - côté).coerceAtLeast(0))
        val droite = (gauche + côté).coerceAtMost(largeur)
        val bas = (haut + côté).coerceAtMost(hauteur)
        // Une toile plus petite que la fenêtre voulue : on la prend entière.
        if (droite - gauche <= 0) gauche = 0
        if (bas - haut <= 0) haut = 0
        return Rect(gauche, haut, maxOf(droite, gauche + 1), maxOf(bas, haut + 1))
    }

    /**
     * Le détail, décodé à la définition voulue et pas au-delà.
     *
     * ═══ côtéVisé EST LA LARGEUR DE LA ZONE, PAS CELLE DE LA DALLE ═══
     *
     * La distinction n'est pas de la pédanterie, elle vaut un facteur quatre.
     * inSampleSize ne procède que par puissances de deux, et la règle garde la
     * région AU-DESSUS de la cible : viser 1920 sur une région de 3000 px rend
     * donc un facteur 1, soit 3000×3000 = 36 Mo. Viser 1000 — la largeur
     * réelle qu'occupe l'œuvre sur l'écran d'accueil — rend un facteur 2, soit
     * 1500×1500 = 9 Mo, pour une netteté que l'œil ne distingue pas puisque la
     * zone ne fait pas 3000 pixels de large.
     *
     * Éprouvé sur des toiles de 4 à 1200 mégapixels : le coût d'un détail
     * reste sous vingt mégaoctets dans tous les cas. C'est la propriété qui
     * compte — la mémoire est bornée par la ZONE, jamais par l'œuvre.
     *
     * Rend null plutôt que de lever : un fichier abîmé ne doit pas emporter la
     * visite, et l'appelant retombera sur la vue d'ensemble.
     */
    fun détail(fichier: File, x: Float, y: Float, côtéVisé: Int): Bitmap? = try {
        val mesure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(fichier.absolutePath, mesure)
        if (mesure.outWidth <= 0 || mesure.outHeight <= 0) {
            null
        } else {
            val région = regionAutour(mesure.outWidth, mesure.outHeight, x, y)
            val décodeur = ouvrir(fichier)
            try {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = réduction(région.width(), côtéVisé)
                }
                décodeur?.decodeRegion(région, options)
            } finally {
                décodeur?.recycle()
            }
        }
    } catch (e: OutOfMemoryError) {
        // Attrapé explicitement : ce n'est pas une Exception. Déjà payé dans ce
        // projet, au même endroit (voir VerificateurPhoto).
        Log.w(TAG, "Mémoire insuffisante pour un détail de ${fichier.name}", e)
        null
    } catch (e: Exception) {
        Log.w(TAG, "Détail illisible dans ${fichier.name}", e)
        null
    }

    @Suppress("DEPRECATION")
    private fun ouvrir(fichier: File): BitmapRegionDecoder? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(fichier)
        } else {
            BitmapRegionDecoder.newInstance(fichier.absolutePath, false)
        }

    /**
     * Puissance de deux la plus grande qui garde la région au-dessus de la
     * définition voulue. Même calcul que pour les photos — inSampleSize
     * n'accepte que ça.
     */
    fun réduction(côtéRégion: Int, cible: Int): Int {
        var facteur = 1
        while (côtéRégion / (facteur * 2) >= cible) facteur *= 2
        return facteur
    }
}
