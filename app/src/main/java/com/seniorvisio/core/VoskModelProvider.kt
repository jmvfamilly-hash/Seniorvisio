package com.seniorvisio.core

import android.content.Context
import android.util.Log
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Modèle de reconnaissance vocale française utilisé par [VoskCaptionRecognizer],
 * partagé pour toute la durée de vie du process : charger un modèle Vosk
 * prend plusieurs secondes, il ne doit pas être refait à chaque appel.
 *
 * Téléchargé une seule fois au premier lancement (~45 Mo, modèle "small" —
 * suffisant pour du sous-titrage, pas pour de la dictée précise), puis tout
 * fonctionne hors-ligne. [prepare] est appelé dès le démarrage du service
 * d'écoute permanent (CallListenerService) pour maximiser les chances que le
 * modèle soit prêt avant le premier appel réel.
 */
object VoskModelProvider {
    private const val TAG = "VoskModelProvider"
    private const val MODEL_DIR_NAME = "vosk-model-fr"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var model: Model? = null
    @Volatile private var attempted = false

    /** null tant que le modèle n'est pas prêt (téléchargement/chargement en cours, ou échec). */
    fun getModel(): Model? = model

    /** Idempotent : sans effet si déjà chargé ou déjà en cours de préparation. */
    fun prepare(context: Context) {
        if (model != null || attempted) return
        attempted = true
        val appContext = context.applicationContext
        executor.execute {
            try {
                val modelDir = File(appContext.filesDir, MODEL_DIR_NAME)
                if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                    downloadAndUnzip(appContext, modelDir)
                }
                model = Model(modelDir.absolutePath)
                Log.i(TAG, "Modèle Vosk chargé (${modelDir.absolutePath})")
            } catch (e: Exception) {
                // Pas de réseau au premier lancement, modèle corrompu, etc. : pas
                // bloquant, les sous-titres retombent sur le texte relayé par le
                // PWA (voir WebRtcCallEngine.listenForCaptions) tant que null.
                Log.w(TAG, "Modèle Vosk indisponible : ${e.message}")
            }
        }
    }

    private fun downloadAndUnzip(context: Context, modelDir: File) {
        val zipFile = File(context.filesDir, "vosk-model.zip")
        URL(MODEL_URL).openStream().use { input ->
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

        // L'archive Vosk contient un seul dossier racine (ex.
        // "vosk-model-small-fr-0.22") : on le retrouve et le range sous le nom
        // stable attendu par getModel(), pour ne pas dépendre du nom exact du
        // modèle téléchargé si l'URL change un jour.
        val extractedRoot = extractDir.listFiles()?.firstOrNull { it.isDirectory } ?: extractDir
        extractedRoot.copyRecursively(modelDir, overwrite = true)
        extractDir.deleteRecursively()
    }
}
