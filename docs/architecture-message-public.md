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

**Priorité à ce qui se passe réellement dans la pièce.** Si quelqu'un parle
(zone 2, `zoneRoom`) ou qu'un appel est en cours (zone 3 prend alors les
sous-titres de l'appel, comme aujourd'hui), le message public s'efface
temporairement — il n'entre jamais en concurrence avec du vivant. Il revient
dès que la zone se libère, tant qu'il n'a pas expiré.

**Hors appel seulement**, par défaut : je ne l'affiche pas pendant un appel
vidéo (zone 3 y est prise par les sous-titres de l'appel ou, désormais, du
répondeur). À confirmer si vous préférez qu'il réapparaisse entre deux
prises de parole même en plein appel — ce serait plus de code pour un
bénéfice pas évident.

## 4. Déroulé côté appelant (PWA)

0. **Point d'entrée : l'écran d'accueil du PWA**, en dessous d'« Appeler »
   et de « Laisser un message » (répondeur) — un lien tertiaire, « Écrire un
   mot sur son écran », volontairement moins visible que les deux boutons
   d'appel : c'est un geste occasionnel, pas une action principale. Ce même
   écran affiche aussi, en une ligne, le statut du mot actuellement affiché
   s'il y en a un (texte complet en §4bis ci-dessous).
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
| `DeviceStatusReporter` | lecture/écriture de `devices/{appareil}/messagePublic/actuel`, et publication de `captionVisibleLines` vers le PWA si elle n'y est pas déjà exposée |
| `TranscriptionSource` / `RollingCaptionZone` | une troisième source, affichée en zone 3 quand rien de plus prioritaire ne l'occupe |
| `web-caller/` | écran de dictée + correction, sélecteur de durée, écran de statut avec Modifier/Retirer |

Aucun nouvel écran Android, aucune nouvelle vue : c'est la différence la
plus nette avec le répondeur — cette fonction ne touche que du texte dans
une zone qui sait déjà l'afficher.

## 6. Étapes

1. Le socle : schéma Firestore, écriture/lecture depuis le PWA, statut.
2. Le budget de caractères côté PWA, aligné sur `captionVisibleLines`.
3. Le brancher en zone 3 côté tablette, avec la priorité du §3.
4. L'expiration automatique et la bascule Modifier/Retirer.

## 7. Décisions qui reviennent à vous

**1. Pendant un appel.** Je pars sur « invisible pendant un appel, revient
après » (§3). Dites-moi si vous préférez autrement.

**2. Un seul message, pas une file.** Publier un nouveau remplace l'ancien
sans confirmation particulière — est-ce le comportement voulu, ou faut-il
prévenir l'appelant qu'un message d'un AUTRE proche est actuellement affiché
avant de l'écraser ?

**3. La vie privée.** Même remarque que pour le répondeur et les recueils :
sans authentification, ce texte est lisible par quiconque connaît le chemin
Firestore. Moins sensible qu'un message vidéo, mais ça reste un mot destiné
à Jean et personne d'autre.
