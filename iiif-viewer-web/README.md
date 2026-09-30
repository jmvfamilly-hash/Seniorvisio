# IIIF Viewer — version web (iPad, navigateur)

Port en JavaScript pur, sans dépendance, de `iiif-viewer/` : un seul fichier, `index.html`.
Même moteur (calcul de tuiles, annulation des requêtes hors écran, cache LRU, rendu progressif du flou
vers le net) et mêmes gestes : pan, pinch, inertie, double-tap, appui long pour coller un lien
`info.json`, molette sur ordinateur.

## Utilisation

- Ouvrir `index.html` dans Safari (ou le servir depuis n'importe quel hébergement statique).
  Sans lien, la **démo locale** (ensemble de Mandelbrot calculé tuile par tuile) s'affiche : elle marche sans réseau.
- Sur iPad : *Partager → Sur l'écran d'accueil* pour l'ouvrir en plein écran, comme une application.
- Un lien peut aussi être passé dans l'adresse : `index.html?url=https://serveur/iiif/image/info.json`.

## Limites

- Le serveur IIIF doit autoriser l'`info.json` en CORS (`Access-Control-Allow-Origin`), ce que font la plupart.
- La page ne doit pas être publiée sur le GitHub Pages du dépôt : il sert la production (`web-caller/`).

## Hébergement

Publiée sur un site Firebase Hosting à part : **https://seniorvisio-iiif.web.app**
(workflow `.github/workflows/deploy-iiif-viewer-web.yml`, configuration `firebase.json` de ce dossier).
Chaque push touchant `iiif-viewer-web/` la redéploie ; elle est servie en `no-cache`, une mise à jour arrive au chargement suivant.
