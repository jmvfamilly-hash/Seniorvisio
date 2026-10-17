# Testeurs de l'appli impressionniste

- `/` : page d'inscription (cible du QR code, `qr.svg`)
- `/app/` : **lien stable** de l'application, à communiquer aux testeurs dans quelques jours.
  Pour livrer la vraie PWA, remplacer le contenu de `app/` en gardant ce dossier.

## Mise en service (une fois)
1. Créer le site : `firebase hosting:sites:create seniorvisio-impressionnistes --project seniorvisio`
   (si le nom est pris, le changer dans `.firebaserc` et `scripts/qr_impressionnistes.py`).
2. Créer le formulaire Tally ou Google Forms avec : prénom, e-mail, téléphone (facultatif),
   appareil (iPhone/Android), et une case **non pré-cochée** :
   « J'accepte d'être contacté(e) par e-mail pour recevoir le lien de l'appli de test ».
   Coller son adresse « intégrable » dans `formUrl` de `config.js`.
3. Console Firebase > Analytics : copier l'identifiant `G-…` dans `measurementId`.
   La mesure ne démarre qu'après accord du visiteur (bandeau). Stats : console Firebase / Google Analytics.
4. Pousser sur la branche : le workflow `deploy-impressionnistes.yml` publie le site.
5. Régénérer et imprimer le QR : `python3 scripts/qr_impressionnistes.py`.

## Envoi du lien
Exporter les réponses du formulaire, ne retenir que les personnes ayant coché la case,
et leur écrire (Brevo/Mailjet pour un envoi groupé avec lien de désinscription).
