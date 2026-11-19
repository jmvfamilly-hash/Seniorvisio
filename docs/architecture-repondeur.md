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
```

Un seul champ `état` suffit : pas de compteur de vues, pas d'historique — le
message disparaît après sa lecture (§6).

## 3. Déroulé côté appelant (PWA)

1. Bouton « Laisser un message », à côté de « Appeler ». Choix audio ou
   vidéo, comme le choix caméra/micro existant à l'ouverture d'un appel.
2. Écran d'enregistrement : aperçu caméra (si vidéo) identique à `localVideo`
   aujourd'hui, un bouton Enregistrer/Arrêter, une durée maximale annoncée à
   l'écran (proposition : 2 minutes — large assez pour un message, court
   assez pour ne pas remplir le quota).
3. Relecture avant envoi, avec refaire/envoyer — personne n'envoie un message
   à Jean sans savoir ce qu'il contient.
4. Envoi : upload vers Storage (`MediaRecorder` côté navigateur, comme pour
   les recueils vidéo), puis écriture du document Firestore une fois l'upload
   terminé — jamais avant, pour ne jamais pointer vers une URL qui n'existe
   pas encore.
5. Confirmation à l'écran : « Message envoyé, Jean l'écoutera. »

## 4. Déroulé côté Jean

**Rien de nouveau à apprendre.** Deux pièces déjà en place suffisent :

- le bouton conditionnel de la colonne de droite (même mécanisme que
  `boutonAppelerAidant`, voir `MainActivity.rafraîchirBoutonAppelerAidant`) :
  masqué tant qu'aucun message n'attend, visible et vert sinon — texte
  « ÉCOUTER LE MESSAGE DE [NOM] » ;
- la consigne écrite en grand juste au-dessus ou au-dessous, même procédé que
  `textConsigneSousTitres` : « Un message vous attend. Appuyez sur le bouton
  vert pour l'écouter. »

Jean appuie → écran plein, qui reprend **exactement** la mise en page de
`activity_incoming_call.xml` une fois l'appel connecté (vidéo ou photo en
fond, zones de texte par-dessus), avec deux différences :

- un bandeau fixe, bien visible (même style que `fond_consigne_appel`) :
  « Message de [Nom] — [il/elle] n'est pas en ligne » — pour qu'il n'y ait
  aucune ambiguïté avec un appel réel ;
- un seul bouton, « Terminé » — même rouge, même taille, même emplacement que
  le bouton raccrocher aujourd'hui. Pas de « Répondre » : rien à décrocher,
  la lecture démarre seule dès l'ouverture de l'écran.

Si message audio : la photo de l'appelant occupe tout l'écran (centerCrop,
comme `imageCallerPhoto` aujourd'hui), le son joue derrière. Si message
vidéo : la vidéo occupe l'écran à la place.

La lecture se termine seule à la fin du fichier, ou avant si Jean appuie sur
Terminé. Dans les deux cas : §6.

## 5. La transcription pendant la lecture

Pendant un appel, les sous-titres viennent de la transcription du flux audio
distant WebRTC en direct (voir `WebRtcCallEngine` et les implémentations de
`TranscriptionEngine`). Un message enregistré n'est pas un flux WebRTC, mais
la même chaîne de transcription peut recevoir n'importe quel flux audio
décodé — c'est le point d'entrée qu'on réutilise : on décode le fichier du
message (extraction audio, `MediaExtractor`/`MediaCodec` côté Android) et on
nourrit le `TranscriptionEngine` déjà en place avec ces échantillons au lieu
de ceux de WebRTC, à la cadence de la lecture. L'affichage (`PacedCaptionZone`,
zone 3) ne change pas : il ne sait déjà pas d'où vient le texte.

Pour une vidéo, la lecture elle-même (image) ne peut pas passer par
`SurfaceViewRenderer` (WebRTC) — il faut un lecteur de fichier (ExoPlayer ou
`MediaPlayer` sur une `SurfaceView` dédiée), au même emplacement à l'écran.
C'est la pièce techniquement la plus neuve de cette fonction ; tout le reste
réutilise une pièce existante.

## 6. La destruction après lecture

**Détruit, pas seulement marqué lu.** Dès que l'écran se referme (fin
naturelle ou bouton Terminé) :

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
| `web-caller/` | bouton « Laisser un message », écran d'enregistrement, upload |
| `AdminConfig` / `DeviceStatusReporter` | rien de neuf a priori — les messages sont lus directement sous `devices/{appareil}/messages`, pas besoin d'un réglage à distance pour démarrer |
| `activity_main.xml` + `MainActivity` | bouton conditionnel + consigne, sur le modèle de `boutonAppelerAidant` |
| nouvel écran (ou mode de `IncomingCallActivity`) | lecture du message : bandeau, photo/vidéo, sous-titres, bouton Terminé |
| nouveau composant Android | lecteur de fichier audio/vidéo + pont vers `TranscriptionEngine` (voir §5) |

Réutiliser `IncomingCallActivity` en lui ajoutant un « mode message » plutôt
qu'écrire un écran séparé évite de dupliquer toute la mise en page (zones,
pulse d'attention, orientation) — à trancher au moment d'écrire le code, pas
ici.

## 8. Étapes

1. **Le socle** — schéma Storage/Firestore, upload PWA, liste des messages en
   attente visible côté admin (PWA). Rien de visible pour Jean.
2. **La lecture audio** — bouton + écran chez Jean, photo + son + sous-titres
   + suppression. Pas encore la vidéo.
3. **La lecture vidéo** — le lecteur de fichier, et son pont vers la
   transcription.
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
