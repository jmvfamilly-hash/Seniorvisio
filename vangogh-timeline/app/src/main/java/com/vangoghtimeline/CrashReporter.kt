package com.vangoghtimeline

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal de plantage embarqué : sans PC ni `adb`, l'utilisateur peut transmettre la cause d'un plantage.
 *
 * Au plantage, la trace est écrite dans `filesDir/last_crash.txt` puis le gestionnaire d'origine reprend la main (l'appli
 * se ferme comme d'habitude). Au lancement suivant, [MainActivity] affiche la trace avec un bouton « Partager ».
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(app.filesDir, FILE).writeText(
                    "Plantage du $stamp sur le fil « ${thread.name} »\n" +
                        "Version ${BuildConfig.BUILD_REV}\n\n" + Log.getStackTraceString(error),
                )
            } catch (_: Throwable) { /* ne jamais masquer le plantage d'origine */ }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
