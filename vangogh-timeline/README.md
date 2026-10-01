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

### Ouvrir une œuvre : transition vers le visualiseur IIIF (`ui/TimelineHost.kt`)

**Double-tap** sur une carte → la vignette **devient la page** : un habillage (la même image que la carte) part du rectangle de la carte et grandit
jusqu'à remplir l'écran (coins arrondis → droits, fond qui s'assombrit), puis `IiifZoomViewer` (bibliothèque `../iiif-viewer/library`, incluse
par `settings.gradle`) est monté dessous avec l'URL de l'œuvre — `infoJsonUrl` si le manifeste l'a donnée, sinon l'URL du manifeste, que le
visualiseur sait lire. Il démarre sur « image entière » (`initialZoom = 1`), exactement ce que montre l'habillage ; dès les premières tuiles
(`onReady`) l'habillage s'efface. Retour (bouton ou geste système) : le visualiseur est retiré et l'habillage se rétrécit jusqu'à la carte.

Tout est animé dans les phases de mise en page et de dessin (`Modifier.layout`, `graphicsLayer`, `drawBehind`) : aucune recomposition.
La position de la carte est relevée au double-tap (`boundsInRoot`, rouleau compris) ; sa vignette déjà chargée sert de `placeholder` à la grande
version (`placeholderMemoryCacheKey`) : pas de saut.

`SharedTransitionLayout` n'existe qu'à partir de Compose 1.7 ; le projet est sur 1.6 (Kotlin 1.9). Même principe (transformation de
conteneur), écrit à la main, sans API expérimentale.

**Retour** : le visualiseur DÉZOOME d'abord jusqu'à l'image entière (`IiifZoomController.animateToFit`, 280 ms, pendant lequel les doigts sont
absorbés), puis l'habillage — identique à cette vue — prend sa place et se rétrécit jusqu'à la carte. La transition de sortie part donc toujours
d'une vue au zoom minimal, jamais d'un détail zoomé.

### D'où viennent les œuvres (`iiif/ArticParser.kt`, `ArticRepository.kt`)

Par défaut, les **œuvres de Van Gogh de l'Art Institute of Chicago**, lues dans leur API publique (`api.artic.edu`) : chaque œuvre a un
`image_id`, c'est-à-dire un **service d'image IIIF** (`https://www.artic.edu/iiif/2/{image_id}`). La frise en tire des vignettes à la taille
voulue (`…/full/{w},/0/default.jpg`) et le visualiseur s'en sert pour le zoom profond. La réponse est enregistrée : hors ligne, la frise se
rouvre avec la copie locale. Sans réseau ni copie, 40 œuvres de démonstration SANS image s'affichent (elles ne s'ouvrent pas dans le visualiseur).

Limite : l'API ne donne que l'**année**. Ces œuvres sont donc datées à l'année près (précision `YEAR`) et placées au milieu de l'année.
Pour des dates au jour près, une collection IIIF avec `navDate` (voir ci-dessus) : `adb shell am start … -d "<url de la collection>"`.

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

- Sans argument : les œuvres de Van Gogh de l'Art Institute of Chicago (réseau requis la première fois). En secours hors ligne : 40 œuvres de démonstration sans image, dont les dates sont au mois près et écrites de mémoire d'après les chronologies usuelles — à ne pas citer comme source.
- Avec une collection IIIF réelle :
  `adb shell am start -n com.vangoghtimeline/.MainActivity -d "https://serveur/iiif/collection/vangogh.json"`

## Construire

CI : `.github/workflows/build-vangogh-timeline-apk.yml` (tests unitaires, APK debug en Release).
En local (JDK 17 + SDK Android 34) : `cd vangogh-timeline && gradle testDebugUnitTest assembleDebug`.
Le modèle, l'échelle, les couloirs et le parseur n'importent rien d'Android : leurs tests tournent sans émulateur.


## Anticipation du chargement (rouleau → visionneuse)

- **Sommet du rouleau** : une carte qui arrive au centre de l'écran (face à l'utilisateur) voit les tuiles de sa *vue d'arrivée*
  (image entière : niveau le plus grossier puis niveau net, plafonné à 16 Mo) chargées en asynchrone (`IiifPrewarm`). Elles sont
  libérées dès que la carte quitte le sommet (hystérésis 0,5 → 0,9 largeur de carte, 3 images chaudes au plus : `RollerTopPolicy`).
- **Double-tap** : le visualiseur est monté tout de suite sous la vignette avec cette instance ; à la fin de la transition il passe
  au-dessus, fond transparent, et les tuiles se posent sur la vignette. Aucun fractal, aucun écran noir.
- **Prochain défilement** : les vignettes de ce qui apparaîtra si l'on continue (zone large dans le sens du mouvement) sont chargées
  d'avance dans le cache Coil, celles qui font face à l'utilisateur d'abord, de haut en bas (`NextScrollOrder`, `PriorityPrefetcher`,
  3 chargements en parallèle, annulés dès qu'ils ne sont plus utiles).
