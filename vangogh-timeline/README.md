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
et se disputait les doigts. Un toucher simple sur une carte ouvre l'œuvre : `ArtworkCard(onTap = …)` attache un détecteur sans retour visuel
(`TimelineScreen(onArtworkTap = …)`).

### Ouvrir une œuvre : transition vers le visualiseur IIIF (`ui/TimelineHost.kt`)

**Toucher simple** sur une carte → la vignette **devient la page** : un habillage (la même image que la carte) part du rectangle de la carte et grandit
jusqu'à remplir l'écran (coins arrondis → droits, fond qui s'assombrit), puis `IiifZoomViewer` (bibliothèque `../iiif-viewer/library`, incluse
par `settings.gradle`) est monté dessous avec l'URL de l'œuvre — `infoJsonUrl` si le manifeste l'a donnée, sinon l'URL du manifeste, que le
visualiseur sait lire. Il démarre sur « image entière » (`initialZoom = 1`), exactement ce que montre l'habillage ; dès les premières tuiles
(`onReady`) l'habillage s'efface. Retour (bouton ou geste système) : le visualiseur est retiré et l'habillage se rétrécit jusqu'à la carte.

Tout est animé dans les phases de mise en page et de dessin (`Modifier.layout`, `graphicsLayer`, `drawBehind`) : aucune recomposition.
La position de la carte est relevée au toucher (`boundsInRoot`, rouleau compris) ; sa vignette déjà chargée sert de `placeholder` à la grande
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
- **Toucher** : le visualiseur est monté tout de suite sous la vignette avec cette instance ; à la fin de la transition il passe
  au-dessus, fond transparent, et les tuiles se posent sur la vignette. Aucun fractal, aucun écran noir.
- **Prochain défilement** : les vignettes de ce qui apparaîtra si l'on continue (zone large dans le sens du mouvement) sont chargées
  d'avance dans le cache Coil, celles qui font face à l'utilisateur d'abord, de haut en bas (`NextScrollOrder`, `PriorityPrefetcher`,
  3 chargements en parallèle, annulés dès qu'ils ne sont plus utiles).

## Ouverture et retour

- **Ouvrir** : un toucher simple sur une carte (un toucher qui ne fait qu'arrêter l'inertie de la frise n'ouvre rien).
- **Revenir** : pas de bouton. Dans la visionneuse, **dézoomer encore** une fois l'image entière à l'écran (pincement vers l'intérieur
  d'environ 20 % de plus, geste commencé à l'image entière) ferme l'œuvre par la transition inverse ; le geste système retour aussi.
  Zoomé, le même pincement ramène d'abord à l'image entière, sans fermer.

## Sources : trois musées

La frise charge **en parallèle** les œuvres de Van Gogh (1870–1890) de trois sources et les fusionne au fil de leur arrivée
(`UniverseLoader`, une liste de sources par artiste) : elle s'affiche dès la première réponse, les autres s'y ajoutent ; une source en échec ne retire rien aux autres,
et chaque source garde une copie locale pour le hors ligne. Le crédit en bas à droite indique le nombre d'œuvres par musée.

| Source | Service | Comment on arrive à l'image IIIF |
|---|---|---|
| Art Institute of Chicago | API `artworks/search` | `image_id` → service `https://www.artic.edu/iiif/2/{image_id}` ; manifeste `…/artworks/{id}/manifest.json` |
| Rijksmuseum | Data Services (Linked Art, sans clé) | recherche → objet → VisualItem → DigitalObject → URL d'image IIIF → on retire `/{region}/{taille}/0/default.jpg` pour obtenir le service (`IiifImageUrl`) |
| Europeana | Search API `record/v2/search.json` | `id` `/{jeu}/{notice}` → manifeste `https://iiif.europeana.eu/presentation/{jeu}/{notice}/manifest`, lu par le visualiseur ; vignette = `edmPreview` |

Doublons : Europeana agrège aussi le Rijksmuseum ; quand le Rijksmuseum répond directement, ses notices Europeana sont écartées, puis
les œuvres de même titre et de même année sont dédoublonnées (`ArtworkMerge`).

**Limites connues** : ces deux services n'ont pas pu être appelés depuis l'environnement de développement (réseau bloqué) ; les parseurs
sont testés sur des réponses types construites d'après les formats documentés, pas sur des réponses réelles. Europeana utilise la
clé publique de démonstration `api2demo` (volume limité) : en remplacer la valeur (`EuropeanaParser.DEMO_KEY`) par une clé gratuite
personnelle. Les dates ne sont connues qu'à l'année (Europeana) ou à l'intervalle de production (Rijksmuseum) : jamais plus précises.

## Images sans service IIIF

Certains musées (via Europeana) ne publient qu'un fichier JPEG ordinaire dans leur manifeste, sans service d'image IIIF. Le visualiseur
sait alors l'ouvrir quand même : l'image est téléchargée une fois, puis découpée en tuiles par `BitmapRegionDecoder` (`StaticImageUrl`),
et le zoom profond fonctionne comme pour une vraie image IIIF. Le manifeste est lu dans cet ordre : service déclaré, URL d'image IIIF
(dont on déduit le service), image ordinaire.

## Double-tap dans la visionneuse

Trois temps (`DoubleTapZoom`), le point tapé restant fixe à l'écran : image entière → **milieu perceptif** → **zoom maximum** → image
entière. Le milieu perceptif est la moyenne géométrique `√(min × max)` : même facteur de grossissement de l'image entière à l'étape que de
l'étape au maximum (pour un maximum à ×8 : ×1 → ×2,8 → ×8). Depuis un zoom quelconque, on va à la prochaine étape au-dessus. Pour une petite
image dont l'étape serait presque l'image entière, elle est sautée. Le pincement vers l'intérieur depuis l'image entière referme l'œuvre.

## Menu des artistes (maquette « Chronologie des Impressionnistes »)

- Au lancement : un menu de portraits (catalogue `assets/artists_by_movement.json`, 20 artistes en trois familles). Chaque carte montre
  le portrait (vignette de l'article Wikipédia : tableau ou photo ; initiales à défaut), les dates, la **période d'activité** (barre colorée
  par famille) et l'œuvre emblématique. Un artiste **sans univers est grisé**.
- **Un toucher** sélectionne : ses informations s'affichent dessous (origine, style, type d'œuvres, lieux de création, sources et leur état)
  et la connexion de ses sources démarre en arrière-plan. **Un autre toucher** sur l'artiste sélectionné ouvre son univers dans la frise.
  Retour système depuis la frise : retour au menu.
- Univers ouverts pour l'instant : **Van Gogh** (AIC, Rijksmuseum, Cleveland, Met, Europeana), **Sargent** (AIC, Met, Cleveland, Europeana),
  **Sorolla** (Met, AIC, Europeana), **Renoir** (AIC, Met, SMK, Cleveland, Europeana). Les autres sont grisés : `ArtistExtras` (une ligne par artiste)
  est l'interrupteur.

## Valider l'accès IIIF avant de connecter un musée

Une source n'entre dans la frise qu'après validation (`SourceValidator`), à chaque connexion :

1. **Chercher** les œuvres de l'artiste dans l'API du musée ;
2. **Échantillonner** 3 œuvres (première, médiane, dernière) et vérifier ce que le visualiseur va demander : manifeste lisible → service d'image →
   `info.json` avec largeur et hauteur ; ou service IIIF direct (`info.json`) ; ou image ordinaire joignable (`image/*`, code 2xx) ;
3. **Connecter** si au moins la moitié de l'échantillon passe ; sinon la source est **REFUSÉE** et aucune de ses œuvres n'apparaît (pas de carte
   qui ne s'ouvre pas). Le rapport dit pourquoi (code HTTP, URL, « type text/html au lieu d'une image », « largeur/hauteur absentes »…) et s'affiche
   dans le panneau de l'artiste : point vert (connectée / copie hors ligne), rouge (refusée / injoignable), gris (vide / non implémentée), orange (en cours).

## Approche itérative pour sécuriser l'implémentation

Aucun des services ajoutés n'a pu être appelé depuis l'environnement de développement (réseau bloqué) : les parseurs sont testés sur des réponses types
construites d'après les formats documentés. On avance donc par cycles courts, chacun vérifié sur l'appareil par le validateur :

| Cycle | Contenu | Critère de sortie |
|---|---|---|
| 1 | Van Gogh : AIC + Rijksmuseum + Europeana (déjà vus fonctionner) | rapports verts sur l'appareil |
| 2 | Van Gogh : + Cleveland, Met (images ordinaires, découpées localement) | rapport vert, ou cause lue dans le panneau |
| 3 | Sargent : AIC, Met, Cleveland, Europeana | idem ; au moins une source verte |
| 4 | Renoir : + SMK (service IIIF natif) ; Sorolla : Met, AIC, Europeana | idem |
| 5 | Sources à clé ou à données externes : Harvard Art Museums (clé), MFA Boston (clé sur demande), SAAM (clé api.data.gov), NGA (IIIF natif mais les identifiants viennent de l'export open data), Getty, Prado, Barnes, Paris Musées, Hispanic Society | une clé/donnée fournie, puis même validation |
| 6 | Dégriser d'autres artistes (Monet, Pissarro, Cassatt, Cézanne…) en y ajoutant des sources | rapport vert avant d'ouvrir |

Règles : une source est ajoutée par une ligne dans `ArtistExtras` + une classe `MuseumSource` ; elle ne devient visible que si `SourceValidator` la valide ;
un échec est un rapport lisible, jamais un plantage ni une carte morte ; la copie hors ligne d'une connexion validée prend le relais sans réseau.

## Journal et rapport d'anomalies

- **Tout échec est consigné** (`Diag`), **même quand un repli le rattrape** : copie hors ligne utilisée, repli « User-Agent sobre » qui réussit,
  variante de recherche du Met, nouvel essai d'une tuile, échantillon de validation en échec dans une source finalement connectée, notice ignorée…
  Les événements identiques sont regroupés (« ×37 ») ; le journal garde les 600 derniers.
- **Navigation comprise** : vignettes qui ne chargent pas (Coil), tuiles du visualiseur et du préchauffage (chaque essai), ouvertures d'œuvres en échec,
  portraits manquants — attribués à l'artiste dont la frise est ouverte.
- **Erreurs HTTP complètes** : code, URL, serveur, type, redirection et début du corps de la réponse (un « 410 » dit alors s'il vient du service ou d'un pare-feu).
- **Repli sur requête** : si la recherche d'une source échoue, elle est retentée une fois avec un User-Agent sobre ; le Met essaie en plus trois variantes de
  recherche (complète, `q` seul, peintures européennes). Les deux échecs et le succès éventuel du repli sont dans le journal.
- **Copier le rapport** : appui long n'importe où dans la partie basse de la fiche (le panneau d'informations, pour n'importe quel artiste) → rapport texte dans le
  presse-papiers : version et appareil, état des sources de TOUS les artistes (avec les échantillons vérifiés), journal complet, dernier plantage enregistré.

## Le Met : API v1/search retirée le 2026-10-01

Le premier rapport d'anomalies (appui long) a montré que le Met répondait « HTTP 410 — `/public/collection/v1/search` was retired on 2026-10-01 »
et renvoyait vers `/public/collection/v1.1/search` (Elastic, paginée par `offset` et `limit`). Corrections :

- recherche **v1.1** en premier (trois variantes : complète, `q` seul, peintures européennes), pages de 100, deux pages au plus ; les anciennes adresses ne
  servent plus qu'en dernier recours ;
- lecture **tolérante** de la réponse (la forme exacte de la v1.1 n'a pas pu être vérifiée) : `objectIDs`, `objects`, `results`, `items`, `data`, `ids`, entiers ou objets
  `objectID`/`id`, tableau à la racine ; au premier succès, les clés de la réponse, le total et le début du corps sont consignés (INFO) pour confirmer la forme ;
- **API retirée** (corps contenant « retired », ou HTTP 410) : la source passe en « indisponible : API retirée » avec le message du service, sans retenter le User-Agent sobre ;
  une copie d'une connexion passée reste utilisée si elle existe ;
- extrait du corps d'erreur porté à 800 caractères pour le JSON ;
- **Europeana** : trois variantes de nom (exact, sans accents, nom de famille) ; chacune est consignée avec son résultat.

## Corrections après le rapport du 2026-10-01 (rev19)

- **Le Met bloquait l'appli par intermittence** (403 avec page HTML « Incapsula » : pare-feu anti-robot / limite de débit). Tout le trafic vers le Met passe
  maintenant par une file commune à tous les artistes (`RateLimiter` : 2 requêtes à la fois, 150 ms d'écart) ; un blocage temporaire (429, 503, 403 avec page HTML)
  est retenté après 2 s puis 5 s (`RetryingSource`, chaque attente consignée) ; les notices lues sont gardées sur disque (`met_notices/`). Si le blocage persiste, la source
  est **LIMITÉE** (point orange, « nouvel essai automatique ») et retentée une fois après 60 s sans gêner l'affichage.
- **HTTP non chiffré interdit par Android** (« Cleartext HTTP traffic … not permitted ») : toute adresse `http://` (manifestes, `info.json`, tuiles, vignettes, images) est
  réécrite en `https://` (`HttpUpgrade`), sauf adresses locales ; la réécriture est consignée une fois par serveur.
- **Homonymes** (botanistes « E. Sargent » dans Europeana…) : un créateur doit désormais porter le nom de famille ET un prénom ou son initiale (`ArtworkQuery.matchesCreator`) ;
  la recherche Europeana par nom de famille seul exige en plus un créateur déclaré.
- **Sources vides expliquées** : chaque analyse consigne « N reçues, K retenues — écartées : … » avec les raisons (hors domaine public, sans image, autre artiste, hors dates…) et, si
  rien n'est retenu, la forme de la réponse (clés, premier élément) — pour distinguer « le service ne renvoie rien » de « le filtre écarte tout » (Met et Europeana pour Sorolla, SMK pour Renoir).

## Magasin JSON local et cache de tuiles (l'appli ne recharge plus tout à chaque ouverture)

**Magasin d'univers** (`filesDir/universe_store/{artiste}.json`, `UniverseStore`) : pour chaque artiste et chaque source, l'état de la dernière tentative, les œuvres, le rapport
de validation, la date d'obtention et la date de dernière tentative. À la sélection d'un artiste :

1. l'univers enregistré s'affiche **tout de suite, sans réseau** ;
2. chaque source est décidée par `StorePolicy` : connectée depuis **moins de 7 jours** → utilisée telle quelle ; **plus de 7 jours** → utilisée ET rafraîchie en arrière-plan ;
   copie hors ligne ou échec (refusée, injoignable, vide, limitée…) **depuis plus d'une heure** → retentée ; jamais obtenue → cherchée ;
3. si la mise à jour échoue (hors ligne), l'ancienne copie reste affichée avec **sa vraie date** ;
4. le panneau de l'artiste montre « Données mises à jour il y a N jours (actualisation automatique après 7 jours) » et un bouton **Actualiser maintenant** (recherche et
   revalide tout, en gardant l'ancien univers affiché pendant ce temps).

`UniverseStore.PARSER_VERSION` est à incrémenter chaque fois que les règles de lecture/filtrage changent : les fichiers d'une autre version sont ignorés, sans quoi des œuvres mal
filtrées resteraient 7 jours. Écriture atomique (fichier temporaire puis renommage).

**Cache de tuiles** (`cacheDir/iiif_tiles`, `DiskTileCache`, 256 Mo) : les tuiles, images ordinaires (Met, Cleveland) et `info.json`/manifestes déjà vus sont gardés sur disque ; au-delà du
plafond, les moins récemment utilisées sont supprimées en premier (la date du fichier est rafraîchie à chaque lecture). Les textes expirent après 30 jours. Une œuvre déjà ouverte se
rouvre sans retélécharger, même hors ligne. Les vignettes ont leur propre cache disque (Coil, 100 Mo). Les statistiques du cache (fichiers, Mo, taux de réussite) figurent dans le rapport d'anomalies.

## Toutes les sources ouvertes, droits affichés (rev22)

Pour les 4 artistes qui ont un univers (Van Gogh, Sargent, Sorolla, Renoir), **toutes les sources sont ouvertes** : AIC, Rijksmuseum, Europeana, Met, Cleveland, SMK, **Wikimedia** et la
sonde **Hispanic Society**. Les autres artistes seront ajoutés plus tard.

- **Œuvres consultables en usage privé** : on ne filtre plus sur le seul domaine public (AIC, Met, Cleveland, SMK, Europeana sans `reusability=open`). Une œuvre protégée qui a une image publiée est
  gardée, marquée « consultation privée » ; sans image publiée, elle est écartée (raison consignée).
- **Licences et conditions** (`model/Rights.kt`) : chaque œuvre porte un `RightsInfo` (PD / CC / © / ?, libellé, URL de licence, attribution, conditions). Pastille en haut à droite de la vignette ;
  dans la visionneuse, la barre `RightsBar` (une ligne, un tap la déplie : titre, date, fournisseur, attribution, conditions, lien) ; le panneau de l'artiste résume
  « N domaine public · M consultation privée ».
- **Wikimedia** (`WikimediaParser`) : Wikidata SPARQL (créateur P170, image P18, date P571, collection P195) + licence lue sur Commons (`extmetadata`) ; images via `Special:FilePath`.
- **Europeana** : variante par fournisseur de données (Sorolla) ; droits lus dans `rights` ; `previewNoDistribute` → pas de vignette, mention dans les conditions.
- **Hispanic Society** : la reconnaissance (rev22) a montré que ses deux serveurs sont derrière un pare-feu anti-robot (Cloudflare « Just a moment », Anubis « Making sure you're not a bot ») : inexploitable par une appli, la sonde est retirée.
- `UniverseStore.PARSER_VERSION` = 2 : les univers enregistrés sont relus avec les nouvelles règles.

Non vérifié (réseau bloqué dans l'environnement de build) : formats réels Wikidata/Commons, termes Rijksmuseum pour Sargent/Renoir/Sorolla, URL de la Hispanic Society. Le rapport d'anomalies
(appui long) montrera les lignes « reconnaissance » / « analyse ».

## Rev23 : tolérance réseau, variantes de recherche, sonde CER.ES

- **Validation tolérante** (`SourceValidator.decide`) : un échantillon en échec de RÉSEAU (429, 503, délai, DNS…) est retenté une fois puis ne compte pas contre la source ; la moitié des échantillons
  concluants doit passer. Si aucun n'est concluant, la source est LIMITÉE (nouvel essai automatique), jamais REFUSÉE à tort. Un vrai refus (404, 403 JSON, page HTML) rejette toujours.
- **Wikidata/Commons** : file commune (2 requêtes, 400 ms d'écart) et 3 nouveaux essais patients (2, 5, 10 s) après un 429.
- **Journal** : « Canceled », « Socket closed » et « … was cancelled » (annulations normales) ne sont plus consignés ; leur nombre figure dans le résumé du rapport.
- **SMK** : mots-clés essayés dans l'ordre (nom complet, sans accents, nom de famille), créateur revérifié ; chaque variante est consignée.
- **Europeana** : variantes exacte, sans accents, « Nom, Prénom », nom ET prénom (créateur exigé), nom de famille seul (créateur exigé).
- **CER.ES / Museo Sorolla** (`CeresProbe`, Sorolla seulement) : sonde de reconnaissance, aucune œuvre ajoutée. Elle consigne, pour la fiche d'une œuvre connue (table FDOC, musée MSM), l'accueil et
  des adresses OAI-PMH probables : code, titre, formulaires et champs, images, mentions de droits, début du corps.
