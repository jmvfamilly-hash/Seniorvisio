#!/usr/bin/env bash
# Transforme un clip généré (fond vert, voir docs de prompt dans la
# conversation/commit qui a introduit ce script) en une suite d'images WebP
# avec transparence, jouée par VignetteAnimee.kt une fois publiée et
# téléchargée par la tablette (voir scripts/publier_vignette.sh et
# VignetteStore.kt — ce script-ci ne fait que le détourage local, il ne
# publie rien).
#
# ═══ POURQUOI UN TRAITEMENT HORS TABLETTE, UNE FOIS POUR TOUTES ═══
#
# Android ne sait pas retirer un fond vert en temps réel de façon fiable sur
# toutes les tablettes (pas de canal alpha vidéo portable, et un rendu par
# shader OpenGL serait une pièce d'ingénierie disproportionnée pour un simple
# agrément visuel). Le détourage se fait donc ici, une seule fois par clip,
# et le résultat (une suite d'images, pas une vidéo) est ce qui est publié.
#
# ═══ LES PARAMÈTRES DE COULEUR SONT À AJUSTER PAR CLIP ═══
#
# 0x119646 est le vert mesuré sur le premier clip (l'écureuil) — un fond
# généré par IA n'est jamais parfaitement uniforme d'un clip à l'autre.
# Mesurer le vert réel d'un nouveau clip :
#   ffmpeg -i clip.mp4 -vf "crop=2:2:10:10" -f rawvideo -pix_fmt rgb24 -frames:v 1 - \
#     | od -An -tu1 -N3
# (crop=2:2 et non 1:1 : yuv420p exige des dimensions paires)
# puis vérifier le résultat (voir --apercu) avant de traiter tout le clip.
# Si le détourage déborde sur un vêtement ou un fond sombre du sujet, resserrer
# similarité et flou (ex. 0.16/0.06 au lieu de 0.22/0.10 par défaut) et
# comparer de nouveau avant de valider.
#
# Usage :
#   scripts/traiter_vignette.sh <clip.mp4> <nom_scene> [vert_hex] [similarité] [flou]
#
# Exemple :
#   scripts/traiter_vignette.sh ecureuil.mp4 ecureuil 0x119646 0.22 0.10
#
# ═══ AJOUTER UNE SCÈNE NE DEMANDE RIEN D'AUTRE QUE CE SCRIPT, PUIS LE SUIVANT ═══
#
# VignetteAnimee.kt et MainActivity.kt ne connaissent aucun nom de scène en
# dur : ils jouent ce que VignetteStore a téléchargé et vérifié sur la
# tablette (voir ce fichier), qui lui-même suit simplement ce qui est publié
# sur Firestore/Storage — rien n'est câblé dans le code ni embarqué dans
# l'APK. Traiter un nouveau clip avec CE script, puis le publier avec
# scripts/publier_vignette.sh, suffit à l'ajouter à la rotation horaire dès
# que la tablette l'a téléchargée — aucune ligne de Kotlin à toucher, et
# aucune recompilation.
set -euo pipefail

CLIP="${1:?clip source manquant}"
SCENE="${2:?nom de la scène manquant (ex: ecureuil)}"
VERT="${3:-0x119646}"
SIMILARITE="${4:-0.22}"
FLOU="${5:-0.10}"

# 1024×576 (16:9, les trois quarts des 1280×720 d'origine), 10 images/seconde :
# la vignette occupe ~92 % de la hauteur de l'écran d'accueil (voir
# VignetteAnimee), une définition bien plus proche de la taille d'affichage
# réelle que les 640×360 du premier essai, qui rendaient le résultat flou une
# fois agrandi à ce point. Chaque image WebP avoisine 70-80 Ko ; le tout reste
# un dossier LOCAL, hors du dépôt (voir /build dans .gitignore) — rien
# n'alourdit plus l'APK, c'est la tablette qui range ce volume, pas lui (voir
# scripts/publier_vignette.sh et VignetteStore.kt).
DOSSIER="build/vignettes/$SCENE"
mkdir -p "$DOSSIER"
rm -f "$DOSSIER"/frame_*.webp

ffmpeg -y -i "$CLIP" \
  -vf "fps=10,scale=1024:576,colorkey=${VERT}:${SIMILARITE}:${FLOU}" \
  -vsync 0 -c:v libwebp -lossless 1 \
  "$DOSSIER/frame_%03d.webp"

NOMBRE=$(ls "$DOSSIER"/frame_*.webp | wc -l)
echo "→ $NOMBRE images écrites dans $DOSSIER"
echo "→ Vérifier le détourage avant de publier (voir --apercu ci-dessous), puis :"
echo "    scripts/publier_vignette.sh $SCENE"
echo ""
echo "Aperçu sur fond sombre et clair (teintes réelles de l'application) :"
echo "  ffmpeg -y -f lavfi -i color=c=0x101A2E:s=1024x576 -i $DOSSIER/frame_050.webp -filter_complex overlay apercu_sombre.png"
echo "  ffmpeg -y -f lavfi -i color=c=0xF2F5FA:s=1024x576 -i $DOSSIER/frame_050.webp -filter_complex overlay apercu_clair.png"
