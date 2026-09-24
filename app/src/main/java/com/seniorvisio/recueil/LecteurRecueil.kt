package com.seniorvisio.recueil

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Tient le recueil ouvert et l'élément courant, et prépare ce qu'il faut
 * afficher.
 *
 * ═══ CE QU'IL NE SAIT PAS, ET C'EST LE POINT ═══
 *
 * QUI le commande. `ouvrir`, `suivant`, `précédent`, `fermer` : quatre gestes,
 * sans la moindre idée de leur provenance. Aujourd'hui c'est l'appelant depuis
 * le PWA ; demain ce sera Jean lui-même, du doigt ou de la voix, et ce sera
 * une source d'appels de plus — pas une ligne à changer ici.
 *
 * Il ne sait pas non plus AFFICHER. Il choisit le rendu qui convient au type
 * de l'élément et remet le résultat à qui écoute. L'écran d'appel reste seul
 * maître de sa mise en page.
 *
 * Ces deux ignorances sont les deux coutures décrites dans
 * docs/architecture-recueils.md, section 5. Elles sont la raison d'être de
 * cette classe : sans elles, ajouter la navigation par Jean voudrait dire
 * rouvrir le code de l'appel.
 *
 * ═══ CE QUI EST VOLONTAIREMENT ABSENT ═══
 *
 * Le bouclage. Arrivé à la dernière photo, `suivant()` ne revient pas à la
 * première : il ne fait rien. Un proche qui commente ses photos une par une
 * doit pouvoir savoir qu'il est au bout ; un diaporama qui repart en boucle
 * le lui cacherait, et Jean reverrait les mêmes photos sans comprendre
 * pourquoi.
 */
class LecteurRecueil(private val store: RecueilStore) {

    /**
     * Ce qu'il y a à montrer maintenant. `rendu` à null signifie fermé :
     * l'écran rend la place à la vidéo du proche.
     *
     * Toujours appelé sur le fil principal.
     */
    var onAffichage: ((état: État) -> Unit)? = null

    /**
     * Ce qu'il y a à montrer, et où on en est.
     *
     * ═══ DES FICHIERS, ET PLUS UN RENDU ═══
     *
     * Cette classe décodait la photo courante hors du fil principal et
     * publiait un bitmap. Elle publie désormais la LISTE des fichiers prêts :
     * c'est la visionneuse qui décode, par tuiles, et qui fait glisser d'une
     * image à l'autre (voir VisionneusePhotos).
     *
     * La liste entière et non l'élément courant, pour la même raison que sur
     * l'accueil : le glissement appartient au pager, et lui donner les photos
     * une par une reviendrait à le priver de ce pour quoi on l'a pris.
     *
     * @param position rang de l'élément courant, à partir de 1, pour être
     *   montré tel quel (« 3 / 12 »). Zéro quand rien n'est ouvert.
     * @param problème phrase écrite POUR LE PROCHE quand il n'y a rien à
     *   montrer. Remplace le rendu impossible d'avant, et garde ce qui
     *   comptait : un recueil qui ne s'affiche pas doit le dire, pas laisser
     *   un écran noir au milieu d'un appel.
     */
    data class État(
        val fichiers: List<File> = emptyList(),
        val titre: String? = null,
        val position: Int = 0,
        val total: Int = 0,
        val problème: String? = null,
    )

    private var recueil: Recueil? = null
    private var index: Int = 0

    // ═══ IL Y AVAIT ICI UN FIL DE DÉCODAGE ET UN COMPTEUR DE GÉNÉRATION ═══
    //
    // Ils servaient à ne pas figer la vidéo de l'appel pendant qu'une photo se
    // décodait, et à ce qu'un défilement rapide n'affiche pas les images dans
    // l'ordre où les décodages se terminent. Les deux problèmes ont disparu
    // avec le décodage : plus rien ne se décode ici.
    private val principal = Handler(Looper.getMainLooper())

    val estOuvert: Boolean get() = recueil != null

    /**
     * Ouvre un recueil à un rang donné.
     *
     * Un recueil inconnu ou sans élément affichable ne fait pas échouer
     * l'appel : il le DIT à l'écran. Le proche a composé ce recueil depuis
     * chez lui et croit légitimement qu'il est là ; découvrir devant Jean
     * qu'il n'y est pas, sans un mot d'explication, est le pire moment.
     */
    fun ouvrir(recueilId: String, rang: Int = 0) {
        val trouvé = store.disponibles().firstOrNull { it.id == recueilId }
        if (trouvé == null) {
            Log.w(TAG, "Recueil $recueilId absent de cette tablette")
            recueil = null
            publier(État(problème = "Ce recueil n'est pas encore arrivé sur la tablette"))
            return
        }
        if (trouvé.prêts.isEmpty()) {
            recueil = null
            publier(État(problème = "Aucune photo de ce recueil n'a pu être préparée"))
            return
        }
        recueil = trouvé
        allerA(rang)
    }

    /** Se place à un rang absolu. Hors bornes, on se recale au plus proche. */
    fun allerA(rang: Int) {
        val courant = recueil ?: return
        index = rang.coerceIn(0, courant.prêts.lastIndex)
        afficherCourant()
    }

    fun suivant() {
        val courant = recueil ?: return
        if (index >= courant.prêts.lastIndex) return
        index++
        afficherCourant()
    }

    fun précédent() {
        recueil ?: return
        if (index <= 0) return
        index--
        afficherCourant()
    }

    /** Referme : l'écran redonne sa place à la vidéo du proche. */
    fun fermer() {
        if (recueil == null) return
        recueil = null
        index = 0
        publier(État())
    }

    /** Plus rien à libérer : le fil de décodage est parti avec le décodage. */
    fun libérer() = Unit

    private fun afficherCourant() {
        val courant = recueil ?: return

        // Les fichiers réellement présents. Un élément déclaré PRÊT dont le
        // fichier a disparu — vidage de cache, élagage mal tombé — rend null
        // ici. On l'écarte plutôt que de laisser un trou au milieu du recueil
        // que le proche est en train de commenter.
        val fichiers = courant.prêts.mapNotNull { store.fichier(courant, it) }
        if (fichiers.isEmpty()) {
            publier(État(problème = "Les photos de ce recueil ne sont plus sur la tablette"))
            return
        }

        val rang = index.coerceIn(0, fichiers.lastIndex)
        index = rang
        publier(
            État(
                fichiers = fichiers,
                titre = courant.titre,
                position = rang + 1,
                total = fichiers.size,
            )
        )
    }

    private fun publier(état: État) {
        if (Looper.myLooper() == Looper.getMainLooper()) onAffichage?.invoke(état)
        else principal.post { onAffichage?.invoke(état) }
    }

    private companion object {
        const val TAG = "LecteurRecueil"
    }
}
