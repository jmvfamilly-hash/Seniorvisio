/**
 * Quel jeu de documents Firestore ce PWA pilote.
 *
 * ═══ CE FICHIER EST RÉÉCRIT AU DÉPLOIEMENT ═══
 *
 * Les valeurs ci-dessous ne servent qu'en développement local. Au
 * déploiement, scripts/prepare_pwa.py réécrit ce fichier avec les valeurs de
 * l'environnement publié. Elles décrivent la production parce que c'est le
 * repli le moins surprenant : une page ouverte depuis le dépôt se comporte
 * comme celle des proches.
 *
 * ═══ DEUX HÉBERGEURS, PAS DEUX DOSSIERS ═══
 *
 * La production est sur GitHub Pages, le banc d'essai sur Firebase Hosting
 * (seniorvisio-test.web.app). Deux hôtes, donc deux ORIGINES — et l'origine
 * est ce qui cloisonne localStorage. Les avoir mis dans deux dossiers d'un
 * même site, comme c'était le cas un temps, laissait les réglages mémorisés
 * et l'identité de l'appelant communs aux deux : un volume coupé pendant un
 * essai ressortait au prochain appel réel.
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
