#!/usr/bin/env bash
# Publie une scène déjà traitée (voir scripts/traiter_vignette.sh) pour que
# VignetteStore.kt la télécharge sur la tablette — comme pour les recueils,
# un dépôt déclenche l'installation côté tablette, sans rien recompiler ni
# réinstaller.
#
# ═══ POURQUOI UN SCRIPT À PART, ET PAS LE PANNEAU ADMIN DU PWA ═══
#
# Le panneau admin dépose le clip BRUT, fond vert (voir web-caller/
# vignettes.js) ; le détourage se fait hors PWA avec ffmpeg (voir
# traiter_vignette.sh), qu'un navigateur ne sait pas faire. Ce script termine
# ce que traiter_vignette.sh a commencé : empaqueter le résultat et le
# publier là où VignetteStore.kt le cherche.
#
# ═══ NI SDK NI IDENTIFIANTS, AU-DELÀ DE LA CLÉ PUBLIQUE DU PROJET ═══
#
# Les règles Storage et Firestore de ce projet sont ouvertes (voir
# storage.rules, firestore.rules), comme pour la tablette et le PWA eux-
# mêmes : ce script écrit en HTTPS simple, sans compte de service, sur le
# modèle déjà établi (voir TelechargementHttp.kt côté application, et
# l'absence du SDK Storage qu'il documente).
#
# Usage :
#   scripts/publier_vignette.sh <nom_scene> [images_par_seconde]
#
# Exemple :
#   scripts/publier_vignette.sh ecureuil
set -euo pipefail

SCENE="${1:?nom de la scène manquant}"
IMAGES_PAR_SECONDE="${2:-10}"

DOSSIER="build/vignettes/$SCENE"
[ -d "$DOSSIER" ] || { echo "Dossier introuvable : $DOSSIER (lancer traiter_vignette.sh d'abord)" >&2; exit 1; }

NOMBRE=$(ls "$DOSSIER"/frame_*.webp 2>/dev/null | wc -l)
[ "$NOMBRE" -gt 0 ] || { echo "Aucune image dans $DOSSIER" >&2; exit 1; }

# Source unique pour le projet/la clé : web-caller/firebase-config.js, pour
# ne jamais diverger de ce que le PWA et l'application utilisent réellement.
CONFIG="$(dirname "$0")/../web-caller/firebase-config.js"
PROJET=$(grep -oP 'projectId:\s*"\K[^"]+' "$CONFIG")
BUCKET=$(grep -oP 'storageBucket:\s*"\K[^"]+' "$CONFIG")
CLE=$(grep -oP 'apiKey:\s*"\K[^"]+' "$CONFIG")

ARCHIVE_LOCALE="$(mktemp -d)/cadres.zip"
# -0 (stockage, sans recompression) : les images sont déjà du WebP compressé,
# les recompresser en zip ne gagnerait quasiment rien et coûterait du temps.
(cd "$DOSSIER" && zip -q -0 "$ARCHIVE_LOCALE" frame_*.webp)

CHEMIN="vignettes_pretes/$SCENE/cadres.zip"
CHEMIN_ENCODE=$(jq -rn --arg v "$CHEMIN" '$v | @uri')

echo "→ Dépôt de $(du -h "$ARCHIVE_LOCALE" | cut -f1) ($NOMBRE images) sur Storage…"
curl -sS -X POST \
  "https://firebasestorage.googleapis.com/v0/b/$BUCKET/o?uploadType=media&name=$CHEMIN_ENCODE" \
  -H "Content-Type: application/zip" \
  --data-binary "@$ARCHIVE_LOCALE" \
  -o /tmp/publier_vignette_storage.json
rm -rf "$(dirname "$ARCHIVE_LOCALE")"

if ! jq -e '.name' /tmp/publier_vignette_storage.json >/dev/null 2>&1; then
  echo "ÉCHEC — réponse Storage :" >&2
  cat /tmp/publier_vignette_storage.json >&2
  exit 1
fi

# Pas de jeton : les règles Storage autorisent déjà la lecture à tout le
# monde sur ce chemin (voir storage.rules), un jeton n'ajouterait rien.
ARCHIVE_URL="https://firebasestorage.googleapis.com/v0/b/$BUCKET/o/$CHEMIN_ENCODE?alt=media"

echo "→ Mise à jour du document Firestore vignettes/$SCENE…"
TRAITE_LE=$(date -u +"%Y-%m-%dT%H:%M:%S.000Z")
curl -sS -X PATCH \
  "https://firestore.googleapis.com/v1/projects/$PROJET/databases/(default)/documents/vignettes/$SCENE?key=$CLE&updateMask.fieldPaths=archive&updateMask.fieldPaths=nombreImages&updateMask.fieldPaths=imagesParSeconde&updateMask.fieldPaths=%C3%A9tat&updateMask.fieldPaths=trait%C3%A9Le" \
  -H "Content-Type: application/json" \
  -d "{\"fields\": {
    \"archive\": {\"stringValue\": \"$ARCHIVE_URL\"},
    \"nombreImages\": {\"integerValue\": \"$NOMBRE\"},
    \"imagesParSeconde\": {\"integerValue\": \"$IMAGES_PAR_SECONDE\"},
    \"état\": {\"stringValue\": \"prête\"},
    \"traitéLe\": {\"stringValue\": \"$TRAITE_LE\"}
  }}" \
  -o /tmp/publier_vignette_firestore.json

if jq -e '.error' /tmp/publier_vignette_firestore.json >/dev/null 2>&1; then
  echo "ÉCHEC — réponse Firestore :" >&2
  cat /tmp/publier_vignette_firestore.json >&2
  exit 1
fi

echo "→ Scène « $SCENE » publiée ($NOMBRE images, $IMAGES_PAR_SECONDE i/s)."
echo "  La tablette la téléchargera au prochain signe de vie du service d'écoute."
