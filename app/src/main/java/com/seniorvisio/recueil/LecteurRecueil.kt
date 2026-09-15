package com.seniorvisio.recueil

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

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
     * @param position rang de l'élément courant, à partir de 1, pour être
     *   montré tel quel (« 3 / 12 »). Zéro quand rien n'est ouvert.
     */
    data class État(
        val rendu: Rendu?,
        val titre: String? = null,
        val position: Int = 0,
        val total: Int = 0,
    )

    private var recueil: Recueil? = null
    private var index: Int = 0

    // Le décodage d'une photo ne se fait pas sur le fil principal : une image
    // d'appareil moderne y prendrait des dizaines de millisecondes, pendant
    // lesquelles la vidéo de l'appel se fige. Un seul fil, car deux photos
    // décodées en parallèle ne servent à rien — seule la dernière demandée
    // compte.
    private val décodeur = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lecteur-recueil").apply { isDaemon = true }
    }
    private val principal = Handler(Looper.getMainLooper())

    // Garde-fou contre le défilement rapide : si le proche appuie trois fois
    // sur « suivant » pendant qu'une photo se décode, seules les demandes
    // encore d'actualité aboutissent. Sans ça, les photos s'afficheraient dans
    // l'ordre où les décodages se terminent, pas dans celui des appuis.
    @Volatile private var demande = 0

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
            publier(État(Rendu.Impossible("Ce recueil n'est pas encore arrivé sur la tablette")))
            return
        }
        if (trouvé.prêts.isEmpty()) {
            recueil = null
            publier(État(Rendu.Impossible("Aucune photo de ce recueil n'a pu être préparée")))
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
        demande++ // annule un décodage en cours qui arriverait après la fermeture
        publier(État(rendu = null))
    }

    fun libérer() {
        décodeur.shutdownNow()
    }

    private fun afficherCourant() {
        val courant = recueil ?: return
        val élément = courant.prêts.getOrNull(index) ?: return
        val fichier = store.fichier(courant, élément)
        val rendu = renduPour(élément.type)
        val mienne = ++demande

        décodeur.execute {
            val résultat = rendu.préparer(élément, fichier)
            principal.post {
                // Périmée : le proche a déjà demandé autre chose entre-temps.
                if (mienne != demande) return@post
                publier(
                    État(
                        rendu = résultat,
                        titre = courant.titre,
                        position = index + 1,
                        total = courant.prêts.size,
                    )
                )
            }
        }
    }

    private fun publier(état: État) {
        if (Looper.myLooper() == Looper.getMainLooper()) onAffichage?.invoke(état)
        else principal.post { onAffichage?.invoke(état) }
    }

    private companion object {
        const val TAG = "LecteurRecueil"
    }
}
