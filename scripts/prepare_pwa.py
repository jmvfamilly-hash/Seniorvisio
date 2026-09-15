#!/usr/bin/env python3
"""
Prépare le PWA pour un environnement donné, dans un dossier de publication.

═══ POURQUOI UN SEUL SCRIPT POUR LES DEUX ENVIRONNEMENTS ═══

La production est publiée sur GitHub Pages, le banc d'essai sur Firebase
Hosting. Deux hébergeurs, deux workflows — et donc deux occasions de diverger.

Or un banc d'essai qui ne se prépare pas exactement comme la production
n'éprouve pas la production : il éprouve autre chose, et les différences se
découvrent le jour où un correctif validé sur l'un ne marche pas sur l'autre.
Tout ce qui n'est pas explicitement déclaré différent ici est donc
rigoureusement identique de part et d'autre.

Trois choses seulement changent d'un environnement à l'autre : les documents
Firestore visés, le nom affiché du raccourci installé, et le bandeau.

Usage :
    prepare_pwa.py --source web-caller --out site \\
                   --environment production --version a1b2c3d
"""

import argparse
import json
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path

# Doit rester identique aux variantes de app/build.gradle (DEVICE_ID,
# CALLS_COLLECTION) et aux environnements de functions/index.js. C'est le
# prix d'avoir trois langages autour des mêmes deux jeux de documents.
ENVIRONNEMENTS = {
    "production": {
        "deviceDocId": "jean_tablet",
        "callsCollection": "calls",
        "nomRaccourci": "Appeler Jean",
    },
    "validation": {
        "deviceDocId": "test_tablet",
        "callsCollection": "calls_test",
        # Nom distinct, et ce n'est pas cosmétique : installé sur l'écran
        # d'accueil, un raccourci nommé « Appeler Jean » qui appelle en
        # réalité la tablette d'essai est un piège. Le bandeau dans la page
        # ne protège pas de ça — on le voit après avoir ouvert, donc après
        # avoir cru appeler Jean.
        "nomRaccourci": "TEST — banc d'essai",
    },
}


def écrire_version(dossier: Path, version: str) -> None:
    """Le commit déployé, affiché discrètement en bas de l'écran d'attente."""
    horodatage = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    (dossier / "version.js").write_text(
        f'window.PWA_VERSION = "{version} · {horodatage}";\n', encoding="utf-8"
    )


def écrire_environnement(dossier: Path, nom: str) -> None:
    """Quel jeu de documents Firestore ce PWA pilote (voir web-caller/environment.js)."""
    env = ENVIRONNEMENTS[nom]
    (dossier / "environment.js").write_text(
        "// Écrit au déploiement par scripts/prepare_pwa.py — ne pas modifier ici.\n"
        "window.SENIORVISIO_ENV = {\n"
        f'  name: "{nom}",\n'
        f'  deviceDocId: "{env["deviceDocId"]}",\n'
        f'  callsCollection: "{env["callsCollection"]}",\n'
        "};\n",
        encoding="utf-8",
    )


def nommer_raccourci(dossier: Path, nom: str) -> None:
    """Le nom sous lequel le PWA s'installe sur l'écran d'accueil."""
    chemin = dossier / "manifest.json"
    manifeste = json.loads(chemin.read_text(encoding="utf-8"))
    manifeste["name"] = ENVIRONNEMENTS[nom]["nomRaccourci"]
    manifeste["short_name"] = ENVIRONNEMENTS[nom]["nomRaccourci"]
    chemin.write_text(json.dumps(manifeste, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def suffixer_les_fichiers(dossier: Path, version: str) -> int:
    """
    Ajoute ?v=<commit> à chaque fichier local référencé par index.html.

    Sans ça, le navigateur met en cache chaque fichier de son côté et les
    renouvelle quand il veut : rien ne garantit qu'il serve index.html et
    app.js de la MÊME version. Un identifiant absent d'un index.html périmé
    suffit à faire tomber tout le câblage JavaScript qui vient après lui.

    Les scripts externes (gstatic) ne sont pas touchés : ils portent déjà leur
    numéro de version dans leur chemin.
    """
    chemin = dossier / "index.html"
    html = chemin.read_text(encoding="utf-8")

    def suffixer(m):
        attribut, url = m.group(1), m.group(2)
        if url.startswith(("http://", "https://", "//", "data:", "#")):
            return m.group(0)
        if "?" in url:
            return m.group(0)
        return f'{attribut}="{url}?v={version}"'

    html, n = re.subn(r'\b(src|href)="([^"]+\.(?:js|css))"', suffixer, html)
    chemin.write_text(html, encoding="utf-8")
    return n


def main() -> None:
    parseur = argparse.ArgumentParser(description=__doc__)
    parseur.add_argument("--source", default="web-caller")
    parseur.add_argument("--out", required=True)
    parseur.add_argument("--environment", required=True, choices=sorted(ENVIRONNEMENTS))
    parseur.add_argument("--version", required=True)
    args = parseur.parse_args()

    source, sortie = Path(args.source), Path(args.out)
    # Repartir d'un dossier vide : un reste d'exécution précédente publierait
    # un fichier que le dépôt ne contient plus.
    if sortie.exists():
        shutil.rmtree(sortie)
    shutil.copytree(source, sortie)

    écrire_version(sortie, args.version)
    écrire_environnement(sortie, args.environment)
    nommer_raccourci(sortie, args.environment)
    suffixés = suffixer_les_fichiers(sortie, args.version)

    print(f"PWA préparé pour « {args.environment} » dans {sortie}/")
    print(f"  {suffixés} fichier(s) local(aux) suffixé(s) en ?v={args.version}")
    print(f"  raccourci : {ENVIRONNEMENTS[args.environment]['nomRaccourci']}")
    print(f"  documents : devices/{ENVIRONNEMENTS[args.environment]['deviceDocId']} "
          f"· {ENVIRONNEMENTS[args.environment]['callsCollection']}")


if __name__ == "__main__":
    main()
