/**
 * Refuse un PWA dont les scripts se marchent dessus.
 *
 * ═══ LA PANNE QUE CE CONTRÔLE EXISTE POUR ARRÊTER ═══
 *
 * Les scripts de web-caller/ sont chargés en balises CLASSIQUES. Leurs
 * déclarations de premier niveau — const, let, class — atterrissent donc
 * toutes dans la MÊME portée globale. Deux fichiers qui déclarent le même nom
 * ne se complètent pas : le second à être analysé lève
 *
 *     Identifier 'DISCRET_OPEN' has already been declared
 *
 * et meurt ENTIÈREMENT, avant sa première ligne exécutée. Le PWA s'affiche
 * normalement, et plus un seul bouton ne répond. C'est arrivé : plus d'appel,
 * plus d'administration, sur un nom de constante partagé entre deux fichiers
 * qui devaient justement s'accorder dessus.
 *
 * Deux contrôles écrits avant celui-ci sont passés à côté, et il vaut la peine
 * de dire pourquoi :
 *
 *   - `node --check` analyse chaque fichier SÉPARÉMENT. Chacun est valide ;
 *     c'est leur cohabitation qui ne l'est pas.
 *   - la comparaison des trois copies du repère a bien vérifié qu'elles
 *     s'écrivaient pareil. C'était même le problème.
 *
 * ═══ COMMENT ═══
 *
 * Les scripts locaux d'index.html sont concaténés DANS L'ORDRE DE LA PAGE et
 * compilés d'un bloc. Une redéclaration lexicale de premier niveau est une
 * erreur de compilation en JavaScript, exactement comme dans le navigateur —
 * le verdict est donc le même, sans navigateur ni réseau.
 *
 * Rien n'est EXÉCUTÉ : pas de DOM à simuler, pas de Firebase à stuber, aucune
 * dépendance à installer. Ce contrôle coûte une fraction de seconde et ne peut
 * pas devenir instable.
 *
 * Il ne remplace pas un chargement réel — une erreur au premier appel de
 * fonction lui échappe — mais il attrape la seule qui tue tout le fichier.
 */
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const racine = process.argv[2] || path.join(__dirname, "..", "web-caller");
const page = path.join(racine, "index.html");

const html = fs.readFileSync(page, "utf8");

// Les scripts locaux seulement : ceux du CDN ne partagent pas nos noms, et on
// n'a de toute façon pas le droit d'aller les chercher ici.
const sources = [...html.matchAll(/<script[^>]*\ssrc="([^"]+)"/g)]
  .map((m) => m[1])
  .filter((s) => !/^https?:/i.test(s))
  .map((s) => s.replace(/\?.*$/, ""));

if (sources.length === 0) {
  console.error("REFUS : aucun script local trouvé dans index.html — le motif de recherche a dû changer.");
  process.exit(1);
}

const morceaux = [];
const absents = [];
for (const src of sources) {
  const fichier = path.join(racine, src);
  if (!fs.existsSync(fichier)) {
    // environment.js et version.js sont fabriqués au déploiement : leur
    // absence ici est normale, et les réclamer ferait échouer le contrôle
    // pour une raison qui n'est pas la sienne.
    absents.push(src);
    continue;
  }
  // Un marqueur de fichier : sans lui, le numéro de ligne d'une erreur
  // renverrait à la concaténation, que personne ne peut ouvrir.
  morceaux.push(`/* ── ${src} ── */\n${fs.readFileSync(fichier, "utf8")}`);
}

const tout = morceaux.join("\n;\n");

try {
  new vm.Script(tout, { filename: "pwa-concaténé.js" });
} catch (e) {
  console.error("╔══════════════════════════════════════════════════════════");
  console.error("║ REFUS : les scripts du PWA ne peuvent pas cohabiter.");
  console.error("║");
  console.error(`║ ${e.message}`);
  console.error("║");
  console.error("║ Ces fichiers partagent une seule portée globale. Un nom");
  console.error("║ déclaré deux fois au premier niveau tue le second fichier");
  console.error("║ en entier — page affichée, aucun bouton ne répond.");
  console.error("║");
  console.error("║ Enfermez le fichier fautif dans une enveloppe");
  console.error("║ (function () { … })() et n'exposez que ce qui est");
  console.error("║ nécessaire sur window, comme le fait exposition.js.");
  console.error("╚══════════════════════════════════════════════════════════");
  process.exit(1);
}

console.log(`${morceaux.length} script(s) local(aux) compilés ensemble : aucune collision de nom.`);
if (absents.length) {
  console.log(`(fabriqués au déploiement, non contrôlés : ${absents.join(", ")})`);
}
