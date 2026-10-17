// Mesure d'audience, chargée seulement après accord du visiteur.
(function () {
  var cfg = window.IMPRESSIONNISTES || {};
  if (!cfg.measurementId) return;

  var CLE = "imp_mesure";
  function lire() { try { return localStorage.getItem(CLE); } catch (e) { return null; } }
  function ecrire(v) { try { localStorage.setItem(CLE, v); } catch (e) {} }

  function charger() {
    window.dataLayer = window.dataLayer || [];
    window.gtag = function () { window.dataLayer.push(arguments); };
    gtag("js", new Date());
    gtag("config", cfg.measurementId, { anonymize_ip: true });
    var s = document.createElement("script");
    s.async = true;
    s.src = "https://www.googletagmanager.com/gtag/js?id=" + encodeURIComponent(cfg.measurementId);
    document.head.appendChild(s);
  }

  var choix = lire();
  if (choix === "oui") return charger();
  if (choix === "non") return;

  var bandeau = document.createElement("div");
  bandeau.className = "bandeau-mesure";
  bandeau.innerHTML =
    "<p>Puis-je mesurer anonymement la fréquentation de cette page, pour améliorer l'application ?</p>" +
    '<button type="button" data-v="oui">Oui, d\'accord</button>' +
    '<button type="button" data-v="non" class="secondaire">Non merci</button>';
  bandeau.addEventListener("click", function (e) {
    var v = e.target && e.target.getAttribute("data-v");
    if (!v) return;
    ecrire(v);
    bandeau.remove();
    if (v === "oui") charger();
  });
  document.body.appendChild(bandeau);
})();
