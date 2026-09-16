package com.seniorvisio.core

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
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

        planifierÉcriture()
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
        return construire(buildRev)
    }

    /**
     * Le même texte, sans rien marquer.
     *
     * Séparé de [dump] parce que l'écriture sur disque ne doit PAS faire
     * croire à Firestore que le journal est parti : les deux filets sont
     * indépendants, et celui qui écrit le fichier passe beaucoup plus souvent
     * que celui qui publie.
     */
    @Synchronized
    private fun construire(buildRev: String): String {
        return buildString {
            appendLine("Senior Visio — journal technique de la visiophonie ($buildRev)")
            // De quelle tablette vient ce journal, et où elle écrit. Deux
            // appareils font tourner ce code sur des documents différents
            // (voir Environnement) : sans cette ligne, une trace relue
            // quelques jours plus tard ne dit pas laquelle des deux l'a
            // produite — et on cherche une panne de production dans un
            // journal de banc d'essai.
            appendLine("Environnement : ${Environnement.description()}")
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

    // ═══════════════════════════════════════════════════════════════════════
    // SURVIVRE À LA MORT DU PROCESSUS
    // ═══════════════════════════════════════════════════════════════════════
    //
    // Tout ce qui précède vit en mémoire, et n'est publié que toutes les vingt
    // secondes (voir DeviceStatusReporter.callLogFlush). Une application qui
    // disparaît pendant un appel emporte donc exactement les lignes qu'on
    // venait y chercher : celles de l'appel qui l'a tuée.
    //
    // Ce n'est pas une précaution théorique, c'est un échec constaté. Le
    // journal relevé après coup portait en tête le numéro de la version
    // PRÉCÉDENTE, parce qu'aucun appel de la nouvelle n'avait vécu assez
    // longtemps pour être publié — et on en a d'abord conclu que la tablette
    // n'avait pas été mise à jour. L'instrument se taisait précisément au
    // moment qu'il était censé décrire.
    //
    // D'où deux filets, qui ne couvrent pas les mêmes morts :
    //
    //   • un FICHIER réécrit au plus toutes les cinq secondes. Il survit à
    //     tout, y compris à ce qu'aucun code Java ne voit passer — plantage
    //     natif de WebRTC, processus tué par le système faute de mémoire.
    //   • un GESTIONNAIRE D'EXCEPTION non rattrapée, qui ajoute la pile
    //     d'appels avant que le processus ne s'éteigne. Lui seul dit pourquoi.
    //
    // Au démarrage suivant, ce fichier est relu et publié tel quel : la panne
    // se raconte donc au redémarrage, sans que personne ait eu à prévoir de
    // l'enregistrer.

    private const val FICHIER = "journal-appel-precedent.txt"

    /**
     * Cinq secondes : assez rapproché pour qu'une mort brutale ne coûte que
     * quelques lignes, assez espacé pour qu'un appel qui écrit une ligne par
     * seconde ne réécrive pas cent kilo-octets à chaque fois.
     */
    private const val DÉLAI_DISQUE_MS = 5_000L

    private var dossier: File? = null
    private var révision: String = "?"
    private var écrivain: Handler? = null

    /**
     * Étranglement, et non anti-rebond.
     *
     * La distinction n'est pas de style : un anti-rebond repousse l'écriture à
     * chaque nouvelle ligne, et pendant un appel il en arrive une par seconde.
     * Le fichier n'aurait donc JAMAIS été écrit pendant la seule situation qui
     * le justifie. Ici, la première ligne arme l'écriture et les suivantes ne
     * la repoussent pas : au pire cinq secondes de retard, jamais l'infini.
     */
    @Volatile private var écritureProgrammée = false

    private val écriture = Runnable {
        écritureProgrammée = false
        écrireSurDisque()
    }

    /**
     * À appeler une fois au démarrage du service, APRÈS
     * [récupérerJournalPrécédent] : la première écriture du nouveau processus
     * écrase le fichier laissé par l'ancien.
     */
    fun installerPersistance(context: Context, buildRev: String) {
        if (dossier != null) return
        dossier = context.filesDir
        révision = buildRev
        écrivain = Handler(HandlerThread("CallTraceDisque").apply { start() }.looper)
        installerFiletDePlantage()
    }

    /**
     * Ce que le processus précédent a laissé derrière lui, ou null s'il s'est
     * arrêté proprement — ou s'il n'y en a jamais eu.
     *
     * Le fichier n'est pas effacé : il sera écrasé de toute façon dans les
     * cinq secondes par le journal du nouveau processus. Un effacement en plus
     * serait une occasion de plus de perdre la seule copie, entre la lecture
     * et une publication qui peut échouer.
     */
    fun récupérerJournalPrécédent(context: Context): String? {
        val f = File(context.filesDir, FICHIER)
        return runCatching { if (f.exists()) f.readText().ifBlank { null } else null }.getOrNull()
    }

    private fun planifierÉcriture() {
        val h = écrivain ?: return
        if (écritureProgrammée) return
        écritureProgrammée = true
        h.postDelayed(écriture, DÉLAI_DISQUE_MS)
    }

    private fun écrireSurDisque() {
        val d = dossier ?: return
        runCatching { File(d, FICHIER).writeText(construire(révision)) }
            .onFailure { android.util.Log.w("CallTrace", "Journal non persisté", it) }
    }

    /**
     * La pile d'appels de ce qui tue l'application, écrite avant qu'elle ne
     * meure.
     *
     * L'ancien gestionnaire est rappelé à la fin, sans condition : celui
     * d'Android termine le processus et remonte le plantage. Le remplacer sans
     * le rappeler laisserait une application à moitié morte, ce qui est pire
     * que le plantage lui-même — et masquerait le défaut aux outils système.
     *
     * Tout est enveloppé : à ce stade le processus est déjà en mauvais état
     * (une OutOfMemoryError ne laisse pas construire cent kilo-octets de
     * texte), et un filet qui plante en voulant décrire un plantage ne
     * laisserait rien du tout.
     */
    private fun installerFiletDePlantage() {
        val précédent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, e ->
            runCatching {
                record(
                    "PLANTAGE",
                    "${e.javaClass.name} sur le fil « ${fil.name} » : ${e.message ?: "sans message"}",
                )
                // Vingt-cinq lignes : de quoi traverser le code de
                // l'application et atteindre le cadre fautif, sans y recopier
                // toute la pile du framework.
                e.stackTraceToString().lineSequence().take(25).forEach {
                    record("PLANTAGE pile", it.trim())
                }
                // Sur le fil qui meurt, et non sur l'écrivain de fond : le
                // processus n'aura pas le temps d'exécuter un message posté
                // dans une file.
                écrireSurDisque()
            }
            précédent?.uncaughtException(fil, e)
        }
    }
}
