# Senior Visio — squelette de projet (v2)

> **Branche expérimentale (`experiment/vosk-on-device-captions`)** : cette
> branche déplace la reconnaissance vocale des sous-titres du navigateur du
> proche vers la tablette elle-même (Vosk, gratuit, 100% hors-ligne une fois
> le modèle téléchargé). Objectif : ne plus dépendre du navigateur de
> l'appelant (règle la limitation Safari/iOS) et gagner en stabilité. C'est
> un changement invasif (nouvelle dépendance native, traitement audio bas
> niveau non testable sans tablette physique) : si ça ne s'avère pas fiable
> en usage réel, il suffit de revenir à la branche précédente sans rien
> perdre du reste. Voir la section dédiée plus bas pour le détail technique.

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
  le bas (à un rythme calculé sur le nombre de mots) au lieu d'être tronquée avec des "…", et le PWA
  affiche un indicateur discret ("Jean n'a pas fini de lire") tant que ce débordement dure, pour que
  le proche puisse ralentir son débit
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
  maintenant" permet aussi de forcer la connexion immédiatement, sans attendre la fin du décompte
- **Miroir de transcription côté PWA** : pendant l'appel, le proche voit exactement le texte que Jean
  reçoit (même source que ce qui est envoyé), avec les 2-3 dernières phrases finalisées en historique.
  Les segments à faible confiance de reconnaissance sont colorés (orange/jaune) pour repérer les
  passages probablement mal transcrits
- **Détection de silence côté PWA** : si la reconnaissance vocale ne capte plus rien pendant 5s
  pendant que le micro écoute, un indicateur discret ("Aucun son détecté") prévient le proche que rien
  n'est transmis (micro coupé, téléphone trop loin, etc.)

## Reconnaissance vocale embarquée sur la tablette (branche expérimentale)
`core/VoskModelProvider.kt` et `core/VoskCaptionRecognizer.kt` ajoutent une reconnaissance vocale
locale avec [Vosk](https://alphacephei.com/vosk/) (gratuit, open source, 100% hors-ligne) :

- `VoskModelProvider` télécharge (une seule fois, ~45 Mo, modèle français "small") puis charge le
  modèle en mémoire, démarré dès `CallListenerService.onCreate()` pour être prêt avant le premier appel.
- `VoskCaptionRecognizer` s'attache directement au flux audio du proche reçu par WebRTC
  (`AudioTrack.addSink`, voir `WebRtcCallEngine.onTrack`) et transcrit en direct, sans jamais passer
  par le navigateur de l'appelant.
- **Priorité au texte du PWA** : le petit modèle Vosk "small" embarqué est nettement moins précis que
  la reconnaissance vocale du navigateur (constaté en test réel — des mots corrects côté PWA arrivaient
  déformés côté tablette). `WebRtcCallEngine.listenForCaptions` affiche donc en priorité le texte relayé
  par Firestore (PWA → tablette) dès qu'il en arrive au moins une fois pendant l'appel ; Vosk ne sert
  que de filet de secours (début d'appel avant le premier texte du PWA, et surtout navigateurs sans
  reconnaissance vocale comme Safari/iOS, qui n'enverront jamais rien par Firestore).
- **Miroir exact côté PWA** : quelle que soit la source retenue par la tablette (texte du PWA ou
  filet de secours Vosk), `IncomingCallActivity` renvoie le texte réellement affiché à l'écran vers
  Firestore (`tabletDisplayedCaption`, voir `WebRtcCallEngine.signalDisplayedCaption`). Le PWA
  l'affiche dans un second encart ("Ce que Jean voit en ce moment") distinct du miroir de sa propre
  transcription ("Ce que tu dis") — le proche voit ainsi toujours exactement ce que Jean a sous les
  yeux, sans avoir à deviner si Vosk a pris le relais.

**Non vérifié en conditions réelles au-delà de ce premier test** (pas de tablette physique disponible
pour un test approfondi) : l'usage CPU/batterie pendant un appel, et la justesse de Vosk en filet de
secours (cas Safari/iOS) restent à valider. En cas de problème, revenir à
`claude/android-apk-debug-build-d3zwgj` ne perd aucun autre changement : cette branche n'a touché que
les sous-titres.

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

## Pourquoi cette architecture
Le moteur d'appel est isolé derrière `CallEngine` des deux côtés, pour rester remplaçable. Le PWA
appelant évite toute compilation iOS (pas de Mac nécessaire) : Safari supporte WebRTC nativement
depuis iOS 11. Le décompte et le blocage sont strictement séparés du moteur d'appel, donc testables
indépendamment. Firestore sert uniquement de "boîte aux lettres" de mise en relation (signaling) ;
une fois connectés, le flux audio/vidéo passe en direct entre les deux appareils, pas par Firebase.

## Prochaine étape recommandée
Créer le projet Firebase (voir section dédiée), fournir la configuration, recompiler l'APK, et
tester un appel réel entre le PWA (sur un navigateur/téléphone) et la tablette Android.
