/**
 * Crée l'espace de stockage Firebase du projet, s'il n'existe pas déjà.
 *
 * ═══ POURQUOI CE SCRIPT EXISTE ═══
 *
 * `firebase deploy --only storage` refuse de poser des règles tant que
 * l'espace n'existe pas, et renvoie vers la console :
 *
 *   Error: Firebase Storage has not been set up on project 'seniorvisio'.
 *   Go to https://console.firebase.google.com/... and click 'Get Started'.
 *
 * Ce geste-là n'est pas un déploiement, c'est une création de ressource — et
 * la console n'est pas la seule façon de la faire : l'API de gestion Firebase
 * Storage expose le même appel. Ce script l'utilise, avec le compte de service
 * dont dispose déjà la chaîne.
 *
 * ═══ CE QU'IL DÉCIDE, ET CE QUI EST DÉFINITIF ═══
 *
 * L'EMPLACEMENT. Il est choisi ici, et il ne se change JAMAIS ensuite : un
 * espace de stockage ne se déplace pas, il faudrait le recréer et tout
 * réinstaller. europe-west1 (Belgique) parce que les photos concernent une
 * famille française et que la donnée doit rester proche d'elle — pour la
 * latence comme pour le droit.
 *
 * Il est passé par variable d'environnement et non écrit en dur, pour qu'un
 * autre projet n'hérite pas de ce choix sans l'avoir fait.
 *
 * ═══ CE QU'IL NE FAIT PAS ═══
 *
 * Il ne pose aucune règle. Créer l'espace et décider qui peut y écrire sont
 * deux décisions distinctes : la seconde reste dans storage.rules, déployée
 * séparément et délibérément. Un espace fraîchement créé refuse tout par
 * défaut, ce qui est le bon état de départ.
 *
 * Et il ne fait rien si l'espace existe déjà : on peut le relancer sans
 * crainte.
 */

const { GoogleAuth } = require("google-auth-library");

const PROJET = process.env.FIREBASE_PROJECT;
const EMPLACEMENT = process.env.STORAGE_LOCATION;

if (!PROJET || !EMPLACEMENT) {
  console.error(
    "FIREBASE_PROJECT et STORAGE_LOCATION sont requis. Aucune valeur par " +
      "défaut : l'emplacement d'un espace de stockage est définitif, il ne " +
      "doit jamais être deviné."
  );
  process.exit(1);
}

async function main() {
  const auth = new GoogleAuth({
    scopes: ["https://www.googleapis.com/auth/cloud-platform"],
  });
  const client = await auth.getClient();

  const base = `https://firebasestorage.googleapis.com/v1beta/projects/${PROJET}`;

  // D'abord regarder. Un espace déjà en place et ce script n'a rien à faire —
  // et surtout rien à redécider.
  try {
    const état = await client.request({ url: `${base}/defaultBucket` });
    console.log("L'espace de stockage existe déjà :");
    console.log(JSON.stringify(état.data, null, 2));
    return;
  } catch (e) {
    const code = e.response && e.response.status;
    if (code !== 404) {
      console.error(`Lecture impossible (HTTP ${code}) :`);
      console.error(JSON.stringify((e.response && e.response.data) || e.message, null, 2));
      process.exit(1);
    }
    console.log("Aucun espace de stockage sur ce projet — création.");
  }

  try {
    const créé = await client.request({
      url: `${base}/defaultBucket`,
      method: "POST",
      data: { location: EMPLACEMENT },
    });
    console.log(`Espace de stockage créé dans ${EMPLACEMENT} :`);
    console.log(JSON.stringify(créé.data, null, 2));
  } catch (e) {
    const code = e.response && e.response.status;
    console.error(`Création refusée (HTTP ${code}) :`);
    // La réponse brute, entière. C'est elle qui dira si le compte de service
    // n'a pas le droit, si le nom du champ a changé, ou si cette création
    // n'est décidément possible que dans la console — et il vaut mieux la
    // lire que la deviner.
    console.error(JSON.stringify((e.response && e.response.data) || e.message, null, 2));
    process.exit(1);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
