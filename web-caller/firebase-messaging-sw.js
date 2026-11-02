/**
 * Service worker du rappel par notification (voir app.js,
 * activerAlertesButton, et functions/index.js, envoyerRappel).
 *
 * Message FCM volontairement SANS bloc "notification" de premier niveau :
 * un message de données pures laisse ce fichier seul juge de l'affichage et
 * du clic, ce qui est nécessaire pour poser deux actions distinctes
 * (« Appeler maintenant » / « Plus tard ») menant chacune à sa propre URL.
 * Avec un bloc "notification", Firebase afficherait la notification lui-même
 * et ce fichier ne recevrait jamais l'événement en premier plan ni, surtout,
 * ne pourrait distinguer quelle action a été pressée.
 */
importScripts("https://www.gstatic.com/firebasejs/10.13.2/firebase-app-compat.js");
importScripts("https://www.gstatic.com/firebasejs/10.13.2/firebase-messaging-compat.js");
importScripts("firebase-config.js");

firebase.initializeApp(FIREBASE_CONFIG);
const messaging = firebase.messaging();

messaging.onBackgroundMessage((payload) => {
  const data = payload.data || {};
  self.registration.showNotification(data.titre || "Jean aimerait vous parler", {
    body: data.corps || "Appuyez pour le rappeler.",
    tag: "rappel-aidant",
    requireInteraction: true,
    data: { urlRappel: data.urlRappel, urlDiffere: data.urlDiffere },
    actions: [
      { action: "rappeler", title: "Appeler maintenant" },
      { action: "plus_tard", title: "Plus tard" },
    ],
  });
});

// Un clic sur le corps de la notification (aucune action précise) vaut
// « Appeler maintenant » — c'est le geste par défaut, le même que cliquer le
// premier lien de l'ancien SMS.
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const data = event.notification.data || {};
  const url = event.action === "plus_tard" ? data.urlDiffere : data.urlRappel;
  if (!url) return;

  event.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then((liste) => {
        for (const client of liste) {
          if ("focus" in client) {
            client.navigate(url);
            return client.focus();
          }
        }
        return self.clients.openWindow(url);
      })
  );
});
