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
| PWA — hébergeur | GitHub Pages | **Firebase Hosting** |
| PWA — adresse | celle d'aujourd'hui | `seniorvisio-test.web.app` |
| Nom du raccourci installé | Appeler Jean | TEST — banc d'essai |
| Préfixe de version | `rev47` | `val47` |

**Deux hébergeurs, donc deux origines.** C'est le point le plus important du
tableau, et il ne se lit pas au premier coup d'œil. Une origine, c'est
`schéma + hôte + port` — le chemin n'y entre pas. Tant que les deux PWA
vivaient sous le même hôte, ils partageaient `localStorage` : les réglages
mémorisés, l'identité de l'appelant, les photos du diaporama et la clé de
trace étaient **communs**. Un volume descendu à zéro puis « mémorisé » pendant
un essai serait ressorti au prochain appel réel — le défaut exact qui a coûté
une semaine de recherche.

Deux hébergeurs différents règlent ça sans qu'on ait à y penser. Et le
déploiement de l'un ne peut plus toucher l'autre : ce ne sont plus deux
dossiers d'un même artefact, ce sont deux services sans rien de commun.

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

### a. Les règles Firestore et les fonctions : rien à faire

Elles se déploient toutes seules. Les workflows **Deploy Firestore rules** et
**Deploy Cloud Functions** se déclenchent sur un push de **n'importe quelle
branche** touchant `firestore.rules` ou `functions/` — la collection
`calls_test` et le second déclencheur de réveil sont donc déjà publiés.

Ce comportement a une contrepartie qu'il vaut mieux connaître : **une branche
de travail peut modifier la configuration Firebase de production.** Ici les
deux changements étaient purement additifs, donc sans risque. Le mécanisme,
lui, ne fait pas la différence. C'est le dernier chemin de ce projet par lequel
un essai peut atteindre la production, et il reste ouvert.

### b. Créer le site d'hébergement du banc d'essai

Une seule fois, depuis un poste où `firebase` est installé :

```
firebase hosting:sites:create seniorvisio-test --project seniorvisio
```

(ou deux clics dans la console Firebase → Hosting → Ajouter un autre site).

Puis vérifier que le compte de service du secret `FIREBASE_SERVICE_ACCOUNT`
porte le rôle **Firebase Hosting Admin**. Déployer des règles et des fonctions
ne l'implique pas : c'est le point qui peut faire échouer le premier
déploiement, et le message d'erreur le dira clairement.

Aucun compte ni abonnement à ajouter : le projet est déjà sur le plan Blaze
(les Cloud Functions v2 l'exigent), et le quota gratuit de Hosting — 10 Go de
stockage, 360 Mo de transfert par jour — représente plusieurs milliers de
chargements quotidiens pour une page de 64 Ko.

### c. Installer l'application sur la tablette d'essai

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

### d. Ouvrir le PWA de test

À l'adresse `https://seniorvisio-test.web.app`. Un bandeau violet permanent
l'annonce, le titre de l'onglet est préfixé `[TEST]`, et le raccourci installé
sur l'écran d'accueil s'appelle « TEST — banc d'essai ».

Mettez-le en raccourci sur l'écran d'accueil du téléphone qui sert aux essais.
Le raccourci porte un nom différent, donc il ne peut plus être confondu avec
celui qui appelle Jean — c'était le cas tant que les deux s'installaient sous
« Appeler Jean », et un bandeau ne protège pas de ça : on le voit après avoir
ouvert, c'est-à-dire après avoir cru appeler.

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
dépassé ou une panne du projet touchent les deux environnements — et un push
sur n'importe quelle branche redéploie ces règles. Séparer les projets serait
la protection complète ; cela imposerait un second `google-services.json`, une
seconde configuration PWA et un second jeu de secrets.

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
