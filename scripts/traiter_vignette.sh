#!/usr/bin/env bash
# Transforme un clip généré (fond vert, voir docs de prompt dans la
# conversation/commit qui a introduit ce script) en une suite d'images WebP
# avec transparence, prête à être posée dans app/src/main/assets/animations/
# et jouée par VignetteAnimee.kt.
#
# ═══ POURQUOI UN TRAITEMENT HORS TABLETTE, UNE FOIS POUR TOUTES ═══
#
# Android ne sait pas retirer un fond vert en temps réel de façon fiable sur
# toutes les tablettes (pas de canal alpha vidéo portable, et un rendu par
# shader OpenGL serait une pièce d'ingénierie disproportionnée pour un simple
# agrément visuel). Le détourage se fait donc ici, une seule fois par clip,
# et le résultat (une suite d'images, pas une vidéo) est ce qui est embarqué.
#
# ═══ LES PARAMÈTRES DE COULEUR SONT À AJUSTER PAR CLIP ═══
#
# 0x119646 est le vert mesuré sur le premier clip (l'écureuil) — un fond
# généré par IA n'est jamais parfaitement uniforme d'un clip à l'autre.
# Mesurer le vert réel d'un nouveau clip :
#   ffmpeg -i clip.mp4 -vf "crop=1:1:10:10" -f rawvideo -pix_fmt rgb24 -t 1 - \
#     | od -An -tu1 -N3
# puis vérifier le résultat (voir --apercu) avant de traiter tout le clip.
#
# Usage :
#   scripts/traiter_vignette.sh <clip.mp4> <nom_scene> [vert_hex] [similarité] [flou]
#
# Exemple :
#   scripts/traiter_vignette.sh ecureuil.mp4 ecureuil 0x119646 0.22 0.10
set -euo pipefail

CLIP="${1:?clip source manquant}"
SCENE="${2:?nom de la scène manquant (ex: ecureuil)}"
VERT="${3:-0x119646}"
SIMILARITE="${4:-0.22}"
FLOU="${5:-0.10}"

# 640×360, 10 images/seconde : largement assez net pour un quart de la
# hauteur de l'écran (voir le gabarit de prompt), et garde chaque image
# WebP autour de 30 Ko — cent images tiennent en quelques mégaoctets dans
# l'APK, sans jamais toutes vivre en mémoire en même temps (voir
# VignetteAnimee, qui ne décode qu'une poignée d'images d'avance).
DOSSIER="app/src/main/assets/animations/$SCENE"
mkdir -p "$DOSSIER"
rm -f "$DOSSIER"/frame_*.webp

ffmpeg -y -i "$CLIP" \
  -vf "fps=10,scale=640:360,colorkey=${VERT}:${SIMILARITE}:${FLOU}" \
  -vsync 0 -c:v libwebp -lossless 1 \
  "$DOSSIER/frame_%03d.webp"

NOMBRE=$(ls "$DOSSIER"/frame_*.webp | wc -l)
echo "→ $NOMBRE images écrites dans $DOSSIER"
echo "→ Vérifier le détourage avant de committer (voir --apercu ci-dessous)."
echo ""
echo "Aperçu sur fond sombre et clair (teintes réelles de l'application) :"
echo "  ffmpeg -y -f lavfi -i color=c=0x101A2E:s=640x360 -i $DOSSIER/frame_050.webp -filter_complex overlay apercu_sombre.png"
echo "  ffmpeg -y -f lavfi -i color=c=0xF2F5FA:s=640x360 -i $DOSSIER/frame_050.webp -filter_complex overlay apercu_clair.png"
