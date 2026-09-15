# Chaîne de validation — la tablette d'essai

La tablette de Jean est **en service chez lui**. Toute mise au point se fait
donc ailleurs : une seconde tablette, une version dédiée, et un jeu de
documents Firestore qui n'a rien de commun avec le sien.

Ce document décrit ce qui est en place, ce qui vous reste à faire une seule
fois, et comment s'en servir au quotidien.

---

## 1. Ce qui sépare les deux environnements

Quatre champs, décidés à la compilation (voir `app/build.gradle`), et chacun
ferme un chemin précis par lequel un essai pourrait atteindre le salon de Jean.

| | production | validation |
|---|---|---|
| Document d'appareil | `devices/jean_tablet` | `devices/test_tablet` |
| Collection d'appels | `calls` | `calls_test` |
| Mode kiosque | oui | **non** |
| Lanceur de la tablette | oui | **non** |
| PWA | racine du site | `/test/` |
| Préfixe de version | `rev47` | `val47` |

**Le document d'appareil est le plus dangereux des quatre.** C'est là que la
tablette dépose son jeton de notification, et un seul y tient à la fois : une
tablette d'essai qui écrirait le sien **volerait les appels de Jean** — sa
tablette cesserait simplement de sonner, définitivement, sans que rien nulle
part ne dise pourquoi. C'est la panne la plus grave que ce montage puisse
produire, et la plus silencieuse.

L'`applicationId` est le **même** pour les deux, délibérément :
`google-services.json` ne déclare qu'un seul package et ses valeurs sont
générées par la console Firebase, pas modifiables à la main. Un suffixe
imposerait d'enregistrer une seconde application pour un bénéfice nul — les
deux variantes vivent sur deux tablettes différentes et n'ont jamais à
cohabiter sur la même.

---

## 2. Ce qu'il vous reste à faire, une fois

### a. Déployer les règles Firestore et les fonctions

La collection `calls_test` et le second déclencheur de réveil n'existent pas
tant qu'ils ne sont pas publiés :

- workflow **Deploy Firestore rules** (`firestore.rules` porte désormais
  `calls_test`) ;
- workflow **Deploy functions** (`functions/index.js` expose maintenant
  `notifyIncomingCall` *et* `notifyIncomingTestCall`).

Sans le second déclencheur, la tablette d'essai reçoit quand même les appels
tant qu'elle est éveillée — c'est l'écoute Firestore permanente qui les porte.
Elle les manquera seulement après une longue mise en veille.

### b. Installer l'application sur la tablette d'essai

1. lancer le workflow **Build validation APK** (bouton *Run workflow*, depuis
   n'importe quelle branche) ;
2. ouvrir le résumé du run : il affiche l'adresse de téléchargement ;
3. ouvrir cette adresse **depuis le navigateur de la tablette d'essai**, puis
   installer le fichier. Android demandera l'autorisation d'installer des
   applications depuis cette source — c'est normal, et c'est ce que « appli
   standard » signifie.

**Ne provisionnez pas cette tablette en Device Owner.** Ce n'est pas une
étape oubliée : c'est ce qui la garde utilisable comme une tablette ordinaire.
Le mode kiosque est de toute façon refusé à la compilation sur cette variante,
mais le provisionnement Device Owner emporte d'autres effets (installation
silencieuse, verrouillage des réglages) dont on ne veut pas ici.

### c. Ouvrir le PWA de test

À l'adresse habituelle, suffixée de `/test/`. Un bandeau violet permanent
l'annonce, et le titre de l'onglet est préfixé `[TEST]`.

Mettez-le en raccourci sur l'écran d'accueil du téléphone qui sert aux essais,
**et pas sur celui qui sert à appeler Jean**. Les deux pages sont identiques au
pixel près : le bandeau est la seule chose qui les distingue une fois le
raccourci ouvert.

---

## 3. Au quotidien

**Éprouver un travail en cours.** Le workflow de validation se déclenche à la
main depuis n'importe quelle branche : pas besoin de fusionner quoi que ce soit
d'abord. C'est tout l'intérêt.

**Livrer en continu sur la tablette d'essai.** Pousser sur la branche
`validation` déclenche le build automatiquement.

**Installer une nouvelle version.** Le panneau d'administration du PWA de test
affiche la version disponible ; le téléchargement et l'installation restent
manuels, depuis le navigateur de la tablette. L'installation silencieuse exige
d'être Device Owner, ce que cette tablette n'est pas — et ne doit pas être.

**Savoir sur quoi on travaille.** L'écran de la tablette d'essai affiche
`val47 · VALIDATION` en haut à gauche, là où celle de Jean affiche `rev47`.
Chez lui, l'absence de mention *est* l'information : un bandeau « production »
finirait par ne plus se lire, et son absence un jour de panne ne se
remarquerait pas.

---

## 4. Ce que cette chaîne ne protège pas

**Les clés d'API sont les mêmes.** Les essais consomment donc le même quota de
transcription payante que les appels réels. L'usage de la tablette d'essai est
comptabilisé séparément (`devices/test_tablet/usage`), ce qui permet de le
suivre — mais la facture est commune.

**Le projet Firebase est le même.** Une erreur de règles Firestore, un quota
dépassé ou une panne du projet touchent les deux environnements. Séparer les
projets serait la protection complète ; cela imposerait un second
`google-services.json`, une seconde configuration PWA et un second jeu de
secrets.

**Les règles Firestore restent ouvertes en lecture et en écriture** sur les
deux environnements, comme aujourd'hui. Rien n'a été durci ici : ce document
décrit une séparation d'environnements, pas un contrôle d'accès.

---

## 5. Les trois garde-fous, et ce qu'ils valent

**`scripts/request_remote_update.js` refuse de deviner sa cible.** Pas de
valeur par défaut : une variable oubliée fait échouer le workflow bruyamment au
lieu de livrer chez Jean. C'est le garde-fou le plus solide de la chaîne, parce
qu'il échoue du bon côté.

**Le mode kiosque est refusé à la compilation** sur la variante de validation,
et non selon le provisionnement de la tablette. La tablette d'essai peut donc
être provisionnée Device Owner un jour, pour éprouver l'installation
silencieuse, sans se retrouver verrouillée.

**Les deux chaînes de compilation sont indépendantes.** Deux workflows et non
une matrice : une erreur dans la chaîne d'essai ne peut pas faire échouer — ni
réussir de travers — la livraison en production.

Ce qu'aucun des trois ne couvre : **rien n'empêche de lancer à la main le
workflow de production depuis une branche de travail**. Le garde-fou qui s'y
oppose est ailleurs — la livraison à distance n'est déclenchée que sur la
branche `AssemblyAI` (voir `build-debug-apk.yml`). Un build lancé à la main
depuis une autre branche produit donc un APK, mais ne l'installe nulle part.
