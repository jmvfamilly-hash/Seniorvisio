# Application candidate — première installation chez Jean

Ce document sert de message à l'étiquette `candidat_premiere_installation`
(voir `.github/workflows/tag-candidate.yml`). Il décrit ce que contient le
point de version étiqueté, et surtout ce qu'on sait ne pas encore avoir
vérifié : une candidate qui ne dit pas ses angles morts n'est pas une
candidate, c'est un pari.

## Pourquoi un point unique

La tablette et le PWA se déploient par deux chemins indépendants — l'APK à
chaque poussée, le PWA à la demande. Rien ne garantit donc qu'ils viennent du
même code. Étiqueter fixe un commit dont on sait que les deux en sont issus,
et auquel on peut revenir si une version ultérieure se révèle pire.

## Ce que contient ce point

### Tablette

- Écran de Jean sans bandeau date/météo dès qu'un appel se présente, et sans
  barre de navigation Android.
- Mise en veille rétablie quel que soit le mécanisme d'écoute de la pièce.
- Sons du moteur de reconnaissance d'Android ramenés au plus bas hors appel.
  La sonnerie d'appel est passée sur le flux alarme : elle ne dépend donc plus
  de ce réglage, ni du mode silencieux.
- Écoute de la pièce suspendue dès la demande de connexion, et non plus au
  décrochage.
- Curseur de sensibilité du réveil opérant sur les deux mécanismes d'écoute
  (il n'agissait que sur l'un des deux), échelle 500–15000, avec le niveau
  réellement mesuré affiché face au seuil.
- Seuil de transcription découplé du seuil de réveil, et réserve de deux
  secondes de son rejouée à l'ouverture d'une session : les premiers mots
  d'une phrase ne se perdent plus après un silence.
- Statistiques d'usage : durée facturée et durée qui l'aurait été si le
  moteur payant avait tout transcrit, appels avec jour et heure, chronologie
  de veille et d'éveil, journal consultable à distance, redémarrage de
  l'application et de la tablette à distance.

### PWA

- Panneau d'administration réellement protégé par le code. Il ne l'était pas :
  une règle de style manquante laissait le panneau affiché en permanence sous
  la demande de code, et tout y était modifiable sans en saisir aucun.
- Valeur numérique affichée à côté de chaque curseur.
- Réplique de l'écran de Jean sans zone d'information, texte transcrit sous la
  vidéo à la géométrie exacte de la tablette (mêmes coupures de ligne).
- « Revenir à la vidéo » en pied de fenêtre.

### Services

- Règles Firestore publiées, et republiées automatiquement à chaque
  modification du fichier.
- Cloud Functions déployées.

## Ce qui reste ouvert

Aucun de ces points n'empêche l'installation, mais tous méritent d'être connus
avant d'installer plutôt que découverts après.

- **Validation terrain non faite.** Les correctifs de veille, de sons, de code
  d'administration, de seuil de réveil et de débuts de phrase ont été
  compilés et raisonnés, pas éprouvés dans la chambre.
- **La zone 2 ne suit plus la pièce pendant la sonnerie**, conséquence assumée
  de la coupure d'écoute dès la demande de connexion.
- **Le seuil de sensibilité est reporté en proportion entre les deux moteurs
  d'écoute**, dont les unités ne sont pas comparables. À position de curseur
  égale, la sensibilité réelle diffère un peu de l'un à l'autre.
- **Le plancher de transcription est bas** : une session payante peut s'ouvrir
  sur un bruit qui n'aurait pas réveillé l'écran. Le panneau « Utilisation »
  permet de le constater ; le bon correctif serait alors de remonter ce
  plancher, pas le curseur de réveil.
- **Modèle embarqué et qualité de transcription sur le son d'un appel** n'ont
  pas été mesurés en conditions réelles.

## Installation

L'APK est signé avec le keystore de debug versionné dans le dépôt, identique à
toutes les compilations précédentes. Une mise à jour par-dessus une
installation existante reste donc possible sans désinstaller, ce qui préserve
le provisionnement Device Owner — le perdre imposerait une remise à zéro
complète de la tablette.
