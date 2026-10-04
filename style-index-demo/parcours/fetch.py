#!/usr/bin/env python3
"""Images de la maquette de parcours (National Gallery of Art, CC0, service IIIF).

- chaque œuvre entière, 1600 px de côté au plus : parcours/images/{id}.jpg ;
- chaque détail de `crops` (région en fractions de l'image) à 1200 px de large, découpé PAR LE SERVEUR à pleine résolution : c'est un vrai zoom profond
  (parcours/images/{id}-{crop}.jpg). Ce qui existe déjà n'est pas retéléchargé.
"""
import json, os, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "images")
UA = "ParcoursMaquette/1.0 (https://github.com/jmvfamilly-hash/Seniorvisio; maquette)"


def get(url):
    for i in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=120) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            print("  essai", i + 1, ":", e)
            time.sleep(4 * (i + 1))
    raise RuntimeError(url)


def save(path, url):
    if os.path.exists(path):
        return
    open(path, "wb").write(get(url))
    print(os.path.basename(path), os.path.getsize(path) // 1024, "Ko")
    time.sleep(1)


plan = json.load(open(os.path.join(HERE, "plan.json"), encoding="utf-8"))
os.makedirs(OUT, exist_ok=True)
works = {w["id"]: w for w in plan["works"]}
for w in plan["works"]:
    save(os.path.join(OUT, w["id"] + ".jpg"), f"https://api.nga.gov/iiif/{w['uuid']}/full/!1600,1600/0/default.jpg")
for c in plan["crops"]:
    w = works[c["work"]]
    x, y, cw, ch = (round(c["x"] * w["w"]), round(c["y"] * w["h"]), round(c["w"] * w["w"]), round(c["h"] * w["h"]))
    save(os.path.join(OUT, f"{c['work']}-{c['id']}.jpg"), f"https://api.nga.gov/iiif/{w['uuid']}/{x},{y},{cw},{ch}/1200,/0/default.jpg")

# aperçus (1000 px) pour choisir le point d'intérêt d'un fond de menu ; jamais inclus dans la maquette
prev = os.path.join(HERE, "previews.json")
if os.path.exists(prev):
    os.makedirs(os.path.join(OUT, "previews"), exist_ok=True)
    for p in json.load(open(prev, encoding="utf-8")):
        save(os.path.join(OUT, "previews", p["id"] + ".jpg"), f"https://api.nga.gov/iiif/{p['uuid']}/full/!1000,1000/0/default.jpg")
