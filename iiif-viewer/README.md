# IIIF Viewer — visualiseur Deep Zoom natif

Lecteur d'images pyramidales **IIIF Image API 3.0** (lit aussi la 2.x) en Jetpack Compose pur :
pas de WebView, pas d'OpenSeadragon. Projet Gradle autonome, indépendant de Senior Visio.

## Architecture (`app/src/main/java/com/iiifviewer/`)

| Couche | Fichiers | Rôle |
|---|---|---|
| 1. Domaine | `IiifModels.kt` | `IiifImageInfo`, `ViewportState`, `Tile`, `IiifRegion` |
| 2. Calcul | `TileCalculator.kt` | Niveau de zoom idéal, projection écran → image, culling des tuiles |
| 3. Pipeline | `TileManager.kt`, `LruCache.kt`, `IiifSources.kt` | Téléchargement annulable, cache LRU, niveau grossier épinglé |
| 4. UI | `IiifZoomViewer.kt`, `ViewportController.kt`, `ViewportGestures.kt` | Canvas, pinch/pan, inertie, double-tap, bords collants |
| Plateforme | `AndroidIiifSources.kt` | Seul fichier dépendant d'Android (HTTP + décodage) |

Tout le reste est du Kotlin commun, prêt pour Compose Multiplatform.

## Construire

- **CI** : chaque push touchant `iiif-viewer/` lance `.github/workflows/build-iiif-viewer-apk.yml`
  (essais unitaires, APK debug, publié en *Release* GitHub).
- **En local** (JDK 17 + Android SDK 34) :
  ```
  cd iiif-viewer
  gradle testDebugUnitTest assembleDebug
  ```

## Essayer une autre image

```
adb shell am start -n com.iiifviewer/.MainActivity -d "https://serveur/iiif/image/info.json"
```
