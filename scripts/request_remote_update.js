/**
 * Demande à la tablette de s'auto-mettre à jour, en écrivant dans le document
 * Firestore que DeviceStatusReporter.listenForRemoteUpdate écoute en continu
 * (voir functions/index.js pour le pendant côté réveil d'appel, même
 * mécanisme de "boîte aux lettres" Firestore). Appelé depuis
 * build-debug-apk.yml juste après la publication de la release GitHub — la
 * tablette télécharge et installe silencieusement dès qu'elle voit une
 * version différente de la sienne (côté app : PackageInstaller, seul un
 * Device Owner peut le faire sans confirmation affichée à Jean).
 *
 * Interrupteur autoUpdateEnabled (même document Firestore, à false par
 * défaut au premier ajout du champ ⇒ absence traitée comme true pour ne pas
 * casser un déploiement existant) : permet de mettre en pause ce déclenchement
 * automatique pendant une session de mise au point sur une tablette de test
 * (les builds continuent d'être générés et publiés normalement, seule cette
 * demande-ci est sautée) sans toucher au code ni au workflow — juste ce
 * champ dans la console Firebase (Firestore Database → devices/<tablette>),
 * remis à true pour reprendre les mises à jour automatiques.
 */
const admin = require("firebase-admin");

admin.initializeApp({
  credential: admin.credential.cert(require(process.env.GOOGLE_APPLICATION_CREDENTIALS)),
});

/**
 * Quelle tablette met à jour. Passé par le workflow appelant, JAMAIS écrit en
 * dur ici : c'est ce qui empêche la chaîne d'essai de pousser une version sur
 * la tablette de Jean.
 *
 * Et aucune valeur par défaut. Un défaut sur « jean_tablet » ferait qu'une
 * variable d'environnement oubliée ou mal orthographiée livrerait chez le
 * senior — le contraire de ce que ce paramètre existe pour garantir. Mieux
 * vaut un workflow qui échoue bruyamment qu'une livraison silencieuse au
 * mauvais endroit.
 */
const deviceId = process.env.DEVICE_ID;
if (!deviceId) {
  console.error(
    "DEVICE_ID absent : refus de deviner quelle tablette mettre à jour. " +
      "Le workflow appelant doit le fournir explicitement (jean_tablet ou test_tablet)."
  );
  process.exit(1);
}

const deviceDoc = admin.firestore().doc(`devices/${deviceId}`);

deviceDoc
  .get()
  .then((snapshot) => {
    if (snapshot.get("autoUpdateEnabled") === false) {
      console.log(
        `Mise à jour à distance automatique désactivée (devices/${deviceId}.autoUpdateEnabled = false) : ` +
          `build ${process.env.BUILD_REV} publié normalement, mais pas poussé vers la tablette.`
      );
      return null;
    }
    return deviceDoc.set(
      {
        requestedVersion: process.env.BUILD_REV,
        requestedApkUrl: process.env.APK_URL,
      },
      { merge: true }
    );
  })
  .then((result) => {
    if (result !== null) {
      console.log(`Mise à jour à distance demandée sur ${deviceId} : ${process.env.BUILD_REV} (${process.env.APK_URL})`);
    }
    process.exit(0);
  })
  .catch((err) => {
    console.error("Échec de la demande de mise à jour à distance :", err);
    process.exit(1);
  });
