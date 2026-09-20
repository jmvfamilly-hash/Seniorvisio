// ═══ TOUT CE FICHIER VIT DANS SA PROPRE PORTÉE ═══
//
// Les scripts de ce PWA sont chargés en balises CLASSIQUES : leurs
// déclarations de premier niveau atterrissent toutes dans la même portée
// globale. Deux fichiers qui déclarent « const DISCRET_OPEN » ne se
// complètent donc pas — le second à être analysé lève « Identifier already
// been declared », et TOUT le fichier meurt à l'analyse, avant la première
// ligne exécutée.
//
// C'est arrivé : app.js et ce fichier ont déclaré le même repère, et le
// PWA s'est retrouvé sans bouton d'appel ni bouton d'administration. La
// même panne que l'export ES d'il y a trois jours, par un autre chemin, et
// le fichier la documentait déjà en bas — sans se protéger.
//
// L'enveloppe supprime la classe entière de problème : plus rien d'ici ne
// touche la portée globale, sauf window.Exposition, qui est le contrat.
(function () {
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

/**
 * Part de la largeur de la toile qu'occupe un détail, QUAND LE JSON NE LE DIT
 * PAS — c'est-à-dire pour l'ancienne forme, qui donnait un point et non un
 * cadre. Conservée pour qu'une exposition écrite avant ce changement continue
 * de se découper (voir fenetreDetail).
 */
const PART_DU_DETAIL = 1 / 3;

/** Qualité JPEG des vues produites. */
const QUALITE = 0.85;

/** Part de la largeur du cadre qu'occupe la colonne de la miniature. */
const PART_MINIATURE = 0.25;

/**
 * Le fond de la vue de détail, autour de la miniature.
 *
 * Une couleur FIXE, alors que ce projet refuse ailleurs de figer une teinte de
 * bande — et la différence tient à ce qu'on regarde. Une bande de mise en page
 * doit suivre le thème ; ici, l'image occupe la dalle entière et devient la
 * scène elle-même. Un gris de cimaise très sombre ne jure avec aucun tableau
 * et ne prétend pas être de l'interface.
 */
const FOND_DETAIL = "#141414";

/**
 * Les repères d'une mention secondaire dans une légende.
 *
 * Identiques à DISCRET_OPEN/DISCRET_CLOSE dans RollingCaptionZone.kt et dans
 * app.js : ces trois copies sont la seule chose qui relie les trois côtés, et
 * elles doivent rester écrites pareil. Sans espace à l'intérieur, comme les
 * autres repères de ce protocole — un retour à la ligne au milieu d'une balise
 * en laisserait un fragment à l'écran.
 */
const DISCRET_OPEN = "<discret>";
const DISCRET_CLOSE = "</discret>";

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

/** Ramène une valeur dans [0, 1]. */
function borné01(v) {
  return Math.min(Math.max(v, 0), 1);
}

/**
 * La fenêtre d'un détail quand le JSON donne un CADRE et non un point.
 *
 * ═══ LE CADRE EST ÉTENDU, JAMAIS ROGNÉ ═══
 *
 * Le rectangle fourni n'a aucune raison d'avoir les proportions du panneau où
 * il sera affiché. Deux façons de concilier les deux, et une seule est
 * acceptable : rogner ferait sortir du champ une partie de ce qui a été
 * DÉSIGNÉ comme la cible — c'est-à-dire trahir la consigne du conservateur
 * sans que rien ne le signale. On élargit donc autour, ce qui montre un peu
 * plus que demandé, jamais un peu moins.
 *
 * ET « y_max » EST ACCEPTÉ AU MÊME TITRE QUE « ymax ». Le JSON fourni écrit
 * y_max dans ses dix points. Refuser cette orthographe ferait tomber
 * l'exposition entière sur une faute de frappe, et l'imposer obligerait à la
 * corriger à la main à chaque nouvelle exposition. On lit les deux, et on n'en
 * reparle plus.
 *
 * @returns {sx, sy, sl, sh} en pixels de la toile, ou null si le cadre est
 *   inexploitable — l'appelant retombe alors sur le point, s'il y en a un.
 */
function fenetreDepuisCadre(imgL, imgH, cadre, panneauL, panneauH) {
  if (!cadre || typeof cadre !== "object") return null;
  const lire = (...noms) => {
    for (const n of noms) {
      if (typeof cadre[n] === "number" && Number.isFinite(cadre[n])) return cadre[n];
    }
    return null;
  };
  const xmin = lire("xmin", "x_min");
  const ymin = lire("ymin", "y_min");
  const xmax = lire("xmax", "x_max");
  const ymax = lire("ymax", "y_max");
  if (xmin === null || ymin === null || xmax === null || ymax === null) return null;

  // Ordonnés avant d'être bornés : un cadre écrit à l'envers décrit la même
  // région, et la refuser pour cela seul n'aiderait personne.
  const x0 = borné01(Math.min(xmin, xmax));
  const x1 = borné01(Math.max(xmin, xmax));
  const y0 = borné01(Math.min(ymin, ymax));
  const y1 = borné01(Math.max(ymin, ymax));
  let sl = (x1 - x0) * imgL;
  let sh = (y1 - y0) * imgH;
  if (sl < 1 || sh < 1) return null;
  let sx = x0 * imgL;
  let sy = y0 * imgH;

  // Étendu depuis le CENTRE, pour que la cible reste au milieu de la vue.
  const rapport = panneauL / panneauH;
  const centreX = sx + sl / 2;
  const centreY = sy + sh / 2;
  if (sl / sh < rapport) sl = sh * rapport;
  else sh = sl / rapport;

  // La toile peut être plus petite que la fenêtre voulue. On réduit alors en
  // gardant le rapport : déformer le tableau serait pire que montrer moins.
  if (sl > imgL) {
    sh *= imgL / sl;
    sl = imgL;
  }
  if (sh > imgH) {
    sl *= imgH / sh;
    sh = imgH;
  }

  // RECADRÉE, ET NON DÉPLACÉE, près d'un bord — même règle que pour un point :
  // une cible dans un coin verrait sinon sa fenêtre glisser vers le centre, et
  // sortirait du champ.
  sx = Math.min(Math.max(centreX - sl / 2, 0), Math.max(imgL - sl, 0));
  sy = Math.min(Math.max(centreY - sh / 2, 0), Math.max(imgH - sh, 0));
  return { sx, sy, sl, sh };
}

/**
 * Retire les marques de citation laissées par l'outil qui a rédigé le JSON.
 *
 *    « …à marée basse[span_1](start_span)[span_1](end_span). »
 *
 * Sans ce nettoyage, JEAN LES LIRAIT dans sa légende. C'est le genre de détail
 * qui ne se voit pas en relisant un fichier et saute aux yeux sur l'écran d'un
 * homme de quatre-vingts ans.
 *
 * Les espaces qui restent en double après le retrait sont resserrés, sinon la
 * ponctuation se retrouverait décollée du mot qui la précède.
 */
function nettoyerTexte(texte) {
  return String(texte || "")
    .replace(/\[span_\d+\]\((?:start|end)_span\)/g, "")
    .replace(/[ \t]{2,}/g, " ")
    .trim();
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

/**
 * Où se posent le détail, la miniature et son rectangle, dans un cadre donné.
 *
 * ═══ SÉPARÉ DU DESSIN, POUR POUVOIR ÊTRE ÉPROUVÉ ═══
 *
 * Le dessin demande un canevas, donc un navigateur. Cette fonction-ci ne rend
 * que des nombres, et c'est là que vivent les erreurs qui ne se voient pas à
 * l'œil : un rectangle décalé de quelques pour cent ressemble à un rectangle
 * juste, et désignerait pourtant la mauvaise partie du tableau.
 */
function dispositionDetail(imgL, imgH, fenetre, cadreL, cadreH) {
  const largeurMiniature = Math.max(Math.round(cadreL * PART_MINIATURE), 1);
  const panneauL = Math.max(cadreL - largeurMiniature, 1);
  const marge = Math.max(Math.round(Math.min(largeurMiniature, cadreH) * 0.08), 4);
  const boiteL = Math.max(largeurMiniature - 2 * marge, 1);
  const boiteH = Math.max(cadreH - 2 * marge, 1);
  // Contenue, jamais agrandie : une miniature étirée au-delà de sa définition
  // ne montrerait rien de plus et brouillerait le repère qu'elle porte.
  const échelle = Math.min(boiteL / imgL, boiteH / imgH);
  const miniL = Math.max(imgL * échelle, 1);
  const miniH = Math.max(imgH * échelle, 1);
  return {
    // Le détail occupe tout le panneau de gauche, exactement : c'est pour
    // cela que la fenêtre a été étendue à ses proportions.
    detail: { x: 0, y: 0, largeur: panneauL, hauteur: cadreH },
    miniature: {
      x: panneauL + marge + (boiteL - miniL) / 2,
      // CENTRÉE VERTICALEMENT dans sa colonne, comme demandé : posée en haut,
      // elle flotterait sous une bande vide de la hauteur de l'écran.
      y: marge + (boiteH - miniH) / 2,
      largeur: miniL,
      hauteur: miniH,
    },
    // Le rectangle repère montre la fenêtre RÉELLEMENT affichée, et non le
    // cadre demandé : c'est ce que Jean a sous les yeux à gauche, et les deux
    // diffèrent dès que le cadre a été étendu aux proportions du panneau.
    repere: {
      x: panneauL + marge + (boiteL - miniL) / 2 + fenetre.sx * échelle,
      y: marge + (boiteH - miniH) / 2 + fenetre.sy * échelle,
      largeur: fenetre.sl * échelle,
      hauteur: fenetre.sh * échelle,
    },
  };
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
    texte: légendeDEnsemble(oeuvre),
    // ═══ CE QUE LA VUE EST, ET NON CE QU'ON DEVINE ═══
    //
    // La tablette peut être réglée pour ne pas écrire l'explication d'un
    // DÉTAIL (voir explicationOeuvreSurTablette) tout en gardant la légende
    // d'une vue d'ensemble. Sans ce champ, elle devrait trancher en
    // analysant le texte — et se tromperait le jour où un musée s'appelle
    // « 1878 ».
    role: "ensemble",
  });

  // De ZÉRO À SIX selon le JSON, et rien ici ne suppose un nombre : une œuvre
  // sans point d'intérêt donne sa seule vue d'ensemble, ce qui est un usage
  // légitime — on montre le tableau, sans commentaire.
  const points = Array.isArray(oeuvre.points_interet) ? oeuvre.points_interet : [];
  for (let i = 0; i < points.length; i++) {
    const p = points[i] || {};
    const largeurPanneau = Math.max(
      Math.round(cadre.largeur * (1 - PART_MINIATURE)),
      1
    );
    // Le CADRE d'abord, le point ensuite : la forme actuelle du JSON donne un
    // rectangle, l'ancienne un point. Essayer le cadre en premier fait que la
    // nouvelle forme l'emporte quand les deux sont présents, ce qui est le
    // sens de la lecture — le plus précis gagne.
    const f =
      fenetreDepuisCadre(imgL, imgH, p.cadre, largeurPanneau, cadre.hauteur) ||
      (typeof p.x === "number" && typeof p.y === "number"
        ? fenetreDetail(imgL, imgH, p.x, p.y, largeurPanneau, cadre.hauteur)
        : null);
    // Ni cadre ni point : on passe. Une vue découpée au hasard vaudrait moins
    // que pas de vue du tout, et le compte affiché dirait le contraire.
    if (!f) continue;

    const d = dispositionDetail(imgL, imgH, f, cadre.largeur, cadre.hauteur);
    toile.width = cadre.largeur;
    toile.height = cadre.hauteur;
    ctx.fillStyle = FOND_DETAIL;
    ctx.fillRect(0, 0, cadre.largeur, cadre.hauteur);
    ctx.drawImage(
      image,
      f.sx, f.sy, f.sl, f.sh,
      d.detail.x, d.detail.y, d.detail.largeur, d.detail.hauteur
    );
    ctx.drawImage(
      image,
      0, 0, imgL, imgH,
      d.miniature.x, d.miniature.y, d.miniature.largeur, d.miniature.hauteur
    );

    // ═══ DEUX TRAITS, ET C'EST CE QUI REND LE REPÈRE VISIBLE PARTOUT ═══
    //
    // Un rectangle blanc disparaît sur un ciel, un noir sur une ombre. Le
    // sombre est tracé d'abord et plus épais : il déborde du clair de part et
    // d'autre et lui fait un liseré. Le repère tient alors sur n'importe
    // quelle toile, sans qu'on ait à deviner sa couleur moyenne.
    const trait = Math.max(Math.round(cadre.hauteur / 220), 2);
    ctx.lineJoin = "miter";
    ctx.strokeStyle = "rgba(0, 0, 0, 0.75)";
    ctx.lineWidth = trait * 3;
    ctx.strokeRect(d.repere.x, d.repere.y, d.repere.largeur, d.repere.hauteur);
    ctx.strokeStyle = "#FFFFFF";
    ctx.lineWidth = trait;
    ctx.strokeRect(d.repere.x, d.repere.y, d.repere.largeur, d.repere.hauteur);

    vues.push({
      fichier: new File(
        [await versBlob(toile)],
        `${assainir(oeuvre.titre)}-${i + 1}.jpg`,
        { type: "image/jpeg" }
      ),
      texte: [nettoyerTexte(p.cible), nettoyerTexte(p.texte)]
        .filter(Boolean)
        .join(" — "),
      role: "detail",
    });
  }
  return vues;
}

/**
 * Les points qui produiront vraiment une vue.
 *
 * Un point sans cadre ni coordonnées est ignoré au découpage. Sans ce compte,
 * le panneau annoncerait « 15 vues à découper » et en téléverserait 13, sans
 * que rien ne dise lesquelles manquent — un compteur qui ment sur le travail
 * qu'il vient de faire.
 */
function pointsExploitables(oeuvre) {
  const points = Array.isArray(oeuvre && oeuvre.points_interet)
    ? oeuvre.points_interet
    : [];
  return points.filter((p) => {
    if (!p) return false;
    // Les proportions n'importent pas pour savoir SI un cadre est lisible :
    // un carré suffit à trancher, et évite d'avoir à connaître ici la taille
    // de l'écran de Jean.
    if (fenetreDepuisCadre(1000, 1000, p.cadre, 1, 1)) return true;
    return typeof p.x === "number" && typeof p.y === "number";
  }).length;
}

/**
 * Ce qui s'écrit sous la vue d'ensemble : le peintre, puis l'œuvre, puis le
 * lieu.
 *
 * ═══ L'ORDRE EST UNE DEMANDE, PAS UNE MISE EN PAGE ═══
 *
 *     John Singer Sargent
 *     En route pour la pêche · 1878
 *     National Gallery of Art, Washington      ← petit, en retrait
 *
 * Le peintre au-dessus : c'est le nom qu'on retient, et celui qui donne son
 * sens au reste. Le lieu de conservation en dernier et en retrait : il ne se
 * lit pas du fauteuil, il est là pour qui s'approche.
 *
 * Les repères <discret> sont posés ICI et non côté tablette, parce que c'est
 * ici qu'on sait ce qu'est chaque morceau. Les fabriquer là-bas obligerait la
 * tablette à analyser un texte pour en deviner la structure — et à se tromper
 * le jour où un musée s'appellera « 1878 ».
 */
function légendeDEnsemble(oeuvre) {
  const lignes = [];
  const peintre = nettoyerTexte(oeuvre.artiste);
  if (peintre) lignes.push(peintre);
  const œuvre = [nettoyerTexte(oeuvre.titre), oeuvre.annee]
    .filter(Boolean)
    .join(" · ");
  if (œuvre) lignes.push(œuvre);
  const lieu = nettoyerTexte(oeuvre.musee);
  if (lieu) lignes.push(`${DISCRET_OPEN}${lieu}${DISCRET_CLOSE}`);
  return lignes.join("\n");
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
  fenetreDepuisCadre,
  dispositionDetail,
  pointsExploitables,
  nettoyerTexte,
  légendeDEnsemble,
  tailleVueDEnsemble,
  découperOeuvre,
  lireExposition,
};
})();
