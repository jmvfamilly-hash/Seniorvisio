package com.seniorvisio.core

import android.os.SystemClock

/**
 * Le fil d'information est-il vivant, ou vide — et si vide, depuis quand.
 *
 * ═══ LA QUESTION À LAQUELLE LE JOURNAL NE RÉPONDAIT PAS ═══
 *
 * On pouvait lire dans le journal que la lecture des flux avait échoué
 * (FLUX ÉCHEC), que les serveurs étaient muets (FLUX injoignable) ou que rien
 * n'était daté d'aujourd'hui (FLUX lecture · retenus du jour 0).
 *
 * On ne pouvait PAS lire le cas le plus courant et le plus embêtant : la
 * tablette n'a aucun titre installé, se redonne rendez-vous dans un quart
 * d'heure, et recommence indéfiniment. Ce chemin n'écrivait que dans logcat,
 * c'est-à-dire nulle part pour qui relève le journal à distance. Vu de loin,
 * « aucune ligne » se lisait exactement comme « la tablette est éteinte ».
 *
 * ═══ ET UN BILAN, PLUTÔT QU'UNE LIGNE PAR TITRE ═══
 *
 * Chaque affichage écrivait sa propre ligne. Le tampon qui garde l'état de la
 * machine en compte soixante : sur un journal relevé en fin de journée, les
 * cinquante-sept lignes d'affichage avaient évincé les soixante-quinze
 * précédentes — dont toutes les lignes FLUX, qu'on y avait fait entrer
 * justement pour répondre à cette question.
 *
 * L'instrument censé dire si le fil est vivant avait chassé celui qui dit s'il
 * a été lu. Les affichages sont donc comptés ici et résumés en UNE ligne sur
 * le battement du service : même réponse, soixante fois moins de place.
 *
 * ═══ AUCUNE DONNÉE PERSONNELLE ═══
 *
 * Des compteurs, un rang, et le nom du fil tel qu'il se nomme lui-même
 * (« Le Monde.fr — … »). Jamais le titre d'un article, qui raconterait ce que
 * Jean a sous les yeux.
 */
object VieDuFil {

    @Volatile private var affichages = 0
    @Volatile private var dernierRang = -1
    @Volatile private var dernierTotal = 0
    @Volatile private var dernièreOrigine: String? = null

    /**
     * Quand la liste a été trouvée vide pour la première fois, ou 0.
     *
     * L'INSTANT et non un booléen : « aucun titre » au premier contrôle du
     * matin est normal, « aucun titre depuis quatre heures » est une panne. Le
     * même fait, et deux conclusions opposées — seule la durée les sépare.
     */
    @Volatile private var videDepuisMs = 0L

    @Synchronized
    fun noterAffichage(rang: Int, total: Int, origine: String?) {
        affichages++
        dernierRang = rang
        dernierTotal = total
        dernièreOrigine = origine
        videDepuisMs = 0L
    }

    /** L'ordonnanceur n'a trouvé aucun titre installé à montrer. */
    @Synchronized
    fun noterAucunTitre() {
        if (videDepuisMs == 0L) videDepuisMs = SystemClock.elapsedRealtime()
    }

    /**
     * Une ligne par battement, et une seule — appelée par le service.
     *
     * Trois sorties possibles, et le silence en est une : un titre posé qui
     * reste affiché une demi-heure ne produit rien, ce qui est le régime
     * normal. Ce sont les deux autres qui portent l'information, et elles ne
     * peuvent pas être confondues.
     */
    @Synchronized
    fun publierBilan() {
        val vide = videDepuisMs
        if (affichages > 0) {
            CallTrace.record(
                "ACCUEIL actualité vivant",
                "$affichages affichage(s) depuis le battement précédent · " +
                    "dernier ${dernierRang + 1}/$dernierTotal · " +
                    "origine=${dernièreOrigine ?: "ABSENTE"}",
            )
            affichages = 0
            return
        }
        if (vide != 0L) {
            val minutes = (SystemClock.elapsedRealtime() - vide) / 60_000L
            CallTrace.record(
                "ACCUEIL actualité vide",
                "aucun titre installé depuis $minutes min — nouvel essai toutes les 15 min",
            )
        }
    }
}
