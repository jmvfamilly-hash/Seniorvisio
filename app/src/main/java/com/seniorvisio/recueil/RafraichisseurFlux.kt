package com.seniorvisio.recueil

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.seniorvisio.BuildConfig
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.CallTrace
import com.seniorvisio.core.TelechargementHttp
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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

    private val config: AdminConfig by lazy { AdminConfig(context) }

    /**
     * Les adresses à lire, dans l'ordre. Cet ORDRE compte : c'est lui qui
     * départage deux articles publiés à la même seconde (voir
     * SelectionActualites).
     *
     * La liste de l'administrateur l'emporte sur celle livrée avec l'APK. Les
     * lignes vides et les espaces sont écartés — une liste saisie à la main
     * dans un champ de texte en contient toujours.
     */
    private fun adresses(): List<String> {
        val brut = config.fluxActualites.takeIf { it.isNotBlank() }
            ?: BuildConfig.FLUX_ACTUALITES
        return brut.split(",", "\n", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    val actif: Boolean get() = adresses().isNotEmpty()

    fun démarrer() {
        if (démarré || !actif) return
        démarré = true
        // Le contrôle passe souvent, le remplacement rarement : c'est
        // siDûRafraîchir qui décide, et il ne décide qu'une fois par jour.
        // Un contrôle au quart d'heure ne coûte rien — il ne fait que comparer
        // deux dates — et il rattrape le cas d'une tablette allumée en continu
        // qui doit basculer à 7 h sans que personne ne la touche.
        ordonnanceur.scheduleWithFixedDelay(
            ::siDûRafraîchir, 0, INTERVALLE_CONTROLE_MINUTES, TimeUnit.MINUTES
        )
    }

    /**
     * Le fil est-il périmé ?
     *
     * ═══ UNE SEULE BORNE, PAS UNE LISTE DE CAS ═══
     *
     * L'administrateur a demandé un remplacement complet « une fois par jour, à
     * partir de 7 h le matin ou au démarrage ». Écrit comme une suite de cas —
     * au démarrage, à 7 h, sauf si déjà fait, sauf si redémarré entre-temps —
     * cela produit des règles qui se contredisent aux heures creuses.
     *
     * Une seule comparaison suffit : le dernier remplacement est-il antérieur à
     * la DERNIÈRE ÉCHÉANCE DE 7 H DÉJÀ PASSÉE ? Elle couvre tout :
     *
     *   - tablette allumée depuis hier, il est 7 h 01 → échéance = aujourd'hui
     *     7 h, dernier remplacement hier → on remplace ;
     *   - elle redémarre à 15 h, remplacement déjà fait ce matin → échéance =
     *     aujourd'hui 7 h, remplacement à 7 h 02 → on ne fait rien ;
     *   - elle redémarre à 6 h → échéance = HIER 7 h, remplacement d'hier
     *     matin → on ne fait rien, et les titres d'hier restent jusqu'à 7 h ;
     *   - tablette neuve, jamais rafraîchie → zéro est antérieur à tout → on
     *     remplace, quelle que soit l'heure.
     *
     * Le dernier cas est la raison pour laquelle « ou au démarrage » figure
     * dans la demande : une tablette qui sort du carton ne doit pas attendre le
     * lendemain matin pour montrer quelque chose.
     */
    private fun siDûRafraîchir() {
        val échéance = dernièreÉchéanceDe7h()
        if (config.fluxDernierRafraichissementMs >= échéance) return
        rafraîchir()
    }

    /** L'instant de la dernière échéance de 7 h déjà passée, en millisecondes. */
    private fun dernièreÉchéanceDe7h(): Long {
        val zone = ZoneId.systemDefault()
        val maintenant = java.time.ZonedDateTime.now(zone)
        val septHeuresAujourdhui = maintenant.toLocalDate()
            .atTime(LocalTime.of(HEURE_REMPLACEMENT, 0))
            .atZone(zone)
        val échéance = if (maintenant.isBefore(septHeuresAujourdhui)) {
            septHeuresAujourdhui.minusDays(1)
        } else {
            septHeuresAujourdhui
        }
        return échéance.toInstant().toEpochMilli()
    }

    fun arrêter() {
        démarré = false
        ordonnanceur.shutdownNow()
    }

    /**
     * Lit tous les fils, n'en garde que le jour, et remplace le recueil.
     *
     * ═══ REMPLACEMENT COMPLET, ET NON AJOUT ═══
     *
     * Le document est réécrit entièrement : ce qui n'est plus dans les fils
     * disparaît de l'écran. C'est ce que « remplacer complètement » veut dire,
     * et c'est ce qui permet au filtre sur la date du jour d'avoir un effet —
     * accumuler aurait gardé les titres d'hier à côté de ceux d'aujourd'hui,
     * exactement ce que le filtre cherche à éviter.
     *
     * Un fil injoignable ne fait pas échouer les autres : on lit ce qu'on peut.
     * Sur trois fils dont un serveur est en panne, mieux vaut vingt titres que
     * zéro.
     */
    private fun rafraîchir() {
        val adresses = adresses()
        if (adresses.isEmpty()) return

        val parFlux = mutableListOf<List<FluxRss.Titre>>()
        var injoignables = 0
        adresses.forEachIndexed { rang, adresse ->
            val brut = File(dossierCache, "flux-actualites-$rang.xml")
            try {
                TelechargementHttp.vers(adresse, brut, "fil d'information $rang")
                // Pas de plafond par fil : le plafond de trente s'applique
                // APRÈS la fusion. Couper chaque fil à trente avant de les
                // réunir jetterait des articles récents d'un fil abondant pour
                // garder des articles anciens d'un fil pauvre.
                // .add() et non « += » : parFlux est une liste DE LISTES, donc
                // « += uneListe » se résout vers plus() — qui construit une
                // nouvelle liste et tente de la réassigner à un val — au lieu
                // de plusAssign(). Le compilateur répond « Val cannot be
                // reassigned », ce qui ne désigne pas du tout la vraie cause.
                parFlux.add(brut.inputStream().use { FluxRss.analyser(it, maximum = Int.MAX_VALUE) })
            } catch (e: Exception) {
                // Un réseau absent ou un serveur en panne : on note et on
                // continue. La liste doit garder sa place dans l'ordre, sinon
                // la répartition à égalité de date changerait de sens.
                Log.w(TAG, "Fil $rang injoignable ($adresse)", e)
                injoignables++
                parFlux.add(emptyList())
            } finally {
                brut.delete()
            }
        }

        val zone = ZoneId.systemDefault()
        val titres = SelectionActualites.choisir(parFlux, LocalDate.now(zone), zone)
        val sansDate = SelectionActualites.écartésFauteDeDate(parFlux)

        CallTrace.record(
            "FLUX lecture",
            "${adresses.size} fil(s), $injoignables injoignable(s) · " +
                "lus ${parFlux.sumOf { it.size }} · sans date ${sansDate.sum()} · " +
                "retenus du jour ${titres.size}",
        )

        if (titres.isEmpty()) {
            // On ne PUBLIE PAS un flux vide par-dessus un flux qui marchait.
            // Une panne passagère des serveurs, ou un matin où rien n'est
            // encore paru, effacerait les titres déjà installés — et Jean se
            // retrouverait devant un recueil vide au lieu des titres d'hier,
            // qui, eux, étaient lisibles.
            //
            // La date du dernier remplacement n'est PAS mise à jour : la
            // tentative recommencera au prochain contrôle, dans le quart
            // d'heure, au lieu d'attendre demain 7 h.
            Log.w(TAG, "Aucun titre du jour : l'état précédent est conservé")
            return
        }

        publier(titres)
        config.fluxDernierRafraichissementMs = System.currentTimeMillis()
        Log.i(TAG, "${titres.size} titres publiés")
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
                "origine" to (titre.origine ?: ""),
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
         * Le contrôle, pas le téléchargement.
         *
         * Passer au quart d'heure ne coûte rien — deux dates comparées — et
         * c'est ce qui permet à une tablette allumée en continu de basculer
         * à 7 h sans que personne ne la touche. Le téléchargement, lui, n'a
         * lieu qu'une fois par jour (voir siDûRafraîchir).
         */
        const val INTERVALLE_CONTROLE_MINUTES = 15L

        /**
         * Sept heures, comme demandé par l'administrateur.
         *
         * Volontairement NON relié à nightEndHour, qui vaut aussi sept par
         * défaut : ce sont deux réglages sans rapport — l'un dit quand la
         * journée de Jean commence, l'autre quand les titres du jour sont
         * disponibles chez les éditeurs. Les coudre ensemble ferait qu'avancer
         * l'un déplacerait l'autre sans qu'on l'ait voulu.
         */
        const val HEURE_REMPLACEMENT = 7
    }
}
