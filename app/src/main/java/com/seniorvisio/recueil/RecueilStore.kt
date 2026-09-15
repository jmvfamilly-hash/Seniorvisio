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
        recueils.forEach { recueil ->
            val dossier = dossierDe(recueil.id).apply { mkdirs() }
            var modifié = false
            val misÀJour = recueil.éléments.map { element ->
                // Déjà vérifié ET toujours présent sur le disque : on ne
                // retélécharge pas. Le « toujours présent » n'est pas un excès
                // de prudence — un nettoyage système ou une restauration peut
                // vider filesDir sans que Firestore en sache rien, et un
                // recueil déclaré installé mais vide serait une panne muette.
                if (element.état == ÉtatElement.PRÊT &&
                    element.fichierLocal != null &&
                    File(dossier, element.fichierLocal).exists()
                ) return@map element
                if (element.état == ÉtatElement.REFUSÉ) return@map element
                modifié = true
                installer(element, dossier)
            }
            if (modifié) {
                val complet = recueil.copy(éléments = misÀJour)
                publierÉtat(complet)
                dernierÉtat = dernierÉtat.filter { it.id != complet.id } + complet
            } else {
                dernierÉtat = dernierÉtat.filter { it.id != recueil.id } + recueil
            }
        }
        élaguer(recueils.map { it.id }.toSet())
    }

    private fun installer(element: Element, dossier: File): Element {
        if (element.nature == NatureElement.FLUX) {
            return element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "Les fils d'information ne sont pas encore pris en charge.",
            )
        }
        val brut = File(context.cacheDir, "recueil-${element.id}")
        return try {
            TelechargementHttp.vers(element.source, brut, "élément ${element.id}")
            vérificateurPour(element.type, côtéMax).vérifier(element, brut, dossier)
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
            val source = brut[CHAMP_SOURCE] as? String ?: return@mapIndexedNotNull null
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
                fichierLocal = if (étatDepuis(vu?.get(CHAMP_ÉTAT) as? String) == ÉtatElement.PRÊT)
                    "$élémentId.jpg" else null,
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

    private companion object {
        const val TAG = "RecueilStore"
        const val COLLECTION = "recueils"
        const val DOSSIER = "recueils"

        /** Bornes de la définition de rangement, pour ne dépendre d'aucune dalle particulière. */
        const val CÔTÉ_MIN = 720
        const val CÔTÉ_PLAFOND = 1920

        const val CHAMP_TITRE = "titre"
        const val CHAMP_CRÉÉ_PAR = "crééPar"
        const val CHAMP_ÉLÉMENTS = "elements"
        const val CHAMP_ID = "id"
        const val CHAMP_TYPE = "type"
        const val CHAMP_NATURE = "nature"
        const val CHAMP_SOURCE = "source"
        const val CHAMP_ORDRE = "ordre"
        const val CHAMP_VÉRIFICATION = "verification"
        const val CHAMP_ÉTAT = "etat"
        const val CHAMP_CAUSE = "cause"
        const val CHAMP_ÉTAT_GLOBAL = "etatGlobal"
        const val CHAMP_VÉRIFIÉ_PAR = "verifiePar"
    }
}
