package com.seniorvisio.core

import android.content.Context
import android.util.Log
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Le modèle de reconnaissance vocale française utilisé par
 * [VoskSpeechRecognizer], partagé pour toute la durée de vie du processus :
 * le charger prend plusieurs secondes, il ne doit pas être refait à chaque
 * conversation.
 *
 * Deux tailles disponibles (voir VoskModelSize), réglables et interchangeables
 * à distance. Une fois téléchargés, les deux coexistent sur la tablette :
 * basculer de l'un à l'autre pour comparer ne re-télécharge rien.
 *
 * Téléchargés plutôt qu'embarqués dans l'APK : le grand modèle pèse à lui seul
 * 1,4 Go, ce qui rendrait chaque mise à jour de l'application interminable
 * pour un fichier qui, lui, ne change jamais.
 *
 * L'archive est extraite directement depuis le flux réseau, sans jamais poser
 * le zip sur le disque. Passer par un fichier intermédiaire puis une copie —
 * ce que faisait la première version — demandait à un moment donné de la
 * place pour l'archive, l'extraction ET la copie en même temps : plus de 4 Go
 * de pic pour le grand modèle, sur une tablette qui n'en a pas forcément
 * autant de libre. Ici le pic se limite à la taille du modèle décompressé, et
 * le dossier final est obtenu par renommage, qui ne coûte rien.
 */
object VoskModelProvider {

    /** Où en est le modèle — affiché tel quel dans l'écran admin. */
    sealed interface State {
        object Absent : State
        data class Downloading(val megabytesDone: Int, val megabytesTotal: Int) : State
        object Loading : State
        data class Ready(val size: VoskModelSize) : State
        data class Failed(val reason: String) : State
    }

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var model: Model? = null
    @Volatile private var loadedSize: VoskModelSize? = null
    @Volatile private var state: State = State.Absent
    @Volatile private var preparing: VoskModelSize? = null

    /** null tant que le modèle demandé n'est pas prêt (téléchargement ou chargement en cours, ou échec). */
    fun getModel(): Model? = model

    fun state(): State = state

    /**
     * L'état en une phrase lisible, affichée telle quelle dans l'écran admin de
     * la tablette et republiée dans le signe de vie (voir
     * DeviceStatusReporter.reportHeartbeat) : télécharger 1,4 Go peut prendre
     * une heure, et la personne qui vient de demander la bascule à distance est
     * en général à l'autre bout du pays. Sans cette remontée, elle n'aurait
     * aucun moyen de distinguer un téléchargement qui avance d'un échec.
     */
    fun describeState(): String = when (val current = state) {
        is State.Ready -> "prêt — ${current.size.adminLabel}"
        is State.Downloading -> "téléchargement ${current.megabytesDone} / ${current.megabytesTotal} Mo"
        State.Loading -> "chargement en mémoire…"
        State.Absent -> "pas encore demandé"
        is State.Failed -> "échec : ${current.reason}"
    }

    /**
     * Prépare le modèle de la taille voulue, en le téléchargeant s'il n'est
     * pas déjà là. Sans effet si ce modèle-là est déjà chargé ou déjà en cours
     * de préparation — mais demander une AUTRE taille interrompt bien le
     * modèle courant au profit du nouveau, ce qui est exactement ce qu'on veut
     * quand on bascule à distance pour comparer.
     */
    fun prepare(context: Context, size: VoskModelSize) {
        if (loadedSize == size && model != null) return
        if (preparing == size) return
        preparing = size
        val appContext = context.applicationContext
        executor.execute {
            try {
                val modelDir = File(appContext.filesDir, size.directoryName)
                if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                    downloadAndExtract(appContext, size, modelDir)
                }
                state = State.Loading
                // Le modèle précédent est libéré seulement une fois le nouveau
                // chargé : en cas d'échec, on garde celui qui marchait.
                val loaded = Model(modelDir.absolutePath)
                val previous = model
                model = loaded
                loadedSize = size
                state = State.Ready(size)
                previous?.close()
                Log.i(TAG, "Modèle Vosk chargé : ${size.directoryName}")
            } catch (e: Exception) {
                // Pas de réseau, téléchargement interrompu, place disque
                // insuffisante, archive abîmée : jamais bloquant, la pièce
                // reste transcrite par AssemblyAI (voir TranscriptionEngine).
                state = State.Failed(e.message ?: e.javaClass.simpleName)
                Log.w(TAG, "Modèle Vosk indisponible", e)
            } finally {
                // Remis à zéro même en cas d'échec, pour qu'une nouvelle
                // demande retente : une coupure réseau ponctuelle ne doit pas
                // condamner la fonction jusqu'à la réinstallation.
                preparing = null
            }
        }
    }

    private fun downloadAndExtract(context: Context, size: VoskModelSize, modelDir: File) {
        val stagingDir = File(context.filesDir, "${size.directoryName}-staging").apply {
            deleteRecursively()
            mkdirs()
        }

        val connection = java.net.URL(size.url).openConnection()
        connection.connectTimeout = 30_000
        // Pas de délai de lecture : 1,4 Go sur une liaison de résidence peuvent
        // prendre longtemps, et une coupure au bout de quelques secondes
        // d'inactivité réseau ferait échouer un téléchargement qui avançait.
        connection.readTimeout = 0
        val totalMb = (connection.contentLengthLong / BYTES_PER_MB).toInt()
        var doneBytes = 0L
        state = State.Downloading(0, totalMb)

        connection.getInputStream().use { network ->
            ZipInputStream(network).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(stagingDir, entry.name)
                    // Une entrée dont le chemin remonte hors du dossier
                    // d'extraction écrirait n'importe où dans les fichiers de
                    // l'application. La source est connue, mais c'est le genre
                    // de vérification qui ne se rattrape pas après coup.
                    if (!outFile.canonicalPath.startsWith(stagingDir.canonicalPath + File.separator)) {
                        throw java.io.IOException("Entrée d'archive hors du dossier d'extraction : ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                doneBytes += read
                                val doneMb = (doneBytes / BYTES_PER_MB).toInt()
                                state = State.Downloading(doneMb, totalMb)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        // L'archive contient un unique dossier racine (ex.
        // "vosk-model-fr-0.22") : on le renomme sous un nom stable, pour ne pas
        // dépendre du nom exact du modèle si l'adresse change un jour. Un
        // renommage, pas une copie : sur 1,4 Go la différence est notable.
        val extractedRoot = stagingDir.listFiles()?.singleOrNull { it.isDirectory } ?: stagingDir
        modelDir.deleteRecursively()
        if (!extractedRoot.renameTo(modelDir)) {
            // Renommage refusé (cas rare, ex. dossiers sur des volumes
            // différents) : on retombe sur la copie, plus lente mais sûre.
            extractedRoot.copyRecursively(modelDir, overwrite = true)
        }
        stagingDir.deleteRecursively()
    }

    private const val TAG = "VoskModelProvider"
    private const val BYTES_PER_MB = 1024L * 1024L
}
