package com.seniorvisio.recueil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * Ce qu'on peut mettre à l'écran pour un élément — ou pourquoi on ne peut pas.
 *
 * ═══ POURQUOI UN RÉSULTAT ET NON UN APPEL À UNE VUE ═══
 *
 * Un rendu ne touche aucune vue Android. Il produit quelque chose d'affichable
 * et laisse l'écran décider où le poser.
 *
 * Deux raisons, et la seconde compte plus que la première : cela permet de
 * décoder hors du fil principal sans qu'un rendu ait à connaître les fils, et
 * surtout cela laisse l'écran d'appel seul maître de sa mise en page. Ce
 * projet a déjà payé deux fois, dans la même journée, le fait que plusieurs
 * endroits se disputent la géométrie de l'écran de Jean.
 *
 * ═══ L'AXE D'EXTENSION ═══
 *
 * Ajouter la vidéo, ce sera une variante de plus ici et un RenduVideo — sans
 * toucher au lecteur, ni aux commandes, ni à l'écran (voir
 * docs/architecture-recueils.md, section 5).
 */
sealed class Rendu {
    /** Une image prête à poser. */
    data class Image(val bitmap: Bitmap) : Rendu()

    /**
     * Rien à montrer, et une phrase qui dit pourquoi.
     *
     * Écrite pour être lue par Jean s'il faut, donc sans terme technique : un
     * élément qu'on ne sait pas afficher doit se voir, pas disparaître en
     * silence au milieu d'un recueil qu'on lui présente.
     */
    data class Impossible(val raison: String) : Rendu()
}

/**
 * Sait transformer un élément en quelque chose d'affichable.
 *
 * Un par type. Le lecteur choisit le bon d'après [Element.type] et ne sait
 * rien de plus — c'est ce qui permet d'ajouter un type sans le modifier.
 */
/**
 * Décode un fichier image en le réduisant à la taille où il sera VU.
 *
 * ═══ CE QUE COÛTAIT L'ABSENCE DE CETTE FONCTION ═══
 *
 * decodeFile() sans options rend l'image en pleine définition, en ARGB_8888.
 * Les fichiers rangés font jusqu'à 1920 px de côté (voir RecueilStore.CÔTÉ_
 * PLAFOND) : 1920 × 1080 × 4 octets, soit huit mégaoctets et demi par vignette.
 *
 * Le journal a montré le tas natif passer de 39 à 759 mégaoctets en dix
 * secondes pendant que les titres défilaient. Sept cent vingt mégaoctets
 * divisés par huit et demi font quatre-vingt-sept images — et les titres
 * défilaient à cinq par seconde.
 *
 * ═══ POURQUOI RIEN NE LES REPRENAIT ═══
 *
 * Depuis Android 8, les pixels vivent dans le tas NATIF, mais le ramasse-
 * miettes se déclenche sur la pression du tas JAVA. Celui-ci est resté entre 9
 * et 20 mégaoctets sur 192 pendant toute la montée : il n'a jamais été assez
 * plein pour qu'une collecte parte, donc les images mortes n'ont jamais été
 * rendues. Le tas natif est monté jusqu'à 883 mégaoctets et y est resté une
 * heure, le système annonçant sans arrêt qu'il allait tuer des services.
 *
 * Réduire ne suffit pas à guérir cela — cela divise la vitesse de la fuite,
 * pas la fuite. C'est le recyclage explicite, côté écran, qui rend les octets
 * (voir HomeZonesController.afficherPhoto). Mais décoder huit mégaoctets
 * pour en afficher deux était de toute façon du gâchis pur.
 */
private fun décoderRéduit(fichier: File, côtéVisé: Int, tag: String): Bitmap? {
    // Première passe sans allouer : on veut les dimensions, pas l'image.
    // Décoder en grand pour réduire ensuite serait exactement la dépense qu'on
    // cherche à éviter.
    val mesure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(fichier.absolutePath, mesure)
    if (mesure.outWidth <= 0 || mesure.outHeight <= 0) return null

    var facteur = 1
    while (maxOf(mesure.outWidth, mesure.outHeight) / (facteur * 2) >= côtéVisé) facteur *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = facteur }
    val image = BitmapFactory.decodeFile(fichier.absolutePath, options)
    if (image != null && facteur > 1) {
        Log.d(
            tag,
            "${mesure.outWidth}×${mesure.outHeight} réduit d'un facteur $facteur " +
                "→ ${image.width}×${image.height}",
        )
    }
    return image
}

interface RenduElement {
    fun préparer(element: Element, fichier: File?): Rendu
}

/**
 * Les photos, décodées depuis le fichier que la tablette a rangé.
 *
 * Aucune réduction ici : c'est déjà fait à l'installation, par
 * [VerificateurPhoto], à la définition de cette dalle-ci. Refaire le travail
 * à chaque affichage coûterait un temps visible entre deux photos, pour un
 * résultat identique.
 */
class RenduPhoto(private val côtéVisé: Int) : RenduElement {
    override fun préparer(element: Element, fichier: File?): Rendu {
        if (fichier == null || !fichier.exists()) {
            // Le document Firestore dit « prêt » mais le fichier n'est plus là :
            // vidage du cache, élagage mal tombé, désinstallation partielle.
            // Ça se dit, plutôt que d'afficher un écran vide sans explication.
            return Rendu.Impossible("Cette photo n'est plus sur la tablette")
        }
        return try {
            val bitmap = décoderRéduit(fichier, côtéVisé, TAG)
                ?: return Rendu.Impossible("Cette photo n'a pas pu être ouverte")
            Rendu.Image(bitmap)
        } catch (e: OutOfMemoryError) {
            // OutOfMemoryError est une Error, pas une Exception : un catch
            // ordinaire la laisse passer et emporte l'appel en cours. Déjà vu
            // dans ce projet, sur ce même sujet (voir VerificateurPhoto).
            Log.e(TAG, "Mémoire insuffisante pour ${element.id}", e)
            Rendu.Impossible("Cette photo est trop lourde pour la tablette")
        } catch (e: Exception) {
            Log.e(TAG, "Décodage impossible pour ${element.id}", e)
            Rendu.Impossible("Cette photo n'a pas pu être ouverte")
        }
    }

    private companion object {
        const val TAG = "RenduPhoto"
    }
}

/**
 * Les types déclarés mais pas encore affichables.
 *
 * Existe DÈS MAINTENANT, et ce n'est pas de la place perdue : un recueil
 * composé depuis un PWA plus récent que cette tablette peut contenir une
 * vidéo. Sans ce rendu, le lecteur tomberait sur un type qu'il ne sait pas
 * traiter au milieu d'un appel. Avec lui, Jean voit « une vidéo, que cette
 * tablette ne sait pas encore lire » et son proche passe à la suivante.
 */
class RenduNonPrisEnCharge(private val quoi: String) : RenduElement {
    override fun préparer(element: Element, fichier: File?): Rendu =
        Rendu.Impossible("$quoi — cette tablette ne sait pas encore l'afficher")
}

/**
 * Le rendu qui convient à un type.
 *
 * Unique endroit où la correspondance est écrite : ajouter un type, c'est
 * ajouter une ligne ici et une classe, et rien d'autre.
 */
fun renduPour(type: TypeElement, côtéVisé: Int): RenduElement = when (type) {
    TypeElement.PHOTO -> RenduPhoto(côtéVisé)
    TypeElement.VIDEO -> RenduNonPrisEnCharge("Une vidéo")
    TypeElement.TEXTE -> RenduNonPrisEnCharge("Un texte")
    TypeElement.INCONNU -> RenduNonPrisEnCharge("Un contenu d'un type inconnu")
}
