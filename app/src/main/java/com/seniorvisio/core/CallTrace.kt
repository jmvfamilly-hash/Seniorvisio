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

    /**
     * ═══ UN TROISIÈME TAMPON, PARCE QUE LE DEUXIÈME A EFFACÉ LA PREUVE ═══
     *
     * Deux corrections poussées le même jour se contredisaient.
     *
     * L'une mesure la mémoire AU REPOS, toutes les cinq minutes, parce que la
     * croissance jusqu'à neuf cents mégaoctets ne se produit pas pendant les
     * appels mais entre eux. L'autre vide les deux tampons au début d'un appel,
     * pour que la chronologie cesse de reculer au milieu du texte.
     *
     * La seconde détruit donc exactement ce que la première collecte. Sur le
     * premier journal récupéré après coup, il ne restait AUCUNE mesure au
     * repos : le début de l'appel les avait toutes emportées. L'instrument
     * s'était éteint lui-même, et il fallait relire le code pour s'en rendre
     * compte — vu du journal, « aucune ligne de repos » ressemble à « rien ne
     * s'est passé au repos ».
     *
     * D'où ce tampon-ci : les lignes qui décrivent l'état de la machine, et
     * elles seules, y sont recopiées et n'en sortent JAMAIS au début d'un
     * appel. Il couvre la vie entière du processus, pas celle d'un appel.
     *
     * La chronologie reste juste parce que ces lignes sont imprimées dans une
     * section à part, sous un intertitre qui dit ce qu'elle est. Le défaut
     * d'origine n'était pas que d'anciennes lignes subsistent : c'était
     * qu'elles se mélangeaient aux nouvelles sans rien qui les distingue.
     *
     * Elles restent aussi dans le flot normal, à leur place. Une alerte mémoire
     * pendant une conversation est un événement de cette conversation ; la voir
     * deux fois avec le même horodatage ne trompe personne, ne pas la voir du
     * tout dans la trace de l'appel, si.
     */
    private const val MAX_REPOS = 60

    private val repos = ArrayDeque<String>()
    private var reposPerdues = 0

    /**
     * Ce qui décrit la machine plutôt que l'appel. Un préfixe, et non une
     * liste exacte : « REPOS mémoire » et « REPOS mémoire SAUT » sont la même
     * mesure, et oublier la seconde reviendrait à jeter précisément les sauts.
     */
    private val PRÉFIXES_REPOS = listOf(
        "DÉMARRAGE",
        "REPOS mémoire",
        "MÉMOIRE",
        // Le refus d'un élément : rare, et c'est la seule ligne qui dise
        // POURQUOI l'écran s'est vidé. Sans elle dans ce tampon, elle serait
        // partie au premier appel.
        "ACCUEIL élément refusé",
        // La galerie installée, dite au démarrage. Une seule ligne par
        // processus, et elle répond à « qu'est-ce que cette tablette est
        // censée présenter ? ».
        "GALERIE",
    )

    /**
     * Ce qui ne s'évince pas tant qu'autre chose peut partir à sa place.
     *
     * Les lignes de la galerie sont rares — une à l'installation, une par
     * refus — et ce sont celles qui disent pourquoi l'écran affiche ce qu'il
     * affiche. Les relevés mémoire, eux, reviennent toutes les cinq minutes
     * et le plus ancien ne manque à personne.
     *
     * Sans cette distinction, un tampon plein finissait par ne contenir que
     * les cinq dernières heures de relevés mémoire — et la lecture du matin,
     * la seule qui explique la journée, était partie depuis longtemps.
     */
    private val PRÉFIXES_PROTÉGÉS = listOf(
        "DÉMARRAGE",
        // Un refus d'élément part une fois, puis l'écran reste vide pendant
        // des heures en produisant des relevés mémoire qui l'évinceraient.
        "ACCUEIL élément refusé",
        "GALERIE",
    )

    private fun estLigneDeRepos(source: String): Boolean =
        PRÉFIXES_REPOS.any { source.startsWith(it) }

    /**
     * L'origine d'une ligne déjà formatée, pour décider quoi évincer.
     *
     * Par la fermeture du crochet, et NON par un index fixe : le format place
     * l'origine en vingt-troisième colonne, mais les deux horodatages
     * s'élargissent au-delà de leur gabarit après quelques heures de
     * fonctionnement. Un découpage à position fixe aurait donc été juste sur
     * les essais courts et faux sur la tablette de Jean, qui tourne des
     * semaines — le genre d'erreur qui ne se voit jamais là où on la cherche.
     */
    private fun origineDe(ligne: String): String {
        val début = ligne.indexOf("] ")
        return if (début < 0) ligne else ligne.substring(début + 2)
    }
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

        // ═══ LES DEUX TAMPONS SONT VIDÉS, ET C'EST UN CORRECTIF ═══
        //
        // Seul « opening » l'était. Les lignes d'AVANT l'appel restaient donc
        // dans « entries », et le journal les rendait APRÈS les cent vingt
        // premières lignes de l'appel — puisqu'il imprime opening puis entries.
        //
        // Le résultat se lisait ainsi : 5108s, puis 5000s, puis 5014s, puis
        // 5110s. Des horodatages qui reculent au milieu du texte. C'est le seul
        // instrument dont on dispose pour cette panne, et il rendait une
        // chronologie fausse — de quoi conclure n'importe quoi sur l'ordre des
        // événements.
        //
        // Rien n'est perdu pour autant : le journal est publié toutes les vingt
        // secondes dès qu'il change, donc les lignes d'avant l'appel ont déjà
        // été envoyées. Ce qui reste ici couvre exactement « depuis le début de
        // cet appel », ce qui est précisément ce qu'on vient y chercher.
        //
        // Recopié AVANT l'effacement, et conservé à travers lui : c'est tout
        // l'objet de ce tampon (voir MAX_REPOS).
        if (estLigneDeRepos(source)) {
            repos.addLast(line)
            while (repos.size > MAX_REPOS) {
                // La plus ancienne NON PROTÉGÉE part la première. Si tout est
                // protégé, la plus ancienne part quand même : un tampon qui
                // refuserait de se vider finirait par ne plus rien accepter,
                // ce qui est pire que de perdre une ligne.
                val victime = repos.indexOfFirst { ligne ->
                    val origine = origineDe(ligne)
                    PRÉFIXES_PROTÉGÉS.none { origine.startsWith(it) }
                }
                repos.removeAt(if (victime >= 0) victime else 0)
                reposPerdues++
            }
        }
        if (source == CALL_START) {
            opening.clear()
            entries.clear()
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
            if (repos.isNotEmpty()) {
                appendLine("──── ÉTAT DE LA MACHINE, depuis le démarrage et à travers les appels ────")
                if (reposPerdues > 0) {
                    appendLine("     ($reposPerdues relevé(s) plus ancien(s) évincé(s))")
                }
                append(repos.joinToString("\n"))
                appendLine()
                appendLine()
                appendLine("──── LA SUITE : depuis le début du dernier appel ────")
                appendLine()
            }
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

    /**
     * Où en est la mémoire de cette application, en une ligne.
     *
     * ═══ POURQUOI CETTE MESURE MANQUAIT, ET CE QU'ELLE TRANCHE ═══
     *
     * Une application qui disparaît sans laisser de ligne PLANTAGE n'a pas
     * levé d'exception Java : elle a été tuée. Deux causes seulement, et elles
     * demandent des recherches opposées — un plantage dans du code natif
     * (WebRTC, décodage vidéo), ou le système qui reprend la mémoire.
     *
     * Rien ici ne mesurait la mémoire, nulle part. On ne pouvait donc pas
     * distinguer les deux, et c'est exactement l'arbitrage sur lequel on
     * butait : une courbe qui monte nomme une fuite, une courbe plate
     * l'innocente et désigne le natif.
     *
     * Le tas natif est mesuré séparément du tas Java, et c'est essentiel
     * ici : depuis Android 8 les images vivent dans le tas NATIF. Une
     * application qui en accumule voit donc sa mémoire croître sans que le
     * tas Java bouge — et sans jamais lever d'OutOfMemoryError. Elle est
     * simplement tuée, en silence. Regarder le seul tas Java aurait conclu
     * « la mémoire va bien » au moment précis où elle ne va pas.
     */
    fun mesureMémoire(): String {
        val r = Runtime.getRuntime()
        val moOctets = 1024L * 1024L
        val javaUtilisé = (r.totalMemory() - r.freeMemory()) / moOctets
        val javaMax = r.maxMemory() / moOctets
        val natif = android.os.Debug.getNativeHeapAllocatedSize() / moOctets
        // Le RÉSIDENT en tête, et l'allocateur étiqueté pour ce qu'il est.
        // Deux jours de recherche ont été menés sur un chiffre qui ne mesurait
        // pas l'empreinte ; le nom porte désormais l'avertissement.
        // Le cache d'images est nommé À PART, et ce n'est pas un détail : une
        // montée du résident ne dit pas d'où elle vient, et on a déjà passé
        // deux jours sur un chiffre qui ne mesurait pas ce qu'on croyait.
        // « images=42 Mo » face à un plafond connu tranche en une seconde.
        val images = ChargeurImages.tailleCacheMo()?.let { " · images=$it Mo" } ?: ""
        return "résident=${résidentMo() ?: "?"} Mo · échangé=${échangéMo() ?: "?"} Mo · " +
            "java=${javaUtilisé}/${javaMax} Mo · natif alloué=$natif Mo" + images
    }

    /**
     * L'empreinte réellement en mémoire vive, lue dans /proc/self/status.
     *
     * ═══ POURQUOI CELUI-CI ET PAS LES AUTRES ═══
     *
     * Trois chiffres ont servi de mesure dans ce projet, et les deux premiers
     * ne mesuraient pas ce qu'on croyait.
     *
     * getNativeHeapAllocatedSize est la comptabilité de l'ALLOCATEUR : ce
     * qu'il a distribué, pas ce qui occupe la mémoire vive. Les deux ont
     * divergé d'un facteur deux à trois.
     *
     * summary.total-pss est pire encore pour cet usage, et de façon
     * contre-intuitive : getTotalPss() ajoute les pages PARTIES EN SWAP,
     * qu'aucune des catégories nommées ne compte. Sur le dernier journal de
     * production, total=3031 Mo dont échange=3022 — le « total » décrivait
     * presque entièrement de la mémoire qui n'est plus en RAM. Et comme
     * summary.system est calculé par soustraction, tout le swap y retombait :
     * système ≈ échange n'était pas une découverte, c'était une identité
     * arithmétique, que j'ai lue comme un fait.
     *
     * VmRSS, lui, dit une chose et une seule : le nombre de pages de ce
     * processus effectivement en mémoire vive. Pas de part proportionnelle,
     * pas de swap recompté, pas de résidu. VmSwap dit l'autre moitié de
     * l'histoire, séparément, ce qui est exactement ce qui manquait.
     *
     * Null si le fichier est illisible — on le dit, plutôt que de rendre zéro,
     * qui se lirait comme une mesure.
     */
    fun résidentMo(): Long? = ligneDeStatutMo("VmRSS:")

    /** Ce que ce processus a fait sortir en swap, en Mo. Voir résidentMo. */
    fun échangéMo(): Long? = ligneDeStatutMo("VmSwap:")

    /**
     * Une ligne de /proc/self/status, en mégaoctets.
     *
     * Le noyau l'écrit en kilo-octets : « VmRSS:    123456 kB ». On découpe
     * sur les espaces plutôt que par position — le nombre est aligné à droite
     * sur une largeur qui varie avec sa grandeur.
     *
     * AUCUNE DONNÉE PERSONNELLE : ce fichier ne contient que des compteurs du
     * noyau sur ce processus-ci. Pas un mot de ce qui se dit dans la pièce.
     */
    private fun ligneDeStatutMo(préfixe: String): Long? = runCatching {
        File("/proc/self/status").useLines { lignes ->
            lignes.firstOrNull { it.startsWith(préfixe) }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.div(1024L)
        }
    }.getOrNull()

    /**
     * La même mesure, mais VENTILÉE PAR CATÉGORIE.
     *
     * ═══ POURQUOI LE TAS NATIF GLOBAL NE SUFFIT PAS ═══
     *
     * Il a suffi à trancher la première question — la mémoire, et non un
     * plantage natif — en montrant le tas natif passer de 39 à 757 Mo en
     * trente secondes pendant que le tas Java ne bougeait pas.
     *
     * Il ne dit pas ce QUI enfle. Or les recherches divergent complètement
     * selon la catégorie : « graphics » désigne les tampons de surface et les
     * trames vidéo, donc le rendu WebRTC ; « native » désigne des allocations
     * ordinaires, donc du code qui retient des octets ; « code » désigne des
     * bibliothèques chargées. Chercher au mauvais endroit coûte une journée.
     *
     * Debug.getMemoryInfo coûte quelques dizaines de millisecondes : c'est
     * pour ça qu'elle n'est appelée que sur un SAUT constaté, et non à chaque
     * battement. Mesurer trop souvent aurait ralenti ce qu'on mesure.
     *
     * ═══ CINQ CATÉGORIES NE FAISAIENT PAS LE COMPTE ═══
     *
     * La première fois que cette ventilation s'est déclenchée pour de bon, elle
     * a rendu ceci :
     *
     *     graphique=0 · natif=19 · java=10 · code=4 · pile=0 · total=887
     *
     * Trente-trois mégaoctets nommés sur huit cent quatre-vingt-sept. Les
     * catégories affichées étaient exactes ; elles décrivaient quatre pour cent
     * de la mémoire du processus, et on en tirait des conclusions sur la
     * totalité.
     *
     * private-other et system manquaient — c'est là que vivent les
     * cartographies anonymes et les zones partagées, donc précisément ce qui
     * n'est ni du tas Java, ni du tas natif, ni du code. Elles sont ajoutées,
     * avec l'échange.
     *
     * Et une ligne « non expliqué », qui est la garde : tant qu'elle vaut zéro,
     * la ventilation dit tout. Le jour où elle ne vaut plus zéro, elle le dit
     * elle-même au lieu de laisser croire qu'on a fait le tour — ce qui vient
     * de coûter une conclusion fausse, avancée avec une arithmétique qui
     * tombait juste.
     */
    fun ventilationMémoire(): String {
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        fun stat(nom: String) = info.getMemoryStat(nom)?.toIntOrNull()?.div(1024) ?: -1
        val graphique = stat("summary.graphics")
        val natif = stat("summary.native-heap")
        val java = stat("summary.java-heap")
        val code = stat("summary.code")
        val pile = stat("summary.stack")
        val autrePrivé = stat("summary.private-other")
        val système = stat("summary.system")
        val total = stat("summary.total-pss")
        // Ce qui n'est expliqué par AUCUNE catégorie. Zéro en régime sain ; tout
        // autre chiffre dit que l'instrument regarde à côté (voir ci-dessus).
        val nommées = listOf(graphique, natif, java, code, pile, autrePrivé, système)
            .filter { it >= 0 }.sum()
        return "graphique=$graphique Mo · natif=$natif Mo · java=$java Mo · " +
            "code=$code Mo · pile=$pile Mo · autre-privé=$autrePrivé Mo · " +
            // ═══ « SYSTÈME » ET « TOTAL » SONT ÉTIQUETÉS POUR CE QU'ILS SONT ═══
            //
            // summary.system n'est pas une mesure : Android la CALCULE comme
            // le reste — total-pss moins les catégories nommées. Or
            // getTotalPss() ajoute les pages parties en swap, qu'aucune de ces
            // catégories ne compte. Tout le swap retombe donc dans
            // « système », et l'égalité système ≈ échange que j'ai lue comme
            // une découverte n'était qu'une soustraction.
            //
            // Les deux gardent leur place — ils font le compte, et la ligne
            // « non expliqué » ne vaut que s'il est complet — mais ils portent
            // désormais ce qu'il faut savoir pour ne pas les relire de travers.
            "système=$système Mo (résidu, contient l'échange) · " +
            "échange=${stat("summary.total-swap")} Mo · " +
            "total=$total Mo (échange compris) · non expliqué=${total - nommées} Mo · " +
            "résident réel=${résidentMo() ?: "?"} Mo"
    }

    /**
     * Ce qu'il reste de mémoire À LA TABLETTE ENTIÈRE, et non à nous.
     *
     * ═══ LA MESURE QUI MANQUAIT, ET CE QU'ELLE RETOURNE ═══
     *
     * Le journal porte cette ligne, une seconde et demie après le démarrage :
     *
     *     MÉMOIRE réclamée | CRITIQUE — le système va tuer des services
     *                      | java=3/192 Mo · natif=7 Mo
     *
     * Une application qui vient de naître, qui tient sept mégaoctets, et à qui
     * le système annonce qu'il va tuer des services. Sept mégaoctets ne mettent
     * aucun appareil en difficulté. La pénurie ne vient donc pas de nous — ou
     * pas d'elle seule — et nous sommes peut-être tués comme voisins encombrants
     * plutôt que comme coupables.
     *
     * Tout ce qui était mesuré jusqu'ici décrivait NOTRE consommation. Aucune
     * de ces mesures ne peut distinguer « nous fuyons » de « la tablette est
     * pleine », puisque les deux donnent la même courbe vue de chez nous. Celle
     * d'en face manquait, et elle tranche : si la mémoire libre de l'appareil
     * s'effondre pendant que notre tas reste plat, le coupable est ailleurs.
     *
     * availMem se lit dans /proc/meminfo : quelques centaines de microsecondes,
     * assez peu cher pour accompagner chaque relevé plutôt que les seuls sauts.
     */
    fun mesureSystème(): String {
        val am = gestionnaireActivité ?: return "tablette=non mesurée"
        val info = android.app.ActivityManager.MemoryInfo()
        return runCatching {
            am.getMemoryInfo(info)
            val mo = 1024L * 1024L
            val alerte = if (info.lowMemory) " ⚠ SOUS LE SEUIL" else ""
            "tablette libre=${info.availMem / mo}/${info.totalMem / mo} Mo " +
                "(seuil=${info.threshold / mo} Mo)$alerte"
        }.getOrElse { "tablette=illisible" }
    }

    private const val FICHIER = "journal-appel-precedent.txt"

    /**
     * Cinq secondes : assez rapproché pour qu'une mort brutale ne coûte que
     * quelques lignes, assez espacé pour qu'un appel qui écrit une ligne par
     * seconde ne réécrive pas cent kilo-octets à chaque fois.
     */
    private const val DÉLAI_DISQUE_MS = 5_000L

    private var dossier: File? = null
    @Volatile private var gestionnaireActivité: android.app.ActivityManager? = null
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
        // applicationContext, et non le service : cet objet vit aussi longtemps
        // que le processus, et retenir un composant qui, lui, s'arrête, le
        // garderait en vie pour rien.
        gestionnaireActivité = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
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
