# Van Gogh, frise chronologique

Frise interactive des œuvres de Van Gogh, alimentée par des **manifestes IIIF**. Projet Android autonome
(Kotlin, Jetpack Compose, Coil), sans rapport avec Senior Visio.

## Modèle de données (`model/`)

| Type | Rôle |
|---|---|
| `Artwork` | une œuvre : `id`, `title`, `date`, `place`, `medium`, `iiif` |
| `ArtworkDate` | date de création : année/mois/jour + `DatePrecision` (`DAY`, `MONTH`, `YEAR`). Champs primitifs : stable pour Compose, aucune dépendance |
| `IiifRef` | `manifestUrl`, `imageServiceId`, `thumbnailUrl`, taille du canevas. Fabrique l'URL de vignette IIIF `…/full/!w,h/0/default.jpg` et l'`info.json` (pour ouvrir l'œuvre en zoom profond) |
| `CivilCalendar` | date ⇄ « jours depuis 1970 », sans fuseau ni heure d'été : une date = un entier, l'axe est exact au jour près |

**Où est la date exacte ?** Dans le manifeste, propriété **`navDate`** (`xsd:dateTime`, ex. `1888-10-01T00:00:00Z`), faite pour situer
une ressource dans le temps. À défaut, une métadonnée « Date ». Une métadonnée « Précision de la date » (`jour`/`mois`/`année`) dit que
la date n'est connue qu'au mois ou à l'année : la carte est alors placée au milieu du mois/de l'année, et la date affichée reste honnête
(« juin 1889 », pas « 15 juin 1889 »). Voir `iiif/IiifManifestParser.kt`.

`iiif/ManifestRepository.kt` charge une **Collection** IIIF (ou une liste d'URL), 4 manifestes en parallèle au plus ; un manifeste illisible
ou sans date est ignoré sans faire échouer la frise.

## Mise en page (`model/TimelineEngine.kt`, `ui/`)

- **Axe X = le temps** : `TimeScale(daysPerPixel)`, **1 px = X jours** (1,6 par défaut, réglable de 0,25 à 12 au pincement). La date est en jours
  entiers, donc une œuvre datée au jour près tombe au pixel près.
- **Axe Y = couloirs** : les œuvres, par date croissante, vont dans le premier couloir où leur carte ne chevauche pas la précédente
  (emballage glouton d'intervalles). Zoomer sépare les œuvres, dézoomer les empile ; jamais de carte cachée.
- **`TimelineLayout`** : `Layout` sur mesure, défilement libre dans les deux sens avec inertie (`Modifier.scroll2D`), **virtualisé**.

### Effet « rouleau » (`model/CylinderProjection.kt`)

La frise est enroulée sur un cylindre vertical : au centre, les cartes sont à leur place ; vers les bords gauche et droit, elles tournent
autour du cylindre. Une carte dont le centre est à `u` pixels du centre de l'écran est à l'angle `φ = u / R` ; elle apparaît à `R·sin φ`
(le temps se **comprime** vers les bords), avec `scaleX = cos φ`, `alpha = cos φ ^ 1,3`, et `rotationY` = 0,6·φ (le bord extérieur s'éloigne).
Appliqué au placement par `placeWithLayer` (`scaleX`, `alpha`, `rotationY`, `cameraDistance`, `zIndex` = cos φ : la carte du centre passe devant) :
aucune recomposition, seul le calque GPU de chaque carte change. La règle du temps est projetée de la même façon, donc elle suit les cartes.
Désactivable : `TimelineScreen(roller = false)`.

### Gestes sur les vignettes

Le pincement (zoom/dézoom du temps) marche même si les doigts partent d'une vignette : `scroll2D` observe les doigts en passe
**Initial** (avant les vignettes), et les cartes n'ont plus de `clickable` — hors thème Material, il appliquait un voile de débogage au toucher
et se disputait les doigts. Un double-tap sur une carte est prévu : `ArtworkCard(onDoubleTap = …)` attache un détecteur sans retour visuel
(`TimelineScreen(onArtworkDoubleTap = …)`).

### Pourquoi ça reste fluide avec des dizaines de vignettes

1. Seules les œuvres proches de l'écran (+ 320 dp de marge) sont composées : recherche dichotomique, O(log n).
2. `derivedStateOf` : la liste n'est recomposée que si l'**ensemble** des cartes visibles change, pas à chaque pixel de défilement.
3. Le défilement n'est lu que dans le bloc de placement (et dans le dessin de la règle) : faire défiler ne fait que **déplacer** des cartes déjà
   composées, sans recomposition ni nouvelle mesure. Chaque carte est mesurée à taille fixe.
4. `ArtworkCard` reçoit l'œuvre et sa taille, pas sa position : déplacer une carte ne la recompose pas.
5. **Coil** : taille de décodage exacte, vignette redimensionnée par le serveur IIIF (`!w,h`), RGB 565, clé de cache mémoire stable, pas de fondu ;
   cache disque de 100 Mo et `respectCacheHeaders(false)` (les serveurs IIIF envoient souvent `no-cache`). Une carte qui quitte la composition annule
   son téléchargement ; la marge de 320 dp la fait charger avant qu'elle n'entre à l'écran.

## Essayer

- Sans argument : 40 œuvres de démonstration, hors ligne (aplats aux couleurs du lieu).
  **Leurs dates sont au mois près et écrites de mémoire d'après les chronologies usuelles : à remplacer par les données d'un musée.**
- Avec une collection IIIF réelle :
  `adb shell am start -n com.vangoghtimeline/.MainActivity -d "https://serveur/iiif/collection/vangogh.json"`

## Construire

CI : `.github/workflows/build-vangogh-timeline-apk.yml` (tests unitaires, APK debug en Release).
En local (JDK 17 + SDK Android 34) : `cd vangogh-timeline && gradle testDebugUnitTest assembleDebug`.
Le modèle, l'échelle, les couloirs et le parseur n'importent rien d'Android : leurs tests tournent sans émulateur.
