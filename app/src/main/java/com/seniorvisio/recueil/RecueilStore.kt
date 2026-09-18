package com.seniorvisio.recueil

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.seniorvisio.BuildConfig
import com.seniorvisio.core.TelechargementHttp
import java.io.File

/**
 * Ce que la tablette possède réellement, et comment elle l'obtient.
 *
 * ═══ CE QU'ELLE NE SAIT PAS ═══
 *
 * Ni qu'il existe des appels, ni qu'il existe un écran. Elle installe,
 * vérifie, range, élague, et répond à « qu'est-ce que j'ai ». C'est le
 * lecteur qui saura quoi en faire, et les sources de commande qui sauront
 * quand — voir docs/architecture-recueils.md.
 *
 * Cette ignorance est le seul moyen d'ajouter plus tard la navigation par
 * Jean, puis la voix, sans rien toucher ici.
 *
 * ═══ POURQUOI PAS LE SDK FIREBASE STORAGE ═══
 *
 * Le PWA dépose l'URL de téléchargement dans le document du recueil, et cette
 * URL s'ouvre par une simple requête HTTPS. La tablette réutilise donc le
 * téléchargeur qui lui sert déjà pour ses mises à jour, et le projet garde une
 * dépendance Android de moins — sur un APK qui pèse déjà 174 Mo.
 */
class RecueilStore(private val context: Context) {

    private val db get() = FirebaseFirestore.getInstance()
    private val collection
        get() = db.document("devices/${BuildConfig.DEVICE_ID}").collection(COLLECTION)

    /** Où vivent les éléments rangés. Un sous-dossier par recueil. */
    private val racine: File by lazy {
        File(context.filesDir, DOSSIER).apply { mkdirs() }
    }

    private var écoute: ListenerRegistration? = null

    /**
     * Définition de rangement, prise sur la dalle réelle.
     *
     * Ni figée dans le code, ni devinée : la tablette de Jean et celle du banc
     * d'essai n'ont pas le même écran, et ranger du 1920 sur une dalle de 1280
     * ne ferait que remplir un disque déjà étroit.
     */
    /**
     * Exposée : le rendu en a besoin pour réduire au décodage, et la recalculer
     * de son côté serait une seconde vérité sur la même dalle.
     */
    val définitionDeLaDalle: Int get() = côtéMax

    private val côtéMax: Int by lazy {
        val métriques = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.getMetrics(métriques)
        maxOf(métriques.widthPixels, métriques.heightPixels).coerceIn(CÔTÉ_MIN, CÔTÉ_PLAFOND)
    }

    /**
     * Suit les recueils publiés pour cette tablette et installe ce qui manque.
     *
     * L'installation part sur un fil à part : elle télécharge et décode des
     * images, ce qui n'a rien à faire sur le fil principal — surtout sur une
     * tablette qui met déjà 200 ms à transcrire 30 ms de son.
     */
    fun démarrer() {
        if (écoute != null) return
        actif = this
        écoute = collection.addSnapshotListener { instantané, erreur ->
            if (erreur != null) {
                Log.e(TAG, "Lecture des recueils impossible", erreur)
                return@addSnapshotListener
            }
            val recueils = instantané?.documents?.mapNotNull { lire(it.id, it.data) } ?: return@addSnapshotListener
            Thread { installerCeQuiManque(recueils) }
                .apply { isDaemon = true; name = "SeniorVisio-Recueils" }
                .start()
        }
    }

    fun arrêter() {
        écoute?.remove()
        écoute = null
        if (actif === this) actif = null
    }

    /** Ce que le lecteur pourra ouvrir : uniquement ce qui est réellement là. */
    fun disponibles(): List<Recueil> = dernierÉtat.filter { it.prêts.isNotEmpty() }

    /** Le fichier rangé d'un élément, ou null s'il n'a pas été installé. */
    fun fichier(recueil: Recueil, element: Element): File? {
        val nom = element.fichierLocal ?: return null
        val f = File(dossierDe(recueil.id), nom)
        return if (f.exists()) f else null
    }

    // ---- installation ----

    @Volatile
    private var dernierÉtat: List<Recueil> = emptyList()

    private fun installerCeQuiManque(recueils: List<Recueil>) {
        // Lu UNE fois pour toute la passe, et non par élément : c'est une
        // lecture de préférences, et il y en aurait eu une par photo.
        val recueilDesOeuvres = com.seniorvisio.core.AdminConfig(context).recueilOeuvres
        recueils.forEach { recueil ->
            val dossier = dossierDe(recueil.id).apply { mkdirs() }
            val misÀJour = recueil.éléments.map { element ->
                if (estInstallé(element, dossier)) return@map element
                if (element.état == ÉtatElement.REFUSÉ) return@map element
                // Le recueil d'exposition est rangé sans réduction : c'est
                // dans ces fichiers-là que la visite guidée découpe ses détails
                // (voir VerificateurOeuvre et DecoupeOeuvre).
                installer(element, dossier, recueil.id == recueilDesOeuvres)
            }
            // ═══ ON NE PUBLIE QUE CE QUI A RÉELLEMENT CHANGÉ ═══
            //
            // Comparé au résultat, et non « on a appelé installer, donc c'est
            // modifié ». La nuance évite une BOUCLE INFINIE, et elle n'est pas
            // théorique : un titre de fil d'information sans vignette n'a
            // aucun fichier local à montrer. Avec l'ancien critère, le magasin
            // le croyait à installer à chaque instantané, le réinstallait,
            // republiait le document — ce qui déclenchait l'instantané suivant,
            // et ainsi de suite, indéfiniment, contre Firestore.
            //
            // L'égalité des data classes tranche sans qu'on ait à énumérer les
            // cas : si l'installation n'a rien appris de neuf, rien ne part.
            if (misÀJour != recueil.éléments) {
                val complet = recueil.copy(éléments = misÀJour)
                publierÉtat(complet)
                dernierÉtat = dernierÉtat.filter { it.id != complet.id } + complet
            } else {
                dernierÉtat = dernierÉtat.filter { it.id != recueil.id } + recueil
            }
        }
        élaguer(recueils.map { it.id }.toSet())
    }

    /**
     * Cet élément est-il déjà en place, ou reste-t-il quelque chose à faire ?
     *
     * Le « toujours présent sur le disque » n'est pas un excès de prudence :
     * un nettoyage système ou une restauration peut vider filesDir sans que
     * Firestore en sache rien, et un recueil déclaré installé mais vide serait
     * une panne muette.
     *
     * Le cas d'un élément SANS fichier attendu — un titre que le flux livre
     * sans illustration — est traité explicitement : il est complet tel quel,
     * et le croire inachevé reviendrait à le réinstaller sans fin.
     */
    private fun estInstallé(element: Element, dossier: File): Boolean = when {
        element.état != ÉtatElement.PRÊT -> false
        element.source.isBlank() -> true
        element.fichierLocal == null -> false
        else -> File(dossier, element.fichierLocal).exists()
    }

    private fun installer(element: Element, dossier: File, oeuvres: Boolean = false): Element {
        if (element.type == TypeElement.TEXTE) return installerTexte(element, dossier)
        if (element.nature == NatureElement.FLUX) {
            return element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "Les fils d'information ne sont pas encore pris en charge.",
            )
        }
        val brut = File(context.cacheDir, "recueil-${element.id}")
        return try {
            TelechargementHttp.vers(element.source, brut, "élément ${element.id}")
            vérificateurPour(element.type, côtéMax, oeuvres).vérifier(element, brut, dossier)
        } catch (e: Exception) {
            Log.w(TAG, "Téléchargement impossible pour ${element.id}", e)
            element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "Téléchargement impossible (${e.javaClass.simpleName}).",
            )
        } finally {
            // Le brut ne sert qu'à la vérification : le garder doublerait la
            // place occupée, pour un fichier que personne ne relira jamais.
            brut.delete()
        }
    }

    /**
     * Un titre de fil d'information, et sa vignette quand il y en a une.
     *
     * ═══ UNE VIGNETTE MANQUANTE NE REFUSE JAMAIS LE TITRE ═══
     *
     * C'est la règle qui compte ici. Le texte est ce que Jean doit lire ;
     * l'image n'est qu'un accompagnement. Un serveur d'illustrations lent,
     * une adresse périmée, un format exotique — et le titre serait écarté
     * alors qu'il s'affiche parfaitement sans image.
     *
     * L'échec du téléchargement est donc avalé à dessein, et l'élément reste
     * PRÊT sans fichier local. L'écran d'appel le voit et donne alors toute la
     * largeur au texte (voir RenduTexte et Rendu.Texte.vignette).
     */
    private fun installerTexte(element: Element, dossier: File): Element {
        if (element.texte.isNullOrBlank()) {
            return element.copy(état = ÉtatElement.REFUSÉ, cause = "Titre vide.")
        }
        if (element.source.isBlank()) {
            return element.copy(état = ÉtatElement.PRÊT, fichierLocal = null)
        }
        val brut = File(context.cacheDir, "vignette-${element.id}")
        return try {
            TelechargementHttp.vers(element.source, brut, "vignette ${element.id}")
            // Vérifiée comme une photo de famille, par le même chemin : un flux
            // public n'a pas plus le droit qu'un proche de déposer ici une
            // image que cette tablette ne sait pas décoder.
            val vérifiée = VerificateurPhoto(côtéMax).vérifier(element, brut, dossier)
            element.copy(
                état = ÉtatElement.PRÊT,
                fichierLocal = vérifiée.fichierLocal.takeIf { vérifiée.état == ÉtatElement.PRÊT },
            )
        } catch (e: Exception) {
            Log.i(TAG, "Vignette indisponible pour ${element.id} — le titre reste affichable", e)
            element.copy(état = ÉtatElement.PRÊT, fichierLocal = null)
        } finally {
            brut.delete()
        }
    }

    /**
     * Rend au PWA ce que la tablette a constaté, élément par élément.
     *
     * C'est toute la valeur de l'étape : le proche sait AVANT l'appel quelles
     * photos ne passeront pas, et pourquoi — au lieu de le découvrir devant
     * Jean.
     */
    private fun publierÉtat(recueil: Recueil) {
        val éléments = recueil.éléments.map { e ->
            mapOf(
                CHAMP_ID to e.id,
                CHAMP_ÉTAT to e.état.name.lowercase(),
                CHAMP_CAUSE to e.cause,
            )
        }
        collection.document(recueil.id).set(
            mapOf(
                CHAMP_ÉTAT_GLOBAL to recueil.étatGlobal(),
                CHAMP_VÉRIFIÉ_PAR to BuildConfig.DEVICE_ID,
                CHAMP_VÉRIFICATION to éléments,
            ),
            SetOptions.merge(),
        ).addOnFailureListener { e -> Log.e(TAG, "État du recueil non publié", e) }
    }

    /**
     * Efface les recueils que le PWA a retirés.
     *
     * Sans ça la fonction marcherait six mois puis remplirait le disque — la
     * pire façon de tomber en panne : lentement, et longtemps après qu'on ait
     * cessé d'y penser.
     */
    private fun élaguer(encoreVoulus: Set<String>) {
        racine.listFiles()?.forEach { dossier ->
            if (dossier.isDirectory && dossier.name !in encoreVoulus) {
                dossier.deleteRecursively()
                Log.i(TAG, "Recueil retiré : ${dossier.name}")
            }
        }
    }

    private fun dossierDe(recueilId: String) = File(racine, recueilId)

    // ---- lecture du document ----

    @Suppress("UNCHECKED_CAST")
    private fun lire(id: String, data: Map<String, Any?>?): Recueil? {
        if (data == null) return null
        val bruts = data[CHAMP_ÉLÉMENTS] as? List<Map<String, Any?>> ?: return null
        // La vérification déjà publiée est relue : sans ça, chaque
        // redémarrage repartirait de À_VÉRIFIER et retéléchargerait tout.
        val déjàVus = (data[CHAMP_VÉRIFICATION] as? List<Map<String, Any?>>)
            ?.associateBy { it[CHAMP_ID] as? String ?: "" } ?: emptyMap()
        val éléments = bruts.mapIndexedNotNull { index, brut ->
            val élémentId = brut[CHAMP_ID] as? String ?: return@mapIndexedNotNull null
            // La source peut manquer, et ce n'est plus une anomalie : un titre
            // de fil d'information sans illustration n'a aucune URL à porter.
            // La refuser ici écarterait silencieusement la moitié d'un flux.
            val source = brut[CHAMP_SOURCE] as? String ?: ""
            val vu = déjàVus[élémentId]
            Element(
                id = élémentId,
                type = TypeElement.depuis(brut[CHAMP_TYPE] as? String),
                nature = if ((brut[CHAMP_NATURE] as? String) == "flux") NatureElement.FLUX
                         else NatureElement.FICHIER,
                source = source,
                ordre = (brut[CHAMP_ORDRE] as? Number)?.toInt() ?: index,
                état = étatDepuis(vu?.get(CHAMP_ÉTAT) as? String),
                cause = vu?.get(CHAMP_CAUSE) as? String,
                fichierLocal = if (étatDepuis(vu?.get(CHAMP_ÉTAT) as? String) == ÉtatElement.PRÊT &&
                    File(dossierDe(id), "$élémentId.jpg").exists()
                ) "$élémentId.jpg" else null,
                texte = brut[CHAMP_TEXTE] as? String,
                origine = (brut[CHAMP_ORIGINE] as? String)?.takeIf { it.isNotBlank() },
                crédit = (brut[CHAMP_CRÉDIT] as? String)?.takeIf { it.isNotBlank() },
            )
        }
        return Recueil(
            id = id,
            titre = data[CHAMP_TITRE] as? String ?: "Sans titre",
            crééPar = data[CHAMP_CRÉÉ_PAR] as? String ?: "un proche",
            éléments = éléments,
        )
    }

    private fun étatDepuis(valeur: String?): ÉtatElement =
        ÉtatElement.entries.firstOrNull { it.name.equals(valeur, ignoreCase = true) }
            ?: ÉtatElement.À_VÉRIFIER

    companion object {

        /**
         * Le magasin en service, pour l'écran d'appel.
         *
         * ═══ POURQUOI CETTE RÉFÉRENCE GLOBALE, QUI N'EST PAS ANODINE ═══
         *
         * Le magasin est tenu par le service de premier plan : c'est lui qui
         * vit en continu, écoute Firestore, télécharge et vérifie, y compris
         * quand aucun appel n'est en cours. L'écran d'appel, lui, naît et
         * meurt à chaque appel.
         *
         * En construire un second dans l'écran ne marcherait PAS — pas
         * « marcherait moins bien » : son état est peuplé par l'écouteur, donc
         * une instance neuve est vide, et le lecteur ne trouverait jamais
         * aucun recueil. Le faire transiter par l'Intent ne marcherait pas non
         * plus : un magasin n'est pas sérialisable, et il tient des fichiers.
         *
         * Reste à emprunter celui qui existe. La référence est posée au
         * démarrage de l'écoute et retirée à son arrêt, donc elle vaut null
         * exactement quand il n'y a rien à emprunter — et l'écran d'appel sait
         * s'en passer (voir IncomingCallActivity).
         */
        @Volatile
        var actif: RecueilStore? = null
            private set

        private const val TAG = "RecueilStore"
        private const val COLLECTION = "recueils"
        private const val DOSSIER = "recueils"

        /** Bornes de la définition de rangement, pour ne dépendre d'aucune dalle particulière. */
        private const val CÔTÉ_MIN = 720
        private const val CÔTÉ_PLAFOND = 1920

        private const val CHAMP_TITRE = "titre"
        private const val CHAMP_CRÉÉ_PAR = "crééPar"
        private const val CHAMP_ÉLÉMENTS = "elements"
        private const val CHAMP_ID = "id"
        private const val CHAMP_TYPE = "type"
        private const val CHAMP_NATURE = "nature"
        private const val CHAMP_SOURCE = "source"
        private const val CHAMP_ORDRE = "ordre"
        private const val CHAMP_VÉRIFICATION = "verification"
        private const val CHAMP_ÉTAT = "etat"
        private const val CHAMP_CAUSE = "cause"
        private const val CHAMP_TEXTE = "texte"
        private const val CHAMP_ORIGINE = "origine"
        private const val CHAMP_CRÉDIT = "credit"
        private const val CHAMP_ÉTAT_GLOBAL = "etatGlobal"
        private const val CHAMP_VÉRIFIÉ_PAR = "verifiePar"
    }
}
