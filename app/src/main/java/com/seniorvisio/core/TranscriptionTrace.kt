package com.seniorvisio.core

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Journal détaillé de ce qui traverse la frontière entre l'application et le
 * moteur de reconnaissance d'Android.
 *
 * ═══ Pourquoi il existe ═══
 *
 * Le moteur est une boîte noire : on lui donne la main, on la reprend, et entre
 * les deux il rend des textes qui se corrigent, se remplacent, ou ne viennent
 * jamais. Vu de l'écran, une phrase qui disparaît a plusieurs causes possibles
 * — un résultat vide, une erreur tardive, une décision de l'application — et
 * elles sont rigoureusement indiscernables sans journal.
 *
 * Toute la mise au point du moteur Android est venue d'une trace de ce genre,
 * relevée sur une application d'essai. Celle-ci porte le même instrument dans
 * la tablette elle-même, où les conditions réelles diffèrent : une chambre
 * silencieuse la majeure partie du temps, une voix âgée, des journées entières.
 *
 * ═══ Trois précautions, et chacune répond à un risque précis ═══
 *
 * **Inerte quand elle est éteinte.** [record] sort sur un test booléen avant de
 * faire quoi que ce soit. Un journal qui coûterait quelque chose en usage
 * normal serait un journal qu'on n'ose pas laisser dans l'application.
 *
 * **Arrêt forcé au bout de [MAX_DURATION_MS].** Une trace oubliée allumée est
 * le scénario probable, pas le scénario rare. Dix minutes suffisent à observer
 * ce qu'on cherche, et bornent ce qui peut être enregistré par inadvertance.
 *
 * **Chiffrée avant de partir.** C'est le point qui compte le plus : cette trace
 * contient MOT POUR MOT ce qui se dit dans la chambre de Jean. Les règles
 * Firestore de ce projet laissent lire quiconque connaît l'adresse d'un
 * document. Y déposer un transcript en clair serait publier la vie privée d'un
 * homme pour la commodité d'un diagnostic.
 *
 * ═══ Ce que le chiffrement protège, et ce qu'il ne protège pas ═══
 *
 * La clé arrive par un secret de compilation, elle n'est donc jamais dans
 * Firestore. Elle est en revanche **dans l'APK**, et un APK se démonte. Ce
 * chiffrement met donc la trace hors de portée de qui passe par Firestore — ce
 * qui est le risque réel ici — et non hors de portée de qui aurait la tablette
 * en main et saurait l'ouvrir. Il ne faut pas lui prêter plus.
 */
object TranscriptionTrace {

    private const val TAG = "TranscriptionTrace"

    /**
     * Dix minutes à dix événements par seconde font six mille lignes. Le plafond
     * est au double, pour qu'une pièce animée ne perde pas le début de sa propre
     * trace avant l'arrêt.
     */
    private const val MAX_ENTRIES = 12_000

    /** Au-delà, la trace s'arrête d'elle-même. Voir le commentaire de classe. */
    const val MAX_DURATION_MS = 10 * 60 * 1000L

    /**
     * Taille visée d'un morceau, en caractères de texte clair.
     *
     * Un document Firestore plafonne à un mébioctet. Le chiffrement puis le
     * Base64 gonflent d'environ un tiers, et les champs voisins comptent aussi.
     * Cent cinquante mille caractères laissent une marge que personne n'aura à
     * recalculer le jour où une ligne de trace s'allonge.
     */
    private const val CHUNK_CHARS = 150_000

    private val entries = ArrayDeque<String>()

    @Volatile private var recording = false
    private var startedAtMs = 0L
    private var lastAtMs = 0L
    private var dropped = 0

    /** Vrai tant que la trace enregistre. Lu par l'état publié vers le PWA. */
    fun isRecording(): Boolean = recording

    /** Millisecondes écoulées depuis le début, ou zéro si la trace est éteinte. */
    fun elapsedMs(): Long = if (recording) SystemClock.elapsedRealtime() - startedAtMs else 0L

    @Synchronized
    fun start() {
        entries.clear()
        dropped = 0
        startedAtMs = SystemClock.elapsedRealtime()
        lastAtMs = startedAtMs
        recording = true
        record("APP trace", "démarrée")
    }

    /** Rend le texte complet de la trace et l'éteint. Null si elle ne tournait pas. */
    @Synchronized
    fun stopAndTake(reason: String): String? {
        if (!recording) return null
        record("APP trace", "arrêtée : $reason")
        recording = false
        val body = entries.joinToString("\n")
        entries.clear()
        return header(reason) + body
    }

    /**
     * Note un événement. [source] nomme le rappel du moteur ou la fonction de
     * l'application — c'est lui qui permet de savoir **qui** a produit quoi, ce
     * qui est toute la question quand du texte disparaît.
     */
    @Synchronized
    fun record(source: String, detail: String = "") {
        if (!recording) return
        val now = SystemClock.elapsedRealtime()
        val since = (now - startedAtMs) / 1000.0
        val delta = (now - lastAtMs) / 1000.0
        lastAtMs = now
        // Délimiteur explicite entre l'origine et le contenu, et non un simple
        // remplissage : un nom plus long que la colonne ferait sauter le
        // remplissage et collerait le détail au nom. Une barre ne peut être
        // cassée par aucune longueur.
        entries.addLast(
            String.format(Locale.FRANCE, "[%9.3fs %+8.3fs] %-26s | %s", since, delta, source, detail)
        )
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
            dropped++
        }
    }

    /**
     * Note un résultat du moteur avec **toutes** ses variantes, pas seulement
     * celle qu'on retient.
     *
     * Les variantes expliquent l'impression que « la phrase se remplace par une
     * autre » : le moteur ne corrige pas un mot, il propose un autre découpage
     * de la même suite de sons. Ne journaliser que le premier choix rendrait ce
     * phénomène invisible dans la trace alors qu'il est visible à l'écran.
     */
    @Synchronized
    fun recordResults(source: String, results: android.os.Bundle?) {
        if (!recording) return
        val texts = results?.getStringArrayList(
            android.speech.SpeechRecognizer.RESULTS_RECOGNITION
        )
        if (texts.isNullOrEmpty()) {
            record(source, "AUCUN TEXTE")
            return
        }
        record(
            source,
            texts.mapIndexed { index, text ->
                if (index == 0) "« $text »" else "| variante $index : « $text »"
            }.joinToString(" "),
        )
    }

    /**
     * Découpe le texte en morceaux chiffrés, prêts à être écrits dans Firestore.
     *
     * Rend une liste vide si aucune clé n'a été fournie à la compilation —
     * **jamais le texte en clair**. Une trace qui part en clair faute de clé
     * serait exactement l'accident que tout ce fichier cherche à éviter, et il
     * passerait inaperçu puisque tout aurait l'air de fonctionner.
     */
    fun encryptToChunks(text: String, key: String): List<String> {
        if (key.isBlank()) {
            Log.w(TAG, "Aucune clé de chiffrement : la trace n'est pas publiée")
            return emptyList()
        }
        val secret = SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)),
            "AES",
        )
        return text.chunked(CHUNK_CHARS).map { part -> encrypt(part, secret) }
    }

    /**
     * Un morceau chiffré, sous la forme « vecteur:contenu », les deux en Base64.
     *
     * AES en mode GCM, et un vecteur d'initialisation TIRÉ AU HASARD pour
     * chaque morceau. Réutiliser un vecteur avec la même clé est la faute
     * classique de ce mode : deux textes chiffrés avec le même couple se
     * déchiffrent l'un par l'autre. Douze octets, comme le recommande la
     * spécification du mode.
     */
    private fun encrypt(plain: String, secret: SecretKeySpec): String {
        val iv = ByteArray(12).also { Random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val flags = Base64.NO_WRAP
        return Base64.encodeToString(iv, flags) + ":" + Base64.encodeToString(sealed, flags)
    }

    private fun header(reason: String): String = buildString {
        appendLine("Senior Visio — trace de la reconnaissance vocale de la pièce")
        appendLine("Version : ${BuildConfig.BUILD_REV}")
        appendLine("Durée : %.1f s — arrêt : %s".format(Locale.FRANCE, (lastAtMs - startedAtMs) / 1000.0, reason))
        if (dropped > 0) {
            appendLine("ATTENTION : $dropped ligne(s) perdue(s) en tête, la trace a dépassé sa capacité.")
        }
        appendLine()
        appendLine("Colonnes : [temps depuis le début, écart avec la ligne précédente] origine | contenu")
        appendLine("Origines : API = rappel du moteur · APP = décision de l'application")
        appendLine()
        appendLine("Les lignes à chercher en premier :")
        appendLine()
        appendLine("  « APP → startListening » / « API onResults »  une session du moteur, donc")
        appendLine("        un énoncé. L'écart entre un onResults et le startListening suivant")
        appendLine("        est le temps mort ; celui entre startListening et le premier texte")
        appendLine("        est la surdité de reprise, mesurée de 2 à 8 s sur l'appareil d'essai.")
        appendLine()
        appendLine("  « API onError »  « rien compris » et « silence » sont le cas ordinaire")
        appendLine("        d'une chambre vide. Les autres codes comptent, et le moteur est")
        appendLine("        alors reconstruit.")
        appendLine()
        appendLine("  « APP figé »  chaque ligne réellement écrite à l'écran, et par quel")
        appendLine("        chemin — résultat final, tampon de secours, ou arrêt de l'écoute.")
        appendLine()
        appendLine("  « API onPartialResults »  avec ses variantes. C'est là que se lisent les")
        appendLine("        révisions du moteur, invisibles autrement.")
        appendLine()
        appendLine("─".repeat(78))
        appendLine()
    }
}
