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
        // ═══ ENVELOPPÉ, ET CE N'EST PAS UNE PRÉCAUTION DE STYLE ═══
        //
        // scheduleWithFixedDelay ANNULE DÉFINITIVEMENT la tâche si elle lève,
        // et sans un mot. Une seule exception — un serveur qui rend du XML
        // illisible, une écriture Firestore refusée — et le fil d'information
        // s'arrêterait pour toujours, jusqu'au prochain redémarrage de la
        // tablette. Le symptôme serait « les news ne changent plus », des
        // jours après la cause, sans rien pour les relier.
        ordonnanceur.scheduleWithFixedDelay(
            {
                runCatching { siDûRafraîchir() }
                    .onFailure { e ->
                        Log.e(TAG, "Rafraîchissement du fil en échec", e)
                        CallTrace.record(
                            "FLUX ÉCHEC",
                            "${e.javaClass.simpleName} : ${e.message ?: "sans message"} — " +
                                "nouvel essai au prochain contrôle",
                        )
                    }
            },
            0, INTERVALLE_CONTROLE_MINUTES, TimeUnit.MINUTES,
        )
    }

    /**
     * Relit les fils TOUT DE SUITE, après avoir effacé ce qui est affiché.
     *
     * ═══ POURQUOI IMMÉDIATEMENT, ET NON AU PROCHAIN CONTRÔLE ═══
     *
     * Changer la liste remettait le compteur à zéro, et la relecture avait lieu
     * au contrôle suivant — dans le quart d'heure. Quinze minutes pendant
     * lesquelles l'écran montre encore l'ancien fil, c'est quinze minutes où
     * l'administrateur ne peut pas distinguer « mon réglage n'est pas arrivé »
     * de « il est arrivé et met du temps ». On règle, on regarde, et on
     * conclut à tort.
     *
     * ═══ POURQUOI ON EFFACE D'ABORD ═══
     *
     * Le nettoyage n'est pas une précaution technique : c'est ce qui rend le
     * geste LISIBLE. L'écran se vide, puis se remplit des nouveaux titres. Sans
     * lui, un fil qui donnerait des titres ressemblants laisserait dans le
     * doute — a-t-on relu, ou est-ce encore l'ancien affichage ?
     *
     * Le prix est assumé : si les nouvelles adresses sont injoignables, la
     * zone reste vide. C'est la conséquence exacte de ce qui a été demandé, et
     * le journal la nomme. Mieux vaut cela qu'un ancien fil qui persiste et
     * fait croire que rien n'a été pris en compte.
     *
     * Exécuté sur le fil de l'ordonnanceur, jamais sur celui de l'appelant :
     * cette méthode est appelée depuis un rappel Firestore, qui s'exécute sur
     * le fil principal — y faire un téléchargement réseau lèverait aussitôt.
     */
    fun rafraîchirMaintenant() {
        val tâche = Runnable {
            runCatching {
                // Le drapeau lève la protection contre l'écrasement par du
                // vide : un ordre explicite doit produire un effet visible,
                // même quand cet effet est un écran vide.
                config.fluxListeChangee = true
                publier(emptyList())
                CallTrace.record("FLUX nettoyé", "ancienne liste retirée — relecture immédiate")
                rafraîchir()
            }.onFailure { e ->
                Log.e(TAG, "Relecture immédiate en échec", e)
                CallTrace.record(
                    "FLUX ÉCHEC",
                    "relecture immédiate : ${e.javaClass.simpleName} : ${e.message ?: "sans message"}",
                )
            }
        }
        // L'ordonnanceur peut avoir été arrêté (service qui s'éteint) : le
        // refus est alors normal et ne doit pas emporter le rappel Firestore
        // depuis lequel on nous appelle.
        runCatching { ordonnanceur.execute(tâche) }
            .onFailure { Log.w(TAG, "Relecture immédiate refusée : ordonnanceur arrêté") }
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
        // ═══ UNE VERSION NOUVELLE RÉÉCRIT, SANS ATTENDRE 7 H ═══
        //
        // La règle « une fois par jour » porte sur le CONTENU : ne pas
        // remplacer les titres sous les yeux de Jean à tout bout de champ. Elle
        // ne doit pas s'appliquer au FORMAT. Ajouter un champ au recueil — la
        // provenance du titre — n'avait sinon aucun effet visible avant le
        // lendemain matin : on installe une version, on ne voit rien, et rien
        // ne dit que le document affiché a été écrit par la précédente. On
        // cherche alors un défaut d'affichage qui n'existe pas.
        if (config.fluxDerniereRevision != BuildConfig.BUILD_REV) {
            CallTrace.record(
                "FLUX version",
                "recueil écrit par « ${config.fluxDerniereRevision.ifEmpty { "aucune" }} », " +
                    "cette version est ${BuildConfig.BUILD_REV} — réécriture immédiate",
            )
            rafraîchir()
            return
        }
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

        // ═══ AUCUN FIL JOIGNABLE N'EST PAS UNE RÉPONSE ═══
        //
        // Ce contrôle passe AVANT tout le reste, et il corrige un défaut grave
        // introduit avec le nettoyage de la liste.
        //
        // Au redémarrage qui suit une mise à jour, le Wi-Fi n'est pas encore
        // associé : tous les fils sont injoignables, la sélection ne rend rien,
        // et le drapeau « liste changée » — qui survit au redémarrage — faisait
        // alors publier un recueil VIDE. Pire, la date du dernier
        // remplacement était posée : la tentative suivante n'aurait eu lieu
        // qu'à 7 h le lendemain. Les titres disparaissaient donc pour une
        // journée entière, à cause d'une poignée de secondes sans réseau.
        //
        // Une absence de réponse n'est pas une réponse vide. Rien n'est publié,
        // rien n'est marqué comme fait, et l'on recommence au prochain contrôle
        // — dans le quart d'heure.
        if (injoignables == adresses.size) {
            Log.w(TAG, "Aucun fil joignable : rien n'est publié, nouvel essai au prochain contrôle")
            CallTrace.record(
                "FLUX injoignable",
                "les ${adresses.size} fil(s) sont muets — rien n'est publié, " +
                    "nouvel essai dans le quart d'heure",
            )
            return
        }

        if (titres.isEmpty()) {
            // ═══ SAUF QUAND L'ADMINISTRATEUR VIENT DE CHANGER LA LISTE ═══
            //
            // La règle « ne jamais écraser un flux qui marche par du vide »
            // protège d'une panne passagère : un matin où rien n'est paru, ou
            // des serveurs muets, ne doivent pas laisser Jean devant un écran
            // vide alors que les titres d'hier étaient lisibles.
            //
            // Appliquée à un changement de liste, elle se retourne contre son
            // but. L'administrateur remplace France Info par deux autres fils,
            // ceux-ci ne donnent rien — ils ne datent pas leurs articles, par
            // exemple — et l'écran continue d'afficher France Info. Vu de
            // dehors, le réglage a été IGNORÉ : rien ne distingue « votre
            // liste a été lue et n'a rien donné » de « votre liste n'est
            // jamais arrivée », et on cherche la panne du mauvais côté.
            //
            // Un ordre explicite doit donc produire un effet visible, même
            // quand cet effet est un écran vide. C'est la seule façon que
            // l'administrateur apprenne quelque chose de son essai.
            if (config.fluxListeChangee) {
                publier(emptyList())
                config.fluxDerniereRevision = BuildConfig.BUILD_REV
                config.fluxListeChangee = false
                config.fluxDernierRafraichissementMs = System.currentTimeMillis()
                CallTrace.record(
                    "FLUX vidé",
                    "la nouvelle liste ne donne aucun titre du jour — l'ancien fil est retiré",
                )
                Log.w(TAG, "Nouvelle liste sans titre du jour : l'ancien fil est retiré")
                return
            }
            // La date du dernier remplacement n'est PAS mise à jour : la
            // tentative recommencera au prochain contrôle, dans le quart
            // d'heure, au lieu d'attendre demain 7 h.
            Log.w(TAG, "Aucun titre du jour : l'état précédent est conservé")
            return
        }

        publier(titres)
        config.fluxDerniereRevision = BuildConfig.BUILD_REV
        config.fluxListeChangee = false
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
