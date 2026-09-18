/**
 * Découpe une exposition commentée en vues prêtes à afficher chez Jean.
 *
 * ═══ POURQUOI LA DÉCOUPE EST ICI, ET NON SUR LA TABLETTE ═══
 *
 * Une toile de musée fait couramment cent mégapixels : décodée d'un bloc, elle
 * pèse quatre cents mégaoctets. C'est plus que tout ce que ce projet a traqué
 * pendant deux jours de recherche mémoire, pour UNE image.
 *
 * Découpée ici, dans le navigateur de l'administrateur, la tablette ne reçoit
 * jamais que des vues à sa propre taille. Une toile d'un gigapixel ne lui coûte
 * alors pas un octet de plus qu'une carte postale, et tout le chemin existant —
 * téléversement, vérification, rangement — s'applique sans rien changer.
 *
 * Les originaux ne quittent pas la machine : seul le résultat du découpage est
 * envoyé.
 *
 * ═══ LA TAILLE VIENT DE LA TABLETTE ═══
 *
 * Et non d'une constante. L'œuvre n'occupe pas l'écran : elle occupe la rangée
 * comprise entre la bande d'information et les boutons de navigation — la
 * place d'un titre d'actualité. Cette hauteur dépend des poids de la mise en
 * page, des marges et de la police choisie par l'administrateur ; la
 * recalculer ici reviendrait à réécrire la mise en page Android en JavaScript,
 * et à la voir diverger au premier ajustement.
 *
 * La tablette la mesure et la publie avec son signe de vie (voir
 * oeuvres/CadreOeuvre côté Android, champ « cadreOeuvre »).
 */

/** Part de la largeur de la toile qu'occupe un détail. */
const PART_DU_DETAIL = 1 / 3;

/** Qualité JPEG des vues produites. */
const QUALITE = 0.85;

/**
 * La fenêtre à découper autour d'un point, aux proportions du cadre.
 *
 * ═══ AUX PROPORTIONS DU CADRE, ET C'EST LE POINT ═══
 *
 * Une fenêtre carrée posée dans un cadre large laisserait deux bandes vides de
 * part et d'autre — donc un détail plus petit qu'il ne pourrait l'être, sur un
 * écran qu'on regarde de loin. En lui donnant d'emblée les proportions du
 * cadre, elle le remplit exactement.
 *
 * RECADRÉE, ET NON DÉPLACÉE, près d'un bord : un point à x=0,96 — une
 * signature dans le coin — verrait sinon sa fenêtre glisser vers le centre, et
 * la signature sortirait du cadre. La fenêtre est poussée à l'intérieur en
 * gardant sa taille.
 *
 * Arithmétique pure, exportée pour être éprouvée : c'est la partie où une
 * erreur de bord passe inaperçue à l'œil, un détail décalé ressemblant à un
 * détail.
 */
function fenetreDetail(imgL, imgH, x, y, cadreL, cadreH) {
  const rapport = cadreL / cadreH;
  let sl = imgL * PART_DU_DETAIL;
  let sh = sl / rapport;
  // La toile peut être plus basse que la fenêtre voulue — un format panoramique
  // très étiré, ou un cadre presque carré. On réduit alors par la hauteur.
  if (sh > imgH) {
    sh = imgH;
    sl = sh * rapport;
  }
  if (sl > imgL) {
    sl = imgL;
    sh = sl / rapport;
  }
  const cx = Math.min(Math.max(x, 0), 1) * imgL;
  const cy = Math.min(Math.max(y, 0), 1) * imgH;
  const sx = Math.min(Math.max(cx - sl / 2, 0), Math.max(imgL - sl, 0));
  const sy = Math.min(Math.max(cy - sh / 2, 0), Math.max(imgH - sh, 0));
  return { sx, sy, sl, sh };
}

/**
 * La taille d'une vue d'ensemble : la toile entière, contenue dans le cadre.
 *
 * On ne produit PAS une image à la taille du cadre avec des bandes : la vue
 * garde ses propres proportions, et la tablette la centre (fitCenter). Cela
 * évite d'encoder des pixels vides, et surtout de figer une couleur de bande
 * qui jurerait le jour où le thème passe au clair.
 */
function tailleVueDEnsemble(imgL, imgH, cadreL, cadreH) {
  const échelle = Math.min(cadreL / imgL, cadreH / imgH, 1);
  return {
    largeur: Math.max(Math.round(imgL * échelle), 1),
    hauteur: Math.max(Math.round(imgH * échelle), 1),
  };
}

/** Charge un fichier local en image décodée. */
function chargerImage(fichier) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(fichier);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error(`${fichier.name} n'a pas pu être lue`));
    };
    image.src = url;
  });
}

function versBlob(toile) {
  return new Promise((resolve, reject) => {
    toile.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error("découpe impossible"))),
      "image/jpeg",
      QUALITE
    );
  });
}

/**
 * Découpe une œuvre en vues : l'ensemble d'abord, puis un détail par point.
 *
 * L'ENSEMBLE D'ABORD, TOUJOURS : entrer dans une toile par un détail agrandi,
 * sans l'avoir vue entière, ne veut rien dire. Jean voit l'œuvre, puis il
 * entre dedans.
 *
 * @returns [{ fichier, texte }] prêt pour Recueils.créer
 */
async function découperOeuvre(oeuvre, fichier, cadre) {
  const image = await chargerImage(fichier);
  const imgL = image.naturalWidth;
  const imgH = image.naturalHeight;
  if (!imgL || !imgH) throw new Error(`${fichier.name} est illisible`);

  const vues = [];
  const toile = document.createElement("canvas");
  const ctx = toile.getContext("2d");

  const ensemble = tailleVueDEnsemble(imgL, imgH, cadre.largeur, cadre.hauteur);
  toile.width = ensemble.largeur;
  toile.height = ensemble.hauteur;
  ctx.drawImage(image, 0, 0, imgL, imgH, 0, 0, ensemble.largeur, ensemble.hauteur);
  vues.push({
    fichier: new File(
      [await versBlob(toile)],
      `${assainir(oeuvre.titre)}-ensemble.jpg`,
      { type: "image/jpeg" }
    ),
    // La vue d'ensemble annonce l'œuvre : son titre, son année, son musée.
    // C'est ce que dirait un conservateur en arrivant devant le tableau.
    texte: [oeuvre.titre, oeuvre.annee, oeuvre.musee].filter(Boolean).join(" · "),
  });

  const points = Array.isArray(oeuvre.points_interet) ? oeuvre.points_interet : [];
  for (let i = 0; i < points.length; i++) {
    const p = points[i];
    if (typeof p.x !== "number" || typeof p.y !== "number") continue;
    const f = fenetreDetail(imgL, imgH, p.x, p.y, cadre.largeur, cadre.hauteur);
    // ═══ ON N'AGRANDIT JAMAIS AU-DELÀ DE LA MATIÈRE DISPONIBLE ═══
    //
    // Sur une toile peu définie, la fenêtre d'un détail peut être plus petite
    // que le cadre. L'étirer à la taille du cadre ne créerait aucun pixel
    // nouveau : on obtiendrait une image floue, plus lourde à téléverser que la
    // nette, et Jean verrait de la bouillie là où on lui promet la touche du
    // pinceau.
    //
    // La vue sort donc à la taille de sa fenêtre quand celle-ci est plus
    // petite, et la tablette l'agrandira si elle veut — au moins la décision
    // sera prise là où l'on sait ce qu'on affiche.
    const sortieL = Math.max(Math.round(Math.min(cadre.largeur, f.sl)), 1);
    const sortieH = Math.max(Math.round(sortieL * (cadre.hauteur / cadre.largeur)), 1);
    toile.width = sortieL;
    toile.height = sortieH;
    ctx.drawImage(image, f.sx, f.sy, f.sl, f.sh, 0, 0, sortieL, sortieH);
    vues.push({
      fichier: new File(
        [await versBlob(toile)],
        `${assainir(oeuvre.titre)}-${i + 1}.jpg`,
        { type: "image/jpeg" }
      ),
      texte: [p.cible, p.texte].filter(Boolean).join(" — "),
    });
  }
  return vues;
}

/** Un nom de fichier lisible, sans accent ni caractère qui fâche le stockage. */
function assainir(texte) {
  return (texte || "oeuvre")
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^A-Za-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 40)
    .toLowerCase();
}

/**
 * Lit le JSON d'exposition, ou lève avec une phrase lisible.
 *
 * Les messages parlent à quelqu'un qui vient de coller un texte dans un champ,
 * pas à un programmeur : « le JSON ne contient pas de liste "oeuvres" » vaut
 * mieux que la phrase du moteur, qui désigne un caractère par son rang.
 */
function lireExposition(texte) {
  let brut;
  try {
    brut = JSON.parse(texte);
  } catch (e) {
    throw new Error("Ce n'est pas du JSON valide — vérifiez le copier-coller.");
  }
  if (!brut || !Array.isArray(brut.oeuvres) || brut.oeuvres.length === 0) {
    throw new Error('Le JSON ne contient pas de liste « oeuvres ».');
  }
  const oeuvres = brut.oeuvres.filter((o) => o && o.titre);
  if (!oeuvres.length) throw new Error("Aucune œuvre ne porte de titre.");
  return {
    titre: brut.exposition || "Exposition",
    curateur: brut.curateur || "",
    oeuvres,
  };
}

// Exposé en global, comme Recueils : les scripts de ce PWA sont chargés en
// balises classiques, pas en modules. Un export ES ici n'aurait simplement
// jamais été lu, et le panneau serait resté inerte sans le moindre message.
window.Exposition = {
  fenetreDetail,
  tailleVueDEnsemble,
  découperOeuvre,
  lireExposition,
};
