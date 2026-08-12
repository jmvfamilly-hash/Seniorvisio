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
  IncomingCallService.kt         → réveille l'app à l'appel entrant (foreground service)
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
1. Créer le projet Firebase et fournir la configuration (voir section ci-dessus)
2. Récepteur de notification (push) côté tablette pour réveiller l'app même fermée — aujourd'hui
   la détection d'appel entrant se fait par écoute Firestore en direct tant que l'app est ouverte,
   suffisant pour une tablette qui reste allumée sur l'écran d'accueil
3. Écran de réglages admin (PIN + ajustement de `countdownSeconds`)
4. Héberger le PWA (n'importe quel hébergement statique : GitHub Pages, Netlify, Vercel — gratuit)
5. Tester un appel complet en conditions réelles (deux appareils), ajuster l'ergonomie
6. Éventuellement ajouter un serveur TURN (ex. Open Relay gratuit, ou coturn auto-hébergé) si
   certains réseaux/box échouent à se connecter avec les seuls serveurs STUN publics

## Pourquoi cette architecture
Le moteur d'appel est isolé derrière `CallEngine` des deux côtés, pour rester remplaçable. Le PWA
appelant évite toute compilation iOS (pas de Mac nécessaire) : Safari supporte WebRTC nativement
depuis iOS 11. Le décompte et le blocage sont strictement séparés du moteur d'appel, donc testables
indépendamment. Firestore sert uniquement de "boîte aux lettres" de mise en relation (signaling) ;
une fois connectés, le flux audio/vidéo passe en direct entre les deux appareils, pas par Firebase.

## Prochaine étape recommandée
Créer le projet Firebase (voir section dédiée), fournir la configuration, recompiler l'APK, et
tester un appel réel entre le PWA (sur un navigateur/téléphone) et la tablette Android.
