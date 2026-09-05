# Senior Visio — squelette de projet (v2)

## Fonctionnel cible
Un proche (Android ou iOS) ouvre un lien web et appelle la tablette de
Jean. Sur la tablette : alerte plein écran pendant 30s (paramétrable),
avec un bouton "Bloquer l'appel". Si Jean ne fait rien, la visio démarre
automatiquement à la fin du délai.

## Deux volets

### 1. `app/` — Application Android native (tablette de Jean)
```
core/
  CallEngine.kt              → interface moteur d'appel
  WebRtcCallEngine.kt        → implémentation WebRTC (caméra/micro, PeerConnection)
  CallAlertController.kt     → interface du minuteur + blocage
  AdminConfig.kt             → durée du décompte, activation du blocage, PIN admin

signaling/
  CallSignalingClient.kt     → échange offre/réponse SDP + candidats ICE via Firestore

service/
  CallListenerService.kt         → écoute Firestore en continu (foreground service permanent,
                                    fonctionne écran éteint) et déclenche IncomingCallService
  IncomingCallService.kt         → affiche l'écran d'appel entrant (foreground service ponctuel)
  BootReceiver.kt                → relance CallListenerService au démarrage de la tablette et après mise à jour
  TimedCallAlertController.kt    → implémentation du minuteur (CountDownTimer)

ui/
  IncomingCallActivity.kt    → écran plein format : décompte + bouton "Bloquer", puis vidéo
  MainActivity.kt            → écran par défaut hors appel, écoute Firestore pour les appels entrants
```

### 2. `web-caller/` — PWA appelant (iOS + Android, sans installation)
```
index.html         → interface (bouton d'appel, écrans d'état)
call-engine.js      → contrat CallEngine abstrait
webrtc-engine.js     → implémentation WebRTC (RealCallEngine), même schéma de signaling que côté Android
firebase-config.js   → configuration du projet Firebase (à remplir, voir plus bas)
app.js              → câblage UI uniquement
style.css
manifest.json → permet "Ajouter à l'écran d'accueil"
```

## Ce qui est fonctionnel dans ce squelette
- Écran d'alerte Android avec décompte visuel + bouton de blocage opérationnel
- AdminConfig : durée du décompte et activation du blocage ajustables sans recompiler
- PWA appelant avec les 4 états (idle / appel en cours / bloqué / connecté) et transitions câblées
- **Moteur d'appel vidéo WebRTC branché des deux côtés** (`WebRtcCallEngine.kt` / `webrtc-engine.js`),
  avec signaling par Firestore (voir ci-dessous) — architecture bâtie sur une interface `CallEngine`
  commune (logique similaire Kotlin/JS) pour rester swappable vers un SDK managé plus tard si besoin
- **Détection d'appel en arrière-plan** via `CallListenerService` (foreground service permanent,
  fonctionne écran éteint), relancé automatiquement au démarrage de la tablette
- Métriques vidéo temps réel (résolution, fps, paquets perdus) affichées côté PWA appelant pendant
  l'appel, pour objectiver la qualité au lieu de se fier au ressenti (retirées côté tablette : Jean
  n'a pas besoin de voir ce genre d'information technique — voir recommandations ergonomiques ci-dessous)
- **Réglage du volume à distance** : un curseur côté PWA appelant règle en direct le volume avec
  lequel Jean l'entend sur la tablette (`AudioTrack.setVolume`, propre au flux de l'appel, avec une
  transition progressive sur ~1,2s plutôt qu'un saut brutal). Pendant l'appel, le volume système de
  la tablette est fixé au maximum et les boutons physiques de volume sont neutralisés, pour que seul
  ce curseur fasse foi (sinon Jean pourrait couper le son réglé à distance avec les boutons
  physiques, qui agissent en dernier sur le volume final)
- **Un seul écran pour Jean, en trois zones** (`view_home_zones.xml`, `HomeZonesController`) : l'accueil
  et l'écran d'appel n'en sont plus qu'un de son point de vue. Trois zones empilées par-dessus un fond
  qui, lui, change — uni hors appel, remplacé par la vidéo du proche pendant un appel, puis par ses
  photos s'il lance un diaporama. Zone 1 : date, moment de la journée, météo, toujours affichée.
  Zone 2 : ce qui se dit dans la pièce. Zone 3 : ce que dit la personne au bout de l'appel. Jean n'a
  rien à faire ni à toucher — plus de bouton "Voir ce qui se dit", qui lui demandait de savoir qu'une
  fonction existait et de penser à la lancer. L'ordre d'empilement se règle depuis le panneau admin
  (les six ordres possibles proposés tels quels, pour qu'aucun réglage incohérent ne soit atteignable) :
  le bon ordre dépend de la position de la tablette et de la façon dont Jean la regarde. La météo vient
  de wttr.in, qui ne demande aucune clé API — un compte à créer, une clé à saisir sur la tablette et une
  clé qui expire un jour sans prévenir étaient trois occasions de panne pour une information d'appoint ;
  les codes renvoyés formant une liste fermée, chacun des 48 possibles a son pictogramme et son libellé
- **Affichage cadencé** (`PacedCaptionZone`, règle commune aux zones 2 et 3) : la parole va bien plus
  vite que la lecture, a fortiori pour Jean. Chaque phrase reste donc affichée le temps d'être lue
  (130 mots/minute, avec un plancher pour qu'un seul mot soit remarqué), les suivantes attendent leur
  tour dans une file, et la zone ne s'efface que lorsqu'il n'y a plus rien à lire. C'est l'affichage
  qui s'adapte, là où le bandeau qu'il remplace effaçait au rythme de la parole et laissait au proche
  la charge de ralentir de lui-même. Une phrase trop longue pour la zone défile à vitesse plafonnée
  (`CaptionScrollAnimator`, interpolation image par image validée dans `experiment/caption-scroll` :
  60 im/s, quasi aucune image saccadée) plutôt que d'être tronquée, et sa durée d'affichage est
  prolongée d'autant. L'écart qui en résulte est mesuré et remonté au proche
- **Lisibilité adaptée à la pièce** (`ScreenTheme`) : palette claire le jour, sombre le soir et la
  nuit. L'heure ne suffit pas seule — à 15h volets fermés la pièce est sombre — d'où l'appoint du
  capteur de luminosité ambiante, avec deux seuils pour que la palette ne fasse pas d'aller-retour à
  chaque passage de nuage
- **Trois fenêtres côté PWA** : la vidéo (celle qui s'ouvre à la connexion), les réglages, les photos,
  les deux dernières accessibles par un bouton depuis la première. Trois vues plein écran et non trois
  fenêtres de navigateur : le proche appelle depuis son téléphone, où une seconde fenêtre est au mieux
  un onglet qu'on ne retrouve pas — et surtout, quitter celle qui porte la vidéo mettrait l'appel
  WebRTC en arrière-plan
- **La fenêtre vidéo montre ce que Jean voit, à l'identique** : mêmes proportions que sa dalle, mêmes
  trois zones dans le même ordre, même palette, et surtout les mêmes textes au même moment. Tout est
  publié par la tablette (`CallSignalingClient.publishScreenLayout` / `publishScreenState`) plutôt que
  recalculé côté PWA : le proche peut être dans une autre ville (météo différente), l'ordre des zones
  n'existe que côté tablette, et le texte affiché chez Jean est volontairement en retard sur la parole
  — un décalage qui dépend de la longueur de chaque phrase et de la file d'attente, et dont toute
  reconstitution côté PWA ne serait qu'une approximation. L'indicateur associé dit au proche combien de
  secondes de lecture Jean a encore devant lui
- **Mode "même pièce", détecté tout seul** (`SameRoomDetector`) : quand le proche est à côté de Jean,
  la tablette coupe entièrement son son — entendre sa propre voix revenir avec une seconde de décalage
  est plus gênant que de ne rien entendre — tout en continuant d'afficher le texte. La géolocalisation
  ne sait pas trancher ce cas (le GPS ne fonctionne pas en intérieur, et sa précision se compte en
  dizaines de mètres) ; le son, lui, ne traverse pas les murs à haute fréquence. Le téléphone émet donc
  une tonalité à 17,8 kHz pendant la sonnerie — au-delà de ce que Jean peut entendre, dans ce qu'un
  haut-parleur de téléphone sait produire — et la tablette l'écoute, en comparant l'énergie à cette
  fréquence à celle de deux bandes voisines (un bruit large bande monte partout à la fois, une tonalité
  pure non). C'est la seule fenêtre de l'appel où le micro de la tablette est libre. Le mode reste
  cochable et décochable à la main si le matériel ne coopère pas
- **Aperçu de sa propre caméra chez Jean, masqué par défaut** : la petite vignette qui montrait à Jean
  sa propre image (en haut à droite de son écran, portrait comme paysage) est retirée par défaut —
  simplifie l'écran et libère la place que le bouton Raccrocher occupe désormais en paysage à cet
  endroit. Le proche peut la réactiver à tout moment depuis le PWA (case à cocher pendant l'appel).
  Masquée avec `View.INVISIBLE`, pas `View.GONE` : un `SurfaceViewRenderer` en `GONE` (taille nulle,
  jamais posé à l'écran) ne crée jamais sa surface, ce qui perturbait aussi le rendu de la vidéo du
  proche côté Jean (écran noir constaté en test réel) — les deux renderers partagent le même contexte
  EGL (voir `WebRtcCallEngine.attachRenderers`)
- **Message clair si la caméra/le micro du proche est inaccessible** : un refus ou échec d'autorisation
  navigateur (distincte de l'autorisation système d'une appli native comme WhatsApp — les deux peuvent
  diverger sur un même appareil) plantait silencieusement l'appel : aucun message, l'écran restait
  bloqué sur "Connexion à sa tablette…" indéfiniment. Un message explicite invite maintenant à vérifier
  l'autorisation du site (icône 🔒/ⓘ à côté de l'adresse) et l'appel revient proprement à l'écran d'accueil
- **Fin d'appel automatique en cas de coupure réseau anormale** : constaté en test réel, la caméra/le
  micro pouvaient rester engagés indéfiniment côté tablette après une coupure brutale (Wi-Fi perdu,
  navigateur du proche qui plante ou se ferme sans "raccrocher" propre) — jusqu'à un redémarrage de la
  tablette. Deux causes : (1) rien ne surveillait l'état de la connexion ICE ni côté tablette
  (`onIceConnectionChange` vide) ni côté PWA, donc aucun des deux ne détectait que l'appel était mort
  si l'autre camp ne l'annonçait pas explicitement dans Firestore ; (2) le nettoyage côté tablette
  (`WebRtcCallEngine.cleanup()`) n'attrapait qu'un seul type d'erreur autour de l'arrêt de la caméra —
  toute autre erreur y court-circuitait la libération de la fabrique WebRTC et du contexte EGL, qui
  restaient alors en mémoire pour le reste de la vie du processus (`CallListenerService` étant un
  foreground service permanent, ce processus ne redémarre jamais tout seul). Corrigé des deux côtés :
  chaque camp détecte désormais localement une déconnexion ICE persistante (délai de grâce de 8s, une
  brève coupure se résorbant souvent seule) et raccroche proprement de son côté ; chaque étape du
  nettoyage tablette est isolée dans son propre `try/catch` pour que l'échec d'une seule n'empêche
  jamais les suivantes de s'exécuter
- **Réécoute des appels après une mise à jour silencieuse** : `BootReceiver` relance
  `CallListenerService` non seulement au démarrage de la tablette, mais aussi juste après une mise à
  jour de l'appli (`ACTION_MY_PACKAGE_REPLACED`) — une mise à jour silencieuse (celle déclenchée à
  distance par `DeviceStatusReporter`, voir plus bas) tue le processus en cours sans jamais le
  relancer, ce qui aurait sinon laissé le service d'écoute éteint jusqu'à un redémarrage ou une
  ouverture manuelle, avec tout appel tenté entre-temps raté (voir
  `CallSignalingClient.listenForRingingCalls`, qui ignore volontairement tout appel déjà "en sonnerie"
  au moment où l'écoute démarre, pour ne pas re-déclencher un vieil appel oublié)
- **Mémorisation des réglages du proche d'un appel à l'autre** : bouton "💾 Mémoriser ces réglages pour
  la prochaine fois" côté PWA (onglet réglages), qui sauvegarde volume/sous-titres/taille de
  texte/vitesse de défilement/aperçu de soi dans le `localStorage` du navigateur du proche (propre à
  chaque appelant, pas partagé). Au prochain appel, ces valeurs sont réappliquées à l'interface et
  transmises dès la création du document Firestore (`WebRtcCallEngine.startCall(..., initialSettings)`),
  pour que Jean retrouve directement le confort habituel sans que le proche ait à retoucher chaque
  curseur avant que la vidéo ne soit connectée
- **Photo du proche à la réception de l'appel** : le PWA capture une photo (240x240, JPEG compressé)
  depuis la caméra du proche dès le début de l'appel et l'envoie via Firestore ; la tablette l'affiche
  en rond au-dessus du nom pendant le décompte, pour une reconnaissance immédiate. Non bloquant si la
  caméra n'a pas encore de frame disponible (timeout de secours 1,5s) : Jean voit alors juste le nom
- **Décompte à l'écran discret, porté par une barre de progression** : aucun chiffre affiché côté
  tablette, seule une barre se remplit doucement (transition animée, pas de saut) — évite l'effet
  anxiogène d'un gros chiffre qui défile. Un petit son de notification discret (celui déjà configuré
  sur l'appareil) signale le tout début du décompte, sans réveiller toute la maison. Le texte "On vous
  appelle" remplace l'ancien "Appel de [nom]" pendant l'attente (le nom de l'appelant n'est pas encore
  personnalisable dans ce MVP — la photo du proche, elle, permet déjà de le reconnaître)
- **Progression du décompte visible côté PWA** : pendant les 30s d'alerte sur la tablette, le proche
  voit une barre de progression et le temps restant avant connexion automatique, synchronisés via
  l'horodatage serveur Firestore (pas juste un texte statique "ça sonne"). Un bouton "Se connecter
  maintenant" permet aussi de forcer la connexion immédiatement, sans attendre la fin du décompte —
  désactivé tant que l'appel n'est pas prêt côté PWA (caméra, offre créée) : un appui trop tôt tombait
  dans le vide (le document d'appel n'existait pas encore) tout en désactivant le bouton, sans plus
  aucun moyen de relancer la connexion pour cet appel. À l'inverse, "Annuler" reste actif et efficace
  à tout moment pendant cette même préparation : `startCall()` vérifie à chaque étape (caméra, offre,
  écriture Firestore) si l'appel a entre-temps été annulé, pour ne jamais faire sonner Jean pour un
  appel déjà abandonné — sans ce garde-fou, l'exécution en cours continuait en arrière-plan après un
  "Annuler" et le déclenchait quand même
- **Miroir de transcription côté PWA** : pendant l'appel, le proche voit exactement le texte que Jean
  reçoit (même source que ce qui est envoyé), avec les 2-3 dernières phrases finalisées en historique.
  Les segments à faible confiance de reconnaissance sont colorés (orange/jaune) pour repérer les
  passages probablement mal transcrits
- **Sous-titres de la pièce, indépendants de tout appel** : un unique gros bouton "🔤 Sous-titres" sur
  l'écran d'accueil active la reconnaissance vocale locale de la tablette (`SpeechRecognizer` d'Android,
  pas Vosk — abandonné, moins précis que le moteur des navigateurs) pour sous-titrer en direct les
  conversations dans la pièce avec Jean, pour un besoin distinct des sous-titres d'appel vidéo :
  personne côté "proche" ici, donc la reconnaissance doit obligatoirement tourner sur l'appareil
  lui-même. Se coupe automatiquement si un appel entrant prend l'écran (conflit de micro avec la vidéo)
  et reprend seule au retour sur l'écran d'accueil, sans que Jean ait à rappuyer sur le bouton. Le
  service de reconnaissance d'Android joue un bip audible à chaque début d'écoute (comportement du
  système, pas de l'appli) : un court silence entre deux phrases suffisait à faire expirer la
  reconnaissance (`ERROR_SPEECH_TIMEOUT`) et relancer aussitôt, donnant des bips répétitifs sans
  jamais laisser le temps de capter une phrase entière — corrigé en allongeant la tolérance au silence
  (`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`) et en abandonnant proprement (message clair,
  plutôt qu'une boucle de bips) après plusieurs échecs consécutifs
- **Détection de silence côté PWA** : si la reconnaissance vocale ne capte plus rien pendant 5s
  pendant que le micro écoute, un indicateur discret ("Aucun son détecté") prévient le proche que rien
  n'est transmis (micro coupé, téléphone trop loin, etc.)

## Configuration Firebase (obligatoire pour que les appels fonctionnent)
Le signaling (échange de l'offre/réponse SDP et des candidats ICE entre la
tablette et le PWA appelant) passe par **Firestore**, gratuit sur le plan
Spark pour cet usage. Il faut créer un projet une seule fois :

1. Aller sur [console.firebase.google.com](https://console.firebase.google.com), créer un projet (gratuit).
2. Dans le projet, activer **Firestore Database** (mode production, avec les règles ci-dessous).
3. Ajouter une **app Android** : package `com.seniorvisio` → télécharger `google-services.json`
   et le placer à la racine de `app/` (à côté de `app/build.gradle`).
4. Ajouter une **app Web** (icône `</>`) → copier les valeurs de config dans `web-caller/firebase-config.js`.
5. Règles de sécurité Firestore (Firestore Database > Règles), volontairement simples pour un
   usage familial à un seul foyer :
   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /calls/{callId} {
         allow read, write: if true;
         match /{subcollection}/{docId} {
           allow read, write: if true;
         }
       }
     }
   }
   ```
   (Ouvert en lecture/écriture sans authentification — acceptable ici car les ID de document sont
   aléatoires et non devinables, mais à durcir avec Firebase Auth si le projet grandit.)

Aucune de ces valeurs n'est secrète (clés client Firebase, publiques par conception). Tant que
`google-services.json` n'est pas fourni, le module Android compile quand même : le plugin Firebase
ne s'active que si le fichier est présent (voir `app/build.gradle`), et l'app détecte l'absence de
config pour éviter de planter.

## Ce qu'il reste à faire (ordre logique)
1. Écran de réglages admin (PIN + ajustement de `countdownSeconds`)
2. Tester un appel complet en conditions réelles (deux appareils), ajuster l'ergonomie
3. Éventuellement ajouter un serveur TURN (ex. Open Relay gratuit, ou coturn auto-hébergé) si
   certains réseaux/box échouent à se connecter avec les seuls serveurs STUN publics
4. Déployer la Cloud Function de réveil push (voir note ci-dessous) : nécessite de passer le projet
   Firebase au plan payant "Blaze" et d'ajouter le secret GitHub `FIREBASE_SERVICE_ACCOUNT`

### Note sur la fiabilité du réveil en arrière-plan
La détection d'appel entrant tourne dans `CallListenerService`, un foreground service permanent
(notification discrète "En attente d'appel") qui maintient une écoute Firestore active même écran
éteint — contrairement à une simple Activity, un foreground service est exempté de l'essentiel des
restrictions Android (Doze / mise en veille). C'est gratuit et suffisant dans l'immense majorité des
cas pour une tablette qui reste allumée/branchée en continu.

Limite connue : certains constructeurs (Xiaomi/MIUI, Huawei, Oppo notamment) tuent parfois les
foreground services malgré tout, sauf si l'app est explicitement exemptée d'optimisation batterie
(demandé automatiquement au premier lancement) et/ou autorisée manuellement à "démarrer
automatiquement" dans les réglages de batterie du fabricant. Confirmé en déploiement réel : sans
notification push, un appel entrant ne réveillait la tablette de façon fiable que si l'écran restait
allumé en permanence ("always on display") — dès que l'écran s'éteignait pour de bon, la connexion
Firestore permanente de `CallListenerService` finissait par être suspendue (Doze), avant même que le
mécanisme de réveil plein écran n'ait sa chance de s'exécuter.

**Réveil push (FCM), implémenté** : `functions/index.js` (Cloud Function déclenchée à la création
d'un document `calls/{callId}`) envoie un message FCM en priorité haute à la tablette dès qu'un appel
apparaît — c'est le seul mécanisme qu'Android garantit de faire percer la mise en veille profonde,
sans dépendre d'une connexion réseau qui reste ouverte. Côté Android, `SeniorVisioMessagingService`
reçoit ce message et démarre directement `IncomingCallService`, exactement comme le fait
`CallListenerService` — les deux voies coexistent (le push est la voie fiable, l'écoute Firestore
permanente reste une redondance qui ne coûte rien). Le token FCM de la tablette est enregistré dans
Firestore (`devices/jean_tablet`) au démarrage de `MainActivity` et à chaque renouvellement du token.
Le message push ne contient volontairement que l'identifiant d'appel et le nom de l'appelant (FCM
limite chaque message à 4 Ko, largement dépassé par une photo encodée en base64) ; le reste de
l'écran d'appel suit son circuit habituel une fois l'appel décroché.

**Mise en service, deux étapes manuelles restantes** (pas faisables depuis ce dépôt) :
1. Passer le projet Firebase `seniorvisio` au plan payant "Blaze" dans la console Firebase — requis
   par Google pour toute Cloud Function, le volume réel de cette appli restera dans le quota gratuit
   (Blaze n'implique pas d'être facturé, juste d'avoir une carte enregistrée).
2. Générer une clé de compte de service (console Firebase → ⚙️ Paramètres du projet → Comptes de
   service → Générer une nouvelle clé privée) et l'ajouter comme secret GitHub du dépôt sous le nom
   `FIREBASE_SERVICE_ACCOUNT` (coller le contenu JSON tel quel). Une fois ce secret présent, le
   workflow `.github/workflows/deploy-functions.yml` déploie automatiquement la fonction à chaque
   modification de `functions/`.

Tant que ces deux étapes ne sont pas faites, la Cloud Function n'est pas déployée : les appels
continuent de fonctionner via la voie existante (`CallListenerService`), sans le bénéfice du réveil
push.

En complément de l'exemption d'optimisation batterie ci-dessus, `IncomingCallService` acquiert aussi
un `WakeLock` partiel (toujours avec un timeout de sécurité de 10s, pour ne jamais fuiter en cas de
crash) au moment précis où l'appel entrant est confirmé, avant même l'affichage de l'écran d'alerte —
utile en particulier sur les surcouches constructeur (Samsung notamment) qui imposent parfois des
restrictions de veille supplémentaires au-delà du Doze standard d'Android. `IncomingCallActivity`
force ensuite l'écran allumé (`FLAG_KEEP_SCREEN_ON`) pendant toute la durée du décompte et de l'appel,
retiré explicitement dès la fin (raccroché, blocage, ou appel terminé côté proche) pour ne jamais
garder l'écran forcé allumé hors appel. Le délai réel entre la réception du signal et l'affichage de
l'écran est tracé dans les logs (`Log.i`, tag `IncomingCallActivity`), pour pouvoir diagnostiquer une
régression après une future mise à jour Android sur le parc de tablettes.

## Labo de comparaison de transcription (étude, sans lien avec l'usage normal)

Accessible par un appui long sur le bouton vert "🔤 Sous-titres" de l'écran d'accueil
(`TranscriptionLabActivity.kt`). Objectif : comparer la qualité de plusieurs moteurs de
reconnaissance vocale sur un seul et même enregistrement, pour que la comparaison porte sur le
moteur et non sur la variabilité naturelle de deux prises différentes de la même phrase.

- Enregistre une phrase test une fois (`MediaRecorder`, fichier `.m4a`).
- Teste chaque moteur de reconnaissance vocale déclaré sur la tablette (natif Samsung, Google...),
  détectés dynamiquement via `PackageManager.queryIntentServices`. Limite technique assumée : l'API
  `SpeechRecognizer` d'Android n'accepte que le micro en direct, jamais un fichier — l'enregistrement
  est donc rejoué à travers le haut-parleur pendant que chaque moteur écoute à son tour (acoustique de
  la pièce non maîtrisée, mais phrase strictement identique pour chacun).
- Envoie ensuite le même enregistrement à **AssemblyAI** (clé API à renseigner dans Réglages admin —
  appui long sur le numéro de version + PIN), avec diarisation activée (`speaker_labels: true`) :
  identifie des locuteurs distincts ("Locuteur A/B...") dans l'enregistrement.
- Résultats affichés côte à côte (texte, confiance si disponible, tours de parole par locuteur pour
  AssemblyAI) et exportables en JSON (bouton "Copier le JSON").

**Sur l'objectif final** (filtrer la voix de Jean de celle des autres personnes présentes) : la
diarisation d'AssemblyAI distingue des locuteurs différents dans un enregistrement donné, mais ne
"reconnaît" pas Jean spécifiquement d'une session à l'autre — un repérage manuel reste nécessaire
("c'est Jean qui parle ici"), pas encore une empreinte vocale personnelle apprise une fois pour
toutes. Une vraie reconnaissance de locuteur persistante serait une étape ultérieure distincte,
nécessitant un enrôlement explicite de la voix de Jean.

## Kiosque et déploiement : Device Owner intégré, plus de MDM tiers

Le déploiement via Headwind MDM (essayé initialement) a été abandonné : disproportionné pour le
besoin réel (empêcher de quitter l'appli, forcer une mise à jour à distance, connaître l'état de la
tablette), et un blocage Knox est survenu après suppression d'un appareil côté console sans
désenrôlement propre (la tablette restait verrouillée en Device Owner, liée à un enregistrement qui
n'existait plus côté serveur, sans qu'aucune combinaison de touches ne permette d'y accéder à
nouveau). Remplacé par un mécanisme intégré à l'appli, sans compte ni service tiers :

- **Kiosque** (`KioskManager.kt`) : Senior Visio se déclare lui-même en mode kiosque natif Android
  (`Activity.startLockTask()`) si — et seulement si — il est provisionné comme **Device Owner** de la
  tablette (`SeniorVisioDeviceAdminReceiver.kt`). Appelé au démarrage de `MainActivity` et
  `IncomingCallActivity` ; ne fait rien tant que l'appli n'est pas provisionnée (développement, test).
- **Mise à jour à distance** (`DeviceStatusReporter.kt`) : `CallListenerService` (déjà un foreground
  service permanent) écoute en continu un champ Firestore (`devices/jean_tablet.requestedVersion` +
  `.requestedApkUrl`) ; dès qu'une version différente de celle installée est demandée, télécharge l'APK
  et l'installe silencieusement via `PackageInstaller` — seul un Device Owner peut le faire sans
  confirmation manuelle affichée sur l'écran de la tablette. Le résultat (succès/échec) est remonté
  dans Firestore par `UpdateStatusReceiver.kt`. **Attention** : "silencieux" dispense Jean de confirmer,
  pas Android de vérifier la signature — une mise à jour vers un APK signé différemment échoue comme
  n'importe quel `adb install -r` (voir la clé de signature versionnée, section CI ci-dessous). Ces
  champs Firestore sont désormais renseignés automatiquement par `build-debug-apk.yml` à la fin de
  chaque build réussi sur `AssemblyAI` (`scripts/request_remote_update.js`) : plus rien à faire à la
  main pour qu'une tablette déjà enrôlée récupère la dernière version en quelques minutes.

  **Pause pendant une mise au point sur tablette de test** : ajouter le champ booléen
  `devices/jean_tablet.autoUpdateEnabled` à `false` (console Firebase → Firestore Database) suspend
  uniquement cette dernière étape — les builds continuent d'être générés et publiés en release GitHub
  normalement, seule la demande poussée vers la tablette réelle de Jean est sautée (visible dans les logs
  du build : "Mise à jour à distance automatique désactivée"). Remettre le champ à `true` (ou le
  supprimer, absence traitée comme activé) reprend l'envoi automatique dès le prochain build.
- **Statut** : le même service publie toutes les 5 minutes un signe de vie dans Firestore (niveau de
  batterie, version installée `BuildConfig.BUILD_REV`, horodatage) — reste à construire une petite page
  de suivi (sur le modèle de `web-caller/`) pour le consulter facilement ; en attendant, consultable
  directement dans la console Firebase (Firestore Database → `devices/jean_tablet`).

- **Configuration Wi-Fi à distance de tout accès aux Réglages système**
  (`WifiConfigurator.kt`) : écran de réglages admin (`AdminSettingsActivity`, accessible par un appui
  long sur le numéro de version en haut à gauche + PIN admin) avec un champ SSID/mot de passe — utile en
  particulier à l'arrivée dans un nouveau lieu (ex. maison de retraite), où le réseau diffère de celui
  utilisé pendant les tests. Repose sur l'ancienne API `WifiManager` (dépréciée pour les apps
  classiques depuis Android 10), qu'Android continue explicitement d'autoriser pour les apps Device
  Owner — ne fonctionne donc, comme le reste, que sur la tablette réellement déployée.

**Provisionnement (une seule fois, après reset d'usine de la tablette)** : le Device Owner ne peut être
défini que sur un appareil sans aucun compte configuré. Au premier démarrage, avant l'assistant de
configuration Android, tapoter 6 fois sur l'écran de bienvenue déclenche le scanner QR. Ce QR (charge
utile de provisioning Android standard pointant vers `SeniorVisioDeviceAdminReceiver` et l'URL de
téléchargement de l'APK) se génère via le workflow GitHub Actions "Generate Device Owner provisioning QR
code" (`scripts/generate_provisioning_qr.py`), à lancer une fois la tablette prête à être réenrôlée —
l'image PNG produite (artifact du run) est à afficher sur un autre écran et à scanner avec la tablette.

Le SSID Wi-Fi du workflow est optionnel : le lieu de déploiement final (ex. domicile de Jean, maison de
retraite) n'est pas toujours connu à l'avance. Laissé vide, le QR ne contient aucune information Wi-Fi —
Android affiche alors son propre écran de sélection de réseau pendant le provisioning, où le SSID et le
mot de passe se saisissent directement sur la tablette, sur place. Une fois l'app installée et le Device
Owner actif, le réseau peut aussi être changé à tout moment sans repasser par un reset d'usine, via
l'écran de réglages admin intégré à l'app (voir point Wi-Fi ci-dessus).

**Provisionnement alternatif par adb (recommandé en cas d'échec du QR)** : le téléchargement de l'APK
pendant le provisioning par QR passe par le gestionnaire de téléchargement système d'Android, exécuté
depuis l'écran de provisioning lui-même — un simple souci réseau à cet instant précis (portail captif
nécessitant une page web à valider, comme certains Wi-Fi d'hôtel/résidence ; SSID/mot de passe erronés ;
coupure momentanée) suffit à le faire échouer, sans grand-chose à diagnostiquer une fois le tapotement/QR
déjà passés. La méthode par adb évite entièrement cette étape réseau critique : l'app est installée à la
main, la connexion Wi-Fi se fait ensuite normalement (depuis l'app une fois Device Owner, ou depuis les
Réglages système si l'assistant de configuration le permet à ce stade), aucun téléchargement à faire
réussir en plein milieu du provisioning.

1. Réinitialiser la tablette d'usine, passer l'assistant de configuration **sans ajouter aucun compte**
   (Google ou autre) — condition stricte : Device Owner ne peut être défini que sur un appareil sans
   aucun compte configuré, adb ou QR pareil.
2. Activer les options développeur (Réglages → À propos de la tablette → tapoter 7 fois sur le numéro de
   build) puis le débogage USB (Réglages → Options pour les développeurs).
3. Brancher la tablette en USB à un ordinateur avec adb installé, autoriser l'empreinte RSA affichée sur
   la tablette.
4. Installer l'APK et définir Senior Visio comme Device Owner :
   ```
   adb devices                                       # confirme que la tablette est bien détectée
   adb install -r seniorvisio-revNN.apk               # APK récupéré depuis la release GitHub du build voulu
   adb shell dpm set-device-owner com.seniorvisio/.admin.SeniorVisioDeviceAdminReceiver
   ```
   Si `dpm set-device-owner` refuse en signalant un compte existant, un profil géré préexistant, ou un
   autre Device Policy Controller déjà actif, repartir d'un reset d'usine propre — aucune de ces
   conditions ne se contourne autrement.
5. Lancer l'app une fois — `KioskManager`/`DeviceStatusReporter` détectent `isDeviceOwnerApp()` à ce
   moment-là (aucune étape de provisioning spécifique requise côté code, `SeniorVisioDeviceAdminReceiver`
   ne fait rien de plus qu'un receiver minimal) : kiosque et mise à jour à distance s'activent
   immédiatement, sans dépendre du chemin QR.
6. Configurer le Wi-Fi définitif via l'écran de réglages admin intégré (voir point Wi-Fi ci-dessus).

**Cas des Wi-Fi de résidence à portail captif (ex. Wifirst)** : confirmé en déploiement réel — certains
opérateurs de Wi-Fi de résidence senior (Wifirst notamment) laissent le réseau radio entièrement ouvert
(pas de mot de passe WPA) mais bloquent tout accès Internet réel tant qu'un code personnel fourni au
résident n'est pas saisi sur une page web (portail captif). C'est la cause la plus probable d'un blocage
du téléchargement d'APK pendant un provisioning QR (voir ci-dessus) : aucun navigateur n'est disponible à
cette étape pour valider le portail. L'écran Wi-Fi admin (`WifiConfigurator.kt`) gère ce cas directement :
laisser le champ mot de passe vide pour un tel réseau, la connexion se fait en réseau ouvert, puis
`checkInternetReachable()` détecte l'absence d'accès Internet réel malgré l'association Wi-Fi réussie et
affiche un WebView intégré pour saisir le code et valider le portail sans quitter l'app (un navigateur
système externe ou la notification "Se connecter au réseau" d'Android ne sont pas accessibles une fois en
mode kiosque, voir `KioskManager`).

**Test réel sur Android 16 (One UI 8.5)** : l'écran d'appel ne s'affichait jamais si l'application
n'était plus au premier plan, y compris avec le service actif (notification visible) et l'app déjà
exclue des listes de mise en veille Samsung — donc indépendant du réglage constructeur ci-dessus.
Cause identifiée : depuis Android 10, un service ne peut plus démarrer une Activity de façon fiable
depuis l'arrière-plan (restriction renforcée à chaque version) ; l'appli utilisait jusqu'ici un simple
`startActivity()` depuis `IncomingCallService`. Remplacé par le mécanisme officiellement prévu par
Android pour les applis d'appel : une notification "plein écran" (`setFullScreenIntent`), seule
autorisée à afficher une Activity par-dessus l'écran verrouillé/éteint depuis un contexte non visible
(permission `USE_FULL_SCREEN_INTENT`, déjà déclarée dans le manifest). À partir d'Android 14, cette
permission n'est plus accordée automatiquement pour toutes les catégories d'app : `MainActivity`
demande maintenant explicitement son autorisation au premier lancement (sinon Android rétrograde
silencieusement la notification plein écran en simple notification discrète, sans réveil).

## Pourquoi cette architecture
Le moteur d'appel est isolé derrière `CallEngine` des deux côtés, pour rester remplaçable. Le PWA
appelant évite toute compilation iOS (pas de Mac nécessaire) : Safari supporte WebRTC nativement
depuis iOS 11. Le décompte et le blocage sont strictement séparés du moteur d'appel, donc testables
indépendamment. Firestore sert uniquement de "boîte aux lettres" de mise en relation (signaling) ;
une fois connectés, le flux audio/vidéo passe en direct entre les deux appareils, pas par Firebase.

## Prochaine étape recommandée
Créer le projet Firebase (voir section dédiée), fournir la configuration, recompiler l'APK, et
tester un appel réel entre le PWA (sur un navigateur/téléphone) et la tablette Android.
