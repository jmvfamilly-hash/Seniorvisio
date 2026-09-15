/**
 * Quel jeu de documents Firestore ce PWA pilote.
 *
 * ═══ CE FICHIER EST RÉÉCRIT AU DÉPLOIEMENT ═══
 *
 * Les valeurs ci-dessous sont celles de la PRODUCTION, et c'est volontaire :
 * le dépôt doit décrire ce qui tourne chez Jean. Le déploiement publie deux
 * copies du même PWA (voir .github/workflows/deploy-web-caller.yml) — la
 * racine garde ce fichier tel quel, et la copie servie sous /test/ le
 * remplace par les valeurs de validation.
 *
 * ═══ POURQUOI PAS UN INTERRUPTEUR DANS L'INTERFACE ═══
 *
 * Un sélecteur « production / test » serait plus souple, et c'est exactement
 * ce qu'on ne veut pas : il suffirait d'un mauvais choix, ou d'un réglage
 * mémorisé d'une session à l'autre, pour appeler Jean en croyant essayer
 * quelque chose. Deux adresses distinctes ne se confondent pas, et l'adresse
 * est visible dans la barre du navigateur.
 *
 * Les valeurs DOIVENT rester identiques aux variantes déclarées dans
 * app/build.gradle (DEVICE_ID, CALLS_COLLECTION) et aux environnements de
 * functions/index.js.
 */
window.SENIORVISIO_ENV = {
  /** "production" ou "validation" — n'affiche un bandeau que dans le second cas. */
  name: "production",
  /** Document d'état de la tablette : signe de vie, batterie, réglages. */
  deviceDocId: "jean_tablet",
  /** Collection Firestore où déposer les appels. */
  callsCollection: "calls",
};
