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

## Navigation prédictive et mémoire

Le `TileManager` ne se contente pas de charger ce qui est à l'écran : il devine où l'utilisateur va regarder.

| Priorité | Zone préchargée | Sert à |
|---|---|---|
| 0 | niveau idéal, écran visible | image nette |
| 1 | niveau parent (N-1), écran visible | flou immédiat |
| 2 | niveau idéal, marge de 35 % autour de l'écran, prolongée dans le sens du mouvement | pan |
| 3 | niveau plus fin, autour du point de zoom | zoom avant |
| 4 | niveaux plus grossiers, champ élargi | dézoom |

- Le mouvement (vitesse de pan, vitesse de zoom, point de zoom) est lissé à chaque geste ; au repos (250 ms),
  un préchargement « tranquille » prépare pan, zoom et dézoom à la fois.
- Le chargement tient compte des priorités : le préchargement n'occupe que 2 des 6 voies tant qu'une tuile visible attend,
  démarre quand le navigateur est inactif, et se remonte en priorité si la tuile devient visible.
- Mémoire : plafond de 96 Mo de tuiles. Après 400 ms de calme, les tuiles éloignées (hors de 0,8 écran autour du point de vue)
  ou plus fines que nécessaire sont relâchées (canvas ramené à 0×0, image vidée). Sous pression, on évince d'abord ce qui est
  loin et mal adapté au zoom, jamais ce qui est voulu. Le préchargement ne dépasse pas 90 % du plafond.
  Application en arrière-plan : tout ce qui n'est pas voulu est rendu à iOS.
- Le niveau le plus grossier est épinglé : jamais d'écran noir, même après un dézoom brutal.

## Limites

- Le serveur IIIF doit autoriser l'`info.json` en CORS (`Access-Control-Allow-Origin`), ce que font la plupart.
- La page ne doit pas être publiée sur le GitHub Pages du dépôt : il sert la production (`web-caller/`).

## Hébergement

Publiée sur un site Firebase Hosting à part : **https://seniorvisio-iiif.web.app**
(workflow `.github/workflows/deploy-iiif-viewer-web.yml`, configuration `firebase.json` de ce dossier).
Chaque push touchant `iiif-viewer-web/` la redéploie ; elle est servie en `no-cache`, une mise à jour arrive au chargement suivant.
