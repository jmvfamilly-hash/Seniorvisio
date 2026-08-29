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
 * champ dans la console Firebase (Firestore Database → devices/jean_tablet),
 * remis à true pour reprendre les mises à jour automatiques.
 */
const admin = require("firebase-admin");

admin.initializeApp({
  credential: admin.credential.cert(require(process.env.GOOGLE_APPLICATION_CREDENTIALS)),
});

const deviceDoc = admin.firestore().doc("devices/jean_tablet");

deviceDoc
  .get()
  .then((snapshot) => {
    if (snapshot.get("autoUpdateEnabled") === false) {
      console.log(
        "Mise à jour à distance automatique désactivée (devices/jean_tablet.autoUpdateEnabled = false) : " +
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
      console.log(`Mise à jour à distance demandée : ${process.env.BUILD_REV} (${process.env.APK_URL})`);
    }
    process.exit(0);
  })
  .catch((err) => {
    console.error("Échec de la demande de mise à jour à distance :", err);
    process.exit(1);
  });
