package com.seniorvisio.recueil

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.seniorvisio.BuildConfig
import com.seniorvisio.core.TelechargementHttp
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tient à jour un recueil fait des titres d'un fil d'information.
 *
 * ═══ POURQUOI UN RECUEIL, ET PAS UN MÉCANISME À PART ═══
 *
 * Parce que c'est exactement ce que l'architecture prévoyait : un élément de
 * nature FLUX « se rafraîchit périodiquement vers une forme simplifiée,
 * elle-même rangée localement », et « les deux aboutissent au même endroit :
 * quelque chose que la tablette sait afficher hors ligne — le lecteur ne fait
 * pas la différence » (docs/architecture-recueils.md, § 5).
 *
 * En publiant les titres sous la forme d'un recueil ordinaire, tout ce qui
 * existe déjà s'applique sans une ligne de plus : le magasin l'installe et le
 * vérifie, le lecteur le fait défiler, le PWA le liste dans son menu et le
 * pilote avec les mêmes flèches que les photos. Un mécanisme séparé aurait
 * demandé un second canal de commande, un second affichage, et deux fois les
 * pannes.
 *
 * ═══ ÉTEINT PAR DÉFAUT ═══
 *
 * L'adresse du flux vient de BuildConfig, et elle est VIDE en production. Un
 * fil d'information qui apparaîtrait de lui-même chez Jean serait un
 * changement d'écran que personne ne lui a demandé, et qu'il n'aurait aucun
 * moyen de faire disparaître.
 */
class RafraichisseurFlux(private val context: Context) {

    /**
     * PARESSEUX, et ce n'est pas une optimisation.
     *
     * Les propriétés d'un Service sont initialisées à sa CONSTRUCTION, avant
     * qu'Android ne lui attache son contexte de base. Lire context.cacheDir
     * tout de suite lèverait donc au démarrage du service — c'est-à-dire au
     * lancement de l'application, avant tout le reste. C'est la raison pour
     * laquelle RecueilStore, juste à côté, ne retient lui aussi qu'une
     * référence et n'ouvre ses dossiers qu'à l'usage.
     */
    private val dossierCache: File by lazy { context.cacheDir }

    private val ordonnanceur = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SeniorVisio-Flux").apply { isDaemon = true }
    }
    private var démarré = false

    val actif: Boolean get() = BuildConfig.FLUX_ACTUALITES.isNotBlank()

    fun démarrer() {
        if (démarré || !actif) return
        démarré = true
        // Premier passage tout de suite : sans lui, un redémarrage de tablette
        // laisserait le flux vide jusqu'à la première demi-heure, c'est-à-dire
        // précisément pendant l'appel du matin.
        ordonnanceur.scheduleWithFixedDelay(
            ::rafraîchir, 0, INTERVALLE_MINUTES, TimeUnit.MINUTES
        )
    }

    fun arrêter() {
        démarré = false
        ordonnanceur.shutdownNow()
    }

    private fun rafraîchir() {
        val brut = File(dossierCache, "flux-actualites.xml")
        try {
            TelechargementHttp.vers(BuildConfig.FLUX_ACTUALITES, brut, "fil d'information")
            val titres = brut.inputStream().use { FluxRss.analyser(it) }
            if (titres.isEmpty()) {
                // On ne PUBLIE PAS un flux vide par-dessus un flux qui marchait.
                // Une panne passagère du serveur effacerait alors les titres
                // déjà installés, et Jean se retrouverait devant un recueil
                // vide au lieu des titres d'hier — qui, eux, étaient lisibles.
                Log.w(TAG, "Aucun titre lu : l'état précédent est conservé")
                return
            }
            publier(titres)
            Log.i(TAG, "${titres.size} titres publiés")
        } catch (e: Exception) {
            // Même raison : un réseau absent ne doit rien effacer.
            Log.w(TAG, "Fil d'information injoignable — état précédent conservé", e)
        } finally {
            brut.delete()
        }
    }

    /**
     * Écrit le recueil que le magasin installera et que le PWA listera.
     *
     * L'identifiant des éléments est dérivé du TEXTE du titre, pas de son rang.
     * Un rang changerait à chaque rafraîchissement — l'article qui était
     * troisième passe deuxième — et la tablette retéléchargerait toutes les
     * vignettes plusieurs fois par heure pour des images déjà rangées. Dérivé
     * du titre, un article garde son identité tant qu'il est au sommaire.
     */
    private fun publier(titres: List<FluxRss.Titre>) {
        val éléments = titres.mapIndexed { index, titre ->
            mapOf(
                "id" to "t" + Integer.toHexString(titre.texte.hashCode()),
                "type" to TypeElement.TEXTE.étiquette,
                "nature" to "flux",
                "source" to (titre.vignette ?: ""),
                "texte" to titre.texte,
                "ordre" to index,
            )
        }
        FirebaseFirestore.getInstance()
            .document("devices/${BuildConfig.DEVICE_ID}")
            .collection("recueils")
            .document(RECUEIL_ID)
            .set(
                mapOf(
                    "titre" to "Les titres de l'actualité",
                    "crééPar" to "le fil d'information",
                    "crééLe" to java.time.Instant.now().toString(),
                    "elements" to éléments,
                )
            )
            .addOnFailureListener { e -> Log.e(TAG, "Recueil du flux non publié", e) }
    }

    private companion object {
        const val TAG = "RafraichisseurFlux"

        /** Identifiant fixe : un seul recueil de flux, réécrit, jamais empilé. */
        const val RECUEIL_ID = "flux-actualites"

        /**
         * Une demi-heure. Un fil de titres bouge quelques fois par heure au
         * plus ; interroger plus souvent userait la batterie et le forfait
         * pour réécrire les mêmes titres.
         */
        const val INTERVALLE_MINUTES = 30L
    }
}
