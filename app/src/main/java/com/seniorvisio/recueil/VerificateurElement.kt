package com.seniorvisio.recueil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Décide si CETTE tablette sait afficher un élément, en essayant réellement.
 *
 * ═══ POURQUOI LA VÉRIFICATION NE PEUT PAS SE FAIRE AILLEURS ═══
 *
 * Seule la tablette sait ce que la tablette sait afficher. Le navigateur du
 * proche peut parfaitement décoder une image que `BitmapFactory` refusera, et
 * l'inverse est vrai aussi — nous venons d'en faire les frais dans ce sens-là
 * précisément : des photos HEIC que Chrome sur Android ne sait pas ouvrir,
 * alors qu'Android le sait depuis la version 9.
 *
 * Un contrôle fondé sur l'extension ou sur le type MIME serait donc une
 * supposition déguisée en vérification. On décode pour de bon, et ce qui est
 * déclaré PRÊT l'est parce qu'on vient de le faire.
 *
 * ═══ ET ON RÉENCODE DANS LA FOULÉE ═══
 *
 * Le fichier rangé n'est pas l'original mais une image à la définition de la
 * dalle. Deux raisons : une photo de téléphone moderne fait quatre fois la
 * définition de l'écran, et la redécoder à chaque affichage coûterait une
 * seconde d'attente sur une tablette ancienne — celle du banc d'essai met
 * déjà 200 ms à transcrire 30 ms de son.
 *
 * Le réencodage est fait UNE FOIS, à l'installation, quand personne n'attend.
 */
interface VerificateurElement {
    /**
     * Vérifie et range. Rend l'élément mis à jour : PRÊT avec son fichier
     * local, ou REFUSÉ avec une cause écrite POUR LE PROCHE — c'est lui qui
     * la lira dans le PWA, pas un développeur dans un journal.
     */
    fun vérifier(element: Element, téléchargé: File, dossier: File): Element
}

/**
 * Le seul type pris en charge aujourd'hui.
 *
 * @param côtéMax définition de rangement. Prise sur la dalle réelle et non
 *   figée ici : la tablette de Jean et celle du banc d'essai n'ont pas le
 *   même écran, et ranger du 1920 sur une dalle de 1280 ne ferait que
 *   remplir le disque.
 */
class VerificateurPhoto(private val côtéMax: Int) : VerificateurElement {

    override fun vérifier(element: Element, téléchargé: File, dossier: File): Element {
        if (téléchargé.length() == 0L) {
            return element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "Fichier vide — la photo n'a peut-être pas fini d'être envoyée.",
            )
        }

        // Première passe sans allouer l'image : on veut ses dimensions pour
        // calculer le facteur de réduction. Décoder une photo de 12 Mpx en
        // pleine taille sur une tablette ancienne, pour la réduire ensuite,
        // est le meilleur moyen de manquer de mémoire — précisément sur les
        // fichiers qu'on cherche à traiter.
        val mesure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(téléchargé.absolutePath, mesure)
        if (mesure.outWidth <= 0 || mesure.outHeight <= 0) {
            return element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "Format non reconnu par la tablette.",
            )
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = facteurDeRéduction(mesure.outWidth, mesure.outHeight, côtéMax)
        }
        val image = try {
            BitmapFactory.decodeFile(téléchargé.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            // Attrapé explicitement : ce n'est pas une Exception, un catch
            // ordinaire le laisserait passer et emporterait l'installation
            // entière pour une seule photo trop lourde.
            Log.w(TAG, "Mémoire insuffisante pour ${element.id}", e)
            return element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "Photo trop lourde pour cette tablette.",
            )
        } ?: return element.copy(
            état = ÉtatElement.REFUSÉ,
            cause = "Image illisible — fichier probablement abîmé.",
        )

        return try {
            val nom = "${element.id}.jpg"
            FileOutputStream(File(dossier, nom)).use { sortie ->
                image.compress(Bitmap.CompressFormat.JPEG, QUALITÉ_RANGEMENT, sortie)
            }
            element.copy(état = ÉtatElement.PRÊT, cause = null, fichierLocal = nom)
        } catch (e: Exception) {
            Log.e(TAG, "Rangement impossible pour ${element.id}", e)
            element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "La tablette n'a pas pu la ranger (${e.javaClass.simpleName}).",
            )
        } finally {
            image.recycle()
        }
    }

    /**
     * Puissance de deux la plus grande qui garde l'image au-dessus de la
     * définition voulue. `inSampleSize` n'accepte que ça : une valeur
     * intermédiaire est arrondie à la puissance inférieure, donc autant la
     * calculer franchement.
     */
    private fun facteurDeRéduction(largeur: Int, hauteur: Int, cible: Int): Int {
        var facteur = 1
        while (maxOf(largeur, hauteur) / (facteur * 2) >= cible) facteur *= 2
        return facteur
    }

    private companion object {
        const val TAG = "VerificateurPhoto"
        const val QUALITÉ_RANGEMENT = 88
    }
}

/**
 * Ce que la tablette ne sait pas encore afficher — et qui le DIT.
 *
 * Déclaré plutôt qu'absent, et c'est le point : un recueil contenant une
 * vidéo doit pouvoir être installé, décrit, et refuser cet élément-là avec
 * une phrase lisible par le proche. Sans cette classe, un type inconnu
 * produirait soit une exception, soit un élément resté À_VÉRIFIER pour
 * toujours — un recueil bloqué en « installation en cours », sans que rien
 * ne dise pourquoi.
 *
 * Le jour où la vidéo arrive, on remplace cette instance par un vrai
 * vérificateur. Rien d'autre ne bouge.
 */
class VerificateurNonPrisEnCharge(private val type: TypeElement) : VerificateurElement {
    override fun vérifier(element: Element, téléchargé: File, dossier: File): Element =
        element.copy(
            état = ÉtatElement.REFUSÉ,
            cause = "Les éléments de type « ${type.étiquette} » ne sont pas encore " +
                "affichables sur la tablette.",
        )
}

/** À qui confier quoi. Unique table de correspondance type → vérificateur. */
/**
 * Range une œuvre SANS TOUCHER À SA DÉFINITION.
 *
 * ═══ POURQUOI UN SECOND CHEMIN, ET NON UN RÉGLAGE DU PREMIER ═══
 *
 * VerificateurPhoto ré-encode toute image à la définition de la dalle, et
 * c'est juste : une photo de famille se regarde en entier, garder douze
 * mégapixels pour l'afficher sur mille deux cents pixels remplirait le disque
 * sans rien apporter.
 *
 * Une toile de musée se regarde AUTREMENT. La visite guidée agrandit un détail
 * — la touche du pinceau, une signature dans un coin — et ce détail se découpe
 * dans le fichier rangé (voir DecoupeOeuvre). Rangée à 1920 px, la toile ne
 * contient plus le détail qu'on prétend montrer : on agrandirait de la
 * bouillie en croyant montrer la matière.
 *
 * Les deux besoins sont opposés, donc deux chemins. Les mélanger aurait donné
 * un vérificateur avec un drapeau, et un drapeau finit toujours par être posé
 * du mauvais côté.
 *
 * LE FICHIER EST COPIÉ TEL QUEL, sans ré-encodage : un JPEG ré-encodé perd de
 * la matière à chaque passage, et c'est précisément la matière qu'on vient
 * regarder.
 *
 * Il est quand même MESURÉ avant d'être accepté — bornes seulement, sans
 * allouer un seul pixel. Un fichier que la tablette ne saura pas ouvrir doit
 * être refusé à l'installation, pas découvert devant Jean.
 */
class VerificateurOeuvre : VerificateurElement {
    override fun vérifier(element: Element, téléchargé: File, dossier: File): Element {
        val mesure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(téléchargé.absolutePath, mesure)
        if (mesure.outWidth <= 0 || mesure.outHeight <= 0) {
            return element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "Format non reconnu par la tablette.",
            )
        }
        return try {
            val nom = "${element.id}.jpg"
            téléchargé.copyTo(File(dossier, nom), overwrite = true)
            Log.i(
                TAG_OEUVRE,
                "Œuvre rangée sans réduction : ${mesure.outWidth}×${mesure.outHeight} px",
            )
            element.copy(état = ÉtatElement.PRÊT, cause = null, fichierLocal = nom)
        } catch (e: Exception) {
            Log.e(TAG_OEUVRE, "Rangement impossible pour ${element.id}", e)
            element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "La tablette n'a pas pu la ranger (${e.javaClass.simpleName}).",
            )
        }
    }

    private companion object {
        const val TAG_OEUVRE = "VerificateurOeuvre"
    }
}

/**
 * @param oeuvres vrai pour le recueil d'exposition : les images y sont rangées
 *   sans réduction, parce qu'on va découper dedans (voir VerificateurOeuvre).
 */
fun vérificateurPour(
    type: TypeElement,
    côtéMax: Int,
    oeuvres: Boolean = false,
): VerificateurElement = when (type) {
    TypeElement.PHOTO -> if (oeuvres) VerificateurOeuvre() else VerificateurPhoto(côtéMax)
    else -> VerificateurNonPrisEnCharge(type)
}
