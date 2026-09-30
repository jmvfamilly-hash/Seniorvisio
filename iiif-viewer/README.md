# IIIF Viewer — visualiseur Deep Zoom natif

Lecteur d'images pyramidales **IIIF Image API 3.0** (lit aussi la 2.x) en Jetpack Compose pur :
pas de WebView, pas d'OpenSeadragon. Projet Gradle autonome, indépendant de Senior Visio.

## Architecture (`app/src/main/java/com/iiifviewer/`)

| Couche | Fichiers | Rôle |
|---|---|---|
| 1. Domaine | `IiifModels.kt` | `IiifImageInfo`, `ViewportState`, `Tile`, `IiifRegion` |
| 2. Calcul | `TileCalculator.kt` | Niveau de zoom idéal, projection écran → image, culling des tuiles |
| 3. Pipeline | `TileManager.kt`, `PrefetchPlanner.kt`, `IiifSources.kt` | Navigation prédictive, file à priorités, téléchargements annulables, mémoire plafonnée, niveau grossier épinglé |
| 4. UI | `IiifZoomViewer.kt`, `ViewportController.kt`, `ViewportGestures.kt` | Canvas, pinch/pan, inertie, double-tap, bords collants |
| Plateforme | `AndroidIiifSources.kt` | Seul fichier dépendant d'Android (HTTP + décodage) |

Tout le reste est du Kotlin commun, prêt pour Compose Multiplatform.

## Navigation prédictive et mémoire

`PrefetchPlanner` (Kotlin pur, testé sur la JVM) décide quelles tuiles vont servir, avec une priorité :

| Priorité | Zone | Sert à |
|---|---|---|
| 0 | niveau idéal, écran visible | image nette |
| 1 | niveau parent (N-1), écran visible | flou immédiat |
| 2 | niveau idéal, marge de 35 % prolongée dans le sens du mouvement | pan |
| 3 | niveau plus fin autour du point de zoom | zoom avant |
| 4 | niveaux plus grossiers, champ élargi | dézoom |

Le `TileManager` estime le mouvement (vitesse de pan et de zoom, lissée), sert les plus urgentes d'abord (le préchargement
n'occupe que 2 des 6 voies tant qu'une tuile visible attend) et, au repos, prépare pan, zoom et dézoom à la fois.
Mémoire plafonnée à 96 Mo : après 400 ms de calme, les tuiles éloignées ou plus fines que nécessaire sont relâchées, et sous
pression on évince d'abord ce qui est loin et mal adapté au zoom. Application en arrière-plan : tout ce qui n'est pas voulu est rendu.

## Construire

- **CI** : chaque push touchant `iiif-viewer/` lance `.github/workflows/build-iiif-viewer-apk.yml`
  (essais unitaires, APK debug, publié en *Release* GitHub).
- **En local** (JDK 17 + Android SDK 34) :
  ```
  cd iiif-viewer
  gradle testDebugUnitTest assembleDebug
  ```

## Essayer une autre image

**Appui long** n'importe où sur l'écran : une fenêtre propose de coller le lien de l'`info.json`
(ou l'adresse de base de l'image). Si le presse-papiers contient une URL, elle est déjà remplie.

Depuis un ordinateur :

```
adb shell am start -n com.iiifviewer/.MainActivity -d "https://serveur/iiif/image/info.json"
```
