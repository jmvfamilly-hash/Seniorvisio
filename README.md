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
  BootReceiver.kt                → relance CallListenerService au démarrage de la tablette
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
- **Mode "sous-titres"** en surimpression façon sous-titrage TV : les paroles du proche, transcrites
  en direct par la reconnaissance vocale du navigateur, s'affichent dans un bandeau semi-opaque en
  bas de l'écran, par-dessus la vidéo qui reste plein écran (remplace l'ancien écran divisé 80/20).
  Activé/désactivé **à distance depuis le PWA** (case à cocher côté proche — pas de bouton sur la
  tablette), avec un fondu à l'apparition/disparition pour éviter tout changement brutal côté Jean ;
  ne fonctionne que si le proche appelle depuis un navigateur supportant la reconnaissance vocale
  (Chrome Android/desktop) — **pas Safari/iOS**, qui ne l'implémente pas ; l'appel vidéo lui-même
  n'est pas affecté, seuls les sous-titres restent vides. Un second curseur côté PWA règle aussi la
  taille de ce texte (24 à 100sp), avec un fondu enchaîné au changement plutôt qu'un redimensionnement brut.
  Aucun texte n'est perdu : une phrase trop longue pour l'espace visible défile automatiquement vers
  le bas au lieu d'être tronquée avec des "…", en suivant la parole en continu façon sous-titrage TV
  en direct ("roll-up", CEA-608) — le défilement avance par petits crans au fil des mots plutôt que de
  repartir du haut à chaque mise à jour (ce qui rendait le défilement inutilisable en parole continue :
  l'animation n'avait jamais le temps d'aller au bout avant d'être relancée depuis le début). Il ne
  revient en haut que lorsqu'une phrase réellement nouvelle démarre. Le défilement suit la position
  cible par interpolation image par image (`Choreographer`, remplace l'ancien `smoothScrollTo` natif),
  réglage validé dans le labo de défilement (`experiment/caption-scroll`, `web-caller/caption-scroll-lab.html`)
  sur un enregistrement vocal réel : 60 im/s en moyenne, quasi aucune image saccadée. Le PWA envoie aussi le
  texte transcrit plus souvent (300ms au lieu de 500ms, par petits incréments), et la tablette ignore
  les écritures Firestore qui ne changent pas le texte — au total, plusieurs optimisations pour un
  défilement plus fluide. En paysage comme en portrait, le bandeau reste en surimpression basse
  par-dessus la vidéo plein écran (une première version mettait la vidéo à droite et les sous-titres
  dans une colonne à gauche en paysage — jugée plus perturbante à l'usage, abandonnée)
- **Vitesse de lecture plafonnée, avec retour de retard côté PWA** : des tests réels ont montré que si
  le proche parle avec un débit rapide, Jean n'a pas le temps de lire avant que le texte suivant
  arrive. Le défilement avance donc à une vitesse maximale constante et paramétrable (curseur "Vitesse
  de défilement" côté PWA, en dp/s) plutôt que proportionnelle au texte en attente — l'ancien réglage
  accélérait d'autant plus que le proche parlait vite, l'inverse de l'effet recherché. Le texte reçu
  peut ainsi s'accumuler en attente le temps que Jean rattrape son retard, et ce retard (en secondes)
  est signalé en continu au PWA ("Jean a environ X,Xs de retard sur ta voix, ralentis un peu"), pour un
  repère précis plutôt qu'un simple indicateur "ça déborde" ou non
- **Onglet "visio" côté PWA, séparé des réglages** : dès la connexion, le proche arrive sur une vue
  quasi plein écran (juste la vidéo, le bouton Raccrocher et un bouton "⚙️ Réglages") plutôt que
  l'écran complet de contrôles — pensée pour l'usage pendant la conversation elle-même. Un bouton
  permet de naviguer vers l'écran de réglages complet (volume, sous-titres, miroir de transcription…)
  et d'en revenir. Quand les sous-titres sont activés, cet onglet affiche aussi un bandeau en
  surimpression façon Android — mais avec le texte tel qu'il apparaît **réellement** chez Jean à cet
  instant (retardé du retard de lecture mesuré, voir ci-dessus), pas ce que le proche vient de dire en
  temps réel comme le fait le miroir de transcription de l'écran de réglages
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
  aucun moyen de relancer la connexion pour cet appel
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
  et reprend seule au retour sur l'écran d'accueil, sans que Jean ait à rappuyer sur le bouton
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
4. Éventuellement passer au réveil par notification push (FCM) si `CallListenerService` s'avère
   pas assez fiable sur certains appareils (voir note ci-dessous)

### Note sur la fiabilité du réveil en arrière-plan
La détection d'appel entrant tourne dans `CallListenerService`, un foreground service permanent
(notification discrète "En attente d'appel") qui maintient une écoute Firestore active même écran
éteint — contrairement à une simple Activity, un foreground service est exempté de l'essentiel des
restrictions Android (Doze / mise en veille). C'est gratuit et suffisant dans l'immense majorité des
cas pour une tablette qui reste allumée/branchée en continu.

Limite connue : certains constructeurs (Xiaomi/MIUI, Huawei, Oppo notamment) tuent parfois les
foreground services malgré tout, sauf si l'app est explicitement exemptée d'optimisation batterie
(demandé automatiquement au premier lancement) et/ou autorisée manuellement à "démarrer
automatiquement" dans les réglages de batterie du fabricant. Si des appels manqués persistent malgré
ça sur la tablette utilisée, la solution la plus robuste serait de migrer vers un réveil par
notification push (Firebase Cloud Messaging) déclenché par une Cloud Function côté serveur — mais
cela nécessite de passer le projet Firebase au plan payant "Blaze" (le volume réel resterait dans le
quota gratuit, mais Blaze exige une carte bancaire enregistrée), donc volontairement pas fait par
défaut ici pour rester sur du 100% gratuit sans engagement.

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

Reste à configurer côté console Headwind MDM (une fois le compte créé, voir plan de déploiement) :
timeout de mise en veille écran (30-60s), et surtout la liste blanche d'optimisation batterie poussée
par policy plutôt que dépendre uniquement de la demande faite au premier lancement — c'est le réglage
qui se réinitialise le plus souvent après une mise à jour Android ou un reset du device owner, donc à
vérifier après chaque redémarrage/mise à jour de la tablette de test avant promotion en production.

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
