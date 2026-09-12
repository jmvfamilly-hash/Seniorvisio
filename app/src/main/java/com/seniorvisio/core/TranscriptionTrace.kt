package com.seniorvisio.core

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.seniorvisio.BuildConfig
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

    /**
     * Rend le texte de la trace SANS l'arrêter. Null si elle ne tourne pas.
     *
     * Existe pour que la trace puisse être publiée en cours de route, et non
     * seulement à l'arrêt. La raison est directe : la panne qu'on cherche le
     * plus souvent avec un journal, c'est celle qui tue l'application — et une
     * trace qui n'est envoyée qu'à l'arrêt disparaît précisément dans ce
     * cas-là, le seul où elle aurait tout expliqué.
     */
    @Synchronized
    fun snapshot(reason: String): String? {
        if (!recording) return null
        return header(reason) + entries.joinToString("\n")
    }

    /** Rend le texte complet de la trace et l'éteint. Null si elle ne tournait pas. */
    @Synchronized
    fun stopAndTake(reason: String): String? {
        if (!recording) return null
        append("APP trace", "arrêtée : $reason")
        val body = entries.joinToString("\n")
        val text = header(reason) + body
        recording = false
        entries.clear()
        return text
    }

    /**
     * Note un événement. [source] nomme le rappel du moteur ou la fonction de
     * l'application — c'est lui qui permet de savoir **qui** a produit quoi, ce
     * qui est toute la question quand du texte disparaît.
     */
    fun record(source: String, detail: String = "") {
        // Le test AVANT le verrou, et non à l'intérieur. La trace est désormais
        // alimentée depuis les threads de WebRTC autant que depuis le thread
        // principal ; verrouiller cet objet à chaque événement d'une pile temps
        // réel, même pour en ressortir aussitôt, serait exactement le coût
        // qu'une trace éteinte ne doit pas avoir.
        if (!recording) return
        append(source, detail)
    }

    @Synchronized
    private fun append(source: String, detail: String) {
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
     * Exécute un rappel venu de Firestore ou de WebRTC en journalisant son
     * entrée, et SANS laisser une exception remonter.
     *
     * ═══ Pourquoi avaler l'exception est ici le bon choix ═══
     *
     * Un rappel d'instantané Firestore s'exécute sur le thread principal. Une
     * exception qui en sort ne « fait pas échouer le réglage » : elle TUE LE
     * PROCESSUS. Vu de la chambre, l'appel s'interrompt brutalement au moment
     * précis où le proche a touché une case, et rien n'indique que les deux
     * faits sont liés.
     *
     * L'arbitrage n'est donc pas « masquer une erreur » contre « la voir » :
     * c'est « le réglage n'a pas pris, et on sait lequel » contre « l'appel
     * s'arrête ». Pour un appareil que personne ne relève, c'est sans appel.
     *
     * L'erreur n'est pas perdue pour autant — elle part dans le journal
     * système ET dans la trace, avec le nom du rappel fautif, ce qui est
     * strictement plus que ce qu'un plantage laisse derrière lui.
     */
    fun guard(source: String, detail: String = "", block: () -> Unit) {
        record(source, detail)
        try {
            block()
        } catch (e: Throwable) {
            Log.e(TAG, "Exception dans $source, appel préservé", e)
            record("APPEL EXCEPTION", "$source : ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
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
        appendLine("           APPEL = chemin de la visiophonie (WebRTC, consignes du proche)")
        appendLine()
        appendLine("Pour une panne d'APPEL, lire dans cet ordre :")
        appendLine()
        appendLine("  « APPEL answer » puis « APPEL onTrack » puis « APPEL volume »  la chaîne")
        appendLine("        qui décide si Jean entend quelque chose. Le niveau posé sur la piste")
        appendLine("        est écrit en toutes lettres : s'il vaut 0, la suite est inutile à")
        appendLine("        chercher ailleurs. Comparer avec « APPEL consigne volume ».")
        appendLine()
        appendLine("  « APPEL consigne … »  chaque réglage venu du PWA, à l'instant où il")
        appendLine("        arrive. Un réglage sans effet a deux causes très différentes : soit")
        appendLine("        sa ligne manque (rien n'est arrivé jusqu'à la tablette), soit elle")
        appendLine("        est là et n'a rien changé. Les deux se traitent à l'opposé.")
        appendLine()
        appendLine("  « APPEL raccroché » / « APPEL raccroché auto » / « APPEL ICE »  pourquoi")
        appendLine("        un appel s'est arrêté. Le raccroché porte les trois appels qui l'ont")
        appendLine("        déclenché : c'est ce qui distingue le chien de garde du flux d'un")
        appendLine("        échec ICE ou d'un geste sur l'écran.")
        appendLine()
        appendLine("  « APPEL EXCEPTION »  une erreur qui aurait tué l'application, interceptée")
        appendLine("        pour préserver l'appel. Toute ligne de ce type est un défaut à")
        appendLine("        corriger, même si l'appel a continué.")
        appendLine()
        appendLine("Pour la transcription, les lignes à chercher en premier :")
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
