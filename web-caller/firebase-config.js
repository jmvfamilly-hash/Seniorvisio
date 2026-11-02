/**
 * Configuration du projet Firebase, à remplir après création du projet
 * (voir README > Configuration Firebase). Ces valeurs ne sont pas secrètes :
 * ce sont des identifiants client publics par conception chez Firebase ; la
 * protection des données se fait via les règles de sécurité Firestore, pas
 * via la confidentialité de ces champs.
 */
const FIREBASE_CONFIG = {
  apiKey: "AIzaSyDdEvxbetU0V6p6QXKPPA6JWS6ZW4WCcEM",
  authDomain: "seniorvisio.firebaseapp.com",
  projectId: "seniorvisio",
  storageBucket: "seniorvisio.firebasestorage.app",
  messagingSenderId: "319467825373",
  appId: "1:319467825373:web:16fb3faba0d9c8dde7d334",
  // Clé publique « Web Push » (voir app.js, activerAlertesButton), distincte
  // des autres champs ci-dessus : générée une fois dans la console Firebase
  // (Paramètres du projet → Cloud Messaging → Web configuration → Web Push
  // certificates → Générer une paire de clés). Pas secrète non plus, mais
  // propre à cette fonctionnalité — sans elle, l'abonnement aux alertes échoue.
  vapidKey: "À_REMPLACER_AVEC_LA_CLÉ_WEB_PUSH",
};
