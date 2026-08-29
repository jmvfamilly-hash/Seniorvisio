/**
 * Demande à la tablette de s'auto-mettre à jour, en écrivant dans le document
 * Firestore que DeviceStatusReporter.listenForRemoteUpdate écoute en continu
 * (voir functions/index.js pour le pendant côté réveil d'appel, même
 * mécanisme de "boîte aux lettres" Firestore). Appelé depuis
 * build-debug-apk.yml juste après la publication de la release GitHub — la
 * tablette télécharge et installe silencieusement dès qu'elle voit une
 * version différente de la sienne (côté app : PackageInstaller, seul un
 * Device Owner peut le faire sans confirmation affichée à Jean).
 */
const admin = require("firebase-admin");

admin.initializeApp({
  credential: admin.credential.cert(require(process.env.GOOGLE_APPLICATION_CREDENTIALS)),
});

admin
  .firestore()
  .doc("devices/jean_tablet")
  .set(
    {
      requestedVersion: process.env.BUILD_REV,
      requestedApkUrl: process.env.APK_URL,
    },
    { merge: true }
  )
  .then(() => {
    console.log(`Mise à jour à distance demandée : ${process.env.BUILD_REV} (${process.env.APK_URL})`);
    process.exit(0);
  })
  .catch((err) => {
    console.error("Échec de la demande de mise à jour à distance :", err);
    process.exit(1);
  });
