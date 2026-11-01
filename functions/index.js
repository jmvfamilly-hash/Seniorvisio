const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { defineSecret } = require("firebase-functions/params");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { FieldValue } = require("firebase-admin/firestore");

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
 * Rappel par SMS : Jean presse un bouton sur sa tablette (voir
 * RappelAidant.kt), qui dépose un document ici. Cette fonction lit le
 * téléphone de l'aidant sur le document d'appareil et lui envoie un SMS avec
 * le lien du PWA — en cliquant dessus, il se retrouve dans le flux d'appel
 * HABITUEL (proche appelle Jean). Rien de neuf côté visiophonie ni côté
 * transcription, qui s'active pareil une fois l'appel connecté.
 *
 * ═══ UNIQUEMENT EN VALIDATION, POUR L'INSTANT ═══
 *
 * Volontairement : ce chantier se construit et s'éprouve sur le banc d'essai
 * avant d'exister pour de vrai chez Jean. Pas de déclencheur « rappels »
 * (production) tant que ce n'est pas demandé — voir ENVIRONNEMENTS, qui ne
 * porte qu'une entrée VALIDATION pour cette fonction-ci, contrairement à
 * notifyIncomingCall/notifyIncomingTestCall qui couvrent les deux.
 *
 * ═══ LE TÉLÉPHONE NE VIT QUE CÔTÉ FONCTION ═══
 *
 * La tablette ne connaît que le prénom affiché sur son bouton (voir
 * AdminConfig.aidantNom) ; le numéro, lui, est lu ici, directement dans le
 * document — jamais transporté jusqu'à l'Android, qui n'en a aucun besoin.
 *
 * ═══ UN GARDE-FOU DE FRÉQUENCE, PAS SEULEMENT CÔTÉ TABLETTE ═══
 *
 * Le bouton se désactive déjà une minute côté tablette (confort, évite le
 * double-appui) — mais c'est ICI que doit vivre la vraie limite, celle qui
 * protège le coût réel : chaque SMS est payant, et rien n'empêche un
 * deuxième appareil, un autre onglet, ou un bug de republier le même
 * document. dernierRappelEnvoyéLe, écrit après chaque envoi réussi, sert de
 * mémoire pour cette limite.
 */
const TWILIO_ACCOUNT_SID = defineSecret("TWILIO_ACCOUNT_SID");
const TWILIO_AUTH_TOKEN = defineSecret("TWILIO_AUTH_TOKEN");
const TWILIO_FROM_NUMBER = defineSecret("TWILIO_FROM_NUMBER");

/** Entre deux SMS envoyés pour une même tablette, quoi qu'il arrive côté boutons. */
const DÉLAI_MINIMUM_ENTRE_RAPPELS_MS = 10 * 60 * 1000;

async function envoyerRappel(environnement, baseUrl) {
  const db = getFirestore();
  const deviceRef = db.doc(environnement.deviceDoc);
  const deviceSnap = await deviceRef.get();
  const téléphone = deviceSnap.get("aidantTelephone");
  if (!téléphone) {
    console.warn(
      `[${environnement.nom}] Aucun téléphone d'aidant enregistré dans ` +
        `${environnement.deviceDoc} : rappel ignoré.`
    );
    return;
  }

  const dernierEnvoi = deviceSnap.get("dernierRappelEnvoyéLe");
  const dernierEnvoiMs = dernierEnvoi ? dernierEnvoi.toMillis() : 0;
  if (Date.now() - dernierEnvoiMs < DÉLAI_MINIMUM_ENTRE_RAPPELS_MS) {
    console.log(`[${environnement.nom}] Rappel ignoré : trop proche du précédent.`);
    return;
  }

  const identifiants = Buffer.from(
    `${TWILIO_ACCOUNT_SID.value()}:${TWILIO_AUTH_TOKEN.value()}`
  ).toString("base64");
  const réponse = await fetch(
    `https://api.twilio.com/2010-04-01/Accounts/${TWILIO_ACCOUNT_SID.value()}/Messages.json`,
    {
      method: "POST",
      headers: {
        Authorization: `Basic ${identifiants}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: new URLSearchParams({
        To: téléphone,
        From: TWILIO_FROM_NUMBER.value(),
        // Deux liens : le premier ouvre l'appel tout de suite, sans même le
        // décompte habituel de 30 s (voir ?rappel=1, traité par
        // web-caller/app.js, traiterLienRappel) ; le second laisse l'aidant
        // dire à Jean qu'il rappellera plus tard, sans démarrer d'appel
        // (?differe=1). Ouvrir l'un ou l'autre vaut accusé de lecture.
        Body:
          `Jean aimerait vous parler.\n` +
          `Appeler maintenant : ${baseUrl}/?mode=aidant&rappel=1\n` +
          `Pas tout de suite ? Dites-lui quand : ${baseUrl}/?mode=aidant&differe=1`,
      }),
    }
  );

  if (!réponse.ok) {
    const détail = await réponse.text();
    console.error(`[${environnement.nom}] Envoi du SMS refusé par Twilio :`, détail);
    return;
  }

  await deviceRef.set({ dernierRappelEnvoyéLe: FieldValue.serverTimestamp() }, { merge: true });
  console.log(`[${environnement.nom}] SMS de rappel envoyé.`);
}

exports.envoyerRappelAidantTest = onDocumentCreated(
  {
    document: "rappels_test/{id}",
    secrets: [TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM_NUMBER],
  },
  () => envoyerRappel(VALIDATION, "https://seniorvisio-test.web.app")
);
