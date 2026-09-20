package com.seniorvisio.core

import android.content.Context
import android.content.SharedPreferences
import com.seniorvisio.BuildConfig
import org.json.JSONObject

/**
 * Source unique de vérité pour les réglages modifiables sans recompilation.
 * Stocké en local (SharedPreferences) pour la V1 ; prévu pour être remplacé
 * plus tard par une synchronisation distante (petit dashboard web) sans
 * changer les appels dans le reste de l'app - c'est pour ça que tout passe
 * par cette classe et jamais par des accès directs aux préférences ailleurs.
 */
class AdminConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("senior_visio_admin", Context.MODE_PRIVATE)

    // --- Fonction "alerte écran + décrochage présence", désactivable par l'admin ---
    var visualAlertModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_VISUAL_ALERT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VISUAL_ALERT_ENABLED, value).apply()

    // --- Blocage du réveil de l'écran pendant la nuit (voir
    // RoomPresenceService.ensureAwake). Désactivé par défaut : une chambre où
    // l'on parle à trois heures du matin est justement le moment où Jean a le
    // plus besoin de lire ce qui se dit — un soignant qui entre, quelqu'un qui
    // l'appelle. Bloquer par défaut revenait à éteindre la fonction
    // précisément quand elle sert le plus, et de façon invisible : rien à
    // l'écran ne disait que c'était l'heure qui l'empêchait.
    //
    // À activer sur place si la lumière de la dalle finit par gêner le
    // sommeil — ce qui dépend de la pièce et de la personne, pas d'une règle
    // générale. Clé distincte de l'ancienne : le sens du réglage s'inverse,
    // une valeur enregistrée sous l'ancien nom voudrait dire le contraire. ---
    var blockWakeAtNight: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_WAKE_AT_NIGHT, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_WAKE_AT_NIGHT, value).apply()

    // --- Plage horaire considérée comme la nuit (voir blockWakeAtNight) ---
    var nightStartHour: Int
        get() = prefs.getInt(KEY_NIGHT_START_HOUR, 22)
        set(value) = prefs.edit().putInt(KEY_NIGHT_START_HOUR, value).apply()

    var nightEndHour: Int
        get() = prefs.getInt(KEY_NIGHT_END_HOUR, 7)
        set(value) = prefs.edit().putInt(KEY_NIGHT_END_HOUR, value).apply()

    // --- Durée de l'alerte avant connexion automatique (paramétrable, 30s par défaut) ---
    var countdownSeconds: Int
        get() = prefs.getInt(KEY_COUNTDOWN_SECONDS, 30)
        set(value) = prefs.edit().putInt(KEY_COUNTDOWN_SECONDS, value).apply()

    // --- Permet de désactiver totalement le blocage (appel toujours accepté après le délai) ---
    var blockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCKING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, value).apply()

    // --- PIN d'accès au panneau admin ---
    var adminPin: String
        get() = prefs.getString(KEY_ADMIN_PIN, "0000") ?: "0000"
        set(value) = prefs.edit().putString(KEY_ADMIN_PIN, value).apply()

    /**
     * Mot de passe d'accès au PWA, distinct du code admin ci-dessus.
     *
     * ═══ DEUX SECRETS, DEUX PUBLICS ═══
     *
     * Le code admin protège les réglages techniques : une personne le connaît.
     * Celui-ci protège l'accès à l'application elle-même : toute la famille le
     * connaît. Les confondre obligerait à donner les réglages de la tablette à
     * quiconque veut appeler Jean.
     *
     * ═══ VIDE PAR DÉFAUT, ET C'EST VOULU ═══
     *
     * Aucun mot de passe tant que l'administrateur n'en pose pas un : rien ne
     * change pour les proches qui appellent aujourd'hui. Poser une valeur par
     * défaut aurait bloqué tout le monde à la première mise à jour, sans que
     * personne sache quoi taper.
     *
     * ═══ CE QU'IL PROTÈGE, ET CE QU'IL NE PROTÈGE PAS ═══
     *
     * Le PWA est un site statique : sa configuration Firebase est lisible dans
     * le code de la page, et les règles Firestore de ce projet laissent lire et
     * écrire quiconque connaît l'adresse. On peut donc parler à la base sans
     * jamais passer par l'application.
     *
     * Ce mot de passe arrête un téléphone prêté, un enfant, un visiteur, une
     * adresse retrouvée dans un historique. Il n'arrête pas quelqu'un de
     * déterminé, et il ne faut pas lui faire dire autre chose.
     */
    var accessPassword: String
        get() = prefs.getString(KEY_ACCESS_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACCESS_PASSWORD, value).apply()

    // --- Clé API AssemblyAI : utilisée par le labo de comparaison de
    // transcription (voir TranscriptionLabActivity) ET par la transcription
    // temps réel des sous-titres d'appel (voir WebRtcCallEngine.
    // attachTranscriptionSink, AssemblyAiRealtimeTranscriber). Si l'admin n'a
    // rien saisi sur la tablette, on retombe sur celle injectée par la CI
    // depuis le secret GitHub ASSEMBLYAI_API_KEY (voir build.gradle). ---
    var assemblyAiApiKey: String
        get() = prefs.getString(KEY_ASSEMBLYAI_API_KEY, "")
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.ASSEMBLYAI_API_KEY_DEFAULT
        set(value) = prefs.edit().putString(KEY_ASSEMBLYAI_API_KEY, value).apply()

    /**
     * Clé du second service payant. Séparée de celle d'AssemblyAI, et non
     * partagée : ce sont deux comptes chez deux fournisseurs, et confondre
     * les deux champs ferait envoyer une clé au mauvais service — dont le
     * seul symptôme serait un refus d'authentification difficile à relier à
     * sa cause.
     */
    var gladiaApiKey: String
        get() = prefs.getString(KEY_GLADIA_API_KEY, "")
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GLADIA_API_KEY_DEFAULT
        set(value) = prefs.edit().putString(KEY_GLADIA_API_KEY, value).apply()

    /**
     * Plafond mensuel d'écoute d'un service payant, en heures. Zéro veut dire
     * sans limite.
     *
     * Un garde-fou et non un réglage de confort. Aucun portier de voix, aussi
     * bon soit-il, ne distingue une conversation d'une télévision laissée
     * allumée : le pire cas d'un service facturé à la durée est donc une
     * facture qui court des semaines sans que personne ne s'en aperçoive,
     * puisque rien à l'écran de Jean n'en dit rien. Un plafond borne ce pire
     * cas de façon absolue, quelle que soit la qualité de la détection en
     * amont.
     *
     * Dix heures par défaut, ce qui correspond au palier gratuit courant de
     * ces services. À relever en connaissance de cause depuis le panneau
     * d'administration si l'usage réel le justifie — le panneau « Utilisation »
     * dit ce qui a été consommé.
     */
    fun monthlyQuotaHours(engine: TranscriptionEngineChoice): Int =
        prefs.getInt(KEY_QUOTA_PREFIX + engine.remoteValue, DEFAULT_QUOTA_HOURS)

    /**
     * Le portier de voix filtre-t-il l'ouverture des sessions payantes ?
     *
     * Activé par défaut, mais débrayable : un portier trop sévère se
     * manifesterait par une transcription qui « ne marche plus », sans que
     * rien ne le désigne. Pouvoir le couper d'un geste, à distance, est ce qui
     * permet de trancher en trente secondes entre « le portier est trop dur »
     * et « le moteur est en panne » — deux diagnostics qu'on ne peut pas
     * départager autrement.
     */
    var voiceGateEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_GATE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_GATE_ENABLED, value).apply()

    fun setMonthlyQuotaHours(engine: TranscriptionEngineChoice, hours: Int) {
        prefs.edit().putInt(KEY_QUOTA_PREFIX + engine.remoteValue, hours.coerceAtLeast(0)).apply()
    }

    // --- Ordre d'empilement des trois zones de l'écran de Jean (voir
    // HomeZonesController) : de haut en bas. Stocké comme la liste des zones
    // séparées par des virgules plutôt qu'un simple numéro de permutation, pour
    // rester lisible dans les préférences et survivre à l'ajout d'une
    // quatrième zone. Toute valeur incomplète ou abîmée (zone inconnue, zone
    // manquante, doublon) retombe sur l'ordre par défaut plutôt que d'amputer
    // l'écran de Jean d'une zone. ---
    var zoneOrder: List<HomeZone>
        get() {
            val stored = prefs.getString(KEY_ZONE_ORDER, null)
                ?.split(',')
                ?.mapNotNull { name -> HomeZone.entries.firstOrNull { it.name == name } }
                ?: return HomeZone.DEFAULT_ORDER
            return if (stored.toSet() == HomeZone.entries.toSet()) stored else HomeZone.DEFAULT_ORDER
        }
        set(value) = prefs.edit().putString(KEY_ZONE_ORDER, value.joinToString(",") { it.name }).apply()

    // --- Moteur de reconnaissance vocale, réglable séparément par source et
    // modifiable à distance en cours de route (voir DeviceStatusReporter).
    // Une solution par source, l'une et l'autre choisies par l'administrateur
    // depuis le PWA — personne d'autre n'en décide. AUTO met tout sur le moteur
    // embarqué : gratuit, hors-ligne, et sans service en ligne qui puisse
    // tomber au mauvais moment. Mettre AssemblyAI sur les seuls appels
    // distants reste raisonnable, sur la pièce beaucoup moins : elle est
    // écoutée des heures par jour, et la facture suit la durée. ---
    var roomEngine: TranscriptionEngineChoice
        get() = TranscriptionEngineChoice.fromRemoteValue(prefs.getString(KEY_ROOM_ENGINE, null))
            ?: TranscriptionEngineChoice.AUTO
        set(value) = prefs.edit().putString(KEY_ROOM_ENGINE, value.remoteValue).apply()

    var callEngine: TranscriptionEngineChoice
        get() = TranscriptionEngineChoice.fromRemoteValue(prefs.getString(KEY_CALL_ENGINE, null))
            ?: TranscriptionEngineChoice.AUTO
        set(value) = prefs.edit().putString(KEY_CALL_ENGINE, value.remoteValue).apply()

    // --- Taille du modèle embarqué (voir VoskModelSize). Le grand par défaut :
    // le petit s'est révélé conçu pour de la commande vocale plus que pour une
    // conversation captée à deux mètres, ce qui est précisément l'usage ici. ---
    var voskModelSize: VoskModelSize
        get() = VoskModelSize.fromRemoteValue(prefs.getString(KEY_VOSK_MODEL_SIZE, null))
            ?: VoskModelSize.LARGE
        set(value) = prefs.edit().putString(KEY_VOSK_MODEL_SIZE, value.remoteValue).apply()

    // --- Ergonomie des deux zones de texte (voir RollingCaptionZone). Réglages
    // d'appareil et non d'appel : ils décrivent la façon dont Jean lit, qui ne
    // change pas selon qui l'appelle. Ils s'appliquent donc aussi à la pièce,
    // hors de tout appel — c'est même là qu'ils servent le plus, la tablette
    // passant l'essentiel de ses journées sans personne au bout du fil. ---
    var captionVisibleLines: Int
        get() = prefs.getInt(KEY_CAPTION_VISIBLE_LINES, 2)
        set(value) = prefs.edit().putInt(KEY_CAPTION_VISIBLE_LINES, value.coerceIn(1, 4)).apply()

    var captionScrollSpeedDp: Int
        get() = prefs.getInt(KEY_CAPTION_SCROLL_SPEED_DP, 50)
        set(value) = prefs.edit().putInt(KEY_CAPTION_SCROLL_SPEED_DP, value.coerceIn(10, 200)).apply()

    var captionClearDelaySeconds: Int
        get() = prefs.getInt(KEY_CAPTION_CLEAR_DELAY_SECONDS, 30)
        set(value) = prefs.edit().putInt(KEY_CAPTION_CLEAR_DELAY_SECONDS, value.coerceIn(1, 120)).apply()

    // --- Dernière commande à distance exécutée (voir
    // DeviceStatusReporter.applyRemoteCommand). Persistée, et non gardée en
    // mémoire : un redémarrage n'a pas de suite, et une trace en mémoire
    // disparaîtrait précisément avec lui — la tablette relancerait alors la
    // même commande à chaque démarrage, indéfiniment. ---
    var lastExecutedCommandId: String
        get() = prefs.getString(KEY_LAST_COMMAND_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_COMMAND_ID, value).apply()

    // --- Fil d'information sur l'écran d'accueil (voir OrdonnanceurActualites) ---

    /**
     * Combien de temps la dalle reste allumée quand un nouveau titre arrive.
     *
     * Cinq minutes au départ, et réglable : c'est le nombre qu'il faudra
     * corriger si la tablette s'allume trop souvent ou trop brièvement chez
     * Jean, et le corriger ne doit pas demander de reconstruire l'application.
     * Borné à l'usage entre 30 secondes et une heure — un écran allumé plus
     * longtemps ne se distinguerait plus d'un écran jamais éteint.
     */
    var dureeEveilActualiteSecondes: Int
        get() = prefs.getInt(KEY_DUREE_EVEIL_ACTUALITE, 300)
        set(value) = prefs.edit().putInt(KEY_DUREE_EVEIL_ACTUALITE, value).apply()

    /**
     * La transcription de la PIÈCE s'affiche-t-elle encore sur l'accueil ?
     *
     * Mise de côté à la demande de l'administrateur : c'est sa zone que le fil
     * d'information occupe désormais. Le réglage reste, plutôt qu'un
     * retrait pur et simple du code — « jusqu'à nouvel ordre » veut dire qu'un
     * ordre contraire peut venir, et il ne doit pas coûter une reconstruction.
     *
     * Sans effet sur l'écoute de la pièce PENDANT un appel, que le proche
     * déclenche depuis le PWA : c'est un autre chemin, et il n'est pas touché.
     */
    var transcriptionPieceAffichee: Boolean
        get() = prefs.getBoolean(KEY_TRANSCRIPTION_PIECE_AFFICHEE, false)
        set(value) = prefs.edit().putBoolean(KEY_TRANSCRIPTION_PIECE_AFFICHEE, value).apply()

    /**
     * La police employée sur tous les écrans que Jean voit.
     *
     * Réglable à distance, et c'est le point : les consignes d'accessibilité
     * retiennent trois familles pour des raisons différentes — lever la
     * confusion entre caractères ambigus, réduire l'encombrement visuel,
     * confort de lecture en français. Laquelle soulage le plus dépend de la
     * personne, et cela se constate à l'usage.
     *
     * Une valeur imposée à la compilation aurait donc demandé un APK par essai.
     * Ici, l'administrateur bascule depuis le PWA et regarde l'écran.
     *
     * Stocké en texte et non en rang : un ordinal se décale silencieusement le
     * jour où l'on insère une quatrième police au milieu de la liste, et la
     * tablette se mettrait alors dans une autre police que celle affichée.
     */
    /**
     * Les adresses des fils d'information, une par ligne.
     *
     * Vide = on s'en tient à la liste livrée avec l'APK (voir
     * BuildConfig.FLUX_ACTUALITES), elle-même vide en production. Renseignée,
     * elle la remplace entièrement.
     *
     * Réglable à distance, et il le faut : un fil d'information change
     * d'adresse, disparaît, ou se révèle mal écrit. Attendre une reconstruction
     * d'APK pour en retirer un qui renvoie n'importe quoi laisserait Jean
     * devant n'importe quoi pendant ce temps.
     */
    /**
     * Jean peut-il commander la tablette à la voix ?
     *
     * Actif par défaut : les trois mots reconnus doublent des boutons présents
     * à l'écran, ils n'ouvrent donc aucun pouvoir nouveau (voir
     * CommandesVocales).
     *
     * Débrayable, et c'est nécessaire : une commande qui se déclencherait à
     * tort pendant une visite se manifesterait par « l'écran fait n'importe
     * quoi », sans que personne puisse relier l'effet à sa cause. Pouvoir
     * l'éteindre à distance est ce qui permet de trancher en trente secondes
     * entre « la détection est trop permissive » et « autre chose ne va pas ».
     */
    /**
     * L'interligne des zones de transcription, en multiple de la hauteur de
     * ligne. De 0 à 1,5.
     *
     * 1,5 par défaut, comme le demandent les consignes d'accessibilité. Mais
     * ces zones ont une hauteur FIXE et le nombre de lignes visibles y commande
     * la taille de la police : l'interligne et la taille des caractères se
     * disputent donc la même hauteur, et rien ne dit d'avance laquelle des deux
     * soulage le plus une personne donnée. Cela se constate à l'écran.
     *
     * La plage descend jusqu'à zéro à la demande explicite de l'administrateur,
     * en connaissance de ce que cela produit (voir
     * RollingCaptionZone.setLineSpacingMultiplier).
     */
    var captionInterligne: Float
        get() = prefs.getFloat(KEY_CAPTION_INTERLIGNE, 1.5f).coerceIn(0f, 1.5f)
        set(value) = prefs.edit().putFloat(KEY_CAPTION_INTERLIGNE, value.coerceIn(0f, 1.5f)).apply()

    var commandesVocalesActives: Boolean
        get() = prefs.getBoolean(KEY_COMMANDES_VOCALES, true)
        set(value) = prefs.edit().putBoolean(KEY_COMMANDES_VOCALES, value).apply()

    /**
     * La liste des fils, posée à distance par l'administrateur.
     *
     * ELLE PASSE DEVANT celle livrée avec l'APK (voir
     * RafraichisseurFlux.adresses), y compris en production où la valeur bâtie
     * est vide. Remplir ce champ allume donc le fil d'information sur la
     * tablette de Jean, sans reconstruction — c'est voulu, et c'est noté ici
     * parce que le build.gradle a longtemps prétendu le contraire.
     *
     * Vide signifie « rien à dire », pas « éteins » : c'est le repli sur la
     * valeur bâtie, et non une extinction. Éteindre une production qui a été
     * allumée demande donc de vider ce champ ET de savoir que l'APK de
     * production, lui, n'en propose aucun.
     */
    /**
     * L'identifiant du recueil d'œuvres à présenter sur l'écran d'accueil.
     *
     * VIDE = ÉTEINT, et c'est le garde-fou : tant que personne n'a nommé un
     * recueil, l'accueil garde le fil d'information. Une galerie qui
     * apparaîtrait d'elle-même chez Jean serait un changement d'écran que
     * personne ne lui a demandé — la même règle que pour le fil, pour la même
     * raison.
     *
     * Branche d'essai : ce réglage n'a pas encore de commande dans le PWA. Il
     * se pose à la main dans le document de l'appareil, le temps de juger si
     * la galerie mérite d'exister.
     */
    var recueilOeuvres: String
        get() = prefs.getString(KEY_RECUEIL_OEUVRES, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_RECUEIL_OEUVRES, value).apply()

    /**
     * L'explication d'un DÉTAIL s'écrit-elle sur l'écran de Jean ?
     *
     * ═══ VRAI PAR DÉFAUT, ET CE DÉFAUT COMPTE ═══
     *
     * Éteint, le commentaire du conservateur n'apparaît plus chez Jean : le
     * détail cesse d'être recouvert par un pavé de texte, et l'explication
     * n'existe plus que pour le proche qui ouvre l'exposition pendant un
     * appel (voir la réplique de l'écran, côté PWA).
     *
     * C'est un choix qui RETIRE quelque chose à Jean quand personne n'est au
     * bout du fil. Il doit donc être posé sciemment, jamais hérité d'un
     * réglage absent ou illisible — d'où le vrai par défaut, comme partout
     * ailleurs ici où le défaut est ce qui donne, et non ce qui prive.
     *
     * NE CONCERNE PAS la vue d'ensemble, qui garde toujours sa légende :
     * peintre, titre et lieu ne sont pas un commentaire, ils disent ce qu'on
     * regarde. Ni l'écran d'appel, qui n'a jamais écrit la légende d'un
     * recueil — le proche la lit sur son téléphone et la dit de vive voix.
     */
    var explicationOeuvreSurTablette: Boolean
        get() = prefs.getBoolean(KEY_EXPLICATION_OEUVRE, true)
        set(value) = prefs.edit().putBoolean(KEY_EXPLICATION_OEUVRE, value).apply()

    var fluxActualites: String
        get() = prefs.getString(KEY_FLUX_ACTUALITES, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_FLUX_ACTUALITES, value).apply()

    /**
     * Instant du dernier remplacement complet du fil, en millisecondes.
     *
     * Rangé dans les préférences et non en mémoire : c'est ce qui permet à la
     * règle « une fois par jour » de survivre à un redémarrage. Sans ça, une
     * tablette qui redémarre trois fois dans l'après-midi retéléchargerait
     * trois fois — et remplacerait trois fois les titres sous les yeux de
     * Jean, ce que « une fois par jour » interdit précisément.
     */
    /**
     * L'administrateur vient-il de changer la liste des fils ?
     *
     * Ce drapeau lève, pour UN seul rafraîchissement, la règle « ne jamais
     * écraser un flux qui marche par du vide » (voir RafraichisseurFlux).
     *
     * Cette règle protège d'une panne passagère. Appliquée à un ordre
     * explicite, elle se retourne contre son but : une nouvelle liste qui ne
     * donne rien laisserait l'ancien fil à l'écran, et le réglage aurait l'air
     * d'avoir été ignoré. Un ordre doit produire un effet visible, même quand
     * cet effet est un écran vide — c'est la seule façon d'apprendre quelque
     * chose de son essai.
     *
     * Rangé dans les préférences et non en mémoire : le réglage peut arriver
     * juste avant un redémarrage, et l'intention ne doit pas se perdre avec le
     * processus.
     */
    /**
     * La version de l'application qui a écrit le recueil du fil.
     *
     * ═══ POURQUOI CE CHAMP EXISTE ═══
     *
     * Le recueil n'est réécrit qu'une fois par jour. Cette règle porte sur le
     * CONTENU — ne pas remplacer les titres sous les yeux de Jean à tout bout
     * de champ — et elle est juste.
     *
     * Mais elle s'appliquait aussi, par effet de bord, aux changements de
     * FORMAT. Ajouter un champ au recueil — la provenance du titre, par
     * exemple — n'avait alors aucun effet visible avant le lendemain 7 h : on
     * installait une version, on ne voyait rien, et rien ne disait que le
     * document affiché datait d'avant. On cherche alors un défaut d'affichage
     * qui n'existe pas.
     *
     * Un numéro de version qui ne correspond plus force donc une réécriture,
     * une seule fois, au premier démarrage qui suit l'installation.
     */
    var fluxDerniereRevision: String
        get() = prefs.getString(KEY_FLUX_REVISION, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FLUX_REVISION, value).apply()

    var fluxListeChangee: Boolean
        get() = prefs.getBoolean(KEY_FLUX_LISTE_CHANGEE, false)
        set(value) = prefs.edit().putBoolean(KEY_FLUX_LISTE_CHANGEE, value).apply()

    var fluxDernierRafraichissementMs: Long
        get() = prefs.getLong(KEY_FLUX_DERNIER_JOUR, 0L)
        set(value) = prefs.edit().putLong(KEY_FLUX_DERNIER_JOUR, value).apply()

    var policeSenior: String
        get() = prefs.getString(KEY_POLICE_SENIOR, null) ?: PoliceSenior.PAR_DÉFAUT.valeurDistante
        set(value) = prefs.edit().putString(KEY_POLICE_SENIOR, value).apply()

    // --- Réveil de l'écran au moindre son de la pièce (voir RoomPresenceService) ---
    var roomWakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROOM_WAKE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ROOM_WAKE_ENABLED, value).apply()

    // --- Seuil de déclenchement (RMS, échelle 0-32767) : plus petit = plus
    // sensible. Dépend du microphone et de l'acoustique de la pièce, à
    // ajuster sur place plutôt qu'une valeur unique valable partout.
    //
    // Défaut remonté de 1000 à 3000 : à 1000, un bruit de clavier à deux
    // mètres rallumait l'écran, constaté en usage réel. Le pic mesuré est
    // publié face au seuil dans le diagnostic (voir describeRoomListening) —
    // c'est de là que doit venir le réglage fin, pas d'une valeur devinée. ---
    var roomWakeSensitivityThreshold: Int
        get() = prefs.getInt(KEY_ROOM_WAKE_THRESHOLD, 3000)
        set(value) = prefs.edit().putInt(KEY_ROOM_WAKE_THRESHOLD, value).apply()

    // --- Atténuation des paroles de Jean dans la transcription de la pièce
    // (voir RoomSpeakerGate). Volontairement sans aucun lien avec le portier de
    // voix payant : celui-ci ne tourne que sur un moteur facturé à la durée,
    // donc jamais dans la configuration courante, et s'y raccrocher aurait
    // livré une fonction qui ne s'active pas. ---

    /**
     * Lequel des deux moteurs répond à « est-ce Jean qui parle » (voir
     * SpeakerEngineChoice). Réglable à distance comme les moteurs de
     * transcription, et pour la même raison : les comparer sur la même voix
     * dans la même pièce est la seule évaluation qui vaille.
     */
    var speakerEngine: SpeakerEngineChoice
        get() = SpeakerEngineChoice.fromRemoteValue(prefs.getString(KEY_SPEAKER_ENGINE, null))
            ?: SpeakerEngineChoice.EMBEDDED
        set(value) = prefs.edit().putString(KEY_SPEAKER_ENGINE, value.remoteValue).apply()

    /** Clé d'accès Picovoice, obtenue sur leur console. Vide : le moteur refuse de démarrer en le disant. */
    var picovoiceAccessKey: String
        get() = prefs.getString(KEY_PICOVOICE_ACCESS_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.PICOVOICE_ACCESS_KEY_DEFAULT
        set(value) = prefs.edit().putString(KEY_PICOVOICE_ACCESS_KEY, value.trim()).apply()

    /**
     * La signature vocale de Jean, apprise une fois (voir
     * RoomPresenceService.startVoiceEnrollment). Chaîne vide tant qu'elle n'a
     * pas été enregistrée, auquel cas l'atténuation ne peut évidemment rien
     * faire et se tait plutôt que de deviner.
     *
     * **Rangée par moteur**, et c'est indispensable : une moyenne de
     * coefficients acoustiques et un profil neuronal n'ont rien de commun, et
     * donner l'une à l'autre reviendrait à comparer une voix à une empreinte
     * qui ne la décrit pas — sans que rien à l'écran ne le signale. Le rangement
     * séparé a en prime une vertu : basculer d'un moteur à l'autre ne détruit
     * pas l'apprentissage du premier, et revenir en arrière ne demande pas de
     * refaire parler Jean.
     */
    fun jeanVoiceSignature(engine: SpeakerEngineChoice): String =
        prefs.getString(KEY_VOICE_SIGNATURE_PREFIX + engine.remoteValue, "").orEmpty()

    fun setJeanVoiceSignature(engine: SpeakerEngineChoice, signature: String) {
        prefs.edit().putString(KEY_VOICE_SIGNATURE_PREFIX + engine.remoteValue, signature).apply()
    }

    /**
     * Atténue-t-on à l'écran les paroles attribuées à Jean ?
     *
     * Sans effet tant qu'aucune signature n'a été enregistrée. Débrayable à
     * distance : une reconnaissance qui se tromperait ferait passer l'écran
     * pour défaillant, et pouvoir la couper en trente secondes est ce qui
     * permet de trancher entre « la reconnaissance se trompe » et « la
     * transcription est en panne ».
     */
    var dimJeanSpeech: Boolean
        get() = prefs.getBoolean(KEY_DIM_JEAN_SPEECH, true)
        set(value) = prefs.edit().putBoolean(KEY_DIM_JEAN_SPEECH, value).apply()

    /**
     * Ressemblance minimale, en pourcentage, pour attribuer une prise de parole
     * à Jean.
     *
     * Volontairement haut. Le coût d'une erreur n'est pas symétrique :
     * afficher normalement une phrase de Jean ne coûte que quelques mots de
     * place, tandis qu'atténuer celle d'un proche retire à Jean précisément ce
     * qu'il a besoin de lire. Un seuil élevé penche du bon côté.
     *
     * Réglable à distance parce qu'il dépend de la voix de Jean, de celles de
     * ses proches et de l'acoustique de la pièce — trois choses qu'aucune
     * valeur écrite d'avance ne peut deviner. Le diagnostic publie la
     * ressemblance réellement mesurée, qui est ce sur quoi le réglage doit
     * s'appuyer.
     *
     * **Propre à chaque moteur**, et pas seulement par prudence : les deux
     * rendent un nombre entre 0 et 1, mais l'un sort d'un réseau de neurones et
     * l'autre d'une moyenne pondérée entre hauteur de voix et timbre. Le même
     * nombre n'y veut pas dire la même chose. Un seuil commun aurait appliqué
     * au second moteur une exigence réglée pour le premier, et le seul symptôme
     * aurait été une reconnaissance devenue absurde après un simple changement
     * de moteur.
     */
    fun jeanVoiceThresholdPercent(engine: SpeakerEngineChoice): Int =
        prefs.getInt(KEY_VOICE_THRESHOLD_PREFIX + engine.remoteValue, engine.defaultThresholdPercent)

    fun setJeanVoiceThresholdPercent(engine: SpeakerEngineChoice, percent: Int) {
        prefs.edit()
            .putInt(KEY_VOICE_THRESHOLD_PREFIX + engine.remoteValue, percent.coerceIn(10, 95))
            .apply()
    }

    // --- Bascule automatique vers « Transcription instantanée » de Google dès
    // qu'une voix est entendue dans la pièce (voir RoomHandoffController). ---

    /**
     * Le mode est-il actif ? **Faux par défaut**, et c'est délibéré : désactivé,
     * tout se comporte exactement comme avant, transcription intégrée comprise.
     * Ce mode change ce que Jean a sous les yeux — l'écran d'une autre
     * application — et une bascule de cette ampleur ne s'active pas toute seule.
     */
    var roomHandoffEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROOM_HANDOFF_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ROOM_HANDOFF_ENABLED, value).apply()

    /**
     * Filet de sécurité : au bout de combien de minutes on ramène l'écran de
     * Jean si aucun autre chemin ne l'a fait.
     *
     * **Un filet, et non le chemin normal.** Celui-là est la mise en veille :
     * elle survient quand la pièce se vide, c'est-à-dire exactement au bon
     * moment, et le micro est repris dans la foulée pour que la surveillance du
     * bruit reparte. La durée ne sert que si l'écran ne s'éteint jamais —
     * Transcription instantanée est faite pour être lue en continu et pourrait
     * le maintenir allumé. On ne le saura qu'en mesurant (voir
     * RoomHandoffController.returnsByReason).
     *
     * Ce n'est en revanche jamais un retour au silence, qui serait pourtant le
     * bon critère : pendant la bascule, l'application de Google tient le
     * microphone et Senior Visio est **sourd**. Aucune ruse ne contourne cette
     * exclusivité, sauf à reprendre le micro — c'est-à-dire à casser exactement
     * ce qu'on est venu chercher.
     *
     * Zéro : pas de filet, les autres chemins subsistent.
     */
    var roomHandoffReturnMinutes: Int
        get() = prefs.getInt(KEY_ROOM_HANDOFF_RETURN_MINUTES, DEFAULT_HANDOFF_RETURN_MINUTES)
        set(value) = prefs.edit().putInt(KEY_ROOM_HANDOFF_RETURN_MINUTES, value.coerceIn(0, 120)).apply()

    fun isCurrentlyNightWindow(hourNow: Int): Boolean {
        return if (nightStartHour <= nightEndHour) {
            hourNow in nightStartHour until nightEndHour
        } else {
            // plage à cheval sur minuit (ex: 22h -> 7h)
            hourNow >= nightStartHour || hourNow < nightEndHour
        }
    }

    /** Export pratique pour debug/logs à distance. */
    fun toDebugJson(): String = JSONObject().apply {
        put("visualAlertModeEnabled", visualAlertModeEnabled)
        put("blockWakeAtNight", blockWakeAtNight)
        put("nightStartHour", nightStartHour)
        put("nightEndHour", nightEndHour)
        put("countdownSeconds", countdownSeconds)
        put("blockingEnabled", blockingEnabled)
    }.toString()

    companion object {
        private const val KEY_VISUAL_ALERT_ENABLED = "visual_alert_enabled"
        private const val KEY_BLOCK_WAKE_AT_NIGHT = "block_wake_at_night"
        private const val KEY_NIGHT_START_HOUR = "night_start_hour"
        private const val KEY_NIGHT_END_HOUR = "night_end_hour"
        private const val KEY_COUNTDOWN_SECONDS = "countdown_seconds"
        private const val KEY_BLOCKING_ENABLED = "blocking_enabled"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val KEY_ACCESS_PASSWORD = "access_password"
        private const val KEY_ASSEMBLYAI_API_KEY = "assemblyai_api_key"
        private const val KEY_GLADIA_API_KEY = "gladia_api_key"
        private const val KEY_QUOTA_PREFIX = "monthly_quota_hours_"
        private const val KEY_VOICE_GATE_ENABLED = "voice_gate_enabled"

        /** Palier gratuit courant de ces services. Voir monthlyQuotaHours. */
        const val DEFAULT_QUOTA_HOURS = 10
        private const val KEY_ZONE_ORDER = "zone_order"
        private const val KEY_ROOM_ENGINE = "room_engine"
        private const val KEY_CALL_ENGINE = "call_engine"
        private const val KEY_VOSK_MODEL_SIZE = "vosk_model_size"
        private const val KEY_CAPTION_VISIBLE_LINES = "caption_visible_lines"
        private const val KEY_CAPTION_SCROLL_SPEED_DP = "caption_scroll_speed_dp"
        private const val KEY_CAPTION_CLEAR_DELAY_SECONDS = "caption_clear_delay_seconds"
        private const val KEY_LAST_COMMAND_ID = "last_command_id"
        private const val KEY_ROOM_WAKE_ENABLED = "room_wake_enabled"
        private const val KEY_DUREE_EVEIL_ACTUALITE = "duree_eveil_actualite_s"
        private const val KEY_TRANSCRIPTION_PIECE_AFFICHEE = "transcription_piece_affichee"
        private const val KEY_POLICE_SENIOR = "police_senior"
        private const val KEY_FLUX_ACTUALITES = "flux_actualites"
        private const val KEY_RECUEIL_OEUVRES = "recueil_oeuvres"
        private const val KEY_EXPLICATION_OEUVRE = "explication_oeuvre_sur_tablette"
        private const val KEY_COMMANDES_VOCALES = "commandes_vocales_actives"
        private const val KEY_FLUX_DERNIER_JOUR = "flux_dernier_rafraichissement"
        private const val KEY_FLUX_LISTE_CHANGEE = "flux_liste_changee"
        private const val KEY_CAPTION_INTERLIGNE = "caption_interligne"
        private const val KEY_FLUX_REVISION = "flux_derniere_revision"
        private const val KEY_ROOM_WAKE_THRESHOLD = "room_wake_threshold"
        private const val KEY_DIM_JEAN_SPEECH = "dim_jean_speech"
        private const val KEY_ROOM_HANDOFF_ENABLED = "room_handoff_enabled"
        private const val KEY_ROOM_HANDOFF_RETURN_MINUTES = "room_handoff_return_minutes"

        /**
         * Trente minutes. Volontairement long : ce n'est qu'un filet, et il ne
         * doit pas couper une visite qui s'installe. La mise en veille, elle,
         * ramènera l'écran bien avant dès que la pièce se videra — si tant est
         * qu'elle survienne, ce que la mesure dira.
         */
        const val DEFAULT_HANDOFF_RETURN_MINUTES = 30
        private const val KEY_SPEAKER_ENGINE = "speaker_engine"
        private const val KEY_PICOVOICE_ACCESS_KEY = "picovoice_access_key"

        /**
         * Suffixés du nom du moteur, comme les plafonds mensuels : une
         * signature et un seuil n'ont de sens que pour le moteur qui les a
         * produits ou pour lequel ils ont été réglés.
         */
        private const val KEY_VOICE_SIGNATURE_PREFIX = "jean_voice_signature_"
        private const val KEY_VOICE_THRESHOLD_PREFIX = "jean_voice_threshold_"
    }
}
