# Albums photo — architecture

Ce document décrit une fonction qui n'existe pas encore, et dont une partie
seulement sera réalisée maintenant. Il est écrit d'abord parce que la moitié
des décisions structurantes se prennent au début, et qu'une architecture qui
n'a pas prévu la suite se paie à chaque étape suivante.

## 1. Ce qu'on veut, et dans quel ordre

**À terme.** Jean parcourt seul une bibliothèque de photos, et retrouve sous
chacune ce qui s'est dit la dernière fois qu'un proche la lui a commentée.
Hors ligne, sans rien à installer, sans rien à comprendre.

**Maintenant.** Un proche compose depuis le PWA une collection de photos qui
part **s'installer sur la tablette**. La tablette vérifie qu'elle sait les
afficher. Pendant un appel, l'appelant choisit une collection et la fait
défiler chez Jean.

**Plus tard, et c'est un autre chantier.** Jean navigue lui-même — au doigt,
puis peut-être à la voix. Ouvrir, fermer, avancer, revenir.

La règle du projet ne change pas : **Jean n'a jamais rien à faire.** Tout ce
qui lui sera proposé reste facultatif, et l'écran doit continuer de
fonctionner exactement comme aujourd'hui pour quelqu'un qui n'y touche jamais.

## 2. Pourquoi les photos ne peuvent plus passer par le document d'appel

Aujourd'hui une photo voyage en base64 **dans** le document d'appel Firestore,
à côté des SDP et des textes affichés. Ça tient pour une photo à la fois, et
encore : la limite du mébioctet a déjà fait disparaître des photos en silence,
et nous avons dû les réduire à 500 Ko pour que ça passe.

Pour une bibliothèque, ce chemin est sans issue. Trois raisons, dont une seule
suffirait :

- **la limite d'un mébioctet par document** est une limite dure ;
- **une base de données n'est pas un entrepôt de fichiers** : on paierait
  cher, en lectures comme en écritures, pour un usage qu'elle ne vise pas ;
- **ces photos doivent être disponibles hors ligne**, donc de toute façon
  copiées sur la tablette.

## 3. Où vivent les photos

Trois endroits, chacun avec un rôle unique.

**Les octets : Firebase Storage.** Le projet est déjà sur le plan Blaze, donc
disponible sans rien changer. Le PWA téléverse directement depuis le
navigateur.

**La description : un document Firestore par album**, sous l'appareil :

```
devices/{appareil}/albums/{albumId}
    titre        "Noël chez Marie"
    crééPar      "Marie"
    crééLe       horodatage serveur
    état         "à installer" | "installé" | "incomplet"
    photos[]     { id, url, octets, ordre,
                   décodée: true|false|null, cause: "…" }
```

Quelques centaines d'octets par photo : cent photos tiennent très en dessous
de la limite. **Aucune image n'entre ici**, uniquement de quoi les retrouver.

**Les images utilisables : le stockage privé de la tablette.** Après
vérification, chaque photo est réencodée à la définition de la dalle et rangée
localement. C'est cette copie-là que Jean regarde — d'où le hors-ligne, sans
effort particulier.

### Un point qui évite une dépendance

La tablette n'a **pas besoin du SDK Storage**. Le PWA téléverse et range dans
le document l'URL de téléchargement ; la tablette fait une simple requête
HTTPS, comme elle le fait déjà pour les mises à jour (voir
`DeviceStatusReporter.downloadApk`). Une dépendance Android de moins, et un
chemin déjà éprouvé.

## 4. La compatibilité se vérifie sur la tablette, et nulle part ailleurs

C'est le point le plus important de ce document.

**Seule la tablette sait ce que la tablette sait afficher.** Le navigateur du
proche peut parfaitement décoder une image que `BitmapFactory` refusera, et
l'inverse est vrai aussi — nous venons d'en faire les frais dans l'autre sens,
avec des photos HEIC que Chrome sur Android ne sait pas ouvrir alors
qu'Android, lui, le sait depuis la version 9.

Le déroulé est donc :

1. le PWA téléverse **les fichiers d'origine** et écrit l'album en
   « à installer » ;
2. la tablette, au repos et en Wi-Fi, télécharge chaque photo, **la décode
   réellement**, la réencode à sa définition d'écran et la range ;
3. elle inscrit dans le document, photo par photo, `décodée` et la cause en
   cas d'échec ;
4. l'album passe en « installé » quand toutes ont été vérifiées, en
   « incomplet » si certaines ont échoué.

Le PWA affiche cet état. **Le proche sait donc avant l'appel si son album est
utilisable**, et laquelle de ses photos n'est pas passée — au lieu de le
découvrir devant Jean.

Effet de bord heureux : téléverser les originaux plutôt que des images
réencodées par le navigateur **règle le problème HEIC** au lieu de le
contourner. C'est Android qui décode, et il en est capable.

## 5. Le découpage, et où sont les coutures

Quatre pièces, et la couture est entre la troisième et la deuxième — c'est
elle qui permettra d'ajouter la navigation par Jean sans toucher au reste.

**`AlbumStore`** — ce qui existe localement, où, dans quel état. Ne sait rien
des appels ni de l'affichage. Sait installer, vérifier, élaguer.

**`LecteurAlbum`** — affiche une photo, tient l'index courant. Expose
`ouvrir(album)`, `suivante()`, `précédente()`, `fermer()`. **Ne sait pas qui
le commande.**

**Les sources de commande** — des greffons qui pilotent le lecteur :

| source | quand | état |
|---|---|---|
| `CommandeAppelant` | pendant un appel, depuis le PWA | maintenant |
| `CommandeTactile` | Jean touche l'écran | plus tard |
| `CommandeVocale` | Jean parle | plus tard |

**`EnregistreurDeCommentaire`** — associe le texte transcrit à la photo
affichée au moment où il a été dit.

Ce découpage n'est pas inventé pour l'occasion : c'est celui que
`HomeZonesController` applique déjà, et qui lui permet d'ignorer délibérément
s'il y a un appel en cours. Ajouter la voix plus tard consistera à écrire une
source de plus. Le lecteur ne bougera pas.

## 6. Le commentaire attaché à la photo

C'est la partie à **prévoir maintenant même si on ne l'utilise pas tout de
suite**, parce qu'elle ne coûte presque rien ici et serait pénible à rajouter
après.

La tablette transcrit déjà la voix de l'appelant pendant l'appel, et sait déjà
quelle photo est affichée. Il suffit d'attribuer chaque texte **figé** à la
photo en cours au moment où il se clôt, et de ranger ça à côté de l'album.

Rangé **localement uniquement**, et c'est délibéré : ce sont des paroles de
famille. Voir la section 9.

## 7. Ce que ça change pour le diaporama d'aujourd'hui

L'appelant n'envoie plus une image, il envoie **`{album, index}`** — quelques
dizaines d'octets au lieu de cinq cent mille. Quatre conséquences, toutes
bonnes :

- la limite Firestore cesse d'exister pour cette fonction ;
- plus de compromis sur la qualité : on affiche la pleine définition ;
- la photo apparaît **instantanément**, elle est déjà là ;
- ça marche même quand la liaison est mauvaise.

L'envoi d'une photo à la volée, tel qu'il existe, reste utile pour le
spontané — quelqu'un veut montrer ce qu'il a sous les yeux, maintenant. Les
deux chemins coexistent sans se gêner.

## 8. Étapes

Chacune est livrable et vérifiable seule, sur le banc d'essai.

1. **Le socle** — `AlbumStore`, installation, vérification par décodage réel,
   état remonté au PWA. Rien de visible pour Jean.
2. **La composition côté PWA** — créer un album, téléverser, suivre l'état.
3. **Le lecteur et la commande par l'appelant** — choisir un album pendant un
   appel et le faire défiler.
4. **L'enregistrement des commentaires** — silencieux, rien à l'écran.
5. *(chantier suivant)* **La navigation par Jean**, et la relecture des
   commentaires.

## 9. Trois décisions qui vous reviennent

**La vie privée, et c'est la plus sérieuse.** Aujourd'hui les règles Firestore
laissent lire et écrire quiconque connaît un chemin. C'était déjà discutable
pour un journal technique ; ça devient autre chose quand il s'agit de **l'album
de famille et de ce qui s'est dit autour**. Cette fonction ne devrait pas être
mise en service avant que les règles Storage soient écrites correctement — et
à mon avis, les règles Firestore aussi. Je ne le fais pas sans que vous le
décidiez, mais je ne l'écrirai pas non plus en silence.

**Les commentaires enregistrés.** Attribuer la transcription à une photo, c'est
garder une trace écrite de conversations familiales sur la tablette. C'est
précisément ce qui donne sa valeur à la fonction, et c'est aussi quelque chose
dont Jean et ses proches devraient être informés. À décider avant l'étape 4,
pas après.

**La place sur la tablette.** Une dalle ancienne n'a pas de place à gaspiller.
Il faut un budget — disons deux cents photos, ou cinq cents mégaoctets — et une
règle d'élagage. Sans quoi la fonction marchera six mois puis remplira le
disque, ce qui est la pire façon de tomber en panne : lentement, et longtemps
après qu'on ait cessé d'y penser.

## 10. Ce que ça coûte

Storage sur le plan Blaze : 5 Go stockés et 1 Go de téléchargement par jour
avant facturation. Deux cents photos à trois cents kilo-octets font soixante
mégaoctets, téléchargés une fois chacune par la tablette. L'usage réel n'approche
pas le seuil — la même nuance que pour l'hébergement du banc d'essai : facturable
au-delà d'un seuil que nous n'atteignons pas.
