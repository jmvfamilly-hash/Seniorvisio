package com.papyrus

import android.content.Context
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Journal de tout ce qui traverse la frontière entre cette application et le
 * moteur de reconnaissance.
 *
 * ═══ Pourquoi une trace, et pas des suppositions ═══
 *
 * Le moteur est une boîte noire : on lui donne la main, on la reprend, et entre
 * les deux il rend des textes qui se corrigent, se remplacent, ou ne viennent
 * jamais. Vu de l'écran, une phrase qui s'efface a au moins quatre causes
 * possibles — un résultat vide, une erreur tardive, un partiel plus court que
 * le précédent, ou une décision de cette application — et elles sont
 * rigoureusement indiscernables sans journal.
 *
 * Tout est donc noté : chaque rappel du moteur avec son nom, son contenu
 * complet et ses variantes, chaque décision prise ici, et surtout **les deux
 * instants qui comptent** — celui où l'on donne la main au moteur et celui où
 * on la reprend. L'écart entre eux est le temps mort qu'on cherche à mesurer.
 *
 * ═══ L'écart, plus que l'horodatage ═══
 *
 * Chaque ligne porte le temps écoulé depuis le démarrage ET l'écart avec la
 * ligne précédente. C'est le second qui parle : un « +0,004 s » entre la fin
 * d'une session et le début de la suivante dit que la relance est immédiate ;
 * un « +1,200 s » dit qu'une seconde de parole est tombée dans le vide, et le
 * texte manquant se trouve exactement là.
 */
object SpeechTrace {

    /**
     * Le niveau sonore écrit à lui seul quatre lignes par seconde. À six mille
     * entrées, un quart d'heure d'observation suffisait à chasser du journal
     * les lignes qu'on cherche — celles des sessions, rares et décisives. Vingt
     * mille tiennent plus d'une heure et pèsent environ un mégaoctet et demi.
     */
    private const val MAX_ENTRIES = 20_000

    private const val TAG = "SpeechTrace"
    private const val FILE_NAME = "papyrus-trace.txt"

    private val startedAtMs = SystemClock.elapsedRealtime()
    private var lastAtMs = startedAtMs
    private val entries = ArrayDeque<String>()

    /**
     * Note un événement. [source] est le nom du rappel du moteur ou de la
     * fonction de cette application — c'est lui qui permet de savoir **qui**
     * a produit quoi, ce qui est toute la question quand du texte disparaît.
     */
    @Synchronized
    fun record(source: String, detail: String = "") {
        val now = SystemClock.elapsedRealtime()
        val since = (now - startedAtMs) / 1000.0
        val delta = (now - lastAtMs) / 1000.0
        lastAtMs = now
        val line = String.format(
            Locale.FRANCE,
            "[%9.3fs %+8.3fs] %-22s %s",
            since, delta, source, detail,
        )
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        Log.d(TAG, line)
    }

    /**
     * Note un résultat du moteur avec **toutes** ses variantes, pas seulement
     * celle qu'on retient.
     *
     * Les variantes sont ce qui explique l'impression que « la phrase se
     * remplace par une autre » : le moteur ne corrige pas un mot, il propose un
     * autre découpage de la même suite de sons. Ne journaliser que le premier
     * choix rendrait ce phénomène invisible dans la trace alors qu'il est
     * visible à l'écran.
     */
    @Synchronized
    fun recordResults(source: String, results: android.os.Bundle?) {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (texts.isNullOrEmpty()) {
            record(source, "AUCUN TEXTE")
            return
        }
        val detail = texts.mapIndexed { index, text ->
            if (index == 0) "« $text »" else "| variante $index : « $text »"
        }.joinToString(" ")
        record(source, detail)
    }

    @Synchronized
    fun dump(): String =
        if (entries.isEmpty()) "Trace vide." else entries.joinToString("\n")

    /**
     * Écrit la trace dans les fichiers de l'application, d'où elle se récupère
     * par câble sans aucune permission, et se partage par le sélecteur système.
     */
    fun writeTo(context: Context): File? = try {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        File(directory, FILE_NAME).apply { writeText(header() + dump()) }
    } catch (e: Exception) {
        Log.w(TAG, "Écriture de la trace impossible", e)
        null
    }

    private fun header(): String = buildString {
        appendLine("Papyrus — trace de la reconnaissance vocale")
        appendLine("Version : ${BuildConfig.BUILD_REV}")
        appendLine()
        appendLine("Colonnes : [temps depuis le démarrage, écart avec la ligne précédente] origine  contenu")
        appendLine()
        appendLine("Origines : API = rappel du moteur · APP = décision de l'application")
        appendLine("           ÉCRAN = ce qui est réellement affiché")
        appendLine()
        appendLine("Les lignes à chercher en premier, dans cet ordre :")
        appendLine()
        appendLine("  « APP bilan session »   une ligne par session : durée, nombre de partiels,")
        appendLine("                          pic sonore, et texte produit ou non. Commencer par là :")
        appendLine("                          une session sans texte AVEC un pic élevé et une session")
        appendLine("                          sans texte dans le silence sont deux pannes opposées.")
        appendLine()
        appendLine("  « ÉCRAN TEXTE RACCOURCI »  du texte a disparu de l'écran. Les lignes juste")
        appendLine("                          au-dessus en disent la cause — purge d'ancienneté,")
        appendLine("                          ligne figée plus courte que son partiel, ou défaut.")
        appendLine()
        appendLine("  « APP tampon partiel »  la relation entre l'ancien texte et le nouveau :")
        appendLine("                          prolongé, réécrit, ou RACCOURCI. Le troisième cas est")
        appendLine("                          le seul inquiétant.")
        appendLine()
        appendLine("  « REFUSÉ » / « ABANDONNÉE »  une relance qui n'a pas eu lieu. Sans ces lignes,")
        appendLine("                          une écoute qui ne repart jamais ne laisse aucune trace")
        appendLine("                          du tout : le journal s'arrête net sans rien dire.")
        appendLine()
        appendLine("  l'écart entre « API onResults / onError » et le « APP → startListening »")
        appendLine("                          suivant : le temps pendant lequel le moteur n'écoute")
        appendLine("                          pas. Mesuré à 3 et 9 ms sur cet appareil — négligeable.")
        appendLine()
        appendLine("  « API onRmsChanged »    pic sonore par quart de seconde. C'est lui qui sépare")
        appendLine("                          un vrai silence d'une parole non captée.")
        appendLine()
        appendLine("─".repeat(78))
        appendLine()
    }
}
