/**
 * Dépôt des clips fond vert destinés aux vignettes animées de l'écran
 * d'accueil (voir scripts/traiter_vignette.sh et VignetteAnimee.kt côté
 * tablette).
 *
 * ═══ CE QUE CE MODULE NE FAIT PAS ═══
 *
 * Il ne détoure rien, et il ne pose rien sur la tablette : il dépose
 * seulement le clip d'origine dans Firebase Storage, et décrit le dépôt dans
 * Firestore. Le détourage (scripts/traiter_vignette.sh) et la mise en
 * service (nouvelle version de l'application, qui embarque les images
 * obtenues) restent un geste à part, fait une fois le clip récupéré — ce
 * module existe seulement pour qu'il n'y ait plus besoin de faire passer le
 * fichier autrement d'une scène à l'autre.
 *
 * ═══ PAS DE SÉPARATION PAR APPAREIL, CONTRAIREMENT AUX RECUEILS ═══
 *
 * Une scène animée fait partie de l'application elle-même : une fois
 * traitée, elle est embarquée dans CHAQUE tablette au prochain envoi, banc
 * d'essai compris (voir MainActivity.scenesVignettesDisponibles, qui
 * découvre les dossiers d'assets sans distinguer d'appareil). Une seule
 * liste partagée, donc, et non une par appareil comme pour Recueils.
 */

const VIGNETTES_TAILLE_MAX = 60 * 1024 * 1024;

function expliquerErreurStorageVignette(e) {
  const code = (e && e.code) || "";
  switch (code) {
    case "storage/unauthorized":
    case "storage/unauthenticated":
      return "le dépôt n'est pas encore ouvert sur ce projet (réglage à faire une fois par l'administrateur)";
    case "storage/quota-exceeded":
      return "l'espace de stockage est plein";
    case "storage/canceled":
      return "envoi interrompu";
    case "storage/retry-limit-exceeded":
      return "l'envoi n'a pas abouti après plusieurs tentatives — connexion trop faible, ou espace injoignable";
    default:
      return (e && e.message) || "cause inconnue";
  }
}

/**
 * Identifiant de dossier à partir du nom saisi : ascii, minuscules, un seul
 * mot — c'est ce nom qui deviendra, une fois le clip traité, le dossier sous
 * assets/animations/ (voir scripts/traiter_vignette.sh), donc pas d'accents
 * ni d'espaces.
 */
function slugifierScene(nom) {
  const brut = (nom || "")
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 40);
  return brut || `scene${Date.now().toString(36)}`;
}

class VignettesBrutes {
  /** @param app l'application Firebase déjà initialisée par le moteur d'appel. */
  constructor(app) {
    this._disponible = false;
    try {
      this._db = app.firestore();
      this._storage = app.storage();
      this._disponible = true;
    } catch (e) {
      // Comme pour Recueils : le SDK Storage peut manquer (page en cache
      // avant son ajout). Le reste du PWA ne doit pas en dépendre.
      console.error("[Vignettes] Indisponible :", e);
    }
  }

  get disponible() {
    return this._disponible;
  }

  _collection() {
    return this._db.collection("vignettes");
  }

  /** Écoute continue : la liste doit refléter un dépôt fait depuis un autre onglet. */
  écouter(onListe, onErreur = () => {}) {
    if (!this._disponible) return () => {};
    return this._collection()
      .orderBy("déposéLe", "desc")
      .onSnapshot(
        (instantané) => onListe(instantané.docs.map((d) => ({ id: d.id, ...d.data() }))),
        (e) => {
          console.error("[Vignettes] Écoute impossible :", e);
          onErreur(e);
        }
      );
  }

  /**
   * Dépose un clip. Écrase un dépôt précédent du même nom de scène (même
   * chemin de stockage) plutôt que d'en accumuler les ratés.
   */
  async déposer({ nom, fichier }, onProgression = () => {}) {
    if (!this._disponible) throw new Error("Dépôt de vignettes indisponible");
    if (!fichier) throw new Error("Aucun clip sélectionné");
    if (!(fichier.type || "").toLowerCase().startsWith("video/")) {
      throw new Error(`${fichier.name} n'est pas une vidéo (${fichier.type || "type inconnu"})`);
    }
    if (fichier.size > VIGNETTES_TAILLE_MAX) {
      throw new Error(
        `trop lourd (${Math.round(fichier.size / 1024 / 1024)} Mo, maximum ${Math.round(VIGNETTES_TAILLE_MAX / 1024 / 1024)} Mo)`
      );
    }

    const sceneId = slugifierScene(nom);
    const extension = (fichier.name.split(".").pop() || "mp4").toLowerCase();
    const chemin = `vignettes_brutes/${sceneId}/clip.${extension}`;
    const ref = this._storage.ref(chemin);

    try {
      await new Promise((résoudre, rejeter) => {
        const tâche = ref.put(fichier, { contentType: fichier.type });
        onProgression(0);
        tâche.on(
          "state_changed",
          (état) => onProgression(état.bytesTransferred),
          rejeter,
          () => {
            onProgression(fichier.size);
            résoudre();
          }
        );
      });
    } catch (e) {
      console.error("[Vignettes] Téléversement refusé :", e);
      throw new Error(`Envoi refusé : ${expliquerErreurStorageVignette(e)}.`);
    }

    await this._collection()
      .doc(sceneId)
      .set({
        nom: nom || sceneId,
        déposéLe: new Date().toISOString(),
        taille: fichier.size,
        source: await ref.getDownloadURL(),
        état: "à traiter",
      });

    return sceneId;
  }

  /** Retire un dépôt : le document d'abord, les fichiers ensuite (voir Recueils.supprimer). */
  async supprimer(sceneId) {
    if (!this._disponible) return;
    await this._collection().doc(sceneId).delete();
    try {
      const dossier = this._storage.ref(`vignettes_brutes/${sceneId}`);
      const contenu = await dossier.listAll();
      await Promise.all(contenu.items.map((item) => item.delete()));
    } catch (e) {
      console.warn("[Vignettes] Fichiers non effacés :", e);
    }
  }
}
