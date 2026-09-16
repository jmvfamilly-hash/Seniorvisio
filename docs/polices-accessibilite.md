# Luciole manque, et voici comment la compléter

Les consignes d'accessibilité de ce projet retiennent trois polices. Deux sont
embarquées (Atkinson Hyperlegible et Lexend, toutes deux sous SIL Open Font
License 1.1, textes de licence dans `assets/licences-polices/`).

**Luciole ne l'est pas**, et ce n'est pas un oubli : elle n'est distribuée ni
par Google Fonts ni par aucun dépôt public, uniquement depuis le site de ses
auteurs — inaccessible depuis l'environnement où ce code a été écrit.

## Ce qu'il se passe en attendant

Le choix « Luciole » existe dans le panneau d'administration et fonctionne. Tant
que les fichiers ne sont pas là, la tablette garde la police système **et l'écrit
dans le journal technique** :

```
POLICE absente | Luciole n'est pas embarquée — police système conservée
```

Dit plutôt que substitué en silence : une absence muette ferait croire que le
réglage n'a aucun effet, et on chercherait la panne du mauvais côté.

## Les deux gestes qui complètent

1. Télécharger Luciole depuis <https://www.luciole-vision.com> et déposer ici :

   - `luciole_regular.ttf`
   - `luciole_bold.ttf`

   Les noms comptent : Android n'accepte dans `res/font` que des minuscules,
   des chiffres et le souligné.

2. Créer `police_luciole.xml` à côté, sur le modèle de `police_atkinson.xml` :

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <font-family xmlns:android="http://schemas.android.com/apk/res/android">
       <font android:font="@font/luciole_regular"
             android:fontStyle="normal" android:fontWeight="400" />
       <font android:font="@font/luciole_bold"
             android:fontStyle="normal" android:fontWeight="700" />
   </font-family>
   ```

**Aucune ligne de code à modifier.** `PoliceSenior.fonte` résout Luciole par son
nom précisément pour que ce dépôt de fichiers suffise.

## La licence, à vérifier avant de livrer

Luciole est diffusée sous Creative Commons Attribution 4.0 d'après ses auteurs.
Cette mention n'a **pas** pu être vérifiée à la source depuis l'environnement de
développement : confirmez-la, et déposez le texte de licence dans
`assets/licences-polices/` comme pour les deux autres. L'APK est distribué —
une police sous licence restrictive n'a rien à y faire.
