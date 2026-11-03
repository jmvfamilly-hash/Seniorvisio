#!/usr/bin/env bash
#
# Simule un incident de rappel d'urgence sur le banc d'essai, sans passer
# par le bouton de la tablette — pour tester le pipeline complet (push,
# roster partagé, verrou, relance, secours SMS) à volonté, y compris la
# matrice de cas (une seule réponse, deux réponses concurrentes, aucune
# réponse) sans déranger personne ni manipuler l'appareil physique.
#
# Usage : scripts/simuler_incident_rappel.sh
#
# Donne ensuite trois liens, un par proche configuré (c1/c2/c3) — à ouvrir
# depuis des navigateurs différents (ou en navigation privée) pour simuler
# plusieurs proches distincts.

set -euo pipefail

API_KEY="AIzaSyDdEvxbetU0V6p6QXKPPA6JWS6ZW4WCcEM"
PROJET="seniorvisio"
BASE_URL="https://seniorvisio-test.web.app"
HORODATAGE=$(date -u +%Y-%m-%dT%H:%M:%SZ)

REPONSE=$(curl -s -X POST \
  "https://firestore.googleapis.com/v1/projects/${PROJET}/databases/(default)/documents/rappels_test?key=${API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"fields\":{\"demandéLe\":{\"timestampValue\":\"${HORODATAGE}\"},\"origine\":{\"stringValue\":\"simulation\"}}}")

ID=$(python3 -c "import json,sys; print(json.loads('''$REPONSE''')['name'].split('/')[-1])")

echo "Incident simulé créé : ${ID}"
echo
echo "Liens de test, un par proche (ouvrir chacun dans un navigateur distinct) :"
for cle in c1 c2 c3; do
  echo "  ${cle} : ${BASE_URL}/?mode=aidant&incident=${ID}&contact=${cle}&rappel=1"
done
echo
echo "Pour simuler « rappeler plus tard » ou « je ne peux pas », remplacer"
echo "&rappel=1 par rien et agir depuis les boutons de la page."
