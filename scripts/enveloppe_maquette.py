#!/usr/bin/env python3
"""
Rend une maquette servable telle quelle par un hébergeur ordinaire.

═══ POURQUOI CETTE ENVELOPPE EXISTE ═══

Les maquettes de maquettes/ sont écrites pour le service d'artefacts, qui les
insère lui-même dans un document : elles commencent donc directement par leur
<title> et leur <style>, sans <!doctype>, <html>, <head> ni <body>.

Servi tel quel par un hébergeur, un tel fichier s'affiche quand même — mais en
MODE DE COMPATIBILITÉ, faute de doctype. Dans ce mode, le navigateur applique
les règles de mise en page d'avant les standards : les proportions fixes et le
calcul des boîtes ne se comportent pas pareil. La maquette rendrait autrement
que celle qui a été vérifiée, et on jugerait une lisibilité sur une géométrie
qui n'est pas la bonne.

Cette enveloppe reproduit donc exactement celle du service d'artefacts : même
doctype, même meta viewport avec viewport-fit=cover, même remise à zéro
minimale. Une seule source pour le contenu, deux façons de le servir, et le
même rendu des deux côtés.

Usage :
    enveloppe_maquette.py maquettes/rss-portrait.html site-test/maquettes/
"""

import html
import re
import sys
from pathlib import Path

# Repris du contrat du service d'artefacts, à l'identique. Toute divergence
# ici ferait rendre différemment les deux copies de la même maquette.
GABARIT = """<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>
  :root {{
    color-scheme: light;
    padding-top: env(safe-area-inset-top, 0px);
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }}
  body {{ margin: 0; font: 14px system-ui, sans-serif; background: #faf9f7; }}
  img {{ max-width: 100%; }}
  [hidden] {{ display: none !important; }}
</style>
{contenu}
</head>
<body>
{corps}
</body>
</html>
"""


def découper(source: str) -> tuple[str, str]:
    """
    Sépare ce qui va dans <head> de ce qui va dans <body>.

    Le <title>, les <link> et le premier <style> appartiennent à l'en-tête ;
    tout le reste est du corps. Découpage volontairement littéral plutôt que
    par analyse HTML : ces fichiers sont écrits à la main dans une forme
    connue, et une dépendance d'analyse pour trois balises serait payée pour
    rien.
    """
    tête = []
    reste = source

    for motif in (
        r"^\s*<title>.*?</title>",
        r"^\s*<link\b[^>]*>",
        r"^\s*<style>.*?</style>",
    ):
        while True:
            m = re.match(motif, reste, re.S | re.I)
            if not m:
                break
            tête.append(m.group(0).strip())
            reste = reste[m.end():]

    return "\n".join(tête), reste.strip()


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2

    source = Path(sys.argv[1])
    dossier = Path(sys.argv[2])
    dossier.mkdir(parents=True, exist_ok=True)

    brut = source.read_text(encoding="utf-8")
    if "<!doctype" in brut[:200].lower():
        print(f"{source.name} porte déjà son doctype — copié tel quel.")
        (dossier / source.name).write_text(brut, encoding="utf-8")
        return 0

    contenu, corps = découper(brut)
    if not corps:
        print(f"Rien à envelopper dans {source} — fichier vide ?", file=sys.stderr)
        return 1

    (dossier / source.name).write_text(
        GABARIT.format(contenu=contenu, corps=corps), encoding="utf-8"
    )
    titre = re.search(r"<title>(.*?)</title>", contenu, re.S | re.I)
    print(
        f"{source.name} enveloppé dans {dossier}/ "
        f"— « {html.unescape(titre.group(1)).strip() if titre else 'sans titre'} »"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
