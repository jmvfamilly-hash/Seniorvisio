package com.seniorvisio.vignette

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.seniorvisio.core.TelechargementHttp
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Ce que la tablette a réellement téléchargé des vignettes animées, et
 * comment elle l'obtient — même mécanique que RecueilStore (voir ce fichier
 * et docs/architecture-recueils.md) : le PWA dépose un clip, un traitement
 * à part le détoure et publie une archive, et c'est la tablette qui la
 * télécharge et la vérifie elle-même. Rien n'est plus embarqué dans l'APK
 * (voir scripts/traiter_vignette.sh et scripts/publier_vignette.sh) — ce qui
 * règle à la fois le besoin de recompiler pour ajouter une scène et la
 * définition imposée par la taille de l'APK (640×360 au départ ; la
 * tablette, elle, a de la place).
 *
 * ═══ CE QUI DIFFÈRE DE RecueilStore, ET POURQUOI ═══
 *
 * Pas de séparation par appareil (voir vignettes.js) : une scène traitée est
 * la même pour toutes les tablettes, la collection est donc globale
 * (vignettes/{sceneId}), pas sous devices/{id}.
 *
 * Pas de compte-rendu publié vers Firestore : les recueils le font parce
 * qu'un proche doit savoir AVANT un appel quelle photo ne passera pas. Une
 * vignette ratée ne prive personne d'un appel — elle ne joue simplement pas
 * cette heure-là (voir VignetteAnimee, qui l'ignore silencieusement). Publier
 * un état ici ferait en plus coexister plusieurs tablettes écrivant sur un
 * même document partagé, pour un bénéfice qu'aucun écran du PWA n'exploite.
 *
 * Une archive ZIP plutôt que cent URL : cent requêtes HTTP pour dix secondes
 * de scène serait un dialogue réseau disproportionné comparé à un seul
 * fichier, qu'un script produit déjà d'un coup (voir
 * scripts/publier_vignette.sh).
 */
class VignetteStore(private val context: Context) {

    private val db get() = FirebaseFirestore.getInstance()
    private val collection get() = db.collection(COLLECTION)

    /** Où vivent les images installées. Un sous-dossier par scène. */
    private val racine: File by lazy { File(context.filesDir, DOSSIER).apply { mkdirs() } }

    private var écoute: ListenerRegistration? = null

    fun démarrer() {
        if (écoute != null) return
        actif = this
        écoute = collection.addSnapshotListener { instantané, erreur ->
            if (erreur != null) {
                Log.e(TAG, "Lecture des vignettes impossible", erreur)
                return@addSnapshotListener
            }
            val scènes = instantané?.documents?.mapNotNull { lire(it.id, it.data) } ?: return@addSnapshotListener
            Thread { installerCeQuiManque(scènes) }
                .apply { isDaemon = true; name = "SeniorVisio-Vignettes" }
                .start()
        }
    }

    fun arrêter() {
        écoute?.remove()
        écoute = null
        if (actif === this) actif = null
    }

    /** Les scènes réellement installées, prêtes à être jouées — voir VignetteAnimee. */
    fun scenesInstallées(): List<String> = dernierÉtat
        .filter { it.prête && estInstallée(it) }
        .map { it.id }
        .sorted()

    /** Où vit une scène sur le disque, pour VignetteAnimee. Peut ne pas (encore) exister. */
    fun dossierDe(sceneId: String): File = File(racine, sceneId)

    /** Cadence de la scène, telle que publiée au traitement — voir VignetteAnimee.jouer. */
    fun imagesParSecondeDe(sceneId: String): Int =
        dernierÉtat.firstOrNull { it.id == sceneId }?.imagesParSeconde ?: 10

    @Volatile
    private var dernierÉtat: List<Scène> = emptyList()

    private fun installerCeQuiManque(scènes: List<Scène>) {
        scènes.forEach { scène ->
            if (scène.prête && !estInstallée(scène)) installer(scène)
        }
        dernierÉtat = scènes
        élaguer(scènes.filter { it.prête }.map { it.id }.toSet())
    }

    /**
     * Comme RecueilStore.estInstallé : toujours vérifié sur le disque, jamais
     * supposé d'après Firestore — un nettoyage système peut vider filesDir
     * sans que Firestore en sache rien.
     */
    private fun estInstallée(scène: Scène): Boolean {
        val images = dossierDe(scène.id).listFiles { f -> f.name.startsWith("frame_") }
        return (images?.size ?: 0) == scène.nombreImages
    }

    private fun installer(scène: Scène) {
        val archive = File(context.cacheDir, "vignette-${scène.id}.zip")
        try {
            TelechargementHttp.vers(scène.archive, archive, "vignette ${scène.id}")
            dézipper(archive, dossierDe(scène.id))
        } catch (e: Exception) {
            Log.w(TAG, "Installation impossible pour ${scène.id}", e)
        } finally {
            // L'archive ne sert qu'à l'installation : la garder doublerait la
            // place occupée pour un fichier que personne ne relira jamais.
            archive.delete()
        }
    }

    /**
     * Dézippe dans un dossier provisoire puis bascule d'un coup : une scène
     * en cours de rallumage de la tablette ne doit jamais voir un dossier à
     * moitié dézippé — VignetteAnimee.jouer() le lirait tel quel.
     */
    private fun dézipper(archive: File, dossier: File) {
        val provisoire = File(dossier.parentFile, "${dossier.name}.tmp").apply {
            deleteRecursively()
            mkdirs()
        }
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entrée = zip.nextEntry ?: break
                if (!entrée.isDirectory) {
                    File(provisoire, File(entrée.name).name).outputStream().use { sortie ->
                        zip.copyTo(sortie)
                    }
                }
                zip.closeEntry()
            }
        }
        dossier.deleteRecursively()
        provisoire.renameTo(dossier)
    }

    /**
     * Efface les scènes que le traitement a retirées (comme
     * RecueilStore.élaguer) — sans ça le disque se remplirait lentement d'une
     * scène qu'on a cessé de publier.
     */
    private fun élaguer(encoreVoulues: Set<String>) {
        racine.listFiles()?.forEach { dossier ->
            if (dossier.isDirectory && !dossier.name.endsWith(".tmp") && dossier.name !in encoreVoulues) {
                dossier.deleteRecursively()
                Log.i(TAG, "Vignette retirée : ${dossier.name}")
            }
        }
    }

    private data class Scène(
        val id: String,
        val prête: Boolean,
        val archive: String,
        val nombreImages: Int,
        val imagesParSeconde: Int,
    )

    @Suppress("UNCHECKED_CAST")
    private fun lire(id: String, data: Map<String, Any?>?): Scène? {
        if (data == null) return null
        val archive = data[CHAMP_ARCHIVE] as? String ?: ""
        val nombreImages = (data[CHAMP_NOMBRE_IMAGES] as? Number)?.toInt() ?: 0
        val imagesParSeconde = (data[CHAMP_IMAGES_PAR_SECONDE] as? Number)?.toInt() ?: 10
        val prête = (data[CHAMP_ÉTAT] as? String) == VALEUR_ÉTAT_PRÊTE && archive.isNotBlank() && nombreImages > 0
        return Scène(id, prête, archive, nombreImages, imagesParSeconde)
    }

    companion object {
        /**
         * Le magasin en service — même raison que RecueilStore.actif : il vit
         * dans le service de premier plan, qui tourne en continu, et toute
         * autre instance serait vide puisque c'est l'écoute qui la peuple.
         */
        @Volatile
        var actif: VignetteStore? = null
            private set

        private const val TAG = "VignetteStore"
        private const val COLLECTION = "vignettes"
        private const val DOSSIER = "vignettes"
        private const val CHAMP_ÉTAT = "état"
        private const val VALEUR_ÉTAT_PRÊTE = "prête"
        private const val CHAMP_ARCHIVE = "archive"
        private const val CHAMP_NOMBRE_IMAGES = "nombreImages"
        private const val CHAMP_IMAGES_PAR_SECONDE = "imagesParSeconde"
    }
}
