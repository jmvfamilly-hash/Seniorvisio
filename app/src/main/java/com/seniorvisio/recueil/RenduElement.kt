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
     * Un texte à lire, et l'illustration qui l'accompagne — ou rien.
     *
     * La vignette est FACULTATIVE, et ce n'est pas une précaution de style :
     * tous les fils d'information n'en fournissent pas, et rien ne garantit
     * qu'un article donné en ait une. Un écran qui réserverait la moitié de sa
     * place à une image absente donnerait un titre serré à côté d'un trou.
     * L'écran d'appel s'en sert donc pour choisir sa disposition (voir
     * IncomingCallActivity.afficherRecueil).
     */
    data class Texte(val texte: String, val vignette: Bitmap?) : Rendu()

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
class RenduPhoto : RenduElement {
    override fun préparer(element: Element, fichier: File?): Rendu {
        if (fichier == null || !fichier.exists()) {
            // Le document Firestore dit « prêt » mais le fichier n'est plus là :
            // vidage du cache, élagage mal tombé, désinstallation partielle.
            // Ça se dit, plutôt que d'afficher un écran vide sans explication.
            return Rendu.Impossible("Cette photo n'est plus sur la tablette")
        }
        return try {
            val bitmap = BitmapFactory.decodeFile(fichier.absolutePath)
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
 * Les titres d'un fil d'information, et leur vignette si le flux en donne une.
 *
 * Le texte voyage dans le document du recueil, pas dans un fichier : un titre
 * pèse cent octets. La vignette, elle, est un vrai fichier image, rangée et
 * vérifiée par le même chemin qu'une photo de famille — un flux public n'a pas
 * plus le droit qu'un proche d'envoyer à cette tablette une image qu'elle ne
 * sait pas décoder.
 */
class RenduTexte : RenduElement {
    override fun préparer(element: Element, fichier: File?): Rendu {
        val texte = element.texte?.takeIf { it.isNotBlank() }
            ?: return Rendu.Impossible("Ce texte est arrivé vide")

        // L'absence de vignette n'est PAS un échec : c'est le cas courant.
        // Un titre sans image reste un titre, et il occupera toute la largeur.
        val vignette = fichier?.takeIf { it.exists() }?.let {
            try {
                BitmapFactory.decodeFile(it.absolutePath)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Vignette trop lourde pour ${element.id}", e)
                null
            } catch (e: Exception) {
                Log.w(TAG, "Vignette illisible pour ${element.id}", e)
                null
            }
        }
        return Rendu.Texte(texte, vignette)
    }

    private companion object {
        const val TAG = "RenduTexte"
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
fun renduPour(type: TypeElement): RenduElement = when (type) {
    TypeElement.PHOTO -> RenduPhoto()
    TypeElement.VIDEO -> RenduNonPrisEnCharge("Une vidéo")
    TypeElement.TEXTE -> RenduTexte()
    TypeElement.INCONNU -> RenduNonPrisEnCharge("Un contenu d'un type inconnu")
}
