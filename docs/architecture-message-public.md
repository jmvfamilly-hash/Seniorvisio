# Message public — architecture

*(Nom retenu pour cette fonction : « message public ». À ne pas confondre
avec le répondeur — `architecture-repondeur.md` — qui est un message
audio/vidéo privé que Jean choisit d'écouter. Celui-ci est un court texte,
toujours visible, que Jean n'a même pas à ouvrir.)*

## 1. Ce qu'on veut

Un proche dicte un petit mot (voix, puis texte corrigeable), qui s'affiche en
permanence sur l'écran de Jean — pas une conversation, pas un appel, un
pense-bête. Exemple réel : « Rendez-vous chez le dentiste jeudi à 14h, Marie
viendra te chercher. »

- **Dicté puis corrigé**, même geste que pour les sous-titres du répondeur
  (§3 et §5 de `architecture-repondeur.md`) : reconnaissance vocale côté PWA,
  champ éditable, l'appelant corrige avant de publier.
- **Longueur bornée** par le réglage courant du nombre de lignes affichées
  (`AdminConfig.captionVisibleLines`, défaut 2, réglable de 1 à 4 — déjà
  utilisé pour les sous-titres de la pièce et de l'appel, voir
  `RollingCaptionZone`). Le PWA lit ce réglage et affiche un budget de
  caractères approximatif pendant la saisie.
- **Une durée d'affichage**, choisie côté PWA, 24 heures par défaut.
  Au-delà, disparaît seul.
- **Retirable à tout moment** avant l'expiration.
- **Son statut reste visible côté PWA** : ce qui est actuellement affiché
  chez Jean, depuis quand, et jusqu'à quand.

## 2. Où ça vit

Un seul message actif à la fois — pas une file, pas un historique :

```
devices/{appareil}/messagePublic/actuel
    texte         texte final, corrigé par l'appelant avant publication
    de            nom de l'appelant
    crééLe        horodatage serveur
    duréeHeures   nombre, défaut 24
    expireLe      crééLe + duréeHeures (calculé à l'écriture)
```

Publier un nouveau message **remplace** l'ancien directement — écrire ce
document suffit, pas besoin de retirer l'ancien d'abord (la PWA le propose
quand même explicitement, §1). Retirer = supprimer ce document.

Pas de Storage ici : c'est du texte, quelques centaines d'octets au pire,
Firestore suffit très largement — contrairement au répondeur (§2 de l'autre
document), qui porte des fichiers.

## 3. Où ça s'affiche chez Jean

**Zone 3 (`zoneCall`, voir `view_home_zones.xml`), au repos.** Cette zone est
déjà définie comme « tout ce qui ne vient PAS du microphone de la tablette »
— aujourd'hui le son reçu d'un appel, et c'est très exactement ce qu'est un
message public : un texte d'origine distante, pas une parole de la pièce.
Nouvelle valeur pour `TranscriptionSource` (aux côtés de `ROOM` et `CALL`),
affichée par le même `RollingCaptionZone`, sans nouvelle vue à créer.

**En clair : le texte, puis, juste dessous, en jaune clair et bien lisible,
« Message de [Nom] — [Nom] n'est pas en ligne »** — exactement la même
phrase et le même traitement visuel que la mise en garde du répondeur (§4
de `architecture-repondeur.md`), pour que les deux fonctions se
reconnaissent du même coup d'œil, même si l'une est un fichier et l'autre
un simple texte.

**Pas de conflit avec les paroles de la pièce, en fait — correction après
vérification du code réel.** Zone 2 (`zoneRoom`, paroles de la pièce) et
zone 3 (`zoneCall`) sont deux tiers d'écran DIFFÉRENTS et jamais superposés
(voir `view_home_zones.xml`) : le message public, posé en zone 3, ne
disparaît donc jamais parce que Jean ou quelqu'un parle dans la pièce.

**Pas de conflit avec un appel non plus, pour une raison plus simple
encore : ce n'est pas le même écran.** L'accueil (`MainActivity`) et l'écran
d'appel (`IncomingCallActivity`) possèdent chacun leur propre instance de
ces zones. Le message public n'est entretenu que par l'accueil ; dès que
Jean passe à l'écran d'appel, cette zone-là affiche les sous-titres de
l'appel, sans rien savoir du message public — et à son retour à l'accueil,
celui-ci réapparaît tout seul, tant qu'il n'a pas expiré. Rien à orchestrer.

## 4. Déroulé côté appelant (PWA)

0. **Point d'entrée : l'onglet « Mot écrit » de l'écran d'accueil du PWA.**
   L'accueil est désormais en trois onglets — Appeler / Message vocal
   (répondeur) / Mot écrit — plutôt qu'un seul écran qui aurait fini par
   empiler les trois fonctions. Chaque onglet porte son action et son
   statut, sans rien montrer des deux autres.
1. Dicte le texte (reconnaissance vocale navigateur, même mécanisme qu'en
   §5 du répondeur), le corrige dans le champ éditable.
2. Voit en direct le budget restant par rapport au réglage actuel de Jean
   (ex. « 68/80 caractères, 2 lignes maximum ») — publication bloquée au-delà.
3. Choisit une durée (chips 1 h / 6 h / 24 h *(défaut)* / 3 j / 7 j).
4. Publie → écrit `devices/{appareil}/messagePublic/actuel`.
5. Un écran de statut, accessible à tout moment, montre le texte
   actuellement affiché, depuis quand, et dans combien de temps il
   disparaît — avec deux actions : Modifier (rouvre l'étape 1 pré-rempli) et
   Retirer maintenant.

## 5. Ce qui est touché

| Pièce | Nature |
|---|---|
| `AdminConfig` | rien de nouveau — `captionVisibleLines` existe déjà et suffit |
| `DeviceStatusReporter` | lecture de `devices/{appareil}/messagePublic/actuel` (nouveau listener), écriture `état` si besoin. `captionVisibleLines` est déjà exposé au PWA (champ du document `devices/{appareil}`, confirmé dans le code) — rien à ajouter là |
| `TranscriptionSource` / `RollingCaptionZone` | une troisième source ; `MainActivity` la réaffiche à chaque battement (même ticker que `rafraîchirBoutonAppelerAidant`), elle disparaît seule à l'expiration |
| `web-caller/` | écran de dictée + correction, sélecteur de durée, écran de statut avec Modifier/Retirer |

Aucun nouvel écran Android, aucune nouvelle vue : c'est la différence la
plus nette avec le répondeur — cette fonction ne touche que du texte dans
une zone qui sait déjà l'afficher.

## 6. Étapes

1. Le socle : schéma Firestore, écriture/lecture depuis le PWA, statut.
2. Le budget de caractères côté PWA, aligné sur `captionVisibleLines`.
3. Le brancher en zone 3 côté tablette (voir §3, plus simple que prévu).
4. L'expiration automatique et la bascule Modifier/Retirer.

## 7. Décisions qui reviennent à vous

**1. Un seul message, pas une file.** Publier un nouveau remplace l'ancien
sans confirmation particulière — est-ce le comportement voulu, ou faut-il
prévenir l'appelant qu'un message d'un AUTRE proche est actuellement affiché
avant de l'écraser ?

**2. La vie privée.** Même remarque que pour le répondeur et les recueils :
sans authentification, ce texte est lisible par quiconque connaît le chemin
Firestore. Moins sensible qu'un message vidéo, mais ça reste un mot destiné
à Jean et personne d'autre.
