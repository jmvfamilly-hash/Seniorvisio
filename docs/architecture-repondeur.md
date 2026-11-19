# Répondeur — architecture

*(Nom retenu pour cette fonction dans toutes les discussions à venir : «
répondeur ». Ce document est le point de repère à relire à chaque fois qu'on
en reparle.)*

Document écrit avant tout code, comme `architecture-recueils.md` l'a été pour
les albums photo — même raison : la moitié des décisions qui comptent se
prennent au début.

## 1. Ce qu'on veut

Un proche qui ne peut pas joindre Jean en direct (tablette en pause, personne
qui ne décroche, ou simplement « je ne veux pas déranger, je laisse un mot »)
peut laisser un message vidéo ou audio. Jean l'écoute quand il veut, aussi
simplement qu'il répond aujourd'hui à un appel — pas un nouvel écran à
comprendre, le même geste.

**Hypothèse à confirmer (voir §9, décision 1) :** « Laisser un message »
devient une option disponible à tout moment depuis le PWA, à côté
d'« Appeler » — pas seulement quand l'appel échoue. C'est plus simple à
construire et à expliquer qu'une détection automatique d'échec, et ça couvre
aussi le cas « je ne veux pas déranger ».

## 2. Où vivent les messages

Même schéma que les recueils (§3 de `architecture-recueils.md`), pour les
mêmes raisons : un message vidéo pèse des mégaoctets, Firestore n'est pas un
entrepôt de fichiers.

**Les octets : Firebase Storage**, à côté des recueils :

```
messages/{appareil}/{messageId}
```

**La description : un document Firestore par message**, sous l'appareil :

```
devices/{appareil}/messages/{messageId}
    type            "audio" | "video"
    url             (téléchargement Storage)
    octets          taille du fichier
    dureeSec        durée du message
    de              nom de l'appelant (saisi comme pour un appel normal)
    photoAppelant   base64, même champ et même limite (500 Ko) que
                    callerPhotoBase64 aujourd'hui — affichée plein écran
                    si le message est audio
    crééLe          horodatage serveur
    état            "nouveau" | "en cours de lecture" | "lu"
    sousTitres      texte final, corrigé par l'appelant avant envoi (§3) —
                     c'est ce texte, et aucun autre, qui s'affiche chez Jean
```

Un seul champ `état` suffit : pas de compteur de vues, pas d'historique — le
message disparaît après sa lecture (§6).

## 3. Déroulé côté appelant (PWA)

1. **Point d'entrée : l'onglet « Message vocal » de l'écran d'accueil du
   PWA.** L'accueil est en trois onglets — Appeler / Message vocal / Mot
   écrit (voir `architecture-message-public.md`, cette troisième fonction
   partage le même écran à onglets) — plutôt qu'un seul écran qui aurait
   fini par empiler les trois. Cet onglet porte le bouton « Laisser un
   message vidéo ou audio » et le statut du dernier message envoyé (§3,
   étape 6 ci-dessous). Choix audio ou vidéo, comme le choix caméra/micro
   existant à l'ouverture d'un appel.
2. Écran d'enregistrement : aperçu caméra (si vidéo) identique à `localVideo`
   aujourd'hui, un bouton Enregistrer/Arrêter, une durée maximale annoncée à
   l'écran (proposition : 2 minutes — large assez pour un message, court
   assez pour ne pas remplir le quota).
3. **Relecture, avec les sous-titres déjà transcrits et modifiables.** Dès
   l'arrêt de l'enregistrement, le son est transcrit automatiquement (côté
   PWA — voir §5, ça change par rapport à la première version de ce plan) et
   le texte obtenu s'affiche dans un champ éditable. L'appelant corrige ce
   que la transcription automatique a mal compris avant d'envoyer — **c'est
   ce texte-là, tel qu'il est au moment de l'envoi, qui part avec le
   message** et qui sera affiché et lu sur la tablette de Jean. Personne
   n'envoie un message à Jean sans savoir ce qu'il contient, ni ce qui sera
   écrit sous ses yeux.
4. Envoi : upload du fichier vers Storage (`MediaRecorder` côté navigateur,
   comme pour les recueils vidéo), puis écriture du document Firestore une
   fois l'upload terminé — jamais avant, pour ne jamais pointer vers une URL
   qui n'existe pas encore. Le texte corrigé à l'étape 3 part dans ce même
   document (champ `sousTitres`, voir §2).
5. Confirmation à l'écran : « Message envoyé, Jean l'écoutera. »
6. **Statut, visible depuis le PWA tant que le message existe.** Le même
   écran (ou l'écran d'accueil du PWA) affiche l'état courant du dernier
   message envoyé : « envoyé, pas encore écouté » puis « écouté par Jean à
   HH:MM ». Comme le document disparaît une fois lu (§6), le PWA doit retenir
   ce dernier état localement (ex. `localStorage`) au moment où il voit
   passer `état = "lu"`, plutôt que de dépendre de l'existence continue du
   document pour l'afficher.
7. **Tant que `état = "nouveau"`, deux actions supplémentaires** sur ce même
   statut :
   - **Télécharger** — récupère le fichier depuis Storage vers l'appareil de
     l'appelant (un simple lien de téléchargement sur l'URL déjà connue).
     Utile puisque le message, lui, sera détruit dès que Jean l'aura
     écouté : c'est la seule façon pour l'appelant d'en garder une copie.
   - **Supprimer** — retire le message avant que Jean ne l'ait entendu (on
     s'est trompé, on veut le refaire, on a changé d'avis). Supprime le
     fichier Storage et le document Firestore, exactement comme §6 le fait
     après lecture — c'est la même opération, déclenchée plus tôt.
   Les deux disparaissent dès que `état` passe à `"en cours de lecture"` ou
   `"lu"` : au-delà de ce point, agir sur le message ne regarde plus
   l'appelant.

## 4. Déroulé côté Jean

**La notification remplace l'horloge, elle ne s'ajoute pas à côté.** Tant
qu'un message attend, la zone centrale de l'accueil — où vit l'horloge à
aiguilles ou numérique aujourd'hui — est entièrement prise par :

- une grande image d'enveloppe ;
- un texte écrit en gros : « [Nom] vous a laissé un message » ;
- un bouton vert, large, juste dessous : « ÉCOUTER ».

**L'heure ne disparaît pas, elle se déplace.** Elle reste visible, en
digital (pas l'horloge à aiguilles), dans la colonne de droite,
**juste au-dessus du bouton « Bloquer les appels 30 minutes »** — qui, lui,
ne bouge pas.

**Le bouton « Demander à [Nom] de rappeler » n'existe pas encore en
production** (voir `MainActivity.rafraîchirBoutonAppelerAidant`,
`BuildConfig.RAPPEL_AIDANT_VISIBLE`) : il ne doit pas apparaître dans cette
colonne tant qu'il n'est pas lui-même activé. Les deux fonctions sont
indépendantes, et le répondeur ne dépend pas de l'autre pour exister.

Jean appuie sur « ÉCOUTER » → écran plein, qui reprend **exactement** la mise en page de
`activity_incoming_call.xml` une fois l'appel connecté (vidéo ou photo en
fond, zones de texte par-dessus), avec ces différences :

- un bandeau fixe en haut, discret : « Message de [Nom] » — une simple
  identité, pas une mise en garde ;
- juste sous la zone des sous-titres (§5), bien séparée d'eux : la mise en
  garde elle-même, « [Nom] n'est pas en ligne », écrite en jaune clair,
  assez grande pour se lire du fond de la pièce — c'est elle, pas le
  bandeau du haut, qui doit empêcher toute confusion avec un appel réel ;
- deux boutons, symétriques comme `buttonAnswerNow`/`buttonBlock`
  aujourd'hui (même taille, même poids visuel, pour ne pas suggérer que
  l'un compte plus que l'autre) : « Réécouter » en bas à gauche, « Terminé »
  en bas à droite, même rouge et même emplacement que le bouton raccrocher.
  Pas de « Répondre » : rien à décrocher, la lecture démarre seule dès
  l'ouverture de l'écran.

Si message audio : la photo de l'appelant occupe tout l'écran (centerCrop,
comme `imageCallerPhoto` aujourd'hui), le son joue derrière. Si message
vidéo : la vidéo occupe l'écran à la place.

**La lecture qui se termine seule ne ferme rien.** Jean peut vouloir
réentendre un passage — à la fin du fichier, l'écran reste ouvert, prêt à
rejouer depuis le début si Jean appuie sur « Réécouter », autant de fois
qu'il le souhaite. Seul « Terminé » ferme l'écran, et c'est ce geste-là, et
lui seul, qui déclenche §6 — jamais la fin naturelle de la lecture.

## 5. La transcription : écrite avant l'envoi, pas en direct chez Jean

**Changement par rapport à la première version de ce plan.** Pendant un
appel, les sous-titres viennent de la transcription du flux audio WebRTC en
direct — mais un message enregistré n'a personne en ligne pour relire et
corriger ce que la machine aurait mal compris pendant la lecture. La
transcription se fait donc **côté PWA, juste après l'enregistrement**, et
l'appelant la corrige avant d'envoyer (§3, étape 3). Le texte final part
dans le document du message (champ `sousTitres`, §2).

**Conséquence qui simplifie la tablette : elle n'a plus rien à transcrire.**
Elle affiche le texte déjà écrit, dans la même zone (`PacedCaptionZone`,
zone 3) et selon le même rythme que pour les paroles transcrites en direct —
mais ce texte est connu d'avance, donc pas de `TranscriptionEngine`, pas de
décodage audio pour la voix, pas de pont à construire entre un fichier et la
chaîne de transcription. Ce qui restait la pièce la plus incertaine du plan
disparaît.

Pour une vidéo, la lecture de l'image ne peut toujours pas passer par
`SurfaceViewRenderer` (WebRTC) — il faut un lecteur de fichier (ExoPlayer ou
`MediaPlayer` sur une `SurfaceView` dédiée), au même emplacement à l'écran.
C'est la seule pièce technique vraiment neuve côté Android.

**Reste à choisir : quel moteur transcrit côté PWA.** L'API de
reconnaissance vocale du navigateur (`webkitSpeechRecognition`) est la plus
simple à poser, sans service externe ni coût, mais sa qualité et sa
disponibilité varient selon le navigateur du proche. L'alternative — envoyer
l'enregistrement à un service de transcription (AssemblyAI ou Gladia, déjà
utilisés côté Android) — donne un résultat plus fiable mais ajoute un appel
réseau et un coût par message. À trancher à l'implémentation, sans que ça
change rien au reste du plan : l'appelant corrige le texte quel que soit le
moteur qui l'a proposé.

## 6. La destruction après lecture

**Détruit, pas seulement marqué lu.** Dès que Jean appuie sur Terminé — et
seulement à ce moment-là, jamais à la fin naturelle de la lecture (§4, Jean
peut réécouter avant de fermer) :

1. la tablette écrit `état = "lu"` (utile seulement si la suppression qui
   suit échoue en cours de route — pour qu'on sache qu'il faut réessayer,
   pas pour archiver) ;
2. elle supprime le fichier Storage et le document Firestore.

Même garde-fou que pour les recueils : si la suppression Storage échoue
(hors-ligne, etc.), on ne bloque pas Jean — on retente au prochain passage en
Wi-Fi, en se basant sur `état = "lu"` plutôt que sur l'absence du document.

## 7. Ce qui est touché

| Pièce | Nature |
|---|---|
| `storage.rules` | nouveau chemin `messages/{appareil}/{messageId}`, même forme que `recueils/...` (taille et type bornés, lecture/écriture/suppression ouvertes — voir §9 décision 2) |
| `web-caller/` | bouton « Laisser un message », écran d'enregistrement, transcription + relecture modifiable, upload, statut du message envoyé |
| `AdminConfig` / `DeviceStatusReporter` | rien de neuf a priori — les messages sont lus directement sous `devices/{appareil}/messages`, pas besoin d'un réglage à distance pour démarrer |
| `activity_main.xml` + `MainActivity` | notification plein écran remplaçant l'horloge, horloge digitale déplacée au-dessus de `boutonPauseAppels` (voir §4) |
| nouvel écran (ou mode de `IncomingCallActivity`) | lecture du message : bandeau, photo/vidéo, sous-titres déjà écrits (pas de transcription embarquée, voir §5), bouton Terminé |
| nouveau composant Android | lecteur de fichier vidéo (image seulement — plus de pont vers `TranscriptionEngine`, voir §5) |

Réutiliser `IncomingCallActivity` en lui ajoutant un « mode message » plutôt
qu'écrire un écran séparé évite de dupliquer toute la mise en page (zones,
pulse d'attention, orientation) — à trancher au moment d'écrire le code, pas
ici.

## 8. Étapes

1. **Le socle** — schéma Storage/Firestore, upload PWA, liste des messages en
   attente visible côté admin (PWA). Rien de visible pour Jean.
2. **La lecture audio** — notification plein écran + écran chez Jean, photo +
   son + sous-titres déjà écrits + suppression. Pas encore la vidéo.
3. **La lecture vidéo** — le lecteur de fichier pour l'image.
4. **Les finitions** — plusieurs messages en attente, limites de durée/poids
   annoncées côté PWA avant l'enregistrement plutôt que découvertes à l'envoi.

## 9. Décisions qui reviennent à vous

**1. Le déclencheur.** Je pars sur « disponible à tout moment, à côté
d'Appeler » (§1). Si vous préférez que ça n'apparaisse que quand l'appel
échoue (pause active, tablette injoignable), c'est faisable mais plus
complexe à déclencher correctement — dites-le avant que j'écrive le code.

**2. La vie privée.** Même avertissement que pour les recueils (§9 de
`architecture-recueils.md`, non résolu là non plus) : sans authentification,
les règles Storage/Firestore de ce projet laissent lire quiconque connaît un
chemin. Pour un message vidéo personnel adressé à Jean, c'est plus sensible
qu'une photo de famille partagée volontairement. `messageId` n'est pas
deviné, mais ce n'est pas la même chose qu'une vraie protection.

**3. La rétention des messages jamais écoutés.** Un message qu'aucune main
ne vient jamais ouvrir (Jean absent, hospitalisé, etc.) s'accumulerait sans
fin sans une règle de purge. Proposition : suppression automatique après 30
jours, qu'il ait été écouté ou non. À confirmer.

**4. Le texte exact du bandeau et du bouton.** J'ai proposé une formulation
en §4 ; dites-moi si un autre mot passe mieux auprès de Jean que « n'est pas
en ligne ».
