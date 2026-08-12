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
  CallEngine.kt              → interface moteur d'appel (à brancher : Stream/Agora/Twilio)
  CallAlertController.kt     → interface du minuteur + blocage
  AdminConfig.kt             → durée du décompte, activation du blocage, PIN admin

service/
  IncomingCallService.kt         → réveille l'app à l'appel entrant (foreground service)
  TimedCallAlertController.kt    → implémentation du minuteur (CountDownTimer)

ui/
  IncomingCallActivity.kt    → écran plein format : décompte + bouton "Bloquer"
  MainActivity.kt            → écran par défaut hors appel
```

### 2. `web-caller/` — PWA appelant (iOS + Android, sans installation)
```
index.html   → interface (bouton d'appel, écrans d'état)
app.js       → logique + interface CallEngine abstraite (StubCallEngine à remplacer)
style.css
manifest.json → permet "Ajouter à l'écran d'accueil"
```

## Ce qui est fonctionnel dans ce squelette
- Écran d'alerte Android avec décompte visuel + bouton de blocage opérationnel
- AdminConfig : durée du décompte et activation du blocage ajustables sans recompiler
- PWA appelant avec les 4 états (idle / appel en cours / bloqué / connecté) et transitions câblées
- Architecture des deux côtés bâtie sur une interface `CallEngine` commune (logique similaire Kotlin/JS) pour rester swappable

## Ce qu'il reste à faire (ordre logique)
1. **Choisir et intégrer un SDK d'appel managé cross-plateforme** (Stream Video, Agora ou Twilio Video) —
   gère nativement le signaling iOS/Android sans serveur maison à construire
2. Brancher ce SDK dans `CallEngine.kt` (Android) et remplacer `StubCallEngine` (web)
3. Récepteur de notification (push) côté tablette pour déclencher `IncomingCallService`
4. Écran de réglages admin (PIN + ajustement de `countdownSeconds`)
5. Héberger le PWA (n'importe quel hébergement statique : GitHub Pages, Netlify, Vercel — gratuit)
6. Tester le décompte + blocage sur ton téléphone Android, ajuster l'ergonomie du bouton

## Pourquoi cette architecture
Le SDK d'appel (le point le plus susceptible de changer si un choix
s'avère mauvais) est isolé derrière `CallEngine` des deux côtés. Le PWA
appelant évite toute compilation iOS (pas de Mac nécessaire) : Safari
supporte WebRTC nativement depuis iOS 11. Le décompte et le blocage sont
strictement séparés du moteur d'appel, donc testables indépendamment.

## Prochaine étape recommandée
Continuer dans **Claude Code** pour : choisir/intégrer le SDK d'appel,
compiler et installer la partie Android sur ton téléphone via ADB, et
héberger/tester le PWA en conditions réelles.
