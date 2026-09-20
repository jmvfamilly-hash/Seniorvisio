/**
 * Composition des recueils : téléverser des fichiers, décrire le recueil, et
 * suivre ce que la tablette en a fait.
 *
 * ═══ CE QUE CE MODULE NE SAIT PAS ═══
 *
 * Qu'il existe des appels. On compose un recueil hors ligne, tranquillement,
 * avant d'appeler — pas pendant. Le choix du recueil à commenter pendant un
 * appel appartient au moteur d'appel (étape 3), pas à ce fichier.
 *
 * C'est la même séparation que côté tablette, où RecueilStore ignore qu'il
 * existe des appels et un écran (voir docs/architecture-recueils.md).
 *
 * ═══ CE QU'ON TÉLÉVERSE : L'ORIGINAL, PAS UNE COPIE RÉENCODÉE ═══
 *
 * Le diaporama d'appel réduit les photos dans le navigateur, parce qu'elles
 * doivent tenir dans un document Firestore. Ici, non — et c'est décisif :
 *
 *   - le navigateur du proche ne sait pas décoder les mêmes formats
 *     qu'Android. Chrome sur Android refuse le HEIC ; Android le lit depuis
 *     sa version 9. Réencoder ici ferait échouer des photos que la tablette
 *     aurait parfaitement affichées ;
 *   - c'est la tablette qui sait à quelle définition elle veut ranger, parce
 *     que c'est elle qui connaît sa dalle.
 *
 * On transporte donc le fichier tel quel, et la tablette décide. Une seule
 * machine juge, et c'est celle qui affichera.
 */

const RECUEILS_TAILLE_MAX = 25 * 1024 * 1024;

/**
 * Combien de temps le SDK a le droit de réessayer un téléversement bloqué
 * avant d'abandonner.
 *
 * Le défaut de Firebase est de DEUX MINUTES, et c'est ce qui a produit le
 * symptôme « aucun retour, ni bon ni mauvais » : quand le seau Storage n'est
 * pas joignable, le SDK ne rejette pas — il réessaie, sans rien dire, pendant
 * deux minutes. Le proche voit un bouton grisé et conclut que rien ne marche.
 *
 * Ce délai ne concerne QUE les réessais après échec réseau : un téléversement
 * qui progresse, même lentement depuis un téléphone en 3G, n'est jamais
 * interrompu par cette valeur. Un refus de règle (storage/unauthorized), lui,
 * n'est pas réessayé du tout et tombe immédiatement.
 */
const RECUEILS_DELAI_REESSAI_MS = 30 * 1000;

/**
 * Traduit un code d'erreur Firebase Storage en une phrase qui dit quoi faire.
 *
 * « FirebaseError: Firebase Storage: User does not have permission to access
 * 'recueils/...' (storage/unauthorized) » n'apprend rien à quelqu'un qui
 * voulait simplement montrer des photos à son père. Et surtout, cela ne dit
 * pas la seule chose utile : que la cause n'est pas chez lui.
 */
function expliquerErreurStorage(e) {
  const code = (e && e.code) || "";
  switch (code) {
    case "storage/unauthorized":
    case "storage/unauthenticated":
      return (
        "le dépôt de photos n'est pas encore ouvert sur ce projet. " +
        "C'est un réglage à faire une fois par l'administrateur, pas un " +
        "problème de votre côté"
      );
    case "storage/bucket-not-found":
    case "storage/project-not-found":
      return (
        "l'espace de stockage n'existe pas encore sur ce projet. " +
        "L'administrateur doit l'activer une fois dans la console Firebase"
      );
    case "storage/retry-limit-exceeded":
      return (
        "l'envoi n'a pas abouti après plusieurs tentatives — connexion trop " +
        "faible, ou espace de stockage injoignable"
      );
    case "storage/quota-exceeded":
      return "l'espace de stockage est plein";
    case "storage/canceled":
      return "envoi interrompu";
    default:
      return (e && e.message) || "cause inconnue";
  }
}

/** Types que la tablette sait afficher aujourd'hui (voir TypeElement côté Android). */
function typeDeFichier(file) {
  const mime = (file.type || "").toLowerCase();
  if (mime.startsWith("image/")) return "photo";
  if (mime.startsWith("video/")) return "vidéo";
  return "inconnu";
}

/**
 * Identifiant stable d'un élément.
 *
 * Il sert de nom de fichier sur la tablette ET de clé pour les commentaires
 * qui y seront attachés (étape 4). Il ne doit donc jamais être réattribué à
 * autre chose : on ne le dérive pas du nom du fichier, qui peut se répéter
 * d'un recueil à l'autre.
 */
function identifiantÉlément() {
  return `e${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

class Recueils {
  /**
   * @param app l'application Firebase déjà initialisée par le moteur d'appel.
   *   Passée plutôt que réinitialisée : deux initialisations du même projet
   *   lèvent une erreur, et ce module doit pouvoir ne pas exister sans rien
   *   casser.
   */
  constructor(app, deviceDocId) {
    this._deviceDocId = deviceDocId;
    this._disponible = false;
    try {
      this._db = app.firestore();
      this._storage = app.storage();
      // Voir RECUEILS_DELAI_REESSAI_MS : deux minutes de silence, c'est le
      // défaut, et c'est trop long pour qu'on comprenne qu'il se passe quelque
      // chose. setMaxUploadRetryTime n'existe pas dans toutes les versions du
      // SDK — on ne fait pas échouer le module pour un réglage de confort.
      if (typeof this._storage.setMaxUploadRetryTime === "function") {
        this._storage.setMaxUploadRetryTime(RECUEILS_DELAI_REESSAI_MS);
      }
      if (typeof this._storage.setMaxOperationRetryTime === "function") {
        this._storage.setMaxOperationRetryTime(RECUEILS_DELAI_REESSAI_MS);
      }
      this._disponible = true;
    } catch (e) {
      // Le SDK Storage peut ne pas être chargé (page mise en cache avant son
      // ajout). Le reste du PWA doit continuer de fonctionner : appeler Jean
      // ne dépend pas de la composition des recueils.
      console.error("[Recueils] Indisponible :", e);
    }
  }

  get disponible() {
    return this._disponible;
  }

  _collection() {
    return this._db.collection("devices").doc(this._deviceDocId).collection("recueils");
  }

  /**
   * Les recueils existants, avec l'état que la tablette leur a donné.
   *
   * Écoute continue plutôt que lecture unique : la vérification se fait sur
   * la tablette, de façon asynchrone et parfois longue (téléchargement puis
   * décodage). Le proche doit voir son recueil passer de « installation en
   * cours » à « installé » sans avoir à recharger la page.
   */
  écouter(onRecueils, onErreur = () => {}) {
    if (!this._disponible) return () => {};
    return this._collection().onSnapshot(
      (instantané) => {
        const recueils = instantané.docs.map((d) => {
          const données = d.data() || {};
          const vérifs = new Map(
            (données.verification || []).map((v) => [v.id, v])
          );
          return {
            id: d.id,
            titre: données.titre || "Sans titre",
            crééPar: données.crééPar || "",
            etatGlobal: données.etatGlobal || "installation en cours",
            elements: (données.elements || []).map((e) => ({
              ...e,
              etat: (vérifs.get(e.id) || {}).etat || "à_vérifier",
              cause: (vérifs.get(e.id) || {}).cause || null,
            })),
          };
        });
        onRecueils(recueils);
      },
      (e) => {
        // Comme côté tablette : un écouteur Firestore qui reçoit une erreur
        // est DÉFINITIVEMENT terminé. La liste des recueils se figera donc sur
        // son dernier état connu, sans que rien ne le montre — un recueil
        // installé depuis continuerait d'afficher « installation en cours »
        // pour toujours. Cela se dit.
        console.error("[Recueils] Écoute impossible :", e);
        onErreur(e);
      }
    );
  }

  /**
   * Téléverse les fichiers et crée le recueil.
   *
   * @param onProgression appelée à chaque fichier terminé, pour que l'écran
   *   avance au lieu de rester figé — téléverser quinze photos d'appareil
   *   depuis un téléphone prend du temps, et une interface immobile pendant
   *   ce temps-là passe pour une panne.
   * @returns { id, refusés } — les fichiers écartés AVANT téléversement, avec
   *   leur raison. Ce que la tablette refusera ensuite arrivera par l'écoute.
   */
  async créer({ titre, crééPar, fichiers }, onProgression = () => {}) {
    if (!this._disponible) throw new Error("Composition des recueils indisponible");
    const retenus = [];
    const refusés = [];

    for (const entrée of fichiers) {
      // ═══ UN FICHIER, OU UN FICHIER ET SON COMMENTAIRE ═══
      //
      // Les photos de famille n'ont rien à dire d'elles-mêmes : on passe le
      // fichier nu, comme avant. Les vues d'une exposition, elles, portent le
      // commentaire du conservateur — celui que Jean lira dans sa zone de
      // parole. Accepter les deux formes évite un second chemin de
      // téléversement, qui aurait fini par diverger de celui-ci.
      const file = entrée && entrée.file ? entrée.file : entrée;
      const texte = (entrée && entrée.texte) || "";
      const type = typeDeFichier(file);
      if (type === "inconnu") {
        refusés.push({ nom: file.name, raison: `type non pris en charge (${file.type || "inconnu"})` });
        continue;
      }
      if (file.size === 0) {
        refusés.push({ nom: file.name, raison: "fichier vide — photo encore dans le nuage ?" });
        continue;
      }
      if (file.size > RECUEILS_TAILLE_MAX) {
        refusés.push({
          nom: file.name,
          raison: `trop lourd (${Math.round(file.size / 1024 / 1024)} Mo, maximum 25 Mo)`,
        });
        continue;
      }
      retenus.push({ file, type, texte });
    }

    if (!retenus.length) {
      const erreur = new Error("Aucun fichier utilisable dans cette sélection");
      erreur.refusés = refusés;
      throw erreur;
    }

    const recueilId = `r${Date.now().toString(36)}`;
    const elements = [];

    // Le total en octets, connu d'avance : c'est lui qui permet d'afficher une
    // progression honnête. Compter en fichiers terminés donnait une barre qui
    // ne bougeait pas du tout pendant la première photo — et une photo
    // d'appareil peut peser huit mégaoctets.
    const octetsTotal = retenus.reduce((somme, r) => somme + r.file.size, 0);
    let octetsFinis = 0;

    for (let i = 0; i < retenus.length; i++) {
      const { file, type, texte, role } = retenus[i];
      const elementId = identifiantÉlément();
      const chemin = `recueils/${this._deviceDocId}/${recueilId}/${elementId}`;
      const ref = this._storage.ref(chemin);

      try {
        await this._téléverser(ref, file, (octetsDuFichier) => {
          onProgression({
            fichier: file.name,
            index: i + 1,
            total: retenus.length,
            octets: octetsFinis + octetsDuFichier,
            octetsTotal,
          });
        });
      } catch (e) {
        // Traduit ici, au plus près de la cause, et non laissé à l'écran qui
        // n'a aucun moyen de savoir ce qu'est un storage/unauthorized. Le code
        // d'origine est conservé pour la console.
        console.error("[Recueils] Téléversement refusé :", e);
        const erreur = new Error(
          `${file.name} n'a pas pu être envoyée : ${expliquerErreurStorage(e)}.`
        );
        erreur.code = (e && e.code) || "";
        erreur.cause = e;
        throw erreur;
      }

      octetsFinis += file.size;
      elements.push({
        id: elementId,
        type,
        nature: "fichier",
        source: await ref.getDownloadURL(),
        ordre: i,
        // Absent plutôt que vide : la tablette lit ce champ pour savoir s'il y
        // a quelque chose à écrire dans la zone de parole, et une chaîne vide
        // y aurait ouvert une zone pour n'y rien mettre.
        ...(texte ? { texte } : {}),
        // Absent lui aussi quand il n'y a rien à dire : une photo de famille
        // n'est ni une vue d'ensemble ni un détail, et lui coller un rôle
        // vide obligerait la tablette à distinguer « pas de rôle » de « rôle
        // inconnu » pour un champ qui ne la concerne pas.
        ...(role ? { role } : {}),
      });
    }

    // Le document n'est écrit QU'APRÈS que tous les fichiers soient en place.
    // Écrit au fur et à mesure, il aurait fait démarrer l'installation sur la
    // tablette pendant que le téléversement continue — elle aurait échoué sur
    // les éléments pas encore déposés, et les aurait marqués refusés.
    await this._collection().doc(recueilId).set({
      titre: titre || "Sans titre",
      crééPar: crééPar || "un proche",
      crééLe: new Date().toISOString(),
      elements,
    });

    return { id: recueilId, refusés };
  }

  /**
   * Un fichier, en rendant compte pendant qu'il monte et non seulement à la
   * fin.
   *
   * `put()` renvoie une promesse, et c'est ce que faisait la version d'avant :
   * on l'attendait, puis on annonçait « 1/15 fait ». Entre le clic et la fin
   * de la première photo, l'écran ne disait donc RIEN — et si cette première
   * photo n'aboutissait jamais, il ne disait jamais rien du tout.
   *
   * La tâche renvoyée par `put()` émet aussi des événements de progression.
   * On s'y abonne : l'écran bouge dès les premiers kilo-octets, et surtout un
   * envoi bloqué se distingue d'un envoi lent, ce qui était impossible avant.
   */
  _téléverser(ref, file, onOctets) {
    return new Promise((résoudre, rejeter) => {
      const tâche = ref.put(file, { contentType: file.type });
      onOctets(0); // Dire « ça commence » avant même le premier octet confirmé.
      tâche.on(
        "state_changed",
        (état) => onOctets(état.bytesTransferred),
        rejeter,
        () => {
          onOctets(file.size);
          résoudre();
        }
      );
    });
  }

  /**
   * Retire un recueil.
   *
   * Le document d'abord, les fichiers ensuite : c'est la disparition du
   * document qui déclenche l'élagage sur la tablette (voir RecueilStore).
   * Dans l'autre ordre, la tablette aurait pu retélécharger entre-temps des
   * fichiers déjà effacés et marquer tout le recueil en échec.
   */
  async supprimer(recueilId) {
    if (!this._disponible) return;
    await this._collection().doc(recueilId).delete();
    try {
      const dossier = this._storage.ref(`recueils/${this._deviceDocId}/${recueilId}`);
      const contenu = await dossier.listAll();
      await Promise.all(contenu.items.map((item) => item.delete()));
    } catch (e) {
      // Des fichiers orphelins coûtent quelques kilo-octets ; un recueil qui
      // ne disparaît pas de l'écran, lui, est une panne visible. On ne fait
      // donc pas échouer la suppression pour ça, mais on le dit.
      console.warn("[Recueils] Fichiers non effacés :", e);
    }
  }
}
