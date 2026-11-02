const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

/**
 * Les deux environnements, chacun avec sa boîte aux lettres d'appels et son
 * document d'appareil. Doit rester identique aux variantes déclarées dans
 * app/build.gradle (DEVICE_ID et CALLS_COLLECTION) : c'est le seul endroit du
 * projet où les deux côtés doivent s'accorder à la main.
 *
 * ═══ POURQUOI DEUX DÉCLENCHEURS ET NON UN SEUL PARAMÉTRÉ ═══
 *
 * Un déclencheur Firestore est attaché à un chemin figé au déploiement. On ne
 * peut donc pas écouter « la collection d'appels de l'environnement courant »
 * — il n'y a pas d'environnement courant côté serveur, les deux coexistent.
 *
 * Et c'est tant mieux : le jour où l'un des deux déclencheurs tombe, l'autre
 * continue. Une fonction unique qui aiguillerait selon le chemin ferait
 * dépendre les appels de Jean du bon fonctionnement du banc d'essai.
 */
const ENVIRONNEMENTS = [
  { nom: "production", collection: "calls", deviceDoc: "devices/jean_tablet" },
  { nom: "validation", collection: "calls_test", deviceDoc: "devices/test_tablet" },
];

/**
 * Réveille une tablette par notification push dès qu'un appel apparaît dans
 * sa collection, en complément de l'écoute Firestore permanente déjà en place
 * côté Android (CallListenerService). Cette écoute permanente peut être
 * suspendue par Android une fois l'écran éteint depuis un moment (Doze) ;
 * un message FCM en priorité haute est le seul mécanisme qu'Android garantit
 * de faire percer cette mise en veille, sans avoir à garder l'écran allumé
 * en permanence.
 *
 * Payload volontairement minimal (callId + nom de l'appelant, pas la photo) :
 * FCM limite chaque message à 4 Ko, largement dépassé par une photo encodée
 * en base64.
 */
async function réveillerTablette(environnement, event) {
  const call = event.data?.data();
  if (!call || call.status !== "ringing") return;

  const deviceSnap = await getFirestore().doc(environnement.deviceDoc).get();
  const token = deviceSnap.get("fcmToken");
  if (!token) {
    console.warn(
      `[${environnement.nom}] Aucun token FCM enregistré dans ${environnement.deviceDoc} : ` +
        "réveil push impossible pour cet appel."
    );
    return;
  }

  await getMessaging().send({
    token,
    android: { priority: "high" },
    data: {
      type: "incoming_call",
      callId: event.params.callId,
      callerName: String(call.callerName || "un proche"),
      // Le mode « Sous-titres » : le proche est dans la pièce, la tablette ne
      // doit pas sonner. Transporté ici parce que cette voie-ci est la seule
      // qui traverse la veille profonde d'Android — donc justement celle
      // qu'emprunte un appel lancé pendant que Jean a mis l'écran en sommeil.
      //
      // Une CHAÎNE et non un booléen : la charge utile d'un message de
      // données FCM n'accepte que des chaînes, et un booléen y est refusé.
      // La tablette relit de toute façon le document si ce champ manque (voir
      // CallSignalingClient.fetchSousTitresMode), mais un déploiement en
      // retard ferait alors sonner la tablette une fraction de seconde.
      sousTitres: String(!!call.sousTitresMode),
    },
  });
}

const [PRODUCTION, VALIDATION] = ENVIRONNEMENTS;

exports.notifyIncomingCall = onDocumentCreated(
  `${PRODUCTION.collection}/{callId}`,
  (event) => réveillerTablette(PRODUCTION, event)
);

/**
 * Même mécanisme pour la tablette d'essai. Nom de fonction distinct : deux
 * déclencheurs ne peuvent pas partager un nom, et surtout un déploiement qui
 * remplacerait l'un par l'autre couperait les appels de Jean sans que rien ne
 * le signale — le genre de panne qui ne se découvre qu'au moment où quelqu'un
 * essaie de l'appeler.
 */
exports.notifyIncomingTestCall = onDocumentCreated(
  `${VALIDATION.collection}/{callId}`,
  (event) => réveillerTablette(VALIDATION, event)
);

/**
 * Rappel par notification : Jean presse un bouton sur sa tablette (voir
 * RappelAidant.kt), qui dépose un document ici. Cette fonction lit le jeton
 * de notification (FCM) que l'aidant a enregistré depuis son propre
 * téléphone (voir web-caller/app.js, activerAlertesButton) et lui envoie une
 * alerte avec deux actions — en l'ouvrant, elle se retrouve dans le flux
 * d'appel HABITUEL (proche appelle Jean). Rien de neuf côté visiophonie ni
 * côté transcription, qui s'active pareil une fois l'appel connecté.
 *
 * Remplace l'essai précédent par SMS (Twilio) : bloqué par un numéro à
 * acheter et une vérification d'identité (KYC) côté Twilio, pour un service
 * qui n'a jamais eu besoin d'envoyer un texte — seulement de réveiller un
 * téléphone précis. Firebase Cloud Messaging fait exactement ça, déjà
 * utilisé par ce projet pour réveiller la tablette (voir réveillerTablette
 * ci-dessus), gratuitement et sans démarche.
 *
 * ═══ UNIQUEMENT EN VALIDATION, POUR L'INSTANT ═══
 *
 * Volontairement : ce chantier se construit et s'éprouve sur le banc d'essai
 * avant d'exister pour de vrai chez Jean. Pas de déclencheur « rappels »
 * (production) tant que ce n'est pas demandé — voir ENVIRONNEMENTS, qui ne
 * porte qu'une entrée VALIDATION pour cette fonction-ci, contrairement à
 * notifyIncomingCall/notifyIncomingTestCall qui couvrent les deux.
 *
 * Pas de garde-fou de fréquence ici : le bouton se désactive déjà une
 * minute côté tablette (voir MainActivity, DÉLAI_REFROIDISSEMENT_APPEL_AIDANT_MS),
 * et les alertes sont gratuites — rien à protéger d'un double envoi.
 */
async function envoyerRappel(environnement, baseUrl) {
  const db = getFirestore();
  const deviceRef = db.doc(environnement.deviceDoc);
  const deviceSnap = await deviceRef.get();
  const jeton = deviceSnap.get("aidantPushToken");
  if (!jeton) {
    console.warn(
      `[${environnement.nom}] Aucun jeton d'alerte enregistré dans ` +
        `${environnement.deviceDoc} : rappel ignoré.`
    );
    return;
  }

  try {
    // Message de DONNÉES pur, sans bloc "notification" — voir
    // firebase-messaging-sw.js pour pourquoi : c'est lui qui affiche la
    // notification et pose les deux actions (appeler maintenant / plus tard).
    await getMessaging().send({
      token: jeton,
      webpush: { headers: { Urgency: "high" } },
      data: {
        titre: "Jean aimerait vous parler",
        corps: "Appuyez pour le rappeler, ou dites-lui quand.",
        urlRappel: `${baseUrl}/?mode=aidant&rappel=1`,
        urlDiffere: `${baseUrl}/?mode=aidant&differe=1`,
      },
    });
    console.log(`[${environnement.nom}] Alerte de rappel envoyée.`);
  } catch (e) {
    console.error(`[${environnement.nom}] Envoi de l'alerte refusé :`, e.message || e);
  }
}

exports.envoyerRappelAidantTest = onDocumentCreated(
  "rappels_test/{id}",
  () => envoyerRappel(VALIDATION, "https://seniorvisio-test.web.app")
);
