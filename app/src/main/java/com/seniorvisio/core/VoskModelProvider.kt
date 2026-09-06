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
 * Téléchargé une seule fois (~45 Mo), puis tout fonctionne hors-ligne et sans
 * rien payer. Téléchargé plutôt qu'embarqué dans l'APK : celui-ci pèse déjà
 * 55 Mo, et le doubler pénaliserait chaque mise à jour de l'application pour
 * un fichier qui, lui, ne change jamais.
 *
 * Modèle "small" : conçu pour de la commande vocale plus que pour transcrire
 * une conversation captée à deux mètres. C'est la limite assumée du choix —
 * en échange, écouter la pièce toute la journée ne coûte rien, là où un
 * service facturé à la durée reviendrait à une centaine d'euros par mois.
 *
 * [prepare] est appelé au démarrage du service d'écoute (voir
 * RoomPresenceService) pour que le modèle soit prêt avant qu'on en ait
 * besoin. Tant qu'il ne l'est pas, la pièce est transcrite par AssemblyAI —
 * voir TranscriptionEngine : mieux vaut quelques minutes facturées au premier
 * démarrage qu'une fonction muette sans explication.
 */
object VoskModelProvider {

    /** Où en est le modèle — affiché tel quel dans l'écran admin. */
    sealed interface State {
        object Absent : State
        object Downloading : State
        object Ready : State
        data class Failed(val reason: String) : State
    }

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var model: Model? = null
    @Volatile private var state: State = State.Absent
    @Volatile private var attempted = false

    /** null tant que le modèle n'est pas prêt (téléchargement ou chargement en cours, ou échec). */
    fun getModel(): Model? = model

    fun state(): State = state

    /** Idempotent : sans effet si le modèle est déjà chargé ou déjà en cours de préparation. */
    fun prepare(context: Context) {
        if (attempted) return
        attempted = true
        val appContext = context.applicationContext
        executor.execute {
            try {
                val modelDir = File(appContext.filesDir, MODEL_DIR_NAME)
                if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                    state = State.Downloading
                    downloadAndUnzip(appContext, modelDir)
                }
                model = Model(modelDir.absolutePath)
                state = State.Ready
                Log.i(TAG, "Modèle Vosk chargé (${modelDir.absolutePath})")
            } catch (e: Exception) {
                // Pas de réseau au premier démarrage, téléchargement
                // interrompu, modèle abîmé : jamais bloquant, la pièce reste
                // transcrite par AssemblyAI (voir TranscriptionEngine).
                // `attempted` est remis à faux pour qu'un prochain démarrage
                // retente — un échec réseau ponctuel ne doit pas condamner la
                // fonction jusqu'à la réinstallation.
                attempted = false
                state = State.Failed(e.message ?: e.javaClass.simpleName)
                Log.w(TAG, "Modèle Vosk indisponible", e)
            }
        }
    }

    private fun downloadAndUnzip(context: Context, modelDir: File) {
        val zipFile = File(context.filesDir, "vosk-model.zip")
        java.net.URL(MODEL_URL).openStream().use { input ->
            FileOutputStream(zipFile).use { output -> input.copyTo(output) }
        }

        val extractDir = File(context.filesDir, "vosk-model-extract").apply {
            deleteRecursively()
            mkdirs()
        }
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(extractDir, entry.name)
                // Une entrée d'archive dont le chemin remonte hors du dossier
                // d'extraction écrirait n'importe où dans les fichiers de
                // l'application. L'archive vient d'une source connue, mais
                // c'est le genre de vérification qui ne se rattrape pas après
                // coup.
                if (!outFile.canonicalPath.startsWith(extractDir.canonicalPath + File.separator)) {
                    throw java.io.IOException("Entrée d'archive hors du dossier d'extraction : ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        zipFile.delete()

        // L'archive contient un unique dossier racine (ex.
        // "vosk-model-small-fr-0.22") : on le range sous un nom stable, pour
        // ne pas dépendre du nom exact du modèle si l'adresse change un jour.
        val extractedRoot = extractDir.listFiles()?.firstOrNull { it.isDirectory } ?: extractDir
        extractedRoot.copyRecursively(modelDir, overwrite = true)
        extractDir.deleteRecursively()
    }

    private const val TAG = "VoskModelProvider"
    private const val MODEL_DIR_NAME = "vosk-model-fr"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
}
