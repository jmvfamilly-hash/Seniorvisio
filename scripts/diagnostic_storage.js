/**
 * Dit à quel espace de stockage les règles sont RÉELLEMENT attachées.
 *
 * ═══ POURQUOI CE SCRIPT ═══
 *
 * `firebase deploy --only storage` affiche « released rules storage.rules to
 * firebase.storage » et s'arrête là. Cette phrase ne nomme AUCUN espace : elle
 * ne permet pas de savoir si les règles sont posées sur celui que le PWA
 * utilise. Un projet peut en avoir plusieurs, et les projets récents ont
 * changé de convention de nom — « .firebasestorage.app » au lieu de
 * « .appspot.com ».
 *
 * Or si les règles sont attachées à un espace et les écritures dirigées vers
 * un autre, le symptôme est exactement celui qu'on observe : l'espace existe,
 * il répond, et il refuse tout. Cette hypothèse se tranche par une lecture,
 * pas par un raisonnement — d'où ce script.
 *
 * Il ne modifie rien. Il lit et il affiche.
 */

const { GoogleAuth } = require("google-auth-library");

const PROJET = process.env.FIREBASE_PROJECT;
const ESPACE_ATTENDU = process.env.STORAGE_BUCKET;

if (!PROJET) {
  console.error("FIREBASE_PROJECT est requis.");
  process.exit(1);
}

async function main() {
  const auth = new GoogleAuth({
    scopes: ["https://www.googleapis.com/auth/cloud-platform"],
  });
  const client = await auth.getClient();

  console.log("═══ Espaces de stockage déclarés sur le projet ═══\n");
  try {
    const buckets = await client.request({
      url: `https://firebasestorage.googleapis.com/v1beta/projects/${PROJET}/buckets`,
    });
    for (const b of buckets.data.buckets || []) {
      console.log(`  ${b.name}`);
    }
    if (!(buckets.data.buckets || []).length) console.log("  (aucun)");
  } catch (e) {
    console.log(`  lecture impossible (HTTP ${e.response && e.response.status})`);
  }

  console.log("\n═══ Règles publiées, et à quoi elles sont attachées ═══\n");
  const releases = await client.request({
    url: `https://firebaserules.googleapis.com/v1/projects/${PROJET}/releases`,
  });

  const liste = releases.data.releases || [];
  for (const r of liste) {
    // Le nom d'une publication porte sa cible : « firebase.storage/<espace> »
    // pour Storage, « cloud.firestore » pour Firestore.
    const nom = r.name.replace(`projects/${PROJET}/releases/`, "");
    console.log(`  ${nom}`);
    console.log(`      jeu de règles : ${r.rulesetName.split("/").pop()}`);
    console.log(`      publié le     : ${r.updateTime}`);
  }
  if (!liste.length) console.log("  (aucune règle publiée)");

  if (ESPACE_ATTENDU) {
    const cible = `firebase.storage/${ESPACE_ATTENDU}`;
    const trouvée = liste.some((r) => r.name.endsWith(cible));
    console.log(
      `\n═══ Verdict ═══\n\n  Le PWA écrit dans : ${ESPACE_ATTENDU}\n` +
        `  Des règles y sont attachées : ${trouvée ? "OUI" : "NON"}\n`
    );
    if (!trouvée) {
      console.log(
        "  C'est la cause du refus. Les règles ont été publiées, mais pas sur\n" +
          "  l'espace que le PWA utilise — il faut nommer cet espace\n" +
          "  explicitement dans firebase.json (clé « bucket »).\n"
      );
    }
  }
}

main().catch((e) => {
  console.error(`Échec (HTTP ${e.response && e.response.status}) :`);
  console.error(JSON.stringify((e.response && e.response.data) || e.message, null, 2));
  process.exit(1);
});
