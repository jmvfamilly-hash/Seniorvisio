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
- Métriques vidéo temps réel (résolution, fps, paquets perdus) affichées des deux côtés pendant
  l'appel, pour objectiver la qualité au lieu de se fier au ressenti
- **Réglage du volume à distance** : un curseur côté PWA appelant règle en direct le volume avec
  lequel Jean l'entend sur la tablette (`AudioTrack.setVolume`, propre au flux de l'appel). Pendant
  l'appel, le volume système de la tablette est fixé au maximum et les boutons physiques de volume
  sont neutralisés, pour que seul ce curseur fasse foi (sinon Jean pourrait couper le son réglé à
  distance avec les boutons physiques, qui agissent en dernier sur le volume final)
- **Mode "sous-titres géants"** : les paroles du proche, transcrites en direct par la reconnaissance
  vocale du navigateur, s'affichent en très grand sur 80% de l'écran de la tablette, sa vidéo réduite
  dans les 20% restants. Activé/désactivé **à distance depuis le PWA** (case à cocher côté proche —
  pas de bouton sur la tablette) ; ne fonctionne que si le proche appelle depuis un navigateur
  supportant la reconnaissance vocale (Chrome Android/desktop) — **pas Safari/iOS**, qui ne
  l'implémente pas ; l'appel vidéo lui-même n'est pas affecté, seuls les sous-titres restent vides
- **Progression du décompte visible côté PWA** : pendant les 30s d'alerte sur la tablette, le proche
  voit une barre de progression et le temps restant avant connexion automatique, synchronisés via
  l'horodatage serveur Firestore (pas juste un texte statique "ça sonne")

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
