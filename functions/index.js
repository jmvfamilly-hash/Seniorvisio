const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { defineSecret } = require("firebase-functions/params");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
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
 * ═══════════════════════════════════════════════════════════════════════
 * RAPPEL D'URGENCE : TROIS PROCHES, UN INCIDENT PARTAGÉ
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Jean presse un bouton sur sa tablette (voir RappelAidant.kt), qui dépose
 * un document ici — un INCIDENT, pas un message jetable : jusqu'à trois
 * proches le reçoivent en même temps, et les trois voient en direct qui a
 * répondu quoi (voir web-caller/app.js, le roster affiché sur ?mode=aidant).
 * C'est potentiellement un appel de détresse ; la conception suppose que
 * n'importe quel maillon peut échouer (jeton mort, téléphone éteint,
 * notification jamais vue) et prévoit une relance puis un secours par SMS,
 * plutôt que d'espérer qu'un seul envoi suffise.
 *
 * ═══ LE DOCUMENT D'INCIDENT (rappels_test/{id}) ═══
 *
 *   demandéLe     : posé par Android (RappelAidant.kt), inchangé.
 *   statut        : "en_attente" → "pris_en_charge" (quelqu'un répond) ou
 *                   "expiré" (personne, après cinq minutes).
 *   résoluPar     : la clé (c1/c2/c3) de qui a répondu, une fois pris en charge.
 *   contacts.cN   : { nom, statut, rappelPrévuLe?, luLe? } — statut parmi
 *                   "non_lu" | "lu" | "rappel_prévu" | "indisponible" |
 *                   "en_appel" | "jeton_manquant" | "jeton_invalide".
 *   journal       : trace horodatée en texte clair, pour qu'un regard
 *                   extérieur puisse juger après coup si le système a tenu
 *                   ses promesses sur UN incident donné.
 *
 * ═══ LES CONTACTS (devices/{id}.rappelContacts) ═══
 *
 * Jusqu'à trois entrées { nom, pushToken, téléphone }, réglées depuis le
 * panneau admin du PWA (nom, téléphone) et complétées par chaque proche
 * lui-même depuis SON téléphone (pushToken, voir activerAlertesButton) — un
 * slot sans jeton encore enregistré est seulement MOINS couvert, jamais
 * bloquant pour les deux autres.
 *
 * ═══ UNIQUEMENT EN VALIDATION, POUR L'INSTANT ═══
 *
 * Voir ENVIRONNEMENTS, qui ne porte qu'une entrée VALIDATION pour ce
 * chantier — pas de déclencheur « rappels » de production tant que ce
 * n'est pas demandé.
 */

const TWILIO_ACCOUNT_SID = defineSecret("TWILIO_ACCOUNT_SID");
const TWILIO_AUTH_TOKEN = defineSecret("TWILIO_AUTH_TOKEN");
const TWILIO_FROM_NUMBER = defineSecret("TWILIO_FROM_NUMBER");

const CLÉS_CONTACTS = ["c1", "c2", "c3"];
const DÉLAI_RELANCE_MS = 90 * 1000;
const DÉLAI_EXPIRATION_MS = 5 * 60 * 1000;

// « clé » distingue deux entrées pour deux contacts différents qui, sinon,
// seraient de VRAIS doublons structurels aux yeux d'arrayUnion : un même
// événement texte (ex. deux "push envoyé à Un proche", le nom par défaut
// quand l'administrateur n'a pas encore nommé les contacts) posé à la même
// milliseconde depuis une boucle synchrone — arrayUnion ne garde alors QUE
// LE PREMIER des deux (il déduplique y compris à l'intérieur d'un seul
// appel), et le journal perd silencieusement la moitié de ce qui s'est
// réellement passé (piège réel, repéré en re-testant à trois contacts : un
// seul "push envoyé" apparaissait alors que les deux jetons avaient reçu
// leur message).
function journalEntrée(événement, clé) {
  const entrée = { horodatage: Date.now(), événement };
  if (clé) entrée.contact = clé;
  return entrée;
}

/**
 * Envoie l'alerte à un contact donné. Message de DONNÉES pur, sans bloc
 * "notification" — voir firebase-messaging-sw.js pour pourquoi : c'est lui
 * qui affiche la notification et pose les deux actions (appeler maintenant
 * / plus tard), ce qu'un bloc "notification" géré par défaut par FCM ne
 * permet pas de distinguer au clic.
 *
 * Retourne "ok", "jeton_invalide" (jeton mort, confirmé par FCM lui-même —
 * pas une supposition) ou "échec" (tout le reste : réseau, quota...).
 */
async function envoyerPushContact({ jeton, incidentId, contactKey, baseUrl }) {
  if (!jeton) return "jeton_manquant";
  try {
    await getMessaging().send({
      token: jeton,
      webpush: { headers: { Urgency: "high" } },
      data: {
        titre: "Jean aimerait vous parler",
        corps: "Appuyez pour le rappeler, ou dites-lui quand.",
        incidentId,
        contactKey,
        urlRappel: `${baseUrl}/?mode=aidant&incident=${incidentId}&contact=${contactKey}&rappel=1`,
        urlDiffere: `${baseUrl}/?mode=aidant&incident=${incidentId}&contact=${contactKey}&differe=1`,
      },
    });
    return "ok";
  } catch (e) {
    if (e.code === "messaging/registration-token-not-registered") return "jeton_invalide";
    console.error(`Envoi push refusé pour ${contactKey} :`, e.message || e);
    return "échec";
  }
}

/**
 * À la création de l'incident : copie les contacts configurés côté
 * appareil (nom, jeton) dans l'incident lui-même — une PHOTO au moment de
 * la demande, pas une référence vivante, pour qu'un changement ultérieur de
 * contact n'altère pas l'historique d'un incident déjà envoyé — puis
 * envoie le push à chacun.
 */
async function créerEtNotifier(environnement, baseUrl, incidentRef) {
  const db = getFirestore();
  const deviceSnap = await db.doc(environnement.deviceDoc).get();
  const rappelContacts = deviceSnap.get("rappelContacts") || {};

  // Un jeton SEUL suffit à considérer le contact configuré — le nom ne
  // sert qu'à l'affichage (bouton de Jean, roster des proches), le jeton
  // à l'envoi lui-même. Exiger les deux aurait ignoré en silence un
  // proche qui a activé ses alertes avant que l'administrateur ait pris
  // le temps de lui donner un nom dans le panneau admin — repéré en
  // testant : les jetons étaient bien là, mais personne n'était notifié.
  const contacts = {};
  for (const clé of CLÉS_CONTACTS) {
    const brut = rappelContacts[clé];
    if (!brut || (!brut.nom && !brut.pushToken)) continue;
    contacts[clé] = {
      nom: brut.nom || "Un proche",
      statut: "non_lu",
      téléphone: brut.téléphone || null,
    };
  }

  if (Object.keys(contacts).length === 0) {
    console.warn(
      `[${environnement.nom}] Aucun contact de rappel configuré dans ${environnement.deviceDoc}.`
    );
    await incidentRef.set(
      { statut: "expiré", contacts: {}, journal: [journalEntrée("aucun contact configuré")] },
      { merge: true }
    );
    return;
  }

  await incidentRef.set(
    { statut: "en_attente", contacts, journal: [journalEntrée("incident créé")] },
    { merge: true }
  );

  const résultats = await Promise.all(
    Object.entries(contacts).map(async ([clé, c]) => {
      const brut = rappelContacts[clé];
      const résultat = await envoyerPushContact({
        jeton: brut.pushToken,
        incidentId: incidentRef.id,
        contactKey: clé,
        baseUrl,
      });
      return { clé, nom: c.nom, résultat };
    })
  );

  // Objet imbriqué, PAS des clés pointées : .set(…, {merge:true}) ne
  // découpe pas une clé "contacts.c1.statut" en chemin comme le ferait
  // .update() — elle créerait un champ littéralement nommé ainsi, points
  // compris, au lieu de nicher statut sous contacts.c1 (piège réel, repéré
  // en re-testant : voir webrtc-engine.js, setDeviceSetting, même défaut).
  const contactsMisÀJour = {};
  const journal = [];
  for (const { clé, nom, résultat } of résultats) {
    if (résultat !== "ok") {
      contactsMisÀJour[clé] = { statut: résultat };
      journal.push(journalEntrée(`push vers ${nom} : ${résultat}`, clé));
    } else {
      journal.push(journalEntrée(`push envoyé à ${nom}`, clé));
    }
  }
  if (journal.length > 0) {
    // « contacts » n'est inclus QUE s'il y a vraiment un statut à corriger
    // (échec ou jeton mort) : l'écrire à {} quand tout a réussi EFFACERAIT
    // la carte contacts tout juste posée par le set() précédent — un
    // champ présent dans l'objet écrit n'est pas un no-op pour Firestore,
    // même vide (piège réel, repéré en re-testant : plus aucun contact
    // n'apparaissait dans l'incident après un envoi pourtant réussi).
    const correctif = { journal: FieldValue.arrayUnion(...journal) };
    if (Object.keys(contactsMisÀJour).length > 0) correctif.contacts = contactsMisÀJour;
    await incidentRef.set(correctif, { merge: true });
  }
}

exports.envoyerRappelAidantTest = onDocumentCreated(
  "rappels_test/{id}",
  (event) => créerEtNotifier(VALIDATION, "https://seniorvisio-test.web.app", event.data.ref)
);

/**
 * ═══ RELANCE ET SECOURS, MÊME SANS QUE PERSONNE N'OUVRE L'APPLICATION ═══
 *
 * Tourne toutes les minutes, indépendamment de toute action côté client :
 * un incident de détresse ne doit pas dépendre du fait que quelqu'un ait
 * un onglet ouvert pour que la relance parte.
 *
 *   à  90 s sans réponse : renvoie le push à qui ne l'a toujours pas ouvert.
 *   à 300 s sans réponse : marque l'incident expiré, ET envoie un SMS de
 *                          DERNIER RECOURS (Twilio) à qui a un numéro
 *                          enregistré — seul canal qui ne dépend ni d'une
 *                          app installée, ni d'un jeton valide, ni même
 *                          d'une connexion data. Volontairement rare :
 *                          jamais à chaque demande, seulement quand les
 *                          trois pushs ont, en pratique, échoué.
 */
exports.relancerEtSecourirRappel = onSchedule(
  {
    schedule: "every 1 minutes",
    secrets: [TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM_NUMBER],
  },
  async () => {
    const db = getFirestore();
    const enAttente = await db
      .collection("rappels_test")
      .where("statut", "==", "en_attente")
      .get();
    if (enAttente.empty) return;

    const deviceSnap = await db.doc(VALIDATION.deviceDoc).get();
    const rappelContacts = deviceSnap.get("rappelContacts") || {};

    for (const doc of enAttente.docs) {
      const incident = doc.data();
      const demandéLeMs = incident.demandéLe ? incident.demandéLe.toMillis() : 0;
      if (!demandéLeMs) continue;
      const âgeMs = Date.now() - demandéLeMs;
      const contacts = incident.contacts || {};

      const enAttenteDeLecture = (clé) => {
        const statut = contacts[clé] && contacts[clé].statut;
        return statut === "non_lu" || statut === "jeton_invalide" || statut === "jeton_manquant";
      };

      if (âgeMs >= DÉLAI_RELANCE_MS && !incident.relanceEnvoyéeLe) {
        const relances = [];
        for (const clé of Object.keys(contacts)) {
          if (!enAttenteDeLecture(clé)) continue;
          const brut = rappelContacts[clé];
          const résultat = await envoyerPushContact({
            jeton: brut && brut.pushToken,
            incidentId: doc.id,
            contactKey: clé,
            baseUrl: "https://seniorvisio-test.web.app",
          });
          relances.push(journalEntrée(`relance vers ${contacts[clé].nom} : ${résultat}`, clé));
        }
        // FieldValue.arrayUnion() EXIGE au moins un argument — en fournir
        // zéro (aucun contact encore en attente de lecture, ex. incident
        // hérité d'un test précédent avec contacts: {}) ne produit pas un
        // no-op mais une exception, NON INTERCEPTÉE ici, qui arrêtait net
        // toute la boucle sur les incidents en attente : plus aucune
        // relance, expiration ni SMS de secours pour les incidents
        // suivants du même passage (piège réel, repéré en re-testant :
        // cette fonction, qui tourne chaque minute, échouait ainsi en
        // continu depuis plusieurs jours).
        const correctif = { relanceEnvoyéeLe: Date.now() };
        if (relances.length > 0) correctif.journal = FieldValue.arrayUnion(...relances);
        await doc.ref.set(correctif, { merge: true });
      }

      if (âgeMs >= DÉLAI_EXPIRATION_MS) {
        const journal = [journalEntrée("incident expiré, aucune réponse")];
        const secours = Object.keys(contacts).filter(enAttenteDeLecture);

        for (const clé of secours) {
          const téléphone = contacts[clé].téléphone;
          if (!téléphone) {
            journal.push(journalEntrée(`pas de secours SMS pour ${contacts[clé].nom} : aucun numéro`, clé));
            continue;
          }
          try {
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
                  Body:
                    `Jean a besoin d'aide et personne n'a encore répondu.\n` +
                    `Appeler maintenant : https://seniorvisio-test.web.app/?mode=aidant&incident=${doc.id}&contact=${clé}&rappel=1`,
                }),
              }
            );
            if (!réponse.ok) {
              const détail = await réponse.text();
              journal.push(journalEntrée(`SMS de secours refusé pour ${contacts[clé].nom} : ${détail}`, clé));
            } else {
              journal.push(journalEntrée(`SMS de secours envoyé à ${contacts[clé].nom}`, clé));
            }
          } catch (e) {
            journal.push(journalEntrée(`SMS de secours en erreur pour ${contacts[clé].nom} : ${e.message || e}`, clé));
          }
        }

        await doc.ref.set(
          { statut: "expiré", journal: FieldValue.arrayUnion(...journal) },
          { merge: true }
        );
      }
    }
  }
);
