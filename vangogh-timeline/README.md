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

**Berthe Morisot** (rev24) : univers ouvert avec les 7 mêmes sources (Rijksmuseum : « Morisot, Berthe » ; Wikimedia : article `Berthe_Morisot`). Le créateur est vérifié strictement (nom + prénom ou initiale).

## National Gallery of Art (rev24)

Le NGA ne publie **pas d'API en ligne** : seulement son open data en CSV (CC0, ~170 Mo, mis à jour chaque jour). L'appli ne le télécharge jamais. `tools/nga_extract.py` (à relancer de temps en temps
sur un clone de `NationalGalleryOfArt/opendata`) en tire un petit JSON par artiste, `app/src/main/assets/nga/{artiste}.json` (2 à 80 Ko), lu par `NgaParser` sans réseau. Les images sont servies
en IIIF par `api.nga.gov/iiif/{uuid}` (zoom profond) ; la source reste soumise à la validation d'accès comme les autres.

- **Contenu actuel** (extrait de l'open data réel) : Van Gogh 23, Morisot 28, Renoir 80, Sargent 161, Sorolla 1 œuvres, avant filtrage.
- **Écartées et comptées** : « d'après », « suiveur », « imitateur », « attribué à », collaborations ; œuvres sans date précise (l'intervalle d'une vie entière) ; hors des dates plausibles.
- **Droits** : image en open access → domaine public (CC0) ; sinon « accès restreint (usage loyal) » avec la résolution maximale annoncée. Ajouté à tous les artistes qui ont un univers.
- Quand le backend Supabase existera, le glaneur lira ces CSV directement et ces fichiers disparaîtront.

## Rev26 : Wikimedia sans 429 (rapport rev25)

Le rapport rev25 montrait des HTTP 429 de Wikimedia sur les licences (3 essais épuisés), les vignettes (×10) et l'ouverture d'œuvres (2 erreurs « visionneuse »). Causes probables et corrections :

- **Largeurs non standard** : Commons ne sert les miniatures qu'à des largeurs standard (20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840) ; nous demandions 400 et 3000. Désormais 500 pour la vignette, et pour l'ouverture la plus grande largeur standard
  que permet le fichier (3840 au plus ; 1920 si la taille est inconnue). Jamais plus large que le fichier.
- **User-Agent** : Wikimedia exige un User-Agent qui identifie l'application ; un User-Agent de navigateur y reçoit des limites. `HostEtiquette` (bibliothèque) en envoie un dédié à `*.wikimedia.org`, `*.wikidata.org`, `*.wikipedia.org`
  (contact : le profil GitHub, jamais une adresse e-mail), dans les vignettes (Coil), les recherches, la validation et la visionneuse.
- **Cadence** : les vignettes Wikimedia passent par une file (250 ms entre deux départs) avec UN nouvel essai après un 429 (`Retry-After`) ; la visionneuse retente 2 fois un 429/503 (pauses du serveur) ; les lots de licences se lisent un à la fois.

## Rev27 : œuvres sans date et année de secours Europeana

- **Europeana** : sans `year`, l'année vient de `edmTimespanLabel` (ou de sa version par langue) : « 1880 - 1890 » → 1885, « 1890s » → 1890 ; écart de plus de 50 ans ou aucune année lisible → pas de repli.
- **Toutes les sources** (AIC, Europeana, Met, Cleveland, SMK, NGA, Wikimedia) : une œuvre sans date n'est plus écartée. Elle est placée à la **moitié de la période d'activité** de l'artiste (catalogue : ex. Van Gogh 1880–1890 → 1885 ;
  à défaut, le milieu des dates plausibles) et **marquée estimée** : elle s'affiche « vers 1885 (date inconnue) ». Le bilan d'analyse le dit : « 20 retenues (dont 6 à date estimée) ».
- **Doublons** : une œuvre à date estimée disparaît si le même titre existe, daté, dans une autre source (ArtworkMerge).
- Une année connue mais hors des dates plausibles reste écartée. `PARSER_VERSION` = 3 (les univers enregistrés sont relus). Le Rijksmuseum, dont la date vient du format Linked Art, n'est pas concerné.

**Rev28** : le filtre des annulations normales reconnaît désormais les messages préfixés (« essai 1 : Canceled », « essai 1 : Socket is closed ») ; en rev25 il ne voyait que les messages nus, et le journal restait envahi.

**Claude Monet et Paul Gauguin** (rev29) : univers ouverts avec les mêmes sources (Rijksmuseum : « Monet, Claude », « Gauguin, Paul »). NGA : Monet 29 et Gauguin 178 œuvres extraites de l'open data (constituants 1726 et 1330, Wikidata Q296 et Q37693).

## Rev30 : le Met bloqué, chargements multiples, écriture du magasin (rapport rev28)

Le rapport rev28 montrait Wikimedia sans aucun 429 (largeurs 1920/3840 acceptées), mais :

- **Le Met bloqué par son pare-feu (Incapsula)** : le journal de Sargent montre ~9 chargements simultanés (appuis répétés sur « Actualiser »), donc des centaines de requêtes, 155 × HTTP 403 et la source « injoignable ».
  - `AppModel` n'accepte plus qu'**un chargement à la fois par artiste** (la demande en double est ignorée et consignée).
  - **Disjoncteur** (`RateLimiter`) : après un 403 de pare-feu, tous les appelants font une pause (15 s, puis 30, 60, 120 s si le blocage persiste) ; les requêtes échouent tout de suite pendant la pause, sans insister.
  - Un blocage **arrête la source** (état LIMITÉE) au lieu d'écarter en silence des dizaines de notices ; jusqu'à 3 reprises automatiques, chacune lit de nouvelles notices (gardées sur disque) avant le prochain blocage.
  - Cadence du Met : 250 ms entre deux départs (au lieu de 150).
- **Magasin local** : les sources qui finissent en même temps écrivaient le même fichier temporaire et pouvaient se réécrire un état plus ancien. Écriture désormais sérialisée, avec un instantané pris sous verrou.
- **Wikimedia** : la requête SPARQL est ordonnée (`ORDER BY ?item`) : avec `LIMIT 300` sans ordre, le sous-ensemble retenu changeait d'un chargement à l'autre (174 à 242 œuvres pour Sargent). Elle reste limitée à 300 œuvres.

## Rev31 : Met par tranches (E), nom de l'artiste, fiche détaillée de l'œuvre

- **Met progressif (proposition E)** : jusqu'à 400 œuvres (recherche suivie sur 4 pages), lues par tranches de **100 notices non gardées sur disque** par passage et jamais au-delà de 30 s. Une lecture non terminée rend l'état **PARTIEL**
  (connecté et validé, « lecture partielle : la suite au prochain chargement ») ; le chargeur la reprend seul (jusqu'à 3 passages, 60 s d'écart), puis au prochain chargement au bout de 5 minutes (au lieu de 7 jours).
  Chaque notice est lue UNE seule fois (cache disque) ; un blocage anti-robot interrompt le passage sans rien perdre.
- **Vue frise** : le nom de l'artiste et ses dates de vie en en-tête (« Berthe Morisot · 1841–1895 »).
- **Vue détaillée d'une œuvre** : ligne « artiste — titre, date » + licence ; un toucher déplie la fiche : lieu, technique, dimensions, type, département, crédit, n° d'inventaire (selon le musée),
  fournisseur, **lien vers la fiche du musée** (touchable), attribution à citer, conditions, licence. Chaque œuvre porte désormais `details` et `pageUrl` (AIC, Met, Cleveland, NGA, Rijksmuseum, SMK, Europeana, Wikimedia) ; `PARSER_VERSION` = 4.

## Écran noir à l'ouverture d'une œuvre

Cause : le visualiseur, monté par-dessus la vignette dès la fin de l'animation, dessinait un fond OPAQUE tant que son `info.json` n'était pas lu — plusieurs secondes pour une image ordinaire (Met, Cleveland, Wikimedia, NGA), qu'il faut télécharger en entier avant de connaître sa taille.
La vignette disparaissait donc derrière un écran noir, puis l'image apparaissait.

- `IiifZoomViewer` : avec `transparentUntilReady`, le fond est transparent AUSSI pendant l'attente de l'`info.json`.
- `TimelineHost` : la vignette reste telle quelle jusqu'aux premières tuiles (plus de délai de 3 s au-delà duquel elle s'effaçait) ; un témoin d'attente (anneau et « Chargement de l'image… »), visible si l'attente dépasse 300 ms, l'accompagne. En cas d'erreur, la vignette reste avec le message.

## Rev34 : Wikimedia paginé, sondes Getty / MFA Boston / Van Gogh Museum

- **Wikimedia paginé et progressif** : la requête SPARQL (`ORDER BY ?item`) est suivie page par page (300 œuvres, jusqu'à 1 200 par artiste ; avant : 300 au plus, donc Monet ou Sargent tronqués). Les licences ne sont lues que pour les œuvres qu'on garde (dates plausibles), par lots de 30,
  un lot à la fois. **Cache disque** (`wikimedia_cache` : pages SPARQL 24 h, licences) : un passage qui atteint son délai (25 s ; le chargeur coupe à 45 s) rend ce qu'il a — licence « non lue » en attendant — et la source est PARTIELLE, reprise automatiquement
  (3 passages, puis 5 minutes plus tard) jusqu'à ce que tout soit lu ; chaque lot n'est demandé qu'une fois. Un lot en échec (429…) n'enlève rien. `PARSER_VERSION` = 5.
- **Sondes de reconnaissance** (aucune œuvre ajoutée) : **J. Paul Getty Museum** (données Linked Art, SPARQL, recherche du site), **Museum of Fine Arts, Boston** (recherche de la collection), **Van Gogh Museum** (Van Gogh seulement) ; en plus de CER.ES.
  Chaque sonde consigne dans le journal : code, titre, formulaires, champs, images, mentions IIIF / JSON-LD / OAI-PMH / licences, forme d'un JSON, début du corps. Le prochain rapport d'anomalies dira quelle forme prend chaque service, pour écrire les vraies sources.
  Pourquoi pas directement des sources : ces trois musées ne publient aucune donnée sur GitHub (contrairement au NGA) et leurs services n'étaient pas joignables depuis l'environnement de développement ; un lecteur écrit à l'aveugle aurait été faux.

## Rev35 : Met plus doux et partiel, images introuvables, exemples d'écartés (rapport rev33)

Le rapport rev33 (version sans la pagination Wikimedia) montrait : Monet 388, Renoir 520, Morisot 222, Van Gogh 391, Gauguin 685 œuvres ; les appuis répétés sur « Actualiser » ignorés comme prévu ; plus aucun 429 de Wikimedia. Mais :

- **Le Met bloqué par Incapsula après ~70 notices**, à chaque passage : la source rendait « bloquée, aucune copie » alors que 60 à 80 notices venaient d'être lues. Désormais :
  - un blocage **rend ce qui est lu** (état PARTIEL, visible tout de suite) ; il ne lève plus d'erreur que si aucune notice n'a pu être lue ;
  - la cadence est **adaptative** : chaque blocage double l'écart entre deux requêtes (400 ms de base, 2 s au plus) ; 30 réussites de suite le ramènent d'un cran ; une seule requête à la fois ; 60 notices non gardées au plus par passage.
- **Œuvre à image introuvable** : un échantillon dont l'image est DÉFINITIVEMENT absente (404…) est retiré de la source (ex. Rijksmuseum, « Bloemen » de Monet : vignettes 404 ×5) ; un échec de réseau (429, délai) ne retire jamais rien.
- **Journal** : les écartés portent un exemple (« 41 hors domaine public (ex. n°437104 « … », Claude Monet, …, isPublicDomain=false) ») : le Met rend 41 notices de Monet sans image, ce que le journal ne permettait pas d'expliquer.
- Non corrigé : vignette AIC « BitmapFactory returned a null bitmap » (une image Van Gogh que le serveur sert illisible, ×8) ; Wikimedia de Gauguin a dépassé 45 s avant le repli (la pagination de la rev34 lit la première page seule).

## Rev36 : reconnaissance, 2e passe (Getty, MFA Boston, Van Gogh Museum) et reprise du Met (rapport rev35)

Le rapport rev35 a répondu aux sondes :
- **Getty** : `data.getty.edu/museum/collection/` redirige vers `/museum/collection/docs/` ; le point **SPARQL répond** en JSON standard (`head` / `results`, premier sujet `…/collection/group/…`) ; la recherche du site (`getty.edu/art/collection/search`) est une page de coquille qui mentionne IIIF. Une vraie source est donc possible.
- **MFA Boston** : `collections.mfa.org/search/objects/*/{nom}` renvoie une page de résultats HTML (Apache Tapestry, formulaires avec `jsessionid`, 72 Ko) ; pas d'API vue.
- Wikimedia paginé fonctionne (Monet 969 œuvres, Sargent 818, licences lues en plusieurs passages) ; le Met reste le point faible (blocage Incapsula quand plusieurs artistes se chargent à la suite).

2e passe de reconnaissance (la forme exacte des données manque encore pour écrire les sources) :
- **Getty** : lit la documentation (`/docs/`, extrait lisible de 900 caractères), les types RDF les plus fréquents, la première œuvre trouvée par SPARQL (puis sa fiche JSON-LD, suivie) et une recherche par nom.
- **MFA Boston** et **Van Gogh Museum** : lisent la page de résultats puis la PREMIÈRE fiche d'objet qu'elle contient (liens d'objets, images, droits, extrait). Le journal dit « (suite) » pour la fiche suivie.
- **Reprise du Met** : une source LIMITÉE (bloquée avant toute notice) est retentée au bout de **5 minutes** (au lieu d'une heure), comme une lecture partielle.

## Rev37 : nouveau menu (maquette « fond plein écran, bande de portraits »)

Le menu des artistes (`ui/ArtistMenu.kt`) est remplacé selon la maquette :

- **Fond plein écran** : un point d'intérêt d'un tableau majeur de l'artiste sélectionné, en fondu entre deux artistes (`model/ArtistBackdrops.kt`). C'est une DÉCOUPE IIIF (région `x,y,w,h` de la forme de l'écran, calculée selon l'orientation)
  d'une image open access du National Gallery of Art (CC0, `api.nga.gov/iiif`, tailles et identifiants lus dans les données NGA, vérifiés par un test) : Van Gogh *Autoportrait* 1889, Monet *Le Pont japonais*, Renoir *La Fillette à l'arrosoir*,
  Morisot *Les Sœurs*, Gauguin *Danse des petites Bretonnes*, Sargent *Ellen Peabody Endicott*, Sorolla *Isabelita et Thor*. Les centres et zooms de découpe sont des choix éditoriaux, à affiner à l'œil. Légende de crédit en bas.
- **Recherche transparente** (nom, pays, style) et **tri** PÉRIODE (les plus récents en haut, comme la maquette) / PAYS / ALPHABÉTIQUE. Retirés pour l'instant : filtres par pays, zoom sémantique, navigation thématique, tri par affinités.
- **Portrait** : bande verticale de portraits à droite, groupés sous des titres ; fiche en bas (nom, tableau de fond et date, mouvement, Style / Œuvres / Lieu / Période, résumé de l'univers).
- **Paysage** : fiche et recherche à gauche, portraits sur un **arc de cercle** à droite (glisser verticalement pour les faire défiler ; sélectionner un artiste le ramène au centre).
- Conservé : un toucher sélectionne, un autre (ou « Ouvrir la frise ›») ouvre l'univers ; détail des sources dépliable (▼) avec licences, fraîcheur et « Actualiser maintenant » ; **appui long sur la fiche** = rapport d'anomalies.

## Rev39 — menu v2 : demi-cercle unique, fond local sur la règle des tiers

- **Un seul menu** en portrait et en paysage : recherche et tri en haut, fiche au milieu, portraits sur un **demi-cercle centré en bas** (glisser horizontalement ; le portrait central est le plus haut et le plus grand).
- **Un fond pour les 20 artistes**, même sans univers (`assets/backdrops/index.json`) : 14 tableaux open access du National Gallery of Art (CC0) ; pour Cézanne, Munch, Boch, Fattori, Gonzalès et Breslau (absents des données ouvertes du NGA), le tableau le plus connu de Wikidata, servi par Commons (largeur standard 1280).
- **Chargées en local** : `tools/fetch_backdrops.py` télécharge les images au build (étape du workflow) dans `assets/backdrops/{artiste}.jpg`, donc embarquées dans l'APK ; si une image manque, l'appli retombe sur l'adresse distante, puis sur un dégradé.
- **Règle des tiers** (`ThirdsFit`) : le point d'intérêt tombe sur une ligne des tiers en largeur ET en hauteur (regard/visage sur la ligne du tiers supérieur, arbre sur la ligne la plus proche) ; l'image est agrandie juste assez (jusqu'à ×2,2) pour que ce soit possible sans laisser de vide. Testé pour 4 formes d'écran.
- **Point d'intérêt** : estimé à la main dans l'index (visage / arbre) ; au build, OpenCV (Haar) le remplace par le visage détecté s'il y en a un (hors paysages). Sans détection ni arbre connu : centre.

## Rev41 — menu : curseur de détail, tri retiré

- Le panneau de tri sous la recherche est retiré (ordre par période).
- Un **curseur transparent à 3 positions**, au centre sous le demi-cercle : 1 = nom et dates ; 2 = détails actuels ; 3 = contenu étendu (origine, œuvre emblématique, sources détaillées, licences, actualisation) — seulement si l'artiste a un univers (sinon le curseur s'arrête à 2).
