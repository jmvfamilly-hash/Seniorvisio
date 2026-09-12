package com.seniorvisio.core

import android.os.SystemClock
import java.util.Locale

/**
 * Journal technique de la visiophonie. **Aucune donnée personnelle.**
 *
 * ═══ Pourquoi un second journal, à côté de TranscriptionTrace ═══
 *
 * L'autre trace contient MOT POUR MOT ce qui se dit dans la chambre de Jean.
 * Tout ce qui l'entoure découle de ce seul fait : elle est chiffrée, elle
 * s'arrête d'elle-même au bout de dix minutes, une clé doit être collée dans
 * le navigateur pour la lire, et elle ne s'allume que sur demande explicite.
 *
 * Ces précautions sont justes, et elles coûtent cher quand on cherche pourquoi
 * un appel ne marche pas. Il faut penser à allumer avant de reproduire la
 * panne, retrouver la clé, attendre une publication. Trois occasions de ne pas
 * obtenir la trace, pour une question — « à quel niveau le volume a-t-il été
 * posé ? » — qui ne demande AUCUNE donnée personnelle pour être tranchée.
 *
 * D'où ce journal-ci : des nombres, des booléens, des noms d'états. Ce qu'il
 * contient pourrait être affiché sur la porte de la chambre sans rien révéler
 * de personne.
 *
 * ═══ La séparation est une propriété du code, pas une consigne ═══
 *
 * La garantie ne tient pas à la vigilance de qui écrira la prochaine ligne :
 * elle tient à ce que le chemin de la transcription N'APPELLE JAMAIS cet
 * objet. Aucun texte reconnu ne peut donc y entrer, y compris par accident.
 * Une règle qu'on se donne se perd ; une dépendance qui n'existe pas, non.
 *
 * **Toute ligne ajoutée ici doit se relire en se demandant : est-ce que ceci
 * pourrait contenir un mot prononcé par quelqu'un ?** Si oui, elle appartient
 * à l'autre trace.
 *
 * ═══ Toujours allumé ═══
 *
 * Sans interrupteur, justement parce qu'il n'y a rien à protéger. Une panne
 * d'appel se raconte donc toute seule, sans qu'il ait fallu la prévoir — ce
 * qui est le seul cas qui compte, puisqu'on ne prévoit jamais la première
 * fois. Le tampon est borné et l'écriture réseau est espacée : en régime
 * normal, hors appel, il ne s'y écrit rien du tout.
 */
object CallTrace {

    /**
     * ═══ DEUX TAMPONS, ET LA RAISON EST UNE ERREUR DÉJÀ COMMISE ═══
     *
     * Il n'y en avait qu'un, de trois cents lignes, qui jetait les plus
     * anciennes. Puis un relevé par seconde s'y est ajouté. Sur un appel de
     * deux minutes, le compte est dépassé — et les lignes évincées sont celles
     * du DÉBUT : la version installée, les permissions, le focus audio, le
     * routage, les niveaux à la connexion.
     *
     * C'est-à-dire précisément celles qu'on venait y chercher. Un journal qui
     * jette le début d'un appel est un journal qui perd la réponse et garde la
     * question : « aucune trace focus » ne voulait pas dire que la fonction
     * n'avait pas tourné, mais que sa ligne avait été poussée dehors.
     *
     * L'ouverture d'un appel n'est jamais la partie la moins intéressante. Les
     * premières lignes sont donc mises à part et ne sont JAMAIS évincées ; le
     * reste continue de rouler. Ce n'est pas de la place en plus, c'est le bon
     * bout qu'on garde.
     */
    private const val MAX_OPENING = 120

    /** La suite, qui roule. Un appel long ne doit pas faire perdre le début. */
    private const val MAX_ENTRIES = 900

    /** Les premières lignes depuis le dernier début d'appel. Jamais jetées. */
    private val opening = ArrayDeque<String>()

    /** Source qui marque le début d'un appel et remet [opening] à zéro. */
    private const val CALL_START = "APPEL préparation"

    private var droppedFromTail = 0

    private val entries = ArrayDeque<String>()
    private var startedAtMs = SystemClock.elapsedRealtime()
    private var lastAtMs = startedAtMs

    /** Faux tant que rien n'a changé : évite de réécrire le même texte dans Firestore. */
    @Volatile private var dirty = false

    fun hasNewLines(): Boolean = dirty

    @Synchronized
    fun record(source: String, detail: String = "") {
        val now = SystemClock.elapsedRealtime()
        val since = (now - startedAtMs) / 1000.0
        val delta = (now - lastAtMs) / 1000.0
        lastAtMs = now
        val line = String.format(Locale.FRANCE, "[%9.3fs %+8.3fs] %-26s | %s", since, delta, source, detail)

        // Un nouvel appel commence : l'ouverture du précédent a fait son temps.
        if (source == CALL_START) {
            opening.clear()
            droppedFromTail = 0
        }
        if (opening.size < MAX_OPENING) {
            opening.addLast(line)
        } else {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
                droppedFromTail++
            }
        }
        dirty = true

        // Recopié dans la trace chiffrée quand elle tourne, pour que les deux
        // se lisent côte à côte dans un seul fichier : savoir si un appel a
        // lâché pendant que le moteur de reconnaissance se relançait demande
        // les deux séries d'événements sur la même horloge.
        TranscriptionTrace.record(source, detail)
    }

    /**
     * Exécute un rappel venu de Firestore ou de WebRTC en le journalisant, et
     * SANS laisser une exception remonter.
     *
     * ═══ Pourquoi avaler l'exception est ici le bon choix ═══
     *
     * Un rappel d'instantané Firestore s'exécute sur le thread principal. Une
     * exception qui en sort ne « fait pas échouer le réglage » : elle TUE LE
     * PROCESSUS. Vu de la chambre, l'appel s'interrompt brutalement au moment
     * précis où le proche a touché une case, et rien ne relie les deux faits.
     *
     * L'arbitrage n'est donc pas « masquer une erreur » contre « la voir » :
     * c'est « le réglage n'a pas pris, et on sait lequel » contre « l'appel
     * s'arrête ». Pour un appareil que personne ne relève, c'est sans appel.
     * L'erreur n'est d'ailleurs pas perdue — elle arrive ici avec le nom du
     * rappel fautif, ce qu'un plantage ne laisse pas.
     */
    fun guard(source: String, detail: String = "", block: () -> Unit) {
        record(source, detail)
        try {
            block()
        } catch (e: Throwable) {
            android.util.Log.e("CallTrace", "Exception dans $source, appel préservé", e)
            record("APPEL EXCEPTION", "$source : ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    /** Le journal complet, prêt à être publié. Marque le contenu comme envoyé. */
    @Synchronized
    fun dump(buildRev: String): String {
        dirty = false
        return buildString {
            appendLine("Senior Visio — journal technique de la visiophonie ($buildRev)")
            appendLine("Sans donnée personnelle : aucun texte prononcé n'entre ici (voir CallTrace).")
            appendLine("Colonnes : [temps depuis le démarrage, écart avec la ligne précédente] origine | contenu")
            appendLine()
            append(opening.joinToString("\n"))
            if (droppedFromTail > 0) {
                // Dit, et non passé sous silence : un journal qui a perdu des
                // lignes sans le dire se lit comme un journal complet, et une
                // ligne manquante s'y interprète alors comme un événement qui
                // n'a pas eu lieu. C'est exactement l'erreur qui a coûté une
                // version entière.
                appendLine()
                appendLine("──── $droppedFromTail ligne(s) intermédiaire(s) perdue(s), la suite reprend ici ────")
            }
            if (entries.isNotEmpty()) {
                appendLine()
                append(entries.joinToString("\n"))
            }
        }
    }
}
